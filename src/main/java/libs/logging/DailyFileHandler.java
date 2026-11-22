package libs.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/** Дописывает сообщения в файл даты события, включая переход через полночь. */
public final class DailyFileHandler extends Handler {
    private final Path directory;

    public DailyFileHandler(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory;
        setFormatter(new SimpleFormatter());
    }

    public static void configure(Path directory) throws IOException {
        DailyFileHandler handler = new DailyFileHandler(directory);
        Logger rootLogger = Logger.getLogger("");
        for (Handler previous : rootLogger.getHandlers()) {
            rootLogger.removeHandler(previous);
            previous.close();
        }
        rootLogger.addHandler(handler);
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) {
            return;
        }
        String date = record.getInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
        try {
            Files.writeString(directory.resolve(date + ".log"), getFormatter().format(record),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            reportError("Не удалось записать журнал", e, ErrorManager.WRITE_FAILURE);
        }
    }

    @Override
    public void flush() {
        // Каждая запись сразу сохраняется и закрывает файл.
    }

    @Override
    public void close() {
        // Открытых файлов между записями нет.
    }
}
