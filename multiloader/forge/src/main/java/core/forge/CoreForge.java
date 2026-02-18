package core.forge;

import core.common.CoreCommon;
import net.minecraftforge.fml.common.Mod;

@Mod("core")
public final class CoreForge {
    public CoreForge() {
        CoreCommon.init();
    }
}

