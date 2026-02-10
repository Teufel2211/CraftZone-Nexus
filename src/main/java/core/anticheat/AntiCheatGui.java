package core.anticheat;

import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class AntiCheatGui {
    private AntiCheatGui() {}

    public static void open(ServerPlayerEntity player, int page) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inv, p) -> new AntiCheatScreenHandler(syncId, inv),
            Text.literal("Anti-Cheat")
        ));
    }
}

