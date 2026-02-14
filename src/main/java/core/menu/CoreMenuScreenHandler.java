package core.menu;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.text.Text;

public final class CoreMenuScreenHandler extends CommandMenuScreenHandler {
    public CoreMenuScreenHandler(int syncId, PlayerInventory playerInventory) {
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, 3);

        setEntry(10, named(Items.GOLD_INGOT, "Economy"), "/economy");
        setEntry(11, named(Items.CHEST, "Shop"), "/shop");
        setEntry(12, named(Items.EMERALD, "Auctions"), "/auction list");

        setEntry(14, named(Items.MAP, "World Map"), "/worldmap");
        setEntry(15, named(Items.COMPASS, "Waypoints"), "/map waypoint list");

        setEntry(16, named(Items.SHIELD, "Anti-Cheat"), "/ac");

        setEntry(19, named(Items.GRASS_BLOCK, "Claims"), "/claims");
        setEntry(20, named(Items.WHITE_BANNER, "Clans"), "/clan");
        setEntry(21, named(Items.PLAYER_HEAD, "Bounties"), "/bounty list");
        setEntry(22, named(Items.NAME_TAG, "Wanted"), "/wanted list");
        setEntry(23, named(Items.BOOK, "Help"), "/core help");
    }

    private static ItemStack named(net.minecraft.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }
}
