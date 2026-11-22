package repository.json;

import model.User;
import repository.UserRepository;

import java.util.Optional;
import java.util.UUID;

public final class JsonUserRepository implements UserRepository {
    private final JsonStorage storage;

    public JsonUserRepository(JsonStorage storage) {
        this.storage = storage;
    }

    @Override
    public void add(User user) {
        storage.update(state -> {
            if (state.users().stream().noneMatch(existing -> existing.id().equals(user.id()))) {
                state.users().add(user);
            }
            return null;
        });
    }

    @Override
    public Optional<User> findById(UUID userId) {
        return storage.read(state -> state.users().stream().filter(user -> user.id().equals(userId)).findFirst());
    }
}
