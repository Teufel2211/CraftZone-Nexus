package core.economy;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

public final class ShopGui {
    private ShopGui() {}

    public static void open(ServerPlayerEntity player) {
        if (player == null) return;
        player.openHandledScreen(new Factory(false));
    }

    public static void openSell(ServerPlayerEntity player) {
        if (player == null) return;
        player.openHandledScreen(new Factory(true));
    }

    private static final class Factory implements NamedScreenHandlerFactory {
        private final boolean startSellMode;

        private Factory(boolean startSellMode) {
            this.startSellMode = startSellMode;
        }

        @Override
        public Text getDisplayName() {
            return Text.literal(startSellMode ? "Sell" : "Shop");
        }

        @Override
        public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
            return new ShopScreenHandler(syncId, playerInventory, startSellMode);
        }
    }
}
