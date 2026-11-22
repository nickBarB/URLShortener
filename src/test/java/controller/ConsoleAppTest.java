package controller;

import support.TestApplication;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsoleAppTest {
    @TempDir Path directory;

    @Test
    void completeSessionShowsNotificationAndStopsAtExit() {
        try (var app = new TestApplication(directory, () -> "aaa111")) {
            String output = app.runConsole("help\ncreate https://example.com 1\n1\nopen aaa111\nexit\nwhoami\n");
            assertTrue(output.contains("Команды"));
            assertTrue(output.contains("Уведомление:"));
            assertTrue(output.contains("До свидания"));
            assertFalse(output.contains("Ваш UUID:"));
            assertEquals(List.of("https://example.com"), app.browser.opened());
        }
    }

    @Test
    void userAndStorageErrorsDoNotStopLoopAndEofEndsIt() {
        try (var app = new TestApplication(directory, () -> "same11")) {
            String output = app.runConsole("create not-a-url\n1\ncreate https://example.com\n1\n"
                + "create https://example.org\nwhoami\n");
            assertTrue(output.contains("http"));
            assertTrue(output.contains("Внутренняя ошибка"));
            assertTrue(output.contains("Ваш UUID:"));
            assertTrue(output.contains("Ввод завершён"));
        }
    }
}
