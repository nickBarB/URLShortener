package libs.json;

import com.google.gson.Gson;
import libs.storage.StorageException;
import libs.storage.TransactionManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Загружает снимок под блокировкой и атомарно заменяет JSON только после успешной операции. */
public final class JsonFileStore<T> implements TransactionManager, AutoCloseable {
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path file;
    private final Object monitor;
    private final Gson gson;
    private final Class<T> type;
    private final Supplier<T> empty;
    private final Consumer<T> validate;
    private T current;
    private boolean dirty;
    private boolean closed;

    public JsonFileStore(Path path, Gson gson, Class<T> type, Supplier<T> empty, Consumer<T> validate) {
        try {
            Path absolute = path.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            file = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        } catch (IOException e) {
            throw new StorageException("Не удалось открыть каталог JSON-хранилища: " + path, e);
        }
        this.monitor = LOCKS.computeIfAbsent(file, ignored -> new Object());
        this.gson = gson;
        this.type = type;
        this.empty = empty;
        this.validate = validate;
        inTransaction(() -> null);
    }

    public synchronized <R> R read(Function<T, R> operation) {
        return inTransaction(() -> operation.apply(current));
    }

    public synchronized <R> R update(Function<T, R> operation) {
        return inTransaction(() -> {
            R result = operation.apply(current);
            dirty = true;
            return result;
        });
    }

    @Override
    public synchronized <R> R inTransaction(Supplier<R> operation) {
        if (closed) {
            throw new IllegalStateException("JSON-хранилище закрыто");
        }
        if (current != null) {
            return operation.get();
        }
        // JVM-монитор предотвращает OverlappingFileLockException между экземплярами в одном процессе.
        synchronized (monitor) {
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                current = load();
                dirty = false;
                R result = operation.get();
                if (dirty) {
                    save();
                }
                return result;
            } catch (IOException e) {
                throw new StorageException("Не удалось прочитать или сохранить JSON-хранилище: " + file, e);
            } finally {
                current = null;
                dirty = false;
            }
        }
    }

    private T load() throws IOException {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return empty.get();
        }
        try {
            T value = gson.fromJson(json, type);
            validate.accept(value);
            return value;
        } catch (RuntimeException e) {
            throw new StorageException("Некорректное JSON-хранилище: " + file + ". " + e.getMessage(), e);
        }
    }

    private void save() throws IOException {
        byte[] bytes;
        try {
            validate.accept(current);
            bytes = (gson.toJson(current) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new StorageException("Некорректные данные JSON-хранилища: " + file, e);
        }
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            // Без неатомарного fallback: при неподдерживаемой операции прежний файл остаётся целым.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
