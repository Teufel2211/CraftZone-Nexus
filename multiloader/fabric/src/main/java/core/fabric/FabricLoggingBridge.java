package core.fabric;

import core.common.service.LoggingService;
import core.util.Safe;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

public final class FabricLoggingBridge {
    private static volatile boolean initialized;

    private FabricLoggingBridge() {}

    @SuppressWarnings("null")
    public static void init() {
        if (initialized) return;
        initialized = true;

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("FabricLoggingBridge.join", () -> LoggingService.logJoin(handler.player.getName().getString())));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            Safe.run("FabricLoggingBridge.leave", () -> LoggingService.logLeave(handler.player.getName().getString())));

        ServerMessageEvents.CHAT_MESSAGE.register((message, player, params) ->
            Safe.run("FabricLoggingBridge.chat", () -> {
                String msg = message.getContent().getString();
                LoggingService.logChat(player.getName().getString(), msg);
                if (msg.startsWith("/msg ") || msg.startsWith("/tell ") || msg.startsWith("/w ")) {
                    LoggingService.logPrivate(player.getName().getString(), msg);
                }
            }));

        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, params) ->
            Safe.run("FabricLoggingBridge.command", () -> {
                if (source == null || source.getPlayer() == null || message == null) return;
                String msg = message.getContent().getString();
                LoggingService.logCommand(source.getPlayer().getName().getString(), msg);
            }));

        ServerLifecycleEvents.SERVER_STOPPING.register(server ->
            Safe.run("FabricLoggingBridge.shutdown", LoggingService::shutdown));
    }
}
