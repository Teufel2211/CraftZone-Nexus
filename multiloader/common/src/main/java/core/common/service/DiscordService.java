package core.common.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import core.common.CoreCommon;
import core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loader-agnostic discord webhook service.
 */
public final class DiscordService {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "core-discord-webhook");
        t.setDaemon(true);
        return t;
    });
    private static final Deque<Long> SEND_TIMES = new ArrayDeque<>();
    private static volatile boolean initialized;

    private DiscordService() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        var cfg = ConfigManager.getConfig();
        boolean enabled = isEnabled(cfg);
        boolean hasToken = cfg != null && cfg.discord != null && cfg.discord.botToken != null && !cfg.discord.botToken.isBlank();
        LOGGER.info("Common DiscordService initialized (enabled={}, tokenConfigured={}, webhookConfigured={})", enabled, hasToken, hasAnyWebhook(cfg));
        if (hasToken && !hasAnyWebhook(cfg)) {
            LOGGER.warn("Discord bot token is configured, but common runtime currently uses webhook forwarding only. Configure a webhook URL to enable Discord output.");
        }
    }

    public static void forwardJoin(String playerName) {
        var cfg = ConfigManager.getConfig();
        if (!isEnabled(cfg) || cfg.discord == null || !cfg.discord.forwardJoins) return;
        String webhook = chooseWebhook(cfg.discord.adminActionsWebhook, cfg.discord.webhookUrl);
        enqueue(webhook, "\uD83D\uDFE2 **JOIN** `" + playerName + "` joined the server.");
    }

    public static void forwardLeave(String playerName) {
        var cfg = ConfigManager.getConfig();
        if (!isEnabled(cfg) || cfg.discord == null || !cfg.discord.forwardLeaves) return;
        String webhook = chooseWebhook(cfg.discord.adminActionsWebhook, cfg.discord.webhookUrl);
        enqueue(webhook, "\uD83D\uDD34 **LEAVE** `" + playerName + "` left the server.");
    }

    public static void forwardChat(String playerName, String message) {
        var cfg = ConfigManager.getConfig();
        if (!isEnabled(cfg) || cfg.discord == null || !cfg.discord.forwardChat) return;
        String webhook = chooseWebhook(cfg.discord.messageLogWebhook, cfg.discord.webhookUrl);
        enqueue(webhook, "\uD83D\uDCAC **CHAT** `" + playerName + "`: " + safeText(message));
    }

    public static void forwardCommand(String playerName, String command) {
        var cfg = ConfigManager.getConfig();
        if (!isEnabled(cfg) || cfg.discord == null || !cfg.discord.forwardCommands) return;
        String webhook = chooseWebhook(cfg.discord.commandLogWebhook, cfg.discord.webhookUrl);
        enqueue(webhook, "\uD83D\uDCDD **CMD** `" + playerName + "` used `" + safeText(command) + "`");
    }

    public static void shutdown() {
        WORKER.shutdown();
    }

    private static boolean isEnabled(ConfigManager.Config cfg) {
        if (cfg == null || cfg.systems == null || !cfg.systems.discord || cfg.discord == null) return false;
        if (cfg.discord.enabled) return true;
        return !chooseWebhook(
            cfg.discord.webhookUrl,
            cfg.discord.adminActionsWebhook,
            cfg.discord.messageLogWebhook,
            cfg.discord.commandLogWebhook,
            cfg.discord.anticheatAlertsWebhook
        ).isBlank();
    }

    private static boolean hasAnyWebhook(ConfigManager.Config cfg) {
        return cfg != null && cfg.discord != null && !chooseWebhook(
            cfg.discord.webhookUrl,
            cfg.discord.adminActionsWebhook,
            cfg.discord.messageLogWebhook,
            cfg.discord.commandLogWebhook,
            cfg.discord.anticheatAlertsWebhook
        ).isBlank();
    }

    private static String chooseWebhook(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static void enqueue(String webhook, String content) {
        if (webhook == null || webhook.isBlank() || content == null || content.isBlank()) return;
        WORKER.submit(() -> sendWebhook(webhook, withWatermark(content)));
    }

    private static void sendWebhook(String webhook, String content) {
        throttle();
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("content", trimDiscord(content));
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(webhook))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                .build();

            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = res.statusCode();
            if (code == 429) {
                LOGGER.warn("Discord webhook rate-limited (HTTP 429).");
                return;
            }
            if (code < 200 || code >= 300) {
                LOGGER.warn("Discord webhook failed: HTTP {}", code);
            }
        } catch (Exception e) {
            LOGGER.warn("Discord webhook request failed: {}", e.toString());
        }
    }

    private static void throttle() {
        long now = System.currentTimeMillis();
        synchronized (SEND_TIMES) {
            while (!SEND_TIMES.isEmpty() && now - SEND_TIMES.peekFirst() > 5_000) {
                SEND_TIMES.removeFirst();
            }
            if (SEND_TIMES.size() >= 5) {
                long wait = 5_100 - (now - Objects.requireNonNull(SEND_TIMES.peekFirst()));
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            SEND_TIMES.addLast(System.currentTimeMillis());
        }
    }

    private static String safeText(String value) {
        if (value == null) return "";
        return value.replace('\n', ' ').trim();
    }

    private static String trimDiscord(String value) {
        String safe = safeText(value);
        return safe.length() <= 1800 ? safe : safe.substring(0, 1800) + "...";
    }

    private static String withWatermark(String content) {
        if (!CoreCommon.watermarkEnabled()) return content;
        String watermark = CoreCommon.getWatermark();
        if (watermark == null || watermark.isBlank()) return content;
        return "`" + watermark + "`\n" + content;
    }
}
