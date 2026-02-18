package core.common.service;

import core.common.logging.LoggingCore;
import core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic logging service placeholder for migration phase 2.
 * Platform event wiring (Fabric/Forge/NeoForge) should call into this service.
 */
public final class LoggingService {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static volatile boolean initialized;

    private LoggingService() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        var cfg = ConfigManager.getConfig();
        boolean enabled = cfg != null && cfg.systems != null && cfg.systems.logging;
        LOGGER.info("Common LoggingService initialized (enabled={})", enabled);
    }

    public static void logJoin(String playerName) {
        LoggingCore.logEvent("JOIN", playerName, null);
        DiscordService.forwardJoin(playerName);
    }

    public static void logLeave(String playerName) {
        LoggingCore.logEvent("LEAVE", playerName, null);
        DiscordService.forwardLeave(playerName);
    }

    public static void logChat(String playerName, String message) {
        LoggingCore.logChat(playerName, message);
        DiscordService.forwardChat(playerName, message);
    }

    public static void logPrivate(String playerName, String message) {
        LoggingCore.logPrivateMessage(playerName, message);
    }

    public static void logCommand(String playerName, String command) {
        LoggingCore.logCommand(playerName, command);
        DiscordService.forwardCommand(playerName, command);
    }

    public static void shutdown() {
        LoggingCore.shutdown();
    }
}
