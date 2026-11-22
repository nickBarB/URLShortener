package config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ApplicationConfigLoaderTest {

    private final ApplicationConfigLoader loader = new ApplicationConfigLoader();

    private Properties validProperties() {
        Properties p = new Properties();
        p.setProperty("link.ttl", "PT24H");
        p.setProperty("link.min-limit", "1");
        p.setProperty("link.max-limit", "1000");
        p.setProperty("link.default-limit", "1000");
        p.setProperty("link.code-length", "6");
        p.setProperty("link.prefix", "clck.ru/");
        p.setProperty("cleanup.interval", "PT1M");
        p.setProperty("storage.file", "data/shortener.json");
        return p;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "link.ttl,сутки", "link.ttl,PT0S", "link.max-limit,abc", "link.min-limit,1001",
        "link.default-limit,1001", "cleanup.interval,PT0.000000001S"
    })
    void rejectsInvalidFormatOrBoundary(String key, String value) {
        Properties properties = validProperties();
        properties.setProperty(key, value);
        assertThrows(IllegalStateException.class, () -> loader.fromProperties(properties));
    }

    @Test
    void missingAndBlankRequiredValuesNameTheKey() {
        Properties properties = validProperties();
        properties.remove("link.ttl");
        assertTrue(assertThrows(IllegalStateException.class,
            () -> loader.fromProperties(properties)).getMessage().contains("link.ttl"));
        Properties blank = validProperties();
        blank.setProperty("link.prefix", "   ");
        assertTrue(assertThrows(IllegalStateException.class,
            () -> loader.fromProperties(blank)).getMessage().contains("link.prefix"));
    }

    @Test
    void failsWhenResourceIsMissing() {
        assertThrows(IllegalStateException.class, () -> loader.load(Path.of("/no-such-file.properties")));
    }

    @Test
    void loadsRealConfigFile() {
        ApplicationConfig config = loader.load();

        assertEquals("clck.ru/", config.prefix());
        assertEquals(Duration.ofHours(24), config.linkTtl());
        assertEquals(Path.of("data/shortener.json"), config.storageFile());
    }

    @Test
    void rejectsNonJsonStoragePath() {
        Properties properties = validProperties();
        properties.setProperty("storage.file", "data/shortener.txt");
        assertTrue(assertThrows(IllegalStateException.class,
            () -> loader.fromProperties(properties)).getMessage().contains("JSON"));
    }

    @Test
    void rejectsSeparateNotificationFile() {
        Properties properties = validProperties();
        properties.setProperty("storage.notifications-file", "data/notifications.json");
        assertThrows(IllegalStateException.class, () -> loader.fromProperties(properties));
    }

    @Test
    void readsExternalFileWithoutRebuilding() throws Exception {
        Path file = java.nio.file.Files.createTempFile("shortener-config-", ".properties");
        try {
            try (var writer = java.nio.file.Files.newBufferedWriter(file)) {
                validProperties().store(writer, "test");
            }
            assertEquals(1000, loader.load(file).defaultLimit());
            Properties changed = validProperties();
            changed.setProperty("link.default-limit", "5");
            try (var writer = java.nio.file.Files.newBufferedWriter(file)) {
                changed.store(writer, "test");
            }
            assertEquals(5, loader.load(file).defaultLimit());
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
