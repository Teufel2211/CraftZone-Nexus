package core.menu;

import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class CoreMenuGui {
    private CoreMenuGui() {}

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inv, p) -> new CoreMenuScreenHandler(syncId, inv),
            Text.literal("Core Menu")
        ));
    }
}

