package dev.minecraftmcp.mixin;

import dev.minecraftmcp.agent.AgentBridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Switches the game to borderless fullscreen mode on startup so the user can watch the AI agent play.
 *
 * <p>This mixin intercepts the game's initialization and sets the window to borderless fullscreen,
 * which looks like fullscreen but allows easy alt-tabbing and doesn't lock the mouse to the game window.
 */
@Mixin(Minecraft.class)
public class BorderlessFullscreenMixin {
    @Inject(method = "onGameLoadFinished", at = @At("TAIL"))
    private void mcp$setBorderlessFullscreen(CallbackInfo info) {
        if (!AgentBridge.isBorderlessFullscreen()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.getWindow() != null) {
            client.getWindow().toggleFullScreen();
        }
    }
}
