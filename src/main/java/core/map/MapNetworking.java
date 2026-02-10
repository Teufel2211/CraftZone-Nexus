package core.map;

import java.util.Map;
import core.util.Safe;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Networking utilities for map synchronization
 */
public class MapNetworking {
    public static void init() {
        // Register player join event
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            Safe.run("MapNetworking.onJoin", () -> syncWaypointsToClient(handler.player)));
    }

    /**
     * Synchronize waypoints visible to the player.
     */
    public static void syncWaypointsToClient(ServerPlayerEntity player) {
        // Client sync is optional; server-side systems should still work without it.
        Map<String, MapManager.Waypoint> visible = MapManager.getVisibleWaypoints(player);
        if (visible == null || visible.isEmpty()) return;
    }

    /**
     * Placeholder for claim overlay synchronization
     */
    public static void syncClaimOverlayToClient(net.minecraft.server.network.ServerPlayerEntity player, int centerX, int centerZ, int radius) {
        // No-op until a client-side listener is implemented.
    }

    /**
     * Placeholder for player markers synchronization
     */
    public static void syncPlayerMarkersToClient(net.minecraft.server.network.ServerPlayerEntity player) {
        // No-op until a client-side listener is implemented.
    }
}
