package controller;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ConsoleApp {
    private static final Logger LOG = Logger.getLogger(ConsoleApp.class.getName());

    private final ConsoleController processor;
    private final BufferedReader in;
    private final PrintStream out;

    public ConsoleApp(ConsoleController processor, BufferedReader in, PrintStream out) {
        this.processor = processor;
        this.in = in;
        this.out = out;
    }

    public void run() {
        out.println(ConsoleMessages.WELCOME);
        while (true) {
            out.print("> ");
            out.flush();
            String line = readLine();
            if (line == null) {
                out.println();
                out.println("Ввод завершён. До свидания!");
                return;
            }
            if (!handle(line)) {
                return;
            }
        }
    }

    private boolean handle(String line) {
        try {
            boolean goOn = processor.execute(line);
            if (goOn) {
                processor.showPendingNotifications();
            }
            return goOn;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Ошибка выполнения команды консоли", e);
            out.println("Внутренняя ошибка: " + e.getMessage()
                + ". Если она повторяется, проверьте доступ к папке с данными и сообщите разработчику.");
            return true;
        }
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Ошибка чтения консоли", e);
            out.println("Ошибка чтения консоли: " + e.getMessage());
            return null;
        }
    }
}
