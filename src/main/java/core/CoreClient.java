package core;

import core.network.HelloPayload;
import core.util.Safe;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class CoreClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Safe.run("CoreClient.registerPayloads", () ->
            PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC)
        );

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
            Safe.run("CoreClient.handshake", () -> ClientPlayNetworking.send(new HelloPayload()))
        );
    }
}
