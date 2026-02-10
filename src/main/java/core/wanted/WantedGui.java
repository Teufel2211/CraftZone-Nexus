package core.wanted;

import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class WantedGui {
    private WantedGui() {}

    public static void open(ServerPlayerEntity player, int page) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inv, p) -> new WantedScreenHandler(syncId, inv),
            Text.literal("Wanted")
        ));
    }
}

