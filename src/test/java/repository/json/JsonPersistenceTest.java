package repository.json;

import config.ApplicationConfig;
import libs.storage.StorageException;
import model.Notification;
import model.NotificationType;
import repository.NotificationRepository;
import model.ShortLink;
import model.User;
import service.BusinessException;
import service.RandomShortCodeGenerator;
import service.ShortCodeGenerator;
import service.ShortLinkService;
import service.UrlValidator;
import service.UserService;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonPersistenceTest {
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final String URL = "https://example.com/page";
    @TempDir Path directory;
    private final List<JsonStorage> storages = new ArrayList<>();

    private JsonStorage open() {
        JsonStorage storage = new JsonStorage(directory.resolve("shortener.json"));
        storages.add(storage);
        return storage;
    }

    @AfterEach
    void closeStorages() {
        storages.forEach(JsonStorage::close);
    }

    private ShortLinkService service(JsonStorage storage, Instant now, ShortCodeGenerator generator) {
        ApplicationConfig config = new ApplicationConfig(Duration.ofHours(1), 1, 1000, 1000, 6,
            "clck.ru/", Duration.ofMinutes(1), directory.resolve("shortener.json"));
        return new ShortLinkService(new JsonShortLinkRepository(storage), new JsonUserRepository(storage),
            new JsonNotificationRepository(storage), storage, generator, new UrlValidator(), config,
            Clock.fixed(now, ZoneOffset.UTC));
    }

    private ShortLinkService service(JsonStorage storage) {
        return service(storage, NOW, new RandomShortCodeGenerator(6));
    }

    private NotificationRepository failingNotifications(JsonStorage storage) {
        return new NotificationRepository() {
            @Override
            public void add(Notification notification) {
                throw new StorageException("test notification failure", null);
            }

            @Override
            public List<Notification> takeAll(UUID ownerId) {
                return new JsonNotificationRepository(storage).takeAll(ownerId);
            }
        };
    }

    private ShortLinkService serviceWithFailingNotifications(JsonStorage storage, Instant now) {
        ApplicationConfig config = new ApplicationConfig(Duration.ofHours(1), 1, 1000, 1000, 6,
            "clck.ru/", Duration.ofMinutes(1), directory.resolve("shortener.json"));
        return new ShortLinkService(new JsonShortLinkRepository(storage), new JsonUserRepository(storage),
            failingNotifications(storage), storage, new RandomShortCodeGenerator(6), new UrlValidator(),
            config, Clock.fixed(now, ZoneOffset.UTC));
    }

    // Детерминированный сбой атомарной замены: вместо целевого файла временно находится каталог.
    private void failCommit(JsonStorage storage, Runnable operation) throws Exception {
        Path file = directory.resolve("shortener.json");
        Path backup = directory.resolve("original.json");
        try {
            assertThrows(StorageException.class, () -> storage.inTransaction(() -> {
                operation.run();
                try {
                    Files.move(file, backup);
                    Files.createDirectory(file);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return null;
            }));
        } finally {
            if (Files.isDirectory(file)) {
                Files.delete(file);
            }
            if (Files.exists(backup)) {
                Files.move(backup, file);
            }
        }
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void usersAndCountersSurviveClosingAndReopeningStorage() {
        JsonStorage first = open();
        UUID owner = UUID.randomUUID();
        ShortLink link = service(first).createLink(owner, URL, 2);
        service(first).open(link.code());
        first.close();
        storages.remove(first);
        JsonStorage reopened = open();
        assertEquals(owner, new UserService(new JsonUserRepository(reopened)).login(owner).id());
        assertEquals(1, service(reopened).listLinks(owner).getFirst().clickCount());
    }

    @Test
    void deletingLastLinkPreservesUserIdentity() {
        JsonStorage storage = open();
        UUID owner = UUID.randomUUID();
        ShortLinkService links = service(storage);
        ShortLink link = links.createLink(owner, URL);
        links.deleteLink(owner, link.code());
        assertTrue(links.listLinks(owner).isEmpty());
        assertEquals(owner, new UserService(new JsonUserRepository(open())).login(owner).id());
    }

    @Test
    void failedCreationDoesNotLeaveOrphanUser() {
        JsonStorage storage = open();
        ShortLinkService links = service(storage, NOW, () -> "same");
        links.createLink(UUID.randomUUID(), URL);
        UUID newOwner = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> links.createLink(newOwner, URL));
        assertTrue(new JsonUserRepository(storage).findById(newOwner).isEmpty());
    }

    @Test
    void failedNotificationRollsBackLastClickAndAllowsRetry() {
        JsonStorage storage = open();
        ShortLinkService links = service(storage);
        UUID owner = UUID.randomUUID();
        ShortLink link = links.createLink(owner, URL, 1);
        ShortLinkService failing = serviceWithFailingNotifications(storage, NOW);
        assertThrows(StorageException.class, () -> failing.open(link.code()));
        assertEquals(0, links.listLinks(owner).getFirst().clickCount());
        assertEquals(0, new JsonShortLinkRepository(open()).findByCode(link.code()).orElseThrow().clickCount());
        assertEquals(URL, links.open(link.code()));
        assertEquals(1, new JsonNotificationRepository(storage).takeAll(owner).size());
        assertThrows(BusinessException.class, () -> links.open(link.code()));
    }

    @Test
    void failedNotificationRollsBackExpiryDeletion() {
        JsonStorage storage = open();
        UUID owner = UUID.randomUUID();
        ShortLink link = service(storage).createLink(owner, URL);
        ShortLinkService expired = service(storage, link.expiresAt(), new RandomShortCodeGenerator(6));
        ShortLinkService failing = serviceWithFailingNotifications(storage, link.expiresAt());
        assertThrows(StorageException.class, failing::removeExpired);
        assertEquals(1, expired.listLinks(owner).size());
        assertEquals(List.of(link), expired.removeExpired());
        assertTrue(expired.listLinks(owner).isEmpty());
        assertEquals(NotificationType.EXPIRED, new JsonNotificationRepository(storage).takeAll(owner).getFirst().type());
    }

    @Test
    void failedNotificationReadKeepsPendingMessages() throws Exception {
        JsonStorage storage = open();
        UUID owner = UUID.randomUUID();
        ShortLinkService links = service(storage);
        links.open(links.createLink(owner, URL, 1).code());
        JsonNotificationRepository notifications = new JsonNotificationRepository(storage);
        failCommit(storage, () -> notifications.takeAll(owner));
        assertEquals(1, notifications.takeAll(owner).size());
        assertTrue(notifications.takeAll(owner).isEmpty());
    }

    @Test
    void parallelStorageInstancesCannotExceedClickLimit() throws Exception {
        JsonStorage first = open();
        JsonStorage second = open();
        ShortLinkService firstService = service(first);
        ShortLinkService secondService = service(second);
        UUID owner = UUID.randomUUID();
        ShortLink link = firstService.createLink(owner, URL, 25);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ShortLinkService current = i % 2 == 0 ? firstService : secondService;
            calls.add(() -> {
                try { current.open(link.code()); return true; }
                catch (BusinessException e) { assertEquals("link_limit_reached", e.code()); return false; }
            });
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            int successful = 0;
            for (Future<Boolean> result : executor.invokeAll(calls)) { if (result.get()) successful++; }
            assertEquals(25, successful);
        }
        assertEquals(25, firstService.listLinks(owner).getFirst().clickCount());
        assertEquals(1, new JsonNotificationRepository(first).takeAll(owner).size());
    }

    @Test
    void codeCollisionDoesNotOverwriteAnotherOwnersLink() {
        JsonStorage storage = open();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        new JsonUserRepository(storage).add(new User(first, NOW));
        new JsonUserRepository(storage).add(new User(second, NOW));
        JsonShortLinkRepository links = new JsonShortLinkRepository(storage);
        ShortLink original = new ShortLink("same", URL, first, NOW, NOW.plusSeconds(10), 1, 0);
        assertTrue(links.add(original));
        assertFalse(links.add(new ShortLink("same", "https://other.com", second, NOW, NOW.plusSeconds(10), 1, 0)));
        assertEquals(original, links.findByCode("same").orElseThrow());
    }

    @Test
    void rejectsUnknownOwner() {
        JsonStorage storage = open();
        assertThrows(StorageException.class, () -> new JsonShortLinkRepository(storage).add(
            new ShortLink("code", URL, UUID.randomUUID(), NOW, NOW.plusSeconds(10), 1, 0)));
    }

    @Test
    void expiryQueryHandlesNanosecondsAndExactBoundary() {
        JsonStorage storage = open();
        UUID owner = UUID.randomUUID();
        ShortLink link = service(storage).createLink(owner, URL);
        JsonShortLinkRepository links = new JsonShortLinkRepository(storage);
        assertTrue(links.findExpired(link.expiresAt().minusNanos(1)).isEmpty());
        assertEquals(List.of(link), links.findExpired(link.expiresAt()));
        assertEquals(List.of(link), links.findExpired(link.expiresAt().plusNanos(1)));
    }

    @Test
    void notificationForDeletedLinkSurvivesAndIsIsolatedByOwner() {
        JsonStorage storage = open();
        ShortLinkService links = service(storage);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ShortLink link = links.createLink(first, URL, 1);
        links.createLink(second, URL);
        links.open(link.code());
        links.deleteLink(first, link.code());
        JsonNotificationRepository notifications = new JsonNotificationRepository(storage);
        assertTrue(notifications.takeAll(second).isEmpty());
        assertEquals(1, new JsonNotificationRepository(open()).takeAll(first).size());
        assertTrue(notifications.takeAll(first).isEmpty());
    }

    @Test
    void failureDuringLimitEditLeavesOriginalValue() throws Exception {
        JsonStorage storage = open();
        ShortLinkService links = service(storage);
        UUID owner = UUID.randomUUID();
        ShortLink link = links.createLink(owner, URL, 5);
        failCommit(storage, () -> links.editLimit(owner, link.code(), 10));
        assertEquals(5, links.listLinks(owner).getFirst().clickLimit());
    }

    @Test
    void corruptStorageFailsWithoutReplacingFile() throws Exception {
        Path file = directory.resolve("shortener.json");
        Files.writeString(file, "not a storage");
        assertThrows(StorageException.class, this::open);
        assertEquals("not a storage", Files.readString(file));
    }

    @Test
    void futureSchemaVersionIsRejected() throws Exception {
        Path file = directory.resolve("shortener.json");
        String json = "{\"version\":2,\"users\":[],\"links\":[],\"notifications\":[]}";
        Files.writeString(file, json);
        assertThrows(StorageException.class, this::open);
        assertEquals(json, Files.readString(file));
    }

    @Test
    void sameUrlGetsUniqueCodesForMultipleUsersAndMultipleLinks() {
        JsonStorage storage = open();
        ShortLinkService links = service(storage);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ShortLink original = links.createLink(first, URL);
        ShortLink another = links.createLink(first, URL);
        ShortLink foreign = links.createLink(second, URL);
        assertNotEquals(original.code(), another.code());
        assertNotEquals(original.code(), foreign.code());
        assertNotEquals(another.code(), foreign.code());
        assertEquals(2, links.listLinks(first).size());
        assertEquals(List.of(foreign), links.listLinks(second));
        assertEquals(1000, original.clickLimit());
        assertEquals(NOW.plus(Duration.ofHours(1)), original.expiresAt());
    }

    @Test
    void collisionRetriesWithAnotherCode() {
        JsonStorage storage = open();
        var codes = List.of("taken", "taken", "fresh").iterator();
        ShortLinkService links = service(storage, NOW, codes::next);
        ShortLink original = links.createLink(UUID.randomUUID(), URL);
        ShortLink next = links.createLink(UUID.randomUUID(), URL);
        assertEquals("taken", original.code());
        assertEquals("fresh", next.code());
    }

    @Test
    void onlyOwnerCanEditOrDeleteButAnyoneCanOpen() {
        JsonStorage storage = open();
        ShortLinkService links = service(storage);
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        ShortLink link = links.createLink(owner, URL, 5);
        assertEquals("link_forbidden", assertThrows(BusinessException.class,
            () -> links.editLimit(stranger, link.code(), 10)).code());
        assertEquals("link_forbidden", assertThrows(BusinessException.class,
            () -> links.deleteLink(stranger, link.code())).code());
        assertEquals(URL, links.open(link.code()));
        links.deleteLink(owner, link.code());
        assertEquals("link_not_found", assertThrows(BusinessException.class,
            () -> links.open(link.code())).code());
    }

    @Test
    void limitEditRespectsUsedClicksAndCannotRestoreExhaustedLink() {
        JsonStorage storage = open();
        ShortLinkService links = service(storage);
        UUID owner = UUID.randomUUID();
        ShortLink link = links.createLink(owner, URL, 5);
        links.open(link.code());
        assertThrows(BusinessException.class, () -> links.editLimit(owner, link.code(), 1));
        assertThrows(BusinessException.class, () -> links.editLimit(owner, link.code(), 1001));
        ShortLink edited = links.editLimit(owner, link.code(), 2);
        assertEquals(1, edited.clickCount());
        assertEquals(link.expiresAt(), edited.expiresAt());
        links.open(link.code());
        assertEquals("link_limit_reached", assertThrows(BusinessException.class,
            () -> links.editLimit(owner, link.code(), 10)).code());
        assertEquals(2, links.listLinks(owner).getFirst().clickCount());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {1, 24})
    void ttlBlocksImmediatelyAndCleanupNotifiesOnceKeepingFreshLinks(long hours) {
        JsonStorage storage = open();
        ApplicationConfig config = new ApplicationConfig(Duration.ofHours(hours), 1, 1000, 1000, 6,
            "clck.ru/", Duration.ofMinutes(1), directory.resolve("shortener.json"));
        ShortLinkService links = new ShortLinkService(new JsonShortLinkRepository(storage),
            new JsonUserRepository(storage), new JsonNotificationRepository(storage), storage,
            new RandomShortCodeGenerator(6), new UrlValidator(), config, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID owner = UUID.randomUUID();
        ShortLink expired = links.createLink(owner, URL);
        ShortLinkService atExpiry = service(storage, expired.expiresAt(), new RandomShortCodeGenerator(6));
        ShortLink fresh = atExpiry.createLink(owner, URL);
        assertEquals(URL, service(storage, expired.expiresAt().minusNanos(1),
            new RandomShortCodeGenerator(6)).open(expired.code()));
        assertEquals("link_expired", assertThrows(BusinessException.class,
            () -> atExpiry.open(expired.code())).code());
        assertEquals("link_expired", assertThrows(BusinessException.class,
            () -> atExpiry.editLimit(owner, expired.code(), 5)).code());
        assertEquals(1, atExpiry.removeExpired().size());
        assertTrue(atExpiry.removeExpired().isEmpty());
        assertEquals(List.of(fresh), atExpiry.listLinks(owner));
        JsonNotificationRepository notifications = new JsonNotificationRepository(storage);
        assertEquals(NotificationType.EXPIRED, notifications.takeAll(owner).getFirst().type());
        assertTrue(notifications.takeAll(owner).isEmpty());
    }

    @Test
    void invalidCreationLeavesNoUserOrLink() {
        JsonStorage storage = open();
        ShortLinkService links = service(storage);
        UUID owner = UUID.randomUUID();
        assertThrows(BusinessException.class, () -> links.createLink(owner, "not-a-url"));
        assertThrows(BusinessException.class, () -> links.createLink(owner, URL, 0));
        assertThrows(BusinessException.class, () -> links.createLink(owner, URL, 1001));
        assertTrue(links.listLinks(owner).isEmpty());
        assertEquals("user_not_found", assertThrows(BusinessException.class,
            () -> new UserService(new JsonUserRepository(storage)).login(owner)).code());
    }

    @Test
    void failedCommitRollsBackLastClickAndNotification() throws Exception {
        JsonStorage storage = open();
        UUID owner = UUID.randomUUID();
        ShortLinkService links = service(storage);
        ShortLink link = links.createLink(owner, URL, 1);
        failCommit(storage, () -> links.open(link.code()));
        assertEquals(0, links.listLinks(owner).getFirst().clickCount());
        assertTrue(new JsonNotificationRepository(storage).takeAll(owner).isEmpty());
        assertEquals(URL, links.open(link.code()));
        assertEquals(1, new JsonNotificationRepository(open()).takeAll(owner).size());
    }

    @Test
    void failedCommitRollsBackExpiryDeletionAndNotification() throws Exception {
        JsonStorage storage = open();
        UUID owner = UUID.randomUUID();
        ShortLink link = service(storage).createLink(owner, URL);
        ShortLinkService expired = service(storage, link.expiresAt(), new RandomShortCodeGenerator(6));
        failCommit(storage, expired::removeExpired);
        assertEquals(List.of(link), expired.listLinks(owner));
        assertTrue(new JsonNotificationRepository(storage).takeAll(owner).isEmpty());
        assertEquals(List.of(link), expired.removeExpired());
        assertEquals(1, new JsonNotificationRepository(open()).takeAll(owner).size());
    }

    @Test
    void failedCommitDoesNotRegisterNewUserOrLink() throws Exception {
        JsonStorage storage = open();
        ShortLinkService links = service(storage);
        UUID existing = UUID.randomUUID();
        links.createLink(existing, URL);
        UUID owner = UUID.randomUUID();
        failCommit(storage, () -> links.createLink(owner, URL));
        assertTrue(new JsonUserRepository(open()).findById(owner).isEmpty());
        assertTrue(links.listLinks(owner).isEmpty());
        assertEquals(1, links.listLinks(existing).size());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "", "null", "[]", "{}", "{\"version\":1}",
        "{\"version\":1,\"users\":[],\"links\":[],\"notifications\":[]",
        "{\"version\":1,\"users\":[],\"links\":[],\"notifications\":[]} trailing",
        "{\"version\":1,\"users\":[null],\"links\":[],\"notifications\":[]}",
        "{\"version\":1,\"users\":[{}],\"links\":[],\"notifications\":[]}",
        "{\"version\":1,\"users\":[],\"links\":[null],\"notifications\":[]}",
        "{\"version\":1,\"users\":[],\"links\":[],\"notifications\":[null]}"
    })
    void malformedOrIncompleteJsonIsNeverOverwritten(String json) throws Exception {
        Path file = directory.resolve("shortener.json");
        Files.writeString(file, json);
        assertThrows(StorageException.class, this::open);
        assertEquals(json, Files.readString(file));
    }

    @Test
    void unicodeAndNanosecondTimestampsRoundTripInOneJsonFile() throws Exception {
        JsonStorage storage = open();
        Instant now = NOW.plusNanos(123456789);
        UUID owner = UUID.randomUUID();
        String url = "https://example.com/путь?q=%22текст%22&slash=%5C";
        ShortLink link = service(storage, now, () -> "abc123").createLink(owner, url, 1);
        service(storage, now, () -> "unused").open(link.code());
        JsonStorage reopened = open();
        assertEquals(link.withClickRegistered(), new JsonShortLinkRepository(reopened)
            .findByCode(link.code()).orElseThrow());
        Notification notification = new JsonNotificationRepository(reopened).takeAll(owner).getFirst();
        assertEquals(now, notification.createdAt());
        assertEquals(url, notification.originalUrl());
        String json = Files.readString(directory.resolve("shortener.json"));
        assertTrue(json.contains("\"users\""));
        assertTrue(json.contains("\"links\""));
        assertTrue(json.contains("\"notifications\""));
    }

    @Test
    void invalidRecordsAndDuplicateIdentitiesAreRejectedWithoutOverwriting() throws Exception {
        JsonStorage storage = open();
        UUID owner = UUID.randomUUID();
        ShortLink link = service(storage).createLink(owner, URL);
        Path file = directory.resolve("shortener.json");
        String original = Files.readString(file);
        var parser = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
        var users = parser.getAsJsonArray("users");
        users.add(users.get(0).deepCopy());
        Files.writeString(file, parser.toString());
        assertThrows(StorageException.class, this::open);
        assertEquals(parser.toString(), Files.readString(file));
        Files.writeString(file, original.replace(owner.toString(), "invalid-uuid"));
        assertThrows(StorageException.class, this::open);
        parser = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
        parser.getAsJsonArray("links").get(0).getAsJsonObject().addProperty("clickCount", -1);
        Files.writeString(file, parser.toString());
        assertThrows(StorageException.class, this::open);
        assertEquals(parser.toString(), Files.readString(file));
        parser = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
        parser.getAsJsonArray("users").remove(0);
        Files.writeString(file, parser.toString());
        assertThrows(StorageException.class, this::open);
        Files.writeString(file, original);
        assertEquals(link, new JsonShortLinkRepository(open()).findByCode(link.code()).orElseThrow());
    }

}
