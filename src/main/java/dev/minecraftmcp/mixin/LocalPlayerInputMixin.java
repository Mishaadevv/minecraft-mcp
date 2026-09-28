package dev.minecraftmcp.mixin;

import dev.minecraftmcp.agent.AgentInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turns the agent's keys into movement. The player's tick works out where to walk in
 * {@link LocalPlayer#applyInput}, from the input's movement vector; the agent has already had its say on the
 * input itself ({@link KeyboardInputMixin}), so this makes sure the two strafe numbers the physics reads come
 * from the agent's keys rather than from an empty keyboard.
 *
 * <p>Nothing is teleported: the player walks, is blocked by walls, falls off ledges and gets hungry doing it,
 * exactly as they would with a hand on the mouse.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerInputMixin {
    @Inject(method = "applyInput", at = @At("TAIL"))
    private void mcp$agentMovement(CallbackInfo info) {
        Input wanted = AgentInput.presses();
        if (wanted == null) {
            return;
        }

        LocalPlayer player = (LocalPlayer) (Object) this;
        Vec2 move = AgentInput.moveVector(wanted);
        player.xxa = move.x;
        player.zza = move.y;
    }
}
