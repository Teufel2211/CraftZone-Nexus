package core.clans;

import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class ClansGui {
    private ClansGui() {}

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inv, p) -> new ClansScreenHandler(syncId, inv),
            Text.literal("Clans")
        ));
    }
}

