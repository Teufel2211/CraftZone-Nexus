package core.announcement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import core.discord.DiscordManager;
import core.util.Safe;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class AnnouncementManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File ANNOUNCEMENT_FILE = new File("config/core-announcements.json");

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "core-announcements");
        t.setDaemon(true);
        return t;
    });
    private static volatile ScheduledFuture<?> scheduleTask;

    private static volatile MinecraftServer serverRef;
    private static volatile AnnouncementConfig config;
    private static volatile int lastIndex = -1;

    private AnnouncementManager() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("AnnouncementManager.serverStarted", () -> {
            serverRef = server;
            loadConfigFile();
            restartSchedule();
        }));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("AnnouncementManager.serverStopping", () -> stopSchedule()));
    }

    public static void reload() {
        loadConfigFile();
        restartSchedule();
    }

    public static AnnouncementConfig getConfig() {
        return config;
    }

    @SuppressWarnings("null")
    private static void loadConfigFile() {
        if (!ANNOUNCEMENT_FILE.exists()) {
            config = AnnouncementConfig.defaultConfig();
            saveConfigFile();
            return;
        }

        try (FileReader reader = new FileReader(ANNOUNCEMENT_FILE)) {
            AnnouncementConfig loaded = GSON.fromJson(reader, AnnouncementConfig.class);
            config = loaded == null ? AnnouncementConfig.defaultConfig() : loaded.normalized();
        } catch (Exception e) {
            LOGGER.error("Failed to load announcement config, using defaults", e);
            config = AnnouncementConfig.defaultConfig();
        }
    }

    private static void saveConfigFile() {
        try {
            ANNOUNCEMENT_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(ANNOUNCEMENT_FILE)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save announcement config", e);
        }
    }

    private static synchronized void restartSchedule() {
        stopSchedule();
        if (serverRef == null || config == null || !config.enabled || config.messages.isEmpty()) return;

        long interval = Math.max(15, config.intervalSeconds);
        scheduleTask = SCHEDULER.scheduleAtFixedRate(
            () -> Safe.run("AnnouncementManager.tick", AnnouncementManager::broadcastNextAutomatic),
            interval,
            interval,
            TimeUnit.SECONDS
        );
        LOGGER.info("Auto-announcements enabled: every {} seconds ({} messages)", interval, config.messages.size());
    }

    private static synchronized void stopSchedule() {
        if (scheduleTask != null) {
            scheduleTask.cancel(false);
            scheduleTask = null;
        }
    }

    private static void broadcastNextAutomatic() {
        MinecraftServer server = serverRef;
        AnnouncementConfig cfg = config;
        if (server == null || cfg == null || !cfg.enabled || cfg.messages.isEmpty()) return;

        String message = nextMessage(cfg);
        if (message == null || message.isBlank()) return;

        String rendered = applyPlaceholders(server, message);
        String mode = cfg.mode == null ? "chat" : cfg.mode.toLowerCase(Locale.ROOT);
        server.execute(() -> {
            Text payload = Text.literal("§6[Announcement] §f" + rendered);
            if ("actionbar".equals(mode) || "both".equals(mode)) {
                for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                    player.sendMessage(payload, true);
                }
            }
            if (!"actionbar".equals(mode)) {
                server.getPlayerManager().broadcast(payload, false);
            }
        });
        Safe.run("DiscordManager.sendAdminActionLog", () -> DiscordManager.sendAdminActionLog("Auto Announcement: " + rendered));
    }

    private static String nextMessage(AnnouncementConfig cfg) {
        if (cfg.randomOrder) {
            int idx = (int) (Math.random() * cfg.messages.size());
            return cfg.messages.get(idx);
        }
        lastIndex = (lastIndex + 1) % cfg.messages.size();
        return cfg.messages.get(lastIndex);
    }

    private static String applyPlaceholders(MinecraftServer server, String message) {
        String out = message;
        out = out.replace("{online}", String.valueOf(server.getCurrentPlayerCount()));
        out = out.replace("{maxPlayers}", String.valueOf(server.getMaxPlayerCount()));
        out = out.replace("{motd}", server.getServerMotd());
        return out;
    }

    public static final class AnnouncementConfig {
        public boolean enabled = true;
        public int intervalSeconds = 300;
        public String mode = "chat"; // chat | actionbar | both
        public boolean randomOrder = false;
        public List<String> messages = new ArrayList<>();

        public static AnnouncementConfig defaultConfig() {
            AnnouncementConfig cfg = new AnnouncementConfig();
            cfg.messages.add("Welcome on the server! Use /core help for all commands.");
            cfg.messages.add("Shop tip: use /shop and /sell for quick economy actions.");
            cfg.messages.add("Players online: {online}/{maxPlayers}");
            return cfg;
        }

        public AnnouncementConfig normalized() {
            if (intervalSeconds < 15) intervalSeconds = 15;
            if (mode == null || mode.isBlank()) mode = "chat";
            if (messages == null) messages = new ArrayList<>();
            messages.removeIf(m -> m == null || m.isBlank());
            return this;
        }
    }
}
