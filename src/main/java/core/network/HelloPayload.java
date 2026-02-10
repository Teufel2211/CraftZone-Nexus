package core.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * Empty handshake payload sent from client -> server to prove the client has the mod installed.
 */
public record HelloPayload() implements CustomPayload {
    public static final Id<HelloPayload> ID = new Id<>(CoreHandshake.HELLO);
    public static final PacketCodec<RegistryByteBuf, HelloPayload> CODEC =
        CustomPayload.codecOf((buf, payload) -> {}, buf -> new HelloPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}

