package core.fabric;

import core.common.CoreCommon;
import net.fabricmc.api.ModInitializer;

public final class CoreFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CoreCommon.init();
    }
}

