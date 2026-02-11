package core.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder mixin.
 *
 * The mod references this mixin in `core.mixins.json`. Keeping it present avoids client startup crashes
 * even if the actual death hooks are implemented elsewhere for now.
 */
@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityDeathMixin {
    // Intentionally empty.
}

