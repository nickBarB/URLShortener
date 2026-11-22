package repository;

import model.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    void add(User user);
    Optional<User> findById(UUID userId);
}
