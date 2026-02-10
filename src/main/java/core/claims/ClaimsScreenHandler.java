package core.claims;

import core.menu.CommandMenuScreenHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;

public final class ClaimsScreenHandler extends CommandMenuScreenHandler {
    public ClaimsScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, 3);

        setEntry(11, named(Items.GRASS_BLOCK, "Claim Chunk"), "/claim");
        setEntry(13, named(Items.BARRIER, "Unclaim Chunk"), "/unclaim");
        setEntry(15, named(Items.MAP, "Sync"), "/claim sync");
    }

    private static ItemStack named(net.minecraft.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }
}
