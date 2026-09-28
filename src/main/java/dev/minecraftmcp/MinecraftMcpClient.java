package dev.minecraftmcp;

import dev.minecraftmcp.agent.AgentBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft MCP: a Model Context Protocol server that lives inside the running client.
 *
 * <p>An agent outside the game connects to it - over MCP at {@code POST /mcp}, or over the plain REST pair
 * {@code /state} and {@code /act} - and gets to look around and play: walk, mine, build, fight, craft, read
 * chat. Every request is queued and carried out one tick at a time on the game thread, so nothing here can
 * stall a frame, and every answer comes back with a fresh view of the world.
 *
 * <p>It listens on the loopback address only, and it can be switched off in {@code config/minecraft-mcp.json}.
 */
public class MinecraftMcpClient implements ClientModInitializer {
    public static final String MOD_ID = "minecraftmcp";
    /** Set this system property to a port to override the config file; development runs use it. */
    public static final String PORT_PROPERTY = "minecraftmcp.port";
    public static final Logger LOGGER = LoggerFactory.getLogger("Minecraft MCP");

    @Override
    public void onInitializeClient() {
        // The listeners are always in place; whether anything happens depends on the bridge being started,
        // which is a check of a single boolean per tick.
        ClientTickEvents.END_CLIENT_TICK.register(AgentBridge::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> AgentBridge.remember(message.getString()));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, received) ->
                AgentBridge.remember(message.getString()));

        AgentBridge.start(AgentBridge.loadConfig());
    }
}
