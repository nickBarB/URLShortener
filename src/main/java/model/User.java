package model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record User(UUID id, Instant createdAt) {
    public User {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
