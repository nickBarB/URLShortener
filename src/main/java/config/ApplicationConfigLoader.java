package config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public final class ApplicationConfigLoader {
    public ApplicationConfig load() {
        return load(Path.of("config/app.properties"));
    }

    public ApplicationConfig load(Path file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать конфигурацию: " + file, e);
        }
        return fromProperties(properties);
    }

    public ApplicationConfig fromProperties(Properties properties) {
        try {
            String storageFile = required(properties, "storage.file");
            if (properties.containsKey("storage.notifications-file") || !storageFile.endsWith(".json")) {
                throw new IllegalArgumentException("Укажите единое JSON-хранилище storage.file=data/shortener.json "
                    + "и удалите storage.notifications-file. Автоматический импорт старых данных не выполняется.");
            }
            return new ApplicationConfig(
                Duration.parse(required(properties, "link.ttl")),
                Integer.parseInt(required(properties, "link.min-limit")),
                Integer.parseInt(required(properties, "link.max-limit")),
                Integer.parseInt(required(properties, "link.default-limit")),
                Integer.parseInt(required(properties, "link.code-length")),
                required(properties, "link.prefix"),
                Duration.parse(required(properties, "cleanup.interval")),
                Path.of(storageFile));
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
            throw new IllegalStateException("Неверное значение в конфигурации: " + e.getMessage(), e);
        }
    }

    private String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("В конфигурации не задан параметр: " + key);
        }
        return value.strip();
    }
}
