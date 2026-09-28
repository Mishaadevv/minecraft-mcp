package dev.minecraftmcp.mixin;

import dev.minecraftmcp.agent.AgentInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the agent press keys. This is where the client reads the keyboard - the base {@code ClientInput} it
 * overrides does nothing at all, which is why the patch has to live here - so overwriting the result means
 * sprinting, sneaking and jumping behave as if a player were holding those keys.
 *
 * <p>Movement is the other half, in {@link LocalPlayerInputMixin}.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void mcp$agentPresses(CallbackInfo info) {
        Input wanted = AgentInput.presses();
        if (wanted != null) {
            ((KeyboardInput) (Object) this).keyPresses = wanted;
        }
    }
}
