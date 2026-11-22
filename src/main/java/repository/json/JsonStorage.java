package repository.json;

import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import libs.json.JsonFileStore;
import libs.storage.TransactionManager;
import model.Notification;
import model.ShortLink;
import model.User;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/** Общий снимок пользователей, ссылок и уведомлений для всех JSON-репозиториев. */
public final class JsonStorage implements TransactionManager, AutoCloseable {
    private final JsonFileStore<State> store;

    public JsonStorage(Path file) {
        var gson = new GsonBuilder()
            .setStrictness(Strictness.STRICT)
            .disableJdkUnsafe()
            .setPrettyPrinting()
            .registerTypeAdapter(Instant.class, new TypeAdapter<Instant>() {
                @Override
                public void write(JsonWriter out, Instant value) throws IOException {
                    out.value(value.toString());
                }

                @Override
                public Instant read(JsonReader in) throws IOException {
                    return Instant.parse(in.nextString());
                }
            }.nullSafe())
            .create();
        store = new JsonFileStore<>(file, gson, State.class,
            () -> new State(1, new ArrayList<>(), new ArrayList<>(), new ArrayList<>()), State::validate);
    }

    <T> T read(Function<State, T> operation) {
        return store.read(operation);
    }

    <T> T update(Function<State, T> operation) {
        return store.update(operation);
    }

    @Override
    public <T> T inTransaction(Supplier<T> operation) {
        return store.inTransaction(operation);
    }

    @Override
    public void close() {
        store.close();
    }

    record State(int version, List<User> users, List<ShortLink> links, List<Notification> notifications) {
        State {
            if (version != 1) {
                throw new IllegalArgumentException("Неподдерживаемая версия JSON-хранилища: " + version);
            }
            users = new ArrayList<>(Objects.requireNonNull(users, "users"));
            links = new ArrayList<>(Objects.requireNonNull(links, "links"));
            notifications = new ArrayList<>(Objects.requireNonNull(notifications, "notifications"));
        }

        void validate() {
            var ids = new HashSet<UUID>();
            for (User user : users) {
                if (!ids.add(user.id())) {
                    throw new IllegalArgumentException("Повторяющийся UUID пользователя");
                }
            }
            var codes = new HashSet<String>();
            for (ShortLink link : links) {
                if (!codes.add(link.code()) || !ids.contains(link.ownerId())) {
                    throw new IllegalArgumentException("Повторяющийся код или неизвестный владелец ссылки");
                }
            }
            for (Notification notification : notifications) {
                if (!ids.contains(notification.ownerId())) {
                    throw new IllegalArgumentException("Неизвестный владелец уведомления");
                }
            }
        }
    }
}
