import config.ApplicationConfig;
import config.ApplicationConfigLoader;
import controller.ConsoleApp;
import controller.ConsoleController;
import controller.UserSession;
import libs.browser.DesktopBrowser;
import repository.json.JsonStorage;
import libs.storage.StorageException;
import repository.NotificationRepository;
import repository.ShortLinkRepository;
import repository.UserRepository;
import repository.json.JsonNotificationRepository;
import repository.json.JsonShortLinkRepository;
import repository.json.JsonUserRepository;
import libs.scheduling.TaskScheduler;
import service.NotificationService;
import service.RandomShortCodeGenerator;
import service.ShortLinkService;
import service.UrlValidator;
import service.UserIdParser;
import service.UserService;

import libs.logging.DailyFileHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

public final class Main {
    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private Main() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try {
            DailyFileHandler.configure(Path.of("logs"));
            LOG.info("Запуск приложения");
            run(out);
            LOG.info("Приложение завершено");
        } catch (IOException | IllegalStateException | IllegalArgumentException | StorageException e) {
            LOG.log(Level.SEVERE, "Не удалось запустить или завершить приложение", e);
            out.println("Не удалось запустить приложение: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(PrintStream out) {
        ApplicationConfig config = new ApplicationConfigLoader().load();
        Clock clock = Clock.systemDefaultZone();
        try (JsonStorage storage = new JsonStorage(config.storageFile())) {
            LOG.log(Level.INFO, "JSON-хранилище готово: {0}", config.storageFile().toAbsolutePath());
            ShortLinkRepository links = new JsonShortLinkRepository(storage);
            UserRepository users = new JsonUserRepository(storage);
            NotificationRepository notifications = new JsonNotificationRepository(storage);
            ShortLinkService shortLinks = new ShortLinkService(links, users, notifications, storage,
                new RandomShortCodeGenerator(config.codeLength()), new UrlValidator(), config, clock);
            ConsoleController controller = new ConsoleController(shortLinks, new UserService(users),
                new NotificationService(notifications), new UserIdParser(), new UserSession(), new DesktopBrowser(),
                config, clock, out);
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            TaskScheduler scheduler = new TaskScheduler(config.cleanupInterval(), () -> {
                int removed = shortLinks.removeExpired().size();
                if (removed > 0) {
                    LOG.log(Level.INFO, "Очистка по TTL: удалено ссылок — {0}", removed);
                }
            });
            scheduler.start();
            try {
                new ConsoleApp(controller, in, out).run();
            } finally {
                scheduler.stop();
            }
        }
    }
}
