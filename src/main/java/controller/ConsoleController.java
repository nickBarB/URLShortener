package controller;

import config.ApplicationConfig;
import libs.browser.Browser;
import model.Notification;
import model.ShortLink;
import model.ShortLinkStatus;
import service.BusinessException;
import service.NotificationService;
import service.ShortLinkService;
import service.UserIdParser;
import service.UserService;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class ConsoleController {

    private final ShortLinkService shortLinks;
    private final UserService users;
    private final NotificationService notifications;
    private String[] pendingCreate;
    private boolean awaitingUuid;
    private final UserIdParser userIdParser;
    private final UserSession session;
    private final Browser browser;
    private final ApplicationConfig config;
    private final Clock clock;
    private final PrintStream out;

    public ConsoleController(ShortLinkService shortLinks, UserService users, NotificationService notifications,
                             UserIdParser userIdParser, UserSession session, Browser browser,
                            ApplicationConfig config, Clock clock, PrintStream out) {
        this.shortLinks = shortLinks;
        this.users = users;
        this.notifications = notifications;
        this.userIdParser = userIdParser;
        this.session = session;
        this.browser = browser;
        this.config = config;
        this.clock = clock;
        this.out = out;
    }

    /** Выполняет одну команду. Возвращает false, если программу нужно завершить. */
    public boolean execute(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isEmpty()) {
            return true;
        }
        String[] parts = text.split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        try {
            if (pendingCreate != null && !command.equals("exit") && !command.equals("help")) {
                continueCreate(text);
                return true;
            }
            if (args.length == 0 && text.startsWith(config.prefix())) {
                open(new String[] {text});
                return true;
            }
            switch (command) {
                case "help" -> out.println(ConsoleMessages.help(config));
                case "whoami" -> whoami();
                case "notifications" -> printNotifications(notifications.takePending(requireUser()));
                case "login" -> login(args);
                case "logout" -> logout();
                case "create" -> create(args);
                case "list" -> list(args);
                case "open" -> open(args);
                case "edit-limit" -> editLimit(args);
                case "delete" -> delete(args);
                case "exit" -> {
                    out.println("До свидания!");
                    return false;
                }
                default -> throw new BusinessException("invalid_command", "Неизвестная команда: " + parts[0]
                    + ". Введите help, чтобы увидеть список команд.");
            }
        } catch (BusinessException e) {
            out.println(e.getMessage());
        }
        return true;
    }

    public void showPendingNotifications() {
        session.currentUser().ifPresent(
            userId -> printNotifications(notifications.takePending(userId)));
    }

    private void whoami() {
        session.currentUser().ifPresentOrElse(
            id -> out.println("Ваш UUID: " + id),
            () -> out.println("Вы гость. Выполните create <url>, чтобы создать ссылку, "
                + "или login <uuid>, чтобы войти."));
    }

    private void login(String[] args) {
        requireArgs(args, 1, 1, "login <uuid>");
        if (!session.isGuest()) {
            throw new BusinessException("invalid_command", "Вы уже вошли как " + session.currentUser().orElseThrow()
                + ". Сначала выполните logout.");
        }
        UUID userId = userIdParser.parse(args[0]);
        users.login(userId);
        session.login(userId);
        out.println("Вы вошли как " + userId + ".");
        printNotifications(notifications.takePending(userId));
        List<ShortLink> links = shortLinks.listLinks(userId);
        if (links.isEmpty()) {
            out.println("У вас пока нет ссылок. Создайте первую командой create <url>.");
        } else {
            printLinks(links);
        }
    }

    private void logout() {
        if (session.isGuest()) {
            out.println("Вы и так гость.");
            return;
        }
        UUID userId = session.currentUser().orElseThrow();
        session.logout();
        out.println("Вы вышли. Ваш UUID: " + userId
            + " — сохраните его, он понадобится для входа (login <uuid>).");
    }

    private void create(String[] args) {
        requireArgs(args, 1, 2, "create <url> [лимит]");
        if (session.isGuest()) {
            pendingCreate = args.clone();
            awaitingUuid = false;
            out.println("1. Я новый пользователь — получить UUID");
            out.println("2. У меня есть UUID — войти");
            out.println("Введите 1 или 2; cancel — отменить создание.");
            return;
        }
        createForOwner(session.currentUser().orElseThrow(), args);
    }

    private void continueCreate(String text) {
        if (text.equalsIgnoreCase("cancel")) {
            pendingCreate = null;
            awaitingUuid = false;
            out.println("Создание отменено.");
            return;
        }
        if (awaitingUuid) {
            UUID owner = userIdParser.parse(text);
            users.login(owner);
            String[] args = pendingCreate;
            pendingCreate = null;
            awaitingUuid = false;
            session.login(owner);
            createForOwner(owner, args);
            return;
        }
        if (text.equals("2")) {
            awaitingUuid = true;
            out.println("Введите свой UUID; cancel — отменить создание.");
            return;
        }
        if (!text.equals("1")) {
            throw new BusinessException("invalid_identity_choice", "Введите 1 или 2; cancel — отменить создание.");
        }
        String[] args = pendingCreate;
        pendingCreate = null;
        createForOwner(UUID.randomUUID(), args);
    }

    private void createForOwner(UUID owner, String[] args) {
        ShortLink link = args.length == 2
            ? shortLinks.createLink(owner, args[0], parseLimit(args[1]))
            : shortLinks.createLink(owner, args[0]);
        if (session.isGuest()) {
            session.login(owner);
            out.println(ConsoleMessages.newUserBanner(owner));
        }
        out.println("Короткая ссылка создана: " + shortLinks.shortUrl(link));
        out.println("Исходный адрес: " + link.originalUrl());
        out.println("Лимит переходов: " + link.clickLimit() + ". Действует до: "
            + ConsoleMessages.formatTime(link.expiresAt(), clock.getZone()));
    }

    private void list(String[] args) {
        requireArgs(args, 0, 0, "list");
        List<ShortLink> links = shortLinks.listLinks(requireUser());
        if (links.isEmpty()) {
            out.println("У вас пока нет ссылок. Создайте первую командой create <url>.");
            return;
        }
        out.println("Ваши ссылки:");
        printLinks(links);
    }

    private void open(String[] args) {
        requireArgs(args, 1, 1, "open <ссылка>");
        String url = shortLinks.open(args[0]);
        if (browser.open(url)) {
            out.println("Открываю в браузере: " + url);
        } else {
            out.println("Не удалось открыть браузер автоматически. Переход засчитан. "
                + "Скопируйте адрес и откройте его вручную: " + url);
        }
    }

    private void editLimit(String[] args) {
        requireArgs(args, 2, 2, "edit-limit <код> <лимит>");
        UUID userId = requireUser();
        ShortLink updated = shortLinks.editLimit(userId, args[0], parseLimit(args[1]));
        out.println("Лимит изменён: " + updated.clickLimit()
            + " (уже использовано переходов: " + updated.clickCount() + ").");
    }

    private void delete(String[] args) {
        requireArgs(args, 1, 1, "delete <код>");
        shortLinks.deleteLink(requireUser(), args[0]);
        out.println("Ссылка удалена.");
    }

    private UUID requireUser() {
        return session.currentUser().orElseThrow(
            () -> new BusinessException("invalid_command", "Сначала выполните create <url> или login <uuid>."));
    }

    private void printNotifications(List<Notification> notifications) {
        for (Notification notification : notifications) {
            out.println("Уведомление: "
                + ConsoleMessages.notificationText(notification, config.prefix() + notification.code()));
        }
    }

    private void printLinks(List<ShortLink> links) {
        Instant now = clock.instant();
        for (ShortLink link : links) {
            ShortLinkStatus status = link.status(now);
            out.println("- " + shortLinks.shortUrl(link) + " -> " + link.originalUrl());
            out.println("    статус: " + ConsoleMessages.statusName(status)
                + "; переходы: " + link.clickCount() + " из " + link.clickLimit()
                + "; срок до: " + ConsoleMessages.formatTime(link.expiresAt(), clock.getZone()));
            if (status == ShortLinkStatus.ACTIVE) {
                out.println("    лимит можно менять от " + shortLinks.minEditableLimit(link)
                    + " до " + shortLinks.maxEditableLimit());
            }
        }
    }

    private void requireArgs(String[] args, int min, int max, String usage) {
        if (args.length < min || args.length > max) {
            throw new BusinessException("invalid_command", "Использование: " + usage);
        }
    }

    private int parseLimit(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new BusinessException("invalid_command",
                "Лимит должен быть целым числом, например: 5");
        }
    }
}
