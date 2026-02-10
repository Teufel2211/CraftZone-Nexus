package core.claims;

import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class ClaimsGui {
    private ClaimsGui() {}

    public static void open(ServerPlayerEntity player) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inv, p) -> new ClaimsScreenHandler(syncId, inv),
            Text.literal("Claims")
        ));
    }
}

