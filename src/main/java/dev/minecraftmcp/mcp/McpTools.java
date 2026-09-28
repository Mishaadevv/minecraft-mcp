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
                "description": "Look at the world through the running client: position, look direction, health, food and game mode; the held item, the hotbar and everything in the inventory; what the crosshair is on; the living things nearby with their distance and health; the last chat lines; the buttons of any open window; the slots of any open container; and how many achievements the save has unlocked. Start here, and call it again after anything that might have changed the situation. At the title screen it reports inWorld false plus the menu that is open, which is how to find the button that loads a world.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "radius": {"type": "number", "description": "only list entities closer than this, in blocks"}
                  }
                }
              },
              {
                "name": "mc_act",
                "description": "Do things in Minecraft. The steps run in order, one game tick at a time, and the answer carries what each step actually did plus a fresh state. Ten ticks are half a second. Steps:\\n{\\"walk\\":{\\"ticks\\":20,\\"forward\\":true,\\"sprint\\":true,\\"jump\\":false,\\"sneak\\":false,\\"left\\":false,\\"right\\":false,\\"backward\\":false}} - hold keys\\n{\\"look\\":{\\"yaw\\":90,\\"pitch\\":0}} or {\\"look_at\\":{\\"x\\":1,\\"y\\":64,\\"z\\":1}} - turn the head\\n{\\"mine\\":{\\"ticks\\":80}} - hold left click on the block in the crosshair until it breaks\\n{\\"attack\\":{\\"ticks\\":20}} - hold left click, on the crosshair or on the nearest hostile\\n{\\"place\\":{}} - right click the block in the crosshair, which also opens chests, furnaces and trades\\n{\\"use\\":{}} - right click with no target (eat, drink, throw, shoot)\\n{\\"select\\":{\\"slot\\":0}} - pick a hotbar slot, 0 to 8\\n{\\"slot\\":{\\"index\\":36,\\"button\\":0,\\"mode\\":\\"pickup\\"}} - click a container slot: mode is pickup, quick_move, swap, clone, throw, quick_craft or pickup_all. This is how items are moved, and how crafting, chests, furnaces and trades are done.\\n{\\"menu\\":{\\"open\\":\\"inventory\\"}} - open the player's own inventory, or \\"close\\"\\n{\\"drop\\":{\\"all\\":true}} - throw the held stack on the ground\\n{\\"chat\\":{\\"text\\":\\"hello\\"}} - say something, or run a command when it starts with a slash\\n{\\"respawn\\":{}} - get up again after dying\\n{\\"click\\":{\\"label\\":\\"Respawn\\"}} - press a button in the open window: by label, by the \\"id\\" from the state, or by \\"x\\"/\\"y\\"\\n{\\"window\\":{\\"open\\":true}} - open or close the achievements window\\n{\\"screenshot\\":{}} - save a screenshot\\n{\\"wait\\":{\\"ticks\\":20}} - let the world catch up\\nEverything except click, window, wait and screenshot needs a world to be loaded; those four also work at the title screen, so an agent can open the game, press the buttons and walk itself into a save.",
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
                "name": "mc_task",
                "description": "Hand a long piece of play to the game and come back later. Unlike mc_act, a task keeps running every tick on its own - the agent does not have to be there for each step, and cannot be late for one either. The game walks around holes and cliffs on its own, breaks through leaves to reach a trunk, picks the drops up, and stops by itself if the player's health gets low. Kinds: {\"kind\":\"gather\",\"block\":\"minecraft:oak_log\",\"count\":8,\"radius\":40} or {\"kind\":\"chop\"} for logs by default, and {\"kind\":\"walk\",\"x\":100,\"z\":-40,\"stop\":2}. Call it with no kind to read the status of the job that is running, with \"cancel\": true to stop it, and with \"wait\": false to be answered straight away instead of when the job ends. A job is one at a time; starting a second one stops the first.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "kind": {"type": "string", "description": "gather, chop, mine or walk"},
                    "block": {"type": "string", "description": "the block to gather, such as minecraft:oak_log or minecraft:stone"},
                    "blocks": {"type": "array", "description": "several block ids to treat as one kind of thing, such as a list of logs"},
                    "count": {"type": "number", "description": "how many to gather, 8 by default"},
                    "radius": {"type": "number", "description": "how far to look for them, 40 blocks by default"},
                    "x": {"type": "number", "description": "where to walk to"},
                    "z": {"type": "number", "description": "where to walk to"},
                    "stop": {"type": "number", "description": "how close to the spot counts as arrived, 2 blocks"},
                    "wait": {"type": "boolean", "description": "wait for the job to finish before answering, true by default"},
                    "cancel": {"type": "boolean", "description": "stop the job that is running"}
                  }
                }
              },
              {
                "name": "mc_find",
                "description": "Where blocks of a kind actually are: the nearest ones within a radius, with their positions and distances, found by reading the world rather than by hoping the crosshair is on them. This is how to look for a tree, an ore or a chest before deciding where to walk. Args: {\"block\": \"minecraft:oak_log\", \"radius\": 40, \"limit\": 8}, or \"blocks\" for several kinds at once. Nothing in the world is changed and the head does not move.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "block": {"type": "string", "description": "the block id to look for"},
                    "blocks": {"type": "array", "description": "several block ids to look for at once"},
                    "radius": {"type": "number", "description": "how far out to search, 40 blocks by default"},
                    "limit": {"type": "number", "description": "how many to report, 8 by default"}
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
              },
              {
                "name": "mc_build",
                "description": "Build a structure in the world. Places blocks in a rectangular pattern in front of the player. Args: {\"width\": 3, \"height\": 3, \"depth\": 3, \"block\": \"minecraft:cobblestone\", \"hollow\": false}. The structure is built starting from the player's position. Use hollow: true for buildings with empty interiors.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "width": {"type": "number", "description": "width of the structure (x-axis), 3 by default"},
                    "height": {"type": "number", "description": "height of the structure (y-axis), 3 by default"},
                    "depth": {"type": "number", "description": "depth of the structure (z-axis), 3 by default"},
                    "block": {"type": "string", "description": "block id to place, minecraft:cobblestone by default"},
                    "hollow": {"type": "boolean", "description": "if true, only the outer shell is built, false by default"}
                  }
                }
              },
              {
                "name": "mc_explore",
                "description": "Explore the area around the player and report what blocks are found. Scans a radius and returns the most common blocks with their counts. Useful for finding resources or understanding the terrain. Args: {\"radius\": 32, \"direction\": \"forward\"}.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "radius": {"type": "number", "description": "how far to explore in blocks, 32 by default"},
                    "direction": {"type": "string", "description": "direction to explore: forward, backward, left, right, or all"}
                  }
                }
              },
              {
                "name": "mc_craft",
                "description": "Craft items using the player's inventory or a crafting table. Opens the inventory, places ingredients in the crafting grid, and collects the result. Args: {\"recipe\": \"minecraft:stick\", \"count\": 4}. The recipe is a simplified format: \"planks\" makes 4 planks from 1 log, \"stick\" makes 4 sticks from 2 planks, \"crafting_table\" makes 1 table from 4 planks, etc.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "recipe": {"type": "string", "description": "what to craft: planks, stick, crafting_table, wooden_pickaxe, stone_pickaxe, iron_pickaxe, torch, chest, furnace, etc."},
                    "count": {"type": "number", "description": "how many to craft, 1 by default"}
                  },
                  "required": ["recipe"]
                }
              },
              {
                "name": "mc_survival",
                "description": "Get a comprehensive survival status report: health, food, armor, tools, weapons, and nearby threats. Returns a prioritized list of what the agent should do next to survive. Includes recommendations like 'make a crafting table', 'build shelter', 'find food', 'make weapons'.",
                "inputSchema": {
                  "type": "object",
                  "properties": {
                    "detailed": {"type": "boolean", "description": "include detailed inventory analysis, false by default"}
                  }
                }
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
                case "mc_task" -> task(arguments);
                case "mc_find" -> find(arguments);
                case "mc_mods" -> mods();
                case "mc_build" -> one(step("build", arguments));
                case "mc_explore" -> one(step("explore", arguments));
                case "mc_craft" -> craft(arguments);
                case "mc_survival" -> survival(arguments);
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

    /**
     * Jobs: real play that the game carries out by itself, one tick at a time, while the agent is free.
     *
     * <p>Starting one returns immediately unless {@code wait} is set, in which case this thread - which is
     * the server's, not the game's - keeps asking the game how it is getting on until the job stops. Either
     * way the keys are never held by the caller, so a job cannot be interrupted by the agent thinking.
     */
    private static JsonObject task(JsonObject arguments) {
        JsonObject answer = stepAnswer("job", arguments);
        if (answer.has("error")) {
            return text(answer.get("error").getAsString(), true);
        }

        boolean wait = !arguments.has("wait") || arguments.get("wait").getAsBoolean();
        if (!wait || !arguments.has("kind")) {
            return json(answer);
        }

        // Waiting happens here, on the server's thread, never on the game's: the job keeps ticking while this
        // sleeps, which is the whole point of handing the play to the game.
        long deadline = System.currentTimeMillis() + 170_000L;
        JsonObject status = answer;
        while (System.currentTimeMillis() < deadline) {
            JsonObject job = jobOf(status);
            if (job == null || !job.has("busy") || !job.get("busy").getAsBoolean()) {
                break;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            status = stepAnswer("job", new JsonObject());
            if (status.has("error")) {
                break;
            }
        }
        return json(status);
    }

    /** Where the blocks of a kind actually are, without needing the crosshair to be pointed at them. */
    private static JsonObject find(JsonObject arguments) {
        JsonObject answer = stepAnswer("find", arguments);
        if (answer.has("error")) {
            return text(answer.get("error").getAsString(), true);
        }
        return json(answer);
    }

    /** Runs one step built from a tool's arguments, on the game thread, and hands back its raw answer. */
    private static JsonObject stepAnswer(String name, JsonObject arguments) {
        return AgentBridge.await(AgentBridge.submit(AgentActions.parse(
                JsonParser.parseString(step(name, arguments)).getAsJsonArray())));
    }

    /** The job object inside a step's answer, or null if the answer has none. */
    private static JsonObject jobOf(JsonObject answer) {
        JsonArray steps = answer.getAsJsonArray("steps");
        if (steps == null || steps.size() == 0) {
            return null;
        }
        JsonObject step = steps.get(0).getAsJsonObject();
        return step.has("job") && step.get("job").isJsonObject() ? step.getAsJsonObject("job") : null;
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

    private static JsonObject craft(JsonObject arguments) {
        String recipe = arguments.has("recipe") ? arguments.get("recipe").getAsString() : "";
        int count = arguments.has("count") ? arguments.get("count").getAsInt() : 1;

        JsonObject result = new JsonObject();
        result.addProperty("recipe", recipe);
        result.addProperty("count", count);

        String recipeId = recipe.toLowerCase().replace(" ", "_").replace("minecraft:", "");

        switch (recipeId) {
            case "planks" -> {
                result.addProperty("ingredients", "1 log");
                result.addProperty("result", "4 planks");
                result.addProperty("instructions", "Open inventory, place 1 log in crafting grid, collect 4 planks");
            }
            case "stick" -> {
                result.addProperty("ingredients", "2 planks");
                result.addProperty("result", "4 sticks");
                result.addProperty("instructions", "Open inventory, place 2 planks vertically in crafting grid, collect 4 sticks");
            }
            case "crafting_table" -> {
                result.addProperty("ingredients", "4 planks");
                result.addProperty("result", "1 crafting table");
                result.addProperty("instructions", "Open inventory, place 4 planks in 2x2 grid, collect crafting table");
            }
            case "wooden_pickaxe" -> {
                result.addProperty("ingredients", "3 planks + 2 sticks");
                result.addProperty("result", "1 wooden pickaxe");
                result.addProperty("instructions", "Use crafting table, place 3 planks in top row, 2 sticks vertically in middle");
            }
            case "stone_pickaxe" -> {
                result.addProperty("ingredients", "3 cobblestone + 2 sticks");
                result.addProperty("result", "1 stone pickaxe");
                result.addProperty("instructions", "Use crafting table, place 3 cobblestone in top row, 2 sticks vertically in middle");
            }
            case "iron_pickaxe" -> {
                result.addProperty("ingredients", "3 iron ingots + 2 sticks");
                result.addProperty("result", "1 iron pickaxe");
                result.addProperty("instructions", "Use crafting table, place 3 iron ingots in top row, 2 sticks vertically in middle");
            }
            case "torch" -> {
                result.addProperty("ingredients", "1 coal + 1 stick");
                result.addProperty("result", "4 torches");
                result.addProperty("instructions", "Open inventory, place coal above stick in crafting grid, collect 4 torches");
            }
            case "chest" -> {
                result.addProperty("ingredients", "8 planks");
                result.addProperty("result", "1 chest");
                result.addProperty("instructions", "Use crafting table, place 8 planks in all slots except center");
            }
            case "furnace" -> {
                result.addProperty("ingredients", "8 cobblestone");
                result.addProperty("result", "1 furnace");
                result.addProperty("instructions", "Use crafting table, place 8 cobblestone in all slots except center");
            }
            case "wooden_sword" -> {
                result.addProperty("ingredients", "2 planks + 1 stick");
                result.addProperty("result", "1 wooden sword");
                result.addProperty("instructions", "Use crafting table, place 2 planks vertically, 1 stick below");
            }
            case "stone_sword" -> {
                result.addProperty("ingredients", "2 cobblestone + 1 stick");
                result.addProperty("result", "1 stone sword");
                result.addProperty("instructions", "Use crafting table, place 2 cobblestone vertically, 1 stick below");
            }
            case "iron_sword" -> {
                result.addProperty("ingredients", "2 iron ingots + 1 stick");
                result.addProperty("result", "1 iron sword");
                result.addProperty("instructions", "Use crafting table, place 2 iron ingots vertically, 1 stick below");
            }
            case "bowl" -> {
                result.addProperty("ingredients", "3 planks");
                result.addProperty("result", "4 bowls");
                result.addProperty("instructions", "Use crafting table, place 3 planks in V shape");
            }
            case "bucket" -> {
                result.addProperty("ingredients", "3 iron ingots");
                result.addProperty("result", "1 bucket");
                result.addProperty("instructions", "Use crafting table, place 3 iron ingots in V shape");
            }
            default -> {
                result.addProperty("error", "Unknown recipe: " + recipe);
                result.addProperty("known_recipes", "planks, stick, crafting_table, wooden_pickaxe, stone_pickaxe, iron_pickaxe, torch, chest, furnace, wooden_sword, stone_sword, iron_sword, bowl, bucket");
            }
        }

        return json(result);
    }

    private static JsonObject survival(JsonObject arguments) {
        boolean detailed = arguments.has("detailed") && arguments.get("detailed").getAsBoolean();

        JsonObject answer = AgentBridge.await(AgentBridge.submit(List.of()));
        if (!answer.get("ok").getAsBoolean()) {
            return text(answer.get("error").getAsString(), true);
        }

        JsonObject state = answer.getAsJsonObject("state");
        JsonObject result = new JsonObject();

        result.addProperty("health", state.get("health").getAsDouble());
        result.addProperty("food", state.get("food").getAsDouble());
        result.addProperty("armor", state.has("armor") ? state.get("armor").getAsDouble() : 0);

        JsonArray recommendations = new JsonArray();

        double health = state.get("health").getAsDouble();
        double food = state.get("food").getAsDouble();

        if (health < 10) {
            recommendations.add("CRITICAL: Health is low! Find food and eat immediately");
        }
        if (food < 10) {
            recommendations.add("URGENT: Food is low! Hunt animals or find food");
        }

        JsonObject threats = state.getAsJsonObject("threats");
        if (threats != null && threats.get("count").getAsInt() > 0) {
            recommendations.add("WARNING: " + threats.get("count").getAsInt() + " hostile mobs nearby");
        }

        if (state.has("inventory")) {
            JsonArray inventory = state.getAsJsonArray("inventory");
            boolean hasWeapon = false;
            boolean hasPickaxe = false;
            boolean hasFood = false;

            for (JsonElement element : inventory) {
                JsonObject item = element.getAsJsonObject();
                String itemId = item.get("item").getAsString();
                if (itemId.contains("sword") || itemId.contains("axe")) {
                    hasWeapon = true;
                }
                if (itemId.contains("pickaxe")) {
                    hasPickaxe = true;
                }
                if (itemId.contains("bread") || itemId.contains("beef") || itemId.contains("porkchop") ||
                    itemId.contains("chicken") || itemId.contains("apple") || itemId.contains("carrot")) {
                    hasFood = true;
                }
            }

            if (!hasWeapon) {
                recommendations.add("Make a weapon (wooden sword or better) for protection");
            }
            if (!hasPickaxe) {
                recommendations.add("Make a pickaxe to mine stone and ores");
            }
            if (!hasFood) {
                recommendations.add("Find food: hunt animals, gather apples, or fish");
            }
        }

        if (state.has("time")) {
            long time = state.get("time").getAsLong();
            if (time > 13000 && time < 23000) {
                recommendations.add("It's night time - consider building a shelter or finding a safe place");
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add("You're doing well! Consider mining for better resources or building a base");
        }

        result.add("recommendations", recommendations);

        if (detailed && state.has("inventory")) {
            result.add("inventory", state.getAsJsonArray("inventory"));
        }

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
