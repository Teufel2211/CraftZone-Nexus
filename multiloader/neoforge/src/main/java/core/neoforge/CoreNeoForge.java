package core.neoforge;

import core.common.CoreCommon;
import net.neoforged.fml.common.Mod;

@Mod("core")
public final class CoreNeoForge {
    public CoreNeoForge() {
        CoreCommon.init();
    }
}

