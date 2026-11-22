package config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public record ApplicationConfig(Duration linkTtl, int minLimit, int maxLimit, int defaultLimit,
                                int codeLength, String prefix, Duration cleanupInterval, Path storageFile) {
    public ApplicationConfig {
        Objects.requireNonNull(linkTtl, "linkTtl");
        Objects.requireNonNull(cleanupInterval, "cleanupInterval");
        Objects.requireNonNull(storageFile, "storageFile");
        if (linkTtl.isZero() || linkTtl.isNegative()) {
            throw new IllegalArgumentException("Время жизни ссылки должно быть положительным");
        }
        if (cleanupInterval.compareTo(Duration.ofMillis(1)) < 0
                || cleanupInterval.compareTo(Duration.ofMillis(Long.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("Интервал очистки должен быть от 1 миллисекунды до " + Long.MAX_VALUE + " миллисекунд");
        }
        if (minLimit < 1 || maxLimit < minLimit) {
            throw new IllegalArgumentException("Границы лимита заданы неверно: минимум не меньше 1, максимум не меньше минимума");
        }
        if (defaultLimit < minLimit || defaultLimit > maxLimit) {
            throw new IllegalArgumentException("Лимит переходов по умолчанию должен быть в пределах минимума и максимума");
        }
        if (codeLength < 1 || codeLength > 128) {
            throw new IllegalArgumentException("Длина кода ссылки должна быть от 1 до 128");
        }
        if (prefix == null || prefix.isBlank() || prefix.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Префикс ссылки не должен быть пустым или содержать пробелы");
        }
        if (storageFile.toString().isBlank()) {
            throw new IllegalArgumentException("Путь к файлу данных не должен быть пустым");
        }
    }
}
