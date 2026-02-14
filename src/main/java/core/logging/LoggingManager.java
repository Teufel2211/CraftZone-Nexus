package core.logging;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
// import net.fabricmc.fabric.api.event.player.UseItemCallback;
// import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityCombatEvents;
// import net.fabricmc.fabric.api.event.player.ServerPlayerEvents;
// import net.fabricmc.fabric.api.event.advancement.ServerAdvancementEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.network.ServerPlayerEntity;
// import net.minecraft.util.ActionResult;
// import net.minecraft.util.TypedActionResult;
// import net.minecraft.util.hit.BlockHitResult;
// import net.minecraft.item.ItemStack;
import core.discord.DiscordManager;
import core.util.Safe;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class LoggingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ExecutorService FILE_IO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "core-log-writer");
        t.setDaemon(true);
        return t;
    });
    private static final Deque<RecentLogEntry> RECENT_LOGS = new ArrayDeque<>();
    private static final int RECENT_LOG_LIMIT = 500;
    private static volatile boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        ServerMessageEvents.CHAT_MESSAGE.register((message, player, params) ->
            Safe.run("LoggingManager.onChatMessage", () -> onChatMessage(message, player, params)));
        // ServerMessageEvents.COMMAND.register(LoggingManager::onCommandMessage); // Not available in this Fabric API version

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("LoggingManager.onJoin", () -> logEvent("JOIN", handler.player, null)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            Safe.run("LoggingManager.onDisconnect", () -> logEvent("LEAVE", handler.player, null)));
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return;
            Safe.run("LoggingManager.onBlockBreak", () ->
                logEvent("BLOCK_BREAK", serverPlayer, state.getBlock().getName().getString() + " @ " + pos.toShortString()));
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || world.isClient()) return net.minecraft.util.ActionResult.PASS;
            Safe.run("LoggingManager.onBlockUse", () ->
                logEvent("BLOCK_USE", serverPlayer, world.getBlockState(hitResult.getBlockPos()).getBlock().getName().getString() + " @ " + hitResult.getBlockPos().toShortString()));
            return net.minecraft.util.ActionResult.PASS;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
            Safe.run("LoggingManager.shutdown", LoggingManager::shutdown));
        // ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register((server, attacker, target) -> {
        //     if (attacker instanceof ServerPlayerEntity player && target instanceof ServerPlayerEntity victim) {
        //         logEvent("KILL", player, victim.getName().getString() + " killed");
        //     }
        // });
        // ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> logEvent("RESPAWN", newPlayer, null));
        // ServerAdvancementEvents.ADVANCEMENT_GRANTED.register((player, advancement) -> logEvent("ADVANCEMENT", player, advancement.getDisplay().getTitle().getString()));
        // UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
        //     logEvent("BLOCK_INTERACT", (ServerPlayerEntity) player, hitResult.getBlockPos().toString());
        //     return ActionResult.PASS;
        // });
        // UseItemCallback.EVENT.register((player, world, hand) -> {
        //     ItemStack stack = player.getStackInHand(hand);
        //     logEvent("ITEM_USE", (ServerPlayerEntity) player, stack.getItem().getName().getString());
        //     return TypedActionResult.pass(stack);
        // });
    }

    private static void onChatMessage(SignedMessage message, ServerPlayerEntity player, Object params) {
        String msg = message.getContent().getString();
        logChatMessage(msg, player);
        if (msg.startsWith("/msg ") || msg.startsWith("/tell ") || msg.startsWith("/w ")) {
            logPrivateMessage(msg, player);
        }
    }

    private static void logPrivateMessage(String message, ServerPlayerEntity sender) {
        String log = formatPlayerLog(sender, message);
        appendLog("logs/private-messages.log", "private", log);
        Safe.run("DiscordManager.sendMessageLog", () -> DiscordManager.sendMessageLog(log));
    }

    private static void logChatMessage(String message, ServerPlayerEntity sender) {
        String log = formatPlayerLog(sender, message);
        appendLog("logs/chat-messages.log", "chat", log);
        Safe.run("DiscordManager.sendChatLog", () -> DiscordManager.sendChatLog(log));
    }

    private static void logCommand(String command, ServerPlayerEntity player) {
        String log = "[" + LocalDateTime.now().format(FORMATTER) + "] " + player.getName().getString() + " executed: " + command;
        appendLog("logs/commands.log", "command", log);
        Safe.run("DiscordManager.sendCommandLog", () -> DiscordManager.sendCommandLog(log));
    }

    private static void logEvent(String type, ServerPlayerEntity player, String details) {
        String msg = "[" + LocalDateTime.now().format(FORMATTER) + "] " + type + ": " + player.getName().getString();
        if (details != null) {
            msg = msg + " - " + details;
        }
        final String msgFinal = msg;
        appendLog("logs/events.log", "event", msgFinal);
        if (shouldSendEventToDiscord(type)) {
            Safe.run("DiscordManager.sendOPLog", () -> DiscordManager.sendOPLog(msgFinal));
        }
    }

    private static boolean shouldSendEventToDiscord(String type) {
        // Block interactions (including chest/container open clicks) are too noisy for Discord.
        return !"BLOCK_USE".equals(type);
    }

    private static String formatPlayerLog(ServerPlayerEntity sender, String message) {
        String safeMessage = message == null ? "" : message.replace("\n", " ").trim();
        return "[" + LocalDateTime.now().format(FORMATTER) + "] " + sender.getName().getString() + ": " + safeMessage;
    }

    private static void appendLog(String filePath, String channel, String line) {
        pushRecent(channel, line);
        FILE_IO_EXECUTOR.submit(() -> {
            try {
                Files.createDirectories(Paths.get("logs"));
                try (FileWriter writer = new FileWriter(filePath, true)) {
                    writer.write(line + "\n");
                }
            } catch (IOException e) {
                LOGGER.error("Failed to append log file: {}", filePath, e);
            }
        });
    }

    private static void pushRecent(String channel, String line) {
        synchronized (RECENT_LOGS) {
            RECENT_LOGS.addFirst(new RecentLogEntry(System.currentTimeMillis(), channel, line));
            while (RECENT_LOGS.size() > RECENT_LOG_LIMIT) {
                RECENT_LOGS.removeLast();
            }
        }
    }

    public static List<RecentLogEntry> getRecentLogs(int limit) {
        int capped = Math.max(1, Math.min(limit, RECENT_LOG_LIMIT));
        List<RecentLogEntry> out = new ArrayList<>(capped);
        synchronized (RECENT_LOGS) {
            int i = 0;
            for (RecentLogEntry entry : RECENT_LOGS) {
                if (i++ >= capped) break;
                out.add(entry);
            }
        }
        return out;
    }

    public static Map<String, Integer> getRecentChannelCounts(int limit) {
        Map<String, Integer> counts = new HashMap<>();
        for (RecentLogEntry entry : getRecentLogs(limit)) {
            counts.merge(entry.channel, 1, Integer::sum);
        }
        return counts;
    }

    public static final class RecentLogEntry {
        public final long timestamp;
        public final String channel;
        public final String message;

        public RecentLogEntry(long timestamp, String channel, String message) {
            this.timestamp = timestamp;
            this.channel = channel;
            this.message = message;
        }
    }

    private static void shutdown() {
        FILE_IO_EXECUTOR.shutdown();
        try {
            if (!FILE_IO_EXECUTOR.awaitTermination(2, TimeUnit.SECONDS)) {
                FILE_IO_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            FILE_IO_EXECUTOR.shutdownNow();
        }
    }
}
