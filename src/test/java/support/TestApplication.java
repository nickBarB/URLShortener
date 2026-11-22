package support;

import config.ApplicationConfig;
import controller.ConsoleApp;
import controller.ConsoleController;
import controller.UserSession;
import repository.json.JsonStorage;
import repository.json.JsonNotificationRepository;
import repository.json.JsonShortLinkRepository;
import repository.json.JsonUserRepository;
import service.NotificationService;
import service.ShortCodeGenerator;
import service.ShortLinkService;
import service.UrlValidator;
import service.UserIdParser;
import service.UserService;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/** Настоящие сервисы и JSON-хранилище; заменён только внешний браузер. */
public final class TestApplication implements AutoCloseable {
    public static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    public final UserSession session = new UserSession();
    public final FakeBrowser browser = new FakeBrowser();
    private final JsonStorage storage;
    public final ShortLinkService links;
    private final ConsoleController controller;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

    public TestApplication(Path directory, ShortCodeGenerator generator) {
        ApplicationConfig config = new ApplicationConfig(Duration.ofHours(24), 1, 1000, 500, 6,
            "clck.ru/", Duration.ofMinutes(1), directory.resolve("shortener.json"));
        storage = new JsonStorage(config.storageFile());
        var users = new JsonUserRepository(storage);
        var notifications = new JsonNotificationRepository(storage);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        links = new ShortLinkService(new JsonShortLinkRepository(storage), users, notifications, storage,
            generator, new UrlValidator(), config, clock);
        controller = new ConsoleController(links, new UserService(users), new NotificationService(notifications),
            new UserIdParser(), session, browser, config, clock, out);
    }

    /** Выполняет ровно переданную строку, без автоматического ответа на выбор личности. */
    public String command(String line) {
        output.reset();
        if (controller.execute(line)) {
            controller.showPendingNotifications();
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    public String runConsole(String input) {
        output.reset();
        new ConsoleApp(controller, new BufferedReader(new StringReader(input)), out).run();
        return output.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        storage.close();
        out.close();
    }
}
