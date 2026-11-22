package model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ShortLink(
    String code,
    String originalUrl,
    UUID ownerId,
    Instant createdAt,
    Instant expiresAt,
    int clickLimit,
    int clickCount) {

    public ShortLink {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(originalUrl, "originalUrl");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (code.isBlank()) {
            throw new IllegalArgumentException("Код ссылки не должен быть пустым");
        }
        if (clickLimit < 1) {
            throw new IllegalArgumentException("Лимит переходов должен быть не меньше 1");
        }
        if (clickCount < 0 || clickCount > clickLimit) {
            throw new IllegalArgumentException("Счётчик переходов вне допустимых границ: от 0 до лимита");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Срок действия ссылки должен заканчиваться после ее создания");
        }
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isLimitReached() {
        return clickCount >= clickLimit;
    }

    public ShortLinkStatus status(Instant now) {
        if (isExpired(now)) {
            return ShortLinkStatus.EXPIRED;
        }
        if (isLimitReached()) {
            return ShortLinkStatus.LIMIT_REACHED;
        }
        return ShortLinkStatus.ACTIVE;
    }

    public ShortLink withClickRegistered() {
        return new ShortLink(code, originalUrl, ownerId, createdAt, expiresAt, clickLimit, clickCount + 1);
    }

    public ShortLink withClickLimit(int newLimit) {
        return new ShortLink(code, originalUrl, ownerId, createdAt, expiresAt, newLimit, clickCount);
    }
}
