package controller;

import model.ShortLink;
import service.RandomShortCodeGenerator;
import support.TestApplication;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsoleControllerTest {
    private static final String URL = "https://example.com/page";
    @TempDir Path directory;
    private TestApplication app;

    @BeforeEach
    void openApplication() {
        app = new TestApplication(directory, new RandomShortCodeGenerator(6));
    }

    @AfterEach
    void closeApplication() {
        app.close();
    }

    @Test
    void firstCreationRequiresChoiceAndFollowingLinksReuseUser() {
        assertTrue(app.command("create " + URL).contains("Введите 1 или 2"));
        assertTrue(app.session.isGuest());
        assertTrue(app.command("1").contains("СОХРАНИТЕ"));
        UUID owner = app.session.currentUser().orElseThrow();
        assertFalse(app.command("create " + URL).contains("СОХРАНИТЕ"));
        assertEquals(2, app.links.listLinks(owner).size());
        assertTrue(app.command("whoami").contains(owner.toString()));
    }

    @Test
    void cancelledOrInvalidCreationDoesNotRegisterGuest() {
        app.command("create " + URL);
        app.command("cancel");
        assertTrue(app.session.isGuest());
        app.command("create not-a-url");
        assertTrue(app.command("1").contains("http"));
        assertTrue(app.session.isGuest());
    }

    @Test
    void existingIdentityChoiceAllowsCorrectingUuid() {
        UUID owner = UUID.randomUUID();
        app.links.createLink(owner, URL);
        app.command("create " + URL);
        app.command("2");
        assertTrue(app.command("invalid").contains("UUID"));
        assertTrue(app.session.isGuest());
        assertTrue(app.command(UUID.randomUUID().toString()).contains("не найден"));
        app.command(owner.toString());
        assertEquals(owner, app.session.currentUser().orElseThrow());
        assertEquals(2, app.links.listLinks(owner).size());
    }

    @Test
    void logoutAndRestartPreserveIdentityEvenAfterDeletingLastLink() {
        app.command("create " + URL);
        app.command("1");
        UUID owner = app.session.currentUser().orElseThrow();
        ShortLink link = app.links.listLinks(owner).getFirst();
        assertTrue(app.command("delete " + link.code()).contains("удалена"));
        app.command("logout");
        assertTrue(app.session.isGuest());
        app.close();
        app = new TestApplication(directory, new RandomShortCodeGenerator(6));
        assertTrue(app.command("login " + owner).contains("нет ссылок"));
        assertEquals(owner, app.session.currentUser().orElseThrow());
        assertTrue(app.command("create " + URL).contains("Короткая ссылка создана"));
    }

    @Test
    void openingPastedLinkCountsClickAndNotifiesOwnerExactlyOnce() {
        UUID owner = UUID.randomUUID();
        ShortLink link = app.links.createLink(owner, URL, 1);
        app.command("login " + owner);
        assertTrue(app.command("clck.ru/" + link.code()).contains("Уведомление:"));
        assertFalse(app.command("notifications").contains("Уведомление:"));
        assertTrue(app.command("open " + link.code()).contains("исчерпан"));
        assertEquals(List.of(URL), app.browser.opened());
    }

    @Test
    void unavailableBrowserPrintsManualUrlAndStillCountsClick() {
        UUID owner = UUID.randomUUID();
        ShortLink link = app.links.createLink(owner, URL, 5);
        app.browser.succeeds = false;
        String output = app.command("open " + link.code());
        assertTrue(output.contains("вручную"));
        assertTrue(output.contains(URL));
        assertEquals(1, app.links.listLinks(owner).getFirst().clickCount());
    }

    @Test
    void ownerCommandsAndLoginHaveReadableErrors() {
        assertTrue(app.command("list").contains("Сначала выполните"));
        assertTrue(app.command("edit-limit missing 5").contains("Сначала выполните"));
        assertTrue(app.command("delete missing").contains("Сначала выполните"));
        assertTrue(app.command("notifications").contains("Сначала выполните"));
        assertTrue(app.command("create").contains("Использование"));
        assertTrue(app.command("login invalid").contains("UUID"));
        assertTrue(app.command("login " + UUID.randomUUID()).contains("не найден"));
        assertTrue(app.command("dance").contains("help"));
        assertTrue(app.command("help").contains("edit-limit"));
    }

    @Test
    void ownerCanEditLimitAndListReflectsUpdatedValue() {
        UUID owner = UUID.randomUUID();
        ShortLink link = app.links.createLink(owner, URL, 5);
        app.command("login " + owner);
        assertTrue(app.command("edit-limit " + link.code() + " 10").contains("Лимит изменён"));
        assertTrue(app.command("list").contains("переходы: 0 из 10"));
        assertTrue(app.command("edit-limit " + link.code() + " abc").contains("целым числом"));
    }
}
