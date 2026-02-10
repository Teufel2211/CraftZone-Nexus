package core.network;

import core.config.ConfigManager;
import core.util.Safe;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class HandshakeServer {
    private HandshakeServer() {}

    private static final Map<UUID, Integer> pendingTicks = new HashMap<>();
    private static final Map<UUID, Boolean> ok = new HashMap<>();
    private static boolean initialized;

    public static void init() {
        if (initialized) return;
        initialized = true;

        PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(HelloPayload.ID, (payload, context) ->
            context.server().execute(() -> Safe.run("HandshakeServer.onHello", () -> {
                ok.put(context.player().getUuid(), Boolean.TRUE);
                pendingTicks.remove(context.player().getUuid());
            }))
        );

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("HandshakeServer.onJoin", () -> {
                ServerPlayerEntity player = handler.player;
                ok.remove(player.getUuid());
                if (!requireClientMod()) return;
                pendingTicks.put(player.getUuid(), handshakeTimeoutTicks());
                // Client mod will answer with CoreHandshake.HELLO on join.
                // Vanilla clients will not, and will be kicked after timeout.
            })
        );

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            Safe.run("HandshakeServer.onDisconnect", () -> {
                UUID id = handler.player.getUuid();
                pendingTicks.remove(id);
                ok.remove(id);
            })
        );

        ServerTickEvents.END_SERVER_TICK.register(server ->
            Safe.run("HandshakeServer.tick", () -> tick(server))
        );
    }

    private static void tick(net.minecraft.server.MinecraftServer server) {
        if (!requireClientMod()) {
            pendingTicks.clear();
            return;
        }

        Iterator<Map.Entry<UUID, Integer>> it = pendingTicks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }

            UUID uuid = e.getKey();
            it.remove();
            if (Boolean.TRUE.equals(ok.get(uuid))) continue;

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                player.networkHandler.disconnect(Text.literal("This server requires the Core mod installed on the client."));
            }
        }
    }

    private static boolean requireClientMod() {
        return ConfigManager.getConfig() != null && ConfigManager.getConfig().network.requireClientMod;
    }

    private static int handshakeTimeoutTicks() {
        if (ConfigManager.getConfig() == null) return 100;
        return Math.max(20, ConfigManager.getConfig().network.handshakeTimeoutTicks);
    }
}
