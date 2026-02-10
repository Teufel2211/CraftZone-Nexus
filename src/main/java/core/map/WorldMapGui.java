package core.map;

import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class WorldMapGui {
    private WorldMapGui() {}

    public static void open(ServerPlayerEntity player, int page) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inv, p) -> new WorldMapScreenHandler(syncId, inv),
            Text.literal("World Map")
        ));
    }
}

