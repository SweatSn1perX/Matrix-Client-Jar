package net.matrix.systems;

import net.matrix.systems.auth.AuthManager;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ChatLogManager — Handles saving incoming chat messages to a local file.
 * This is decoupled from the module system for better organization.
 */
public class ChatLogManager {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static PrintWriter writer;

    public static void start() {
        if (writer != null)
            return;

        try {
            String username = AuthManager.getUsername();
            String safe = username != null ? username.replaceAll("[^a-zA-Z0-9_.-]", "_") : "default";
            Path userDir = Path.of("matrix", "users", safe);

            if (!Files.exists(userDir)) {
                Files.createDirectories(userDir);
            }

            Path logPath = userDir.resolve("chat.log");
            writer = new PrintWriter(new BufferedWriter(new FileWriter(logPath.toFile(), true)), true);
            writer.println("--- Chat Logging started at " + LocalDateTime.now().format(TIMESTAMP_FORMAT) + " ---");
        } catch (Exception e) {
            System.err.println("[Matrix ChatLogManager] Failed to open log file.");
            e.printStackTrace();
        }
    }

    public static void stop() {
        if (writer != null) {
            writer.println("--- Chat Logging stopped at " + LocalDateTime.now().format(TIMESTAMP_FORMAT) + " ---");
            writer.close();
            writer = null;
        }
    }

    public static void log(String message) {
        if (writer != null && Screens.saveChatToFile.get()) {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            writer.println("[" + timestamp + "] " + message);
        }
    }

    public static void updateState() {
        if (Screens.saveChatToFile.get()) {
            start();
        } else {
            stop();
        }
    }
}
