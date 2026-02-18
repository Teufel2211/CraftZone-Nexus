package core.common;

import core.config.ConfigManager;
import core.common.service.DashboardService;
import core.common.service.DiscordService;
import core.common.service.LoggingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreCommon {
    public static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final String DEFAULT_WATERMARK = "Powered by Core Mod";
    private static volatile boolean shutdownHookRegistered;

    private CoreCommon() {}

    public static void init() {
        ConfigManager.loadConfig();
        LoggingService.init();
        DiscordService.init();
        DashboardService.init();
        LOGGER.info("Watermark: {}", getWatermark());
        registerShutdownHook();
        LOGGER.info("Core common initialized.");
    }

    public static String getWatermark() {
        var cfg = ConfigManager.getConfig();
        if (cfg != null && cfg.branding != null && cfg.branding.watermarkText != null && !cfg.branding.watermarkText.isBlank()) {
            return cfg.branding.watermarkText;
        }
        return DEFAULT_WATERMARK;
    }

    public static boolean watermarkEnabled() {
        var cfg = ConfigManager.getConfig();
        return cfg == null || cfg.branding == null || cfg.branding.enableWatermark;
    }

    private static void registerShutdownHook() {
        if (shutdownHookRegistered) return;
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LoggingService.shutdown();
            DashboardService.shutdown();
            DiscordService.shutdown();
        }, "core-common-shutdown"));
    }
}
