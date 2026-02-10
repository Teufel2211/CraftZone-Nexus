package core.economy;

import core.menu.CommandMenuScreenHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;

public final class EconomyMenuScreenHandler extends CommandMenuScreenHandler {
    public EconomyMenuScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, 3);

        setEntry(10, named(Items.GOLD_NUGGET, "Balance"), "/balance");
        setEntry(11, named(Items.CHEST, "Shop"), "/shop");
        setEntry(12, named(Items.EMERALD, "Baltop"), "/baltop");

        setEntry(14, named(Items.PAPER, "Auction List"), "/auction list");
        setEntry(15, named(Items.DIAMOND, "Auction Claim"), "/auction claim");

        setEntry(16, named(Items.BOOK, "Help"), "/core help");
    }

    private static ItemStack named(net.minecraft.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }
}
