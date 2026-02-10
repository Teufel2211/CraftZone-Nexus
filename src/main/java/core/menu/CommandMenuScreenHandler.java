package core.menu;

import core.util.Safe;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

public class CommandMenuScreenHandler extends ScreenHandler {
    protected final SimpleInventory menuInventory;
    private final String[] commands;
    private final int rows;

    protected CommandMenuScreenHandler(ScreenHandlerType<?> type, int syncId, PlayerInventory playerInventory, int rows) {
        super(type, syncId);
        this.rows = rows;
        this.menuInventory = new SimpleInventory(rows * 9);
        this.commands = new String[menuInventory.size()];

        int slotIndex = 0;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < 9; col++) {
                int x = 8 + col * 18;
                int y = 18 + row * 18;
                this.addSlot(new ReadOnlySlot(menuInventory, slotIndex++, x, y));
            }
        }

        int playerInvY = 18 + rows * 18 + 14;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, playerInvY + row * 18));
            }
        }

        int hotbarY = playerInvY + 58;
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, hotbarY));
        }
    }

    protected void setEntry(int slot, ItemStack stack, String command) {
        if (slot < 0 || slot >= menuInventory.size()) return;
        menuInventory.setStack(slot, stack);
        commands[slot] = command;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        if (slotIndex >= 0 && slotIndex < menuInventory.size()) {
            String command = commands[slotIndex];
            if (command != null && player instanceof ServerPlayerEntity serverPlayer) {
                Safe.run("CommandMenu.execute", () -> execute(serverPlayer, command));
            }
            return;
        }
        super.onSlotClick(slotIndex, button, actionType, player);
    }

    private static void execute(ServerPlayerEntity player, String command) {
        String cmd = command.startsWith("/") ? command.substring(1) : command;
        if (cmd.isBlank()) return;
        player.getCommandSource().getServer().getCommandManager().parseAndExecute(player.getCommandSource(), cmd);
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }

    private static final class ReadOnlySlot extends Slot {
        private ReadOnlySlot(SimpleInventory inventory, int index, int x, int y) {
            super(inventory, index, x, y);
        }

        @Override
        public boolean canInsert(ItemStack stack) {
            return false;
        }

        @Override
        public boolean canTakeItems(PlayerEntity playerEntity) {
            return false;
        }
    }
}
