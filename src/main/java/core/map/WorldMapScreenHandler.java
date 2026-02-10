package core.map;

import core.menu.CommandMenuScreenHandler;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;

public final class WorldMapScreenHandler extends CommandMenuScreenHandler {
    public WorldMapScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, 3);

        setEntry(11, named(Items.MAP, "Waypoints"), "/map waypoint list");
        setEntry(13, named(Items.COMPASS, "Teleport (Waypoint)"), "/map waypoint teleport <name>");
        setEntry(15, named(Items.PAPER, "Share Waypoint"), "/map waypoint share <name> <player>");

        setEntry(22, named(Items.BOOK, "Map Help"), "/map");
    }

    private static ItemStack named(net.minecraft.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }
}
