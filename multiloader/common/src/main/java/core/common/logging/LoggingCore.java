package core.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Loader-agnostic core logging logic.
 * Platform adapters should call these methods for lifecycle/chat/command events.
 */
public final class LoggingCore {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ExecutorService FILE_IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "core-common-log-writer");
        t.setDaemon(true);
        return t;
    });
    private static final Deque<RecentLogEntry> RECENT_LOGS = new ArrayDeque<>();
    private static final int RECENT_LOG_LIMIT = 3000;

    private LoggingCore() {}

    public static void logEvent(String type, String actor, String details) {
        if (type == null || type.isBlank() || actor == null || actor.isBlank()) return;
        String msg = "[" + now() + "] " + type + ": " + actor + (details == null || details.isBlank() ? "" : (" - " + details));
        appendLog("logs/events.log", "event", msg);
    }

    public static void logChat(String playerName, String message) {
        if (playerName == null || playerName.isBlank() || message == null) return;
        String msg = "[" + now() + "] " + playerName + ": " + sanitize(message);
        appendLog("logs/chat-messages.log", "chat", msg);
    }

    public static void logPrivateMessage(String playerName, String message) {
        if (playerName == null || playerName.isBlank() || message == null) return;
        String msg = "[" + now() + "] " + playerName + ": " + sanitize(message);
        appendLog("logs/private-messages.log", "private", msg);
    }

    public static void logCommand(String playerName, String command) {
        if (playerName == null || playerName.isBlank() || command == null || command.isBlank()) return;
        String normalized = command.startsWith("/") ? command : "/" + command;
        String msg = "[" + now() + "] " + playerName + " executed: " + normalized;
        appendLog("logs/commands.log", "command", msg);
    }

    public static List<RecentLogEntry> getRecentLogs(int limit) {
        int capped = Math.max(1, Math.min(limit, RECENT_LOG_LIMIT));
        List<RecentLogEntry> out = new ArrayList<>(capped);
        synchronized (RECENT_LOGS) {
            int i = 0;
            for (RecentLogEntry entry : RECENT_LOGS) {
                if (i++ >= capped) break;
                out.add(entry);
            }
        }
        return out;
    }

    public static Map<String, Integer> getRecentChannelCounts(int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (RecentLogEntry entry : getRecentLogs(limit)) {
            counts.put(entry.channel, counts.getOrDefault(entry.channel, 0) + 1);
        }
        return counts;
    }

    public static void shutdown() {
        FILE_IO_EXECUTOR.shutdown();
        try {
            if (!FILE_IO_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                FILE_IO_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FILE_IO_EXECUTOR.shutdownNow();
        }
    }

    private static String now() {
        return LocalDateTime.now().format(FORMATTER);
    }

    private static String sanitize(String text) {
        return text.replace('\n', ' ').trim();
    }

    private static void appendLog(String filePath, String channel, String line) {
        pushRecent(channel, line);
        FILE_IO_EXECUTOR.submit(() -> {
            try {
                Files.createDirectories(Paths.get("logs"));
                try (FileWriter writer = new FileWriter(filePath, true)) {
                    writer.write(line + "\n");
                }
            } catch (IOException e) {
                LOGGER.error("Failed to append common log file: {}", filePath, e);
            }
        });
    }

    private static void pushRecent(String channel, String line) {
        synchronized (RECENT_LOGS) {
            RECENT_LOGS.addFirst(new RecentLogEntry(System.currentTimeMillis(), channel, line));
            while (RECENT_LOGS.size() > RECENT_LOG_LIMIT) RECENT_LOGS.removeLast();
        }
    }

    public static final class RecentLogEntry {
        public final long timestamp;
        public final String channel;
        public final String message;

        public RecentLogEntry(long timestamp, String channel, String message) {
            this.timestamp = timestamp;
            this.channel = channel;
            this.message = message;
        }
    }
}

