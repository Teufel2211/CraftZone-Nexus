package core.anticheat;

import core.menu.CommandMenuScreenHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;

public final class AntiCheatScreenHandler extends CommandMenuScreenHandler {
    public AntiCheatScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, 3);

        setEntry(12, named(Items.SHIELD, "Anti-Cheat Help"), "/ac help");
        setEntry(14, named(Items.REPEATER, "Reload Config"), "/ac reload");
    }

    private static ItemStack named(net.minecraft.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }
}
