package core.mixin;

import core.logging.LoggingManager;
import net.minecraft.network.packet.c2s.play.ChatCommandSignedC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerCommandMixin {
    @Shadow @Final public ServerPlayerEntity player;

    @Inject(method = "onChatCommandSigned", at = @At("HEAD"))
    private void core$logSignedCommand(ChatCommandSignedC2SPacket packet, CallbackInfo ci) {
        if (packet == null) return;
        LoggingManager.logCommandExecution(this.player, packet.command());
    }

    @Inject(method = "executeCommand", at = @At("HEAD"))
    private void core$logCommandToDiscord(String command, CallbackInfo ci) {
        LoggingManager.logCommandExecution(this.player, command);
    }
}
