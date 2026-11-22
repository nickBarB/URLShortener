package libs.logging;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DailyFileHandlerTest {
    @TempDir Path directory;

    @Test
    void appendsUtf8AndExceptionDetailsAndChangesFileAtMidnight() throws Exception {
        Path logs = directory.resolve("logs");
        LocalDate today = LocalDate.of(2026, 11, 22);
        DailyFileHandler handler = new DailyFileHandler(logs);
        LogRecord record = new LogRecord(Level.INFO, "Приложение запущено");
        record.setInstant(today.atStartOfDay(ZoneId.systemDefault()).toInstant());
        handler.publish(record);
        record.setMessage("Ошибка команды");
        record.setThrown(new IllegalStateException("Ошибка записи JSON"));
        handler.publish(record);
        handler.close();

        handler = new DailyFileHandler(logs);
        record.setThrown(null);
        record.setMessage("Повторный запуск");
        handler.publish(record);
        record.setInstant(today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        record.setMessage("Новый день");
        handler.publish(record);
        handler.close();

        String firstDay = Files.readString(logs.resolve(today + ".log"));
        assertTrue(firstDay.contains("Приложение запущено"));
        assertTrue(firstDay.contains("IllegalStateException: Ошибка записи JSON"));
        assertTrue(firstDay.contains("DailyFileHandlerTest"));
        assertTrue(firstDay.contains("Повторный запуск"));
        assertFalse(firstDay.contains("Новый день"));
        assertTrue(Files.readString(logs.resolve(today.plusDays(1) + ".log")).contains("Новый день"));
    }
}
