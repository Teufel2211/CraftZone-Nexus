package core.bounty;

import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class BountyGui {
    private BountyGui() {}

    public static void open(ServerPlayerEntity player, int page) {
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, inv, p) -> new BountyScreenHandler(syncId, inv),
            Text.literal("Bounties")
        ));
    }
}

