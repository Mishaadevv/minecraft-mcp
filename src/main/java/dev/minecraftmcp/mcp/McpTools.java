package dev.minecraftmcp.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.minecraftmcp.agent.AgentActions;
import dev.minecraftmcp.agent.AgentBridge;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * The tools an agent gets: what the world looks like, what to do in it, and the two ways of talking to the
 * game - chat, and the achievements window.
 *
 * <p>The catalogue is written as plain JSON, because that is what it is: a schema for a client to read. The
 * calls go through {@link AgentBridge}, so they run on the game thread like everything else.
 */
public final class McpTools {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String CATALOGUE = """
            [
              {
                "name": "mc_state",
                "description": "Look at the world through the running client: position, look direction, health, food and game mode; the held item, the hotbar and everything in the inventory; what the crosshair is on; the living things nearby with their distance and health; the last chat lines; the buttons of any open window; the slots of any open container; and how many achievements the save has unlocked. Start here, and call it again after anything that might have changed the situation.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "radius": {"type": "number", "description": "only list entities closer than this, in blocks"}
                  }
                }
              },
              {
                "name": "mc_act",
                "description": "Do things in Minecraft. The steps run in order, one game tick at a time, and the answer carries what each step actually did plus a fresh state. Ten ticks are half a second. Steps:\\n{\\"walk\\":{\\"ticks\\":20,\\"forward\\":true,\\"sprint\\":true,\\"jump\\":false,\\"sneak\\":false,\\"left\\":false,\\"right\\":false,\\"backward\\":false}} - hold keys\\n{\\"look\\":{\\"yaw\\":90,\\"pitch\\":0}} or {\\"look_at\\":{\\"x\\":1,\\"y\\":64,\\"z\\":1}} - turn the head\\n{\\"mine\\":{\\"ticks\\":80}} - hold left click on the block in the crosshair until it breaks\\n{\\"attack\\":{\\"ticks\\":20}} - hold left click, on the crosshair or on the nearest hostile\\n{\\"place\\":{}} - right click the block in the crosshair, which also opens chests, furnaces and trades\\n{\\"use\\":{}} - right click with no target (eat, drink, throw, shoot)\\n{\\"select\\":{\\"slot\\":0}} - pick a hotbar slot, 0 to 8\\n{\\"slot\\":{\\"index\\":36,\\"button\\":0,\\"mode\\":\\"pickup\\"}} - click a container slot: mode is pickup, quick_move, swap, clone, throw, quick_craft or pickup_all. This is how items are moved, and how crafting, chests, furnaces and trades are done.\\n{\\"menu\\":{\\"open\\":\\"inventory\\"}} - open the player's own inventory, or \\"close\\"\\n{\\"drop\\":{\\"all\\":true}} - throw the held stack on the ground\\n{\\"chat\\":{\\"text\\":\\"hello\\"}} - say something, or run a command when it starts with a slash\\n{\\"respawn\\":{}} - get up again after dying\\n{\\"click\\":{\\"label\\":\\"Respawn\\"}} - press a button in the open window: by label, by the \\"id\\" from the state, or by \\"x\\"/\\"y\\"\\n{\\"window\\":{\\"open\\":true}} - open or close the achievements window\\n{\\"screenshot\\":{}} - save a screenshot\\n{\\"wait\\":{\\"ticks\\":20}} - let the world catch up",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "steps": {
                      "type": "array",
                      "description": "the steps to run, in order",
                      "items": {"type": "object"}
                    }
                  },
                  "required": ["steps"]
                }
              },
              {
                "name": "mc_scan",
                "description": "Look around and report which blocks are actually in view: a ring of directions from the eye, nearest first, each with its position, the angle to look at it and how far away it is. This is how to find something to mine or to walk towards without guessing. The player's head is left exactly where it was.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "step": {"type": "number", "description": "degrees between one look and the next, 30 by default"},
                    "pitches": {"type": "array", "description": "the angles down to sweep, 25, 0, -25 and -55 by default"},
                    "reach": {"type": "number", "description": "how far to look, 4.5 blocks by default"}
                  }
                }
              },
              {
                "name": "mc_chat",
                "description": "Talk in Minecraft: a plain message, or a command when the text starts with a slash such as \\"/give @s minecraft:stone 64\\" or \\"/time set day\\". Remember that commands need cheats or operator rights.",
                "inputSchema": {
                  "type": "object",
                  "properties": {"text": {"type": "string", "description": "the message or /command to send"}},
                  "required": ["text"]
                }
              },
              {
                "name": "mc_read_chat",
                "description": "Read the last lines the game printed: chat, command results, deaths, achievements unlocked.",
                "inputSchema": {
                  "type": "object",
                  "properties": {"count": {"type": "number", "description": "how many lines back to read"}}
                }
              },
              {
                "name": "mc_slot",
                "description": "Click a slot of the open container. Slot 0 to 8 is the crafting result and grid of the inventory, 9 to 35 the main inventory, 36 to 44 the hotbar, 45 the off hand; in a chest or a crafting table the container's own slots come first. The state lists every slot with its index and contents. To move a stack with the mouse: click the source slot with mode pickup, click the target slot with mode pickup again. quick_move (shift click) moves a whole stack to the other half.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "index": {"type": "number", "description": "the slot index from the state"},
                    "button": {"type": "number", "description": "0 for left click, 1 for right click"},
                    "mode": {"type": "string", "description": "pickup, quick_move, swap, clone, throw, quick_craft or pickup_all"}
                  },
                  "required": ["index"]
                }
              },
              {
                "name": "mc_menu",
                "description": "Open the player's own inventory (the 2x2 crafting grid and the armour slots), or close whatever window is open. Chests, furnaces and villager trades open when the block or the villager is used with the place step.",
                "inputSchema": {
                  "type": "object",
                  "properties": {"open": {"type": "string", "description": "\\"inventory\\" or \\"close\\""}}
                }
              },
              {
                "name": "mc_achievements",
                "description": "Open or close the game's achievements window and report how the save is coming on: how many achievements the client knows and how many are unlocked. Whatever mod is installed gets to draw that window.",
                "inputSchema": {
                  "type": "object",
                  "properties": {"open": {"type": "boolean", "description": "true to open, false to close"}}
                }
              },
              {
                "name": "mc_screenshot",
                "description": "Save a screenshot of the game window into the run's screenshots folder.",
                "inputSchema": {"type": "object", "properties": {}}
              },
              {
                "name": "mc_mods",
                "description": "List the mods loaded by this client, with their names and versions - useful to see what else the game is running, such as an achievements overhaul that redraws the window.",
                "inputSchema": {"type": "object", "properties": {}}
              }
            ]
            """;

    private static JsonArray catalogue;

    private McpTools() {
    }

    public static JsonObject list() {
        JsonObject result = new JsonObject();
        result.add("tools", tools());
        return result;
    }

    public static JsonObject call(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();

        try {
            return switch (name) {
                case "mc_state" -> state(arguments);
                case "mc_act" -> act(arguments);
                case "mc_chat" -> chat(arguments);
                case "mc_read_chat" -> readChat(arguments);
                case "mc_scan" -> one(step("scan", arguments));
                case "mc_slot" -> one(step("slot", arguments));
                case "mc_menu" -> one(step("menu", arguments));
                case "mc_screenshot" -> one("[{\"screenshot\":{}}]");
                case "mc_achievements" -> achievements(arguments);
                case "mc_mods" -> mods();
                default -> text("This server has no tool called \"" + name + "\".", true);
            };
        } catch (IllegalArgumentException | JsonParseException bad) {
            return text("That request could not be read: " + bad.getMessage(), true);
        } catch (Exception problem) {
            return text(problem.getClass().getSimpleName() + ": " + problem.getMessage(), true);
        }
    }

    // ------------------------------------------------------------------ the tools

    private static JsonObject state(JsonObject arguments) {
        JsonObject answer = AgentBridge.await(AgentBridge.submit(List.of()));
        if (!answer.get("ok").getAsBoolean()) {
            return text(answer.get("error").getAsString(), true);
        }

        JsonObject state = answer.getAsJsonObject("state");
        if (arguments.has("radius") && state.has("entities")) {
            double radius = arguments.get("radius").getAsDouble();
            JsonArray kept = new JsonArray();
            for (JsonElement entity : state.getAsJsonArray("entities")) {
                if (entity.getAsJsonObject().get("distance").getAsDouble() <= radius) {
                    kept.add(entity);
                }
            }
            state.add("entities", kept);
        }
        return json(state);
    }

    private static JsonObject act(JsonObject arguments) {
        if (!arguments.has("steps") || !arguments.get("steps").isJsonArray()) {
            return text("mc_act needs a \"steps\" array.", true);
        }
        JsonArray specs = arguments.getAsJsonArray("steps");
        JsonObject answer = AgentBridge.await(AgentBridge.submit(AgentActions.parse(specs)));
        return json(answer);
    }

    private static JsonObject chat(JsonObject arguments) {
        String message = arguments.has("text") ? arguments.get("text").getAsString() : "";
        return one("[{\"chat\":{\"text\":" + GSON.toJson(message) + "}}]");
    }

    private static JsonObject readChat(JsonObject arguments) {
        int count = arguments.has("count") ? arguments.get("count").getAsInt() : 20;
        JsonObject answer = AgentBridge.await(AgentBridge.submit(List.of()));
        JsonArray chat = answer.has("state") ? answer.getAsJsonObject("state").getAsJsonArray("chat") : new JsonArray();

        JsonArray tail = new JsonArray();
        for (int i = Math.max(0, chat.size() - count); i < chat.size(); i++) {
            tail.add(chat.get(i));
        }
        JsonObject result = new JsonObject();
        result.add("chat", tail);
        return json(result);
    }

    private static JsonObject achievements(JsonObject arguments) {
        boolean open = !arguments.has("open") || arguments.get("open").getAsBoolean();
        JsonObject answer = AgentBridge.await(AgentBridge.submit(AgentActions.parse(
                JsonParser.parseString("[{\"window\":{\"open\":" + open + "}}]").getAsJsonArray())));
        if (!answer.get("ok").getAsBoolean()) {
            return text(answer.get("error").getAsString(), true);
        }

        JsonObject state = answer.getAsJsonObject("state");
        JsonObject result = new JsonObject();
        result.addProperty("open", open);
        result.add("screen", state.get("screen"));
        result.add("achievements", state.get("achievements"));
        return json(result);
    }

    private static JsonObject mods() {
        List<ModContainer> containers = new ArrayList<>(FabricLoader.getInstance().getAllMods());
        containers.sort((left, right) -> left.getMetadata().getId().compareTo(right.getMetadata().getId()));

        JsonArray array = new JsonArray();
        for (ModContainer container : containers) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", container.getMetadata().getId());
            entry.addProperty("name", container.getMetadata().getName());
            entry.addProperty("version", container.getMetadata().getVersion().getFriendlyString());
            array.add(entry);
        }

        JsonObject result = new JsonObject();
        result.add("mods", array);
        result.addProperty("count", array.size());
        return json(result);
    }

    // ------------------------------------------------------------------ plumbing

    /** Runs a single step and answers with what it did. */
    private static JsonObject one(String spec) {
        JsonObject answer = AgentBridge.await(AgentBridge.submit(AgentActions.parse(
                JsonParser.parseString(spec).getAsJsonArray())));
        return json(answer);
    }

    /** Builds one step from a tool's arguments, dropping anything the step does not understand. */
    private static String step(String name, JsonObject arguments) {
        return "[{" + GSON.toJson(name) + ":" + GSON.toJson(arguments) + "}]";
    }

    private static JsonArray tools() {
        if (catalogue == null) {
            catalogue = JsonParser.parseString(CATALOGUE).getAsJsonArray();
        }
        return catalogue;
    }

    private static JsonObject json(JsonObject payload) {
        return text(GSON.toJson(payload), false);
    }

    private static JsonObject text(String body, boolean failed) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", body);

        JsonArray array = new JsonArray();
        array.add(content);

        JsonObject result = new JsonObject();
        result.add("content", array);
        result.addProperty("isError", failed);
        return result;
    }
}
