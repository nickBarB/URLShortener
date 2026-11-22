package service;

import model.User;
import repository.UserRepository;

import java.util.Objects;
import java.util.UUID;

public final class UserService {
    private final UserRepository users;
    public UserService(UserRepository users) {
        this.users = users;
    }

    public User login(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return users.findById(userId).orElseThrow(() -> new BusinessException("user_not_found",
            "Пользователь с таким UUID не найден. Выполните create <url> и выберите нового пользователя."));
    }
}
