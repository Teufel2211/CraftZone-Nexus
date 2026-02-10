package core.economy;

import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class EconomyMenuGui {
    private EconomyMenuGui() {}

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inv, p) -> new EconomyMenuScreenHandler(syncId, inv),
            Text.literal("Economy")
        ));
    }
}

