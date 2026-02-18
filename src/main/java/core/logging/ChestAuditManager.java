package core.logging;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import core.util.Safe;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ChestAuditManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File AUDIT_FILE = new File("data/core-chest-audit.json");
    private static final int MAX_ENTRIES_PER_CHEST = 250;
    private static final int SHOW_ENTRIES = 12;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private static final Map<UUID, OpenChestSession> openSessions = new ConcurrentHashMap<>();
    private static final Map<String, Deque<ChestAuditEntry>> historyByChest = new ConcurrentHashMap<>();

    private ChestAuditManager() {}

    @SuppressWarnings("null")
    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> Safe.run("ChestAuditManager.load", ChestAuditManager::load));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> Safe.run("ChestAuditManager.save", ChestAuditManager::save));
        ServerTickEvents.END_SERVER_TICK.register(server -> Safe.run("ChestAuditManager.tick", () -> tick(server)));

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            if (!isChest(world.getBlockState(pos))) return ActionResult.PASS;

            ItemStack held = serverPlayer.getMainHandStack();
            if (held != null && held.isOf(Items.WOODEN_AXE)) {
                showHistory(serverPlayer, world, pos);
                return ActionResult.SUCCESS;
            }

            startSession(serverPlayer, (ServerWorld) world, pos);
            return ActionResult.PASS;
        });
    }

    private static boolean isChest(BlockState state) {
        return state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST);
    }

    private static void startSession(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof ChestBlockEntity chest)) return;

        OpenChestSession session = new OpenChestSession();
        session.world = world.getRegistryKey().getValue().toString();
        session.pos = pos.toImmutable();
        session.playerName = player.getName().getString();
        session.before = snapshotCounts(chest);
        openSessions.put(player.getUuid(), session);
    }

    private static void tick(net.minecraft.server.MinecraftServer server) {
        List<UUID> done = new ArrayList<>();
        for (Map.Entry<UUID, OpenChestSession> entry : openSessions.entrySet()) {
            UUID uuid = entry.getKey();
            OpenChestSession session = entry.getValue();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player == null) {
                done.add(uuid);
                continue;
            }

            if (player.currentScreenHandler != player.playerScreenHandler) {
                continue;
            }

            ServerWorld world = server.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, Identifier.of(session.world)));
            if (world != null) {
                BlockEntity be = world.getBlockEntity(session.pos);
                if (be instanceof ChestBlockEntity chest) {
                    Map<String, Integer> after = snapshotCounts(chest);
                    writeDiff(session, after);
                }
            }
            done.add(uuid);
        }
        for (UUID uuid : done) {
            openSessions.remove(uuid);
        }
    }

    private static Map<String, Integer> snapshotCounts(ChestBlockEntity chest) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.getStack(i);
            if (stack == null || stack.isEmpty()) continue;
            Item item = stack.getItem();
            Identifier id = Registries.ITEM.getId(item);
            if (id == null) continue;
            String key = id.toString();
            counts.put(key, counts.getOrDefault(key, 0) + stack.getCount());
        }
        return counts;
    }

    private static void writeDiff(OpenChestSession session, Map<String, Integer> after) {
        Map<String, Integer> before = session.before == null ? Map.of() : session.before;
        Map<String, Integer> all = new HashMap<>();
        for (Map.Entry<String, Integer> e : before.entrySet()) all.put(e.getKey(), e.getValue());
        for (Map.Entry<String, Integer> e : after.entrySet()) all.putIfAbsent(e.getKey(), e.getValue());

        long now = System.currentTimeMillis();
        String chestKey = key(session.world, session.pos);
        Deque<ChestAuditEntry> history = historyByChest.computeIfAbsent(chestKey, k -> new ArrayDeque<>());

        for (String itemId : all.keySet()) {
            int b = before.getOrDefault(itemId, 0);
            int a = after.getOrDefault(itemId, 0);
            int delta = a - b;
            if (delta == 0) continue;

            ChestAuditEntry audit = new ChestAuditEntry();
            audit.timestamp = now;
            audit.player = session.playerName;
            audit.item = itemId;
            audit.delta = delta;
            history.addFirst(audit);
        }

        while (history.size() > MAX_ENTRIES_PER_CHEST) {
            history.removeLast();
        }
    }

    private static void showHistory(ServerPlayerEntity player, World world, BlockPos pos) {
        String chestKey = key(world.getRegistryKey().getValue().toString(), pos);
        Deque<ChestAuditEntry> history = historyByChest.get(chestKey);
        player.sendMessage(Text.literal("§6Chest Audit §7" + pos.toShortString()), false);
        if (history == null || history.isEmpty()) {
            player.sendMessage(Text.literal("§7No history for this chest."), false);
            return;
        }

        int count = 0;
        for (ChestAuditEntry e : history) {
            if (count++ >= SHOW_ENTRIES) break;
            String sign = e.delta > 0 ? "§a+" : "§c";
            String action = e.delta > 0 ? "put in" : "took out";
            String itemName = e.item;
            String line = "§8[" + TIME_FORMAT.format(Instant.ofEpochMilli(e.timestamp)) + "] §f" + e.player +
                " §7" + action + " " + sign + Math.abs(e.delta) + " §7x §f" + itemName;
            player.sendMessage(Text.literal(line), false);
        }
    }

    private static String key(String world, BlockPos pos) {
        return world + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ();
    }

    private static void load() {
        historyByChest.clear();
        if (!AUDIT_FILE.exists()) return;
        try (FileReader reader = new FileReader(AUDIT_FILE)) {
            Type type = new TypeToken<Map<String, List<ChestAuditEntry>>>() {}.getType();
            Map<String, List<ChestAuditEntry>> loaded = GSON.fromJson(reader, type);
            if (loaded == null) return;
            for (Map.Entry<String, List<ChestAuditEntry>> e : loaded.entrySet()) {
                List<ChestAuditEntry> sorted = new ArrayList<>(e.getValue());
                sorted.sort(Comparator.comparingLong(a -> -a.timestamp));
                Deque<ChestAuditEntry> deque = new ArrayDeque<>(sorted);
                historyByChest.put(e.getKey(), deque);
            }
            LOGGER.info("Loaded {} chest audit histories", historyByChest.size());
        } catch (IOException e) {
            LOGGER.error("Failed to load chest audit history", e);
        }
    }

    private static void save() {
        try {
            AUDIT_FILE.getParentFile().mkdirs();
            Map<String, List<ChestAuditEntry>> saveData = new HashMap<>();
            for (Map.Entry<String, Deque<ChestAuditEntry>> e : historyByChest.entrySet()) {
                saveData.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            try (FileWriter writer = new FileWriter(AUDIT_FILE)) {
                GSON.toJson(saveData, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save chest audit history", e);
        }
    }

    private static final class OpenChestSession {
        String world;
        BlockPos pos;
        String playerName;
        Map<String, Integer> before;
    }

    private static final class ChestAuditEntry {
        long timestamp;
        String player;
        String item;
        int delta;
    }
}
