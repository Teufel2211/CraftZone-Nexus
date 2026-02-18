package core.common;

import core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreCommon {
    public static final Logger LOGGER = LoggerFactory.getLogger("core");

    private CoreCommon() {}

    public static void init() {
        ConfigManager.loadConfig();
        LOGGER.info("Core common initialized.");
    }
}
