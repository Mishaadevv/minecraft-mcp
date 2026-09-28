package dev.minecraftmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import dev.minecraftmcp.agent.AgentBridge;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The Model Context Protocol half of the mod: JSON-RPC 2.0 over HTTP, which is the transport the protocol
 * calls streamable HTTP.
 *
 * <p>{@code POST /mcp} takes one message or a batch and answers with JSON. A message without an {@code id} is a
 * notification and gets an empty {@code 202}, which is exactly what a client's {@code notifications/initialized}
 * expects. Sessions are not used: nothing here needs to survive between calls, because the game itself is the
 * state.
 *
 * <p>{@code GET /mcp} answers 405 - this server has nothing to push, so it offers no server-sent events.
 */
public final class McpServer {
    private static final String[] SUPPORTED = {"2025-06-18", "2025-03-26", "2024-11-05"};

    private McpServer() {
    }

    public static void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.getResponseHeaders().add("Allow", "POST");
            AgentBridge.sendText(exchange, 405,
                    "MCP over HTTP speaks POST here. The friendly endpoints are /state, /act and /health.");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (JsonParseException broken) {
            AgentBridge.sendJson(exchange, 200, error(null, -32700, "parse error: " + broken.getMessage()));
            return;
        }

        if (parsed.isJsonArray()) {
            JsonArray answers = new JsonArray();
            for (JsonElement message : parsed.getAsJsonArray()) {
                JsonObject answer = dispatch(message);
                if (answer != null) {
                    answers.add(answer);
                }
            }
            if (answers.isEmpty()) {
                AgentBridge.sendEmpty(exchange, 202);
                return;
            }
            AgentBridge.sendJson(exchange, 200, answers);
            return;
        }

        JsonObject answer = dispatch(parsed);
        if (answer == null) {
            AgentBridge.sendEmpty(exchange, 202);
            return;
        }
        AgentBridge.sendJson(exchange, 200, answer);
    }

    /** Carries out one JSON-RPC message. {@code null} means it was a notification and needs no answer. */
    private static JsonObject dispatch(JsonElement element) {
        if (!element.isJsonObject()) {
            return error(null, -32600, "not a JSON-RPC message");
        }

        JsonObject message = element.getAsJsonObject();
        JsonElement id = message.get("id");
        String method = message.has("method") ? message.get("method").getAsString() : "";

        if (id == null || id.isJsonNull()) {
            // A notification: notifications/initialized, notifications/cancelled and the rest need no reply.
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(message));
                case "ping" -> result(id, new JsonObject());
                case "tools/list" -> result(id, McpTools.list());
                case "tools/call" -> result(id, McpTools.call(
                        message.has("params") && message.get("params").isJsonObject()
                                ? message.getAsJsonObject("params")
                                : new JsonObject()));
                default -> error(id, -32601, "this server does not implement " + method);
            };
        } catch (Exception problem) {
            return error(id, -32603, problem.getClass().getSimpleName() + ": " + problem.getMessage());
        }
    }

    private static JsonObject initialize(JsonObject message) {
        String wanted = message.has("params") && message.getAsJsonObject("params").has("protocolVersion")
                ? message.getAsJsonObject("params").get("protocolVersion").getAsString()
                : SUPPORTED[0];

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", supported(wanted) ? wanted : SUPPORTED[0]);

        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);

        JsonObject server = new JsonObject();
        server.addProperty("name", "minecraft-mcp");
        server.addProperty("title", "Minecraft MCP");
        server.addProperty("version", "1.0.0");
        result.add("serverInfo", server);

        result.addProperty("instructions",
                "Tools that play Minecraft through the running client. Start with mc_state to see where the "
                        + "player is and what is around them, then send mc_act batches of steps. Walking is real "
                        + "input, so it obeys physics and the server; keep a single batch to a couple of hundred "
                        + "ticks (ten seconds) so the reply arrives well inside any timeout.");
        return result;
    }

    private static boolean supported(String version) {
        for (String candidate : SUPPORTED) {
            if (candidate.equals(version)) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject result(JsonElement id, JsonObject payload) {
        JsonObject answer = new JsonObject();
        answer.addProperty("jsonrpc", "2.0");
        answer.add("id", id);
        answer.add("result", payload);
        return answer;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject failure = new JsonObject();
        failure.addProperty("code", code);
        failure.addProperty("message", message);

        JsonObject answer = new JsonObject();
        answer.addProperty("jsonrpc", "2.0");
        answer.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        answer.add("error", failure);
        return answer;
    }
}
