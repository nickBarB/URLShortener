package service;

import config.ApplicationConfig;
import model.Notification;
import model.NotificationType;
import model.ShortLink;
import model.User;
import libs.storage.TransactionManager;
import repository.NotificationRepository;
import repository.ShortLinkRepository;
import repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ShortLinkService {
    private static final int MAX_CODE_ATTEMPTS = 10;
    private final ShortLinkRepository links;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final TransactionManager transactions;
    private final ShortCodeGenerator codeGenerator;
    private final UrlValidator urlValidator;
    private final ApplicationConfig config;
    private final Clock clock;

    public ShortLinkService(ShortLinkRepository links, UserRepository users, NotificationRepository notifications,
                            TransactionManager transactions, ShortCodeGenerator codeGenerator,
                            UrlValidator urlValidator, ApplicationConfig config, Clock clock) {
        this.links = links;
        this.users = users;
        this.notifications = notifications;
        this.transactions = transactions;
        this.codeGenerator = codeGenerator;
        this.urlValidator = urlValidator;
        this.config = config;
        this.clock = clock;
    }

    public ShortLink createLink(UUID ownerId, String rawUrl) {
        return createLink(ownerId, rawUrl, config.defaultLimit());
    }

    public ShortLink createLink(UUID ownerId, String rawUrl, int clickLimit) {
        Objects.requireNonNull(ownerId, "ownerId");
        String url = urlValidator.validate(rawUrl);
        if (clickLimit < config.minLimit() || clickLimit > config.maxLimit()) {
            throw new BusinessException("invalid_click_limit", "Лимит переходов должен быть от "
                + config.minLimit() + " до " + config.maxLimit());
        }
        return transactions.inTransaction(() -> {
            Instant now = clock.instant();
            users.add(new User(ownerId, now));
            for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
                ShortLink link = new ShortLink(codeGenerator.generate(), url, ownerId, now,
                    now.plus(config.linkTtl()), clickLimit, 0);
                if (links.add(link)) {
                    return link;
                }
            }
            throw new IllegalStateException("Не удалось подобрать уникальный код ссылки");
        });
    }

    public String shortUrl(ShortLink link) {
        return config.prefix() + link.code();
    }

    public String open(String input) {
        String code = extractCode(input);
        return transactions.inTransaction(() -> {
            Instant now = clock.instant();
            ShortLink link = requireLink(code);
            requireAvailable(link, now);
            ShortLink opened = link.withClickRegistered();
            links.updateClickCount(code, opened.clickCount());
            if (opened.isLimitReached()) {
                notifyOwner(opened, NotificationType.LIMIT_REACHED, now);
            }
            return opened.originalUrl();
        });
    }

    public List<ShortLink> removeExpired() {
        return transactions.inTransaction(() -> {
            Instant now = clock.instant();
            List<ShortLink> expired = links.findExpired(now);
            for (ShortLink link : expired) {
                if (links.delete(link.code())) {
                    notifyOwner(link, NotificationType.EXPIRED, now);
                }
            }
            return expired;
        });
    }

    public ShortLink editLimit(UUID ownerId, String input, int newLimit) {
        Objects.requireNonNull(ownerId, "ownerId");
        String code = extractCode(input);
        return transactions.inTransaction(() -> {
            ShortLink link = requireLink(code);
            requireOwner(link, ownerId);
            requireAvailable(link, clock.instant());
            int min = minEditableLimit(link);
            if (newLimit < min || newLimit > config.maxLimit()) {
                throw new BusinessException("invalid_click_limit",
                    "Допустимый лимит для этой ссылки: от " + min + " до " + config.maxLimit());
            }
            links.updateClickLimit(code, newLimit);
            return link.withClickLimit(newLimit);
        });
    }

    public void deleteLink(UUID ownerId, String input) {
        Objects.requireNonNull(ownerId, "ownerId");
        String code = extractCode(input);
        transactions.inTransaction(() -> {
            requireOwner(requireLink(code), ownerId);
            links.delete(code);
            return null;
        });
    }

    public List<ShortLink> listLinks(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        return links.findByOwner(ownerId).stream()
            .sorted(Comparator.comparing(ShortLink::createdAt).thenComparing(ShortLink::code))
            .toList();
    }

    public int minEditableLimit(ShortLink link) {
        long minimum = Math.max(config.minLimit(), (long) link.clickCount() + 1);
        return (int) Math.min(Integer.MAX_VALUE, minimum);
    }

    public int maxEditableLimit() {
        return config.maxLimit();
    }

    private ShortLink requireLink(String code) {
        return links.findByCode(code).orElseThrow(() -> new BusinessException("link_not_found",
            "Ссылка не найдена или уже удалена"));
    }

    private void requireAvailable(ShortLink link, Instant now) {
        if (link.isExpired(now)) {
            throw new BusinessException("link_expired",
                "Срок действия ссылки истёк. Создайте новую ссылку командой create.");
        }
        if (link.isLimitReached()) {
            throw new BusinessException("link_limit_reached",
                "Лимит переходов по ссылке исчерпан. Создайте новую ссылку командой create.");
        }
    }

    private void requireOwner(ShortLink link, UUID ownerId) {
        if (!link.ownerId().equals(ownerId)) {
            throw new BusinessException("link_forbidden", "Эта ссылка принадлежит другому пользователю. "
                + "Менять и удалять её может только владелец.");
        }
    }

    private void notifyOwner(ShortLink link, NotificationType type, Instant now) {
        notifications.add(new Notification(link.ownerId(), type, link.code(), link.originalUrl(), now));
    }

    private String extractCode(String input) {
        String text = input == null ? "" : input.strip();
        if (text.startsWith(config.prefix())) {
            text = text.substring(config.prefix().length());
        }
        if (text.isBlank()) {
            throw new BusinessException("invalid_short_link",
                "Введите короткую ссылку, например: " + config.prefix() + "aB3xYz");
        }
        return text;
    }
}
