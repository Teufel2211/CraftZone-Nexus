package core.bounty;

import core.menu.CommandMenuScreenHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;

public final class BountyScreenHandler extends CommandMenuScreenHandler {
    public BountyScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, 3);

        setEntry(12, named(Items.PLAYER_HEAD, "Bounty List"), "/bounty list");
        setEntry(14, named(Items.EMERALD, "Bounty Top"), "/bounty top");
    }

    private static ItemStack named(net.minecraft.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }
}
