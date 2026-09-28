package dev.minecraftmcp.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import dev.minecraftmcp.mixin.ClientAdvancementsAccessor;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Everything the agent can perceive at this instant: where it stands, what it holds, what is within reach,
 * what the game has told it, which container is open and what is in its slots.
 *
 * <p>This is the agent's whole sense of the world - it has no eyes, so the shape of this JSON is what it can
 * react to. It is only ever built on the game thread.
 */
public final class AgentState {
    private static final int MAX_ENTITIES = 12;
    private static final int MAX_INVENTORY = 40;

    private AgentState() {
    }

    public static JsonObject snapshot(Minecraft client, int radius, List<String> chat) {
        JsonObject state = new JsonObject();

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            state.addProperty("inWorld", false);
            addChat(state, chat);
            return state;
        }

        state.addProperty("inWorld", true);
        state.addProperty("dimension", client.level.dimension().identifier().toString());
        state.add("pos", vec(player.getX(), player.getY(), player.getZ()));
        state.addProperty("yaw", round(player.getYRot()));
        state.addProperty("pitch", round(player.getXRot()));
        state.addProperty("onGround", player.onGround());
        state.addProperty("health", round(player.getHealth()));
        state.addProperty("food", player.getFoodData().getFoodLevel());
        state.addProperty("xpLevel", player.experienceLevel);
        state.addProperty("sprinting", player.isSprinting());
        state.addProperty("sneaking", player.isShiftKeyDown());
        state.addProperty("dead", player.isDeadOrDying());
        if (client.gameMode != null) {
            state.addProperty("gameMode", client.gameMode.getPlayerMode().getName());
        }

        state.addProperty("screen", client.screen == null ? null : client.screen.getClass().getSimpleName());
        state.add("gui", client.screen == null ? null : gui(client.screen));
        state.add("menu", menu(player));

        state.addProperty("selectedSlot", player.getInventory().getSelectedSlot());
        state.addProperty("held", name(player.getInventory().getSelectedItem()));
        state.add("hotbar", hotbar(player));
        state.add("inventory", inventory(player));
        state.add("lookingAt", lookingAt(client, player));
        state.add("entities", entities(player, radius));
        addChat(state, chat);
        state.add("achievements", achievements(client));

        return state;
    }

    /**
     * The open container, if any: the crafting grid, a chest, a furnace, a villager's trade. Slot indices are
     * the numbers the {@code slot} action clicks, and {@code own} marks the half that belongs to the player.
     */
    private static JsonObject menu(LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null) {
            return null;
        }

        JsonObject result = new JsonObject();
        result.addProperty("containerId", menu.containerId);
        result.addProperty("carried", name(menu.getCarried()));
        result.addProperty("own", menu == player.inventoryMenu);

        JsonArray slots = new JsonArray();
        int index = 0;
        for (Slot slot : menu.slots) {
            JsonObject entry = new JsonObject();
            entry.addProperty("index", index++);
            entry.addProperty("slot", slot.getContainerSlot());
            entry.addProperty("item", name(slot.getItem()));
            entry.addProperty("own", slot.container == player.getInventory());
            slots.add(entry);
        }
        result.add("slots", slots);
        return result;
    }

    /**
     * The window that is open, as a list of its buttons and fields with their labels and places.
     *
     * <p>The agent has no eyes, so this is how it reads a menu: which button says "Respawn", where the
     * achievements window's widgets are, whether a button is greyed out. Clicking is by {@code id} or label.
     */
    private static JsonObject gui(Screen screen) {
        JsonObject gui = new JsonObject();
        gui.addProperty("title", screen.getTitle().getString());

        JsonArray widgets = new JsonArray();
        int index = 0;
        for (GuiEventListener listener : screen.children()) {
            if (!(listener instanceof AbstractWidget widget)) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", index++);
            entry.addProperty("type", widget.getClass().getSimpleName());
            entry.addProperty("label", widget.getMessage().getString());
            entry.addProperty("x", widget.getX());
            entry.addProperty("y", widget.getY());
            entry.addProperty("width", widget.getWidth());
            entry.addProperty("height", widget.getHeight());
            entry.addProperty("active", widget.isActive());
            widgets.add(entry);
        }
        gui.add("widgets", widgets);
        return gui;
    }

    /** What the crosshair is on, which is what mining, placing and attacking will act on. */
    private static JsonObject lookingAt(Minecraft client, LocalPlayer player) {
        JsonObject result = new JsonObject();
        HitResult hit = player.pick(4.5D, 1.0F, false);

        if (hit instanceof BlockHitResult block) {
            BlockPos pos = block.getBlockPos();
            BlockState state = client.level.getBlockState(pos);
            result.addProperty("type", "block");
            result.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            result.add("pos", vec(pos.getX(), pos.getY(), pos.getZ()));
            result.addProperty("face", block.getDirection().getName());
            result.addProperty("distance", round(Math.sqrt(hit.distanceTo(player))));
        } else if (hit instanceof EntityHitResult entity && entity.getEntity() instanceof LivingEntity living) {
            result.addProperty("type", "entity");
            result.addProperty("entity", BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()).toString());
            result.addProperty("name", living.getName().getString());
            result.addProperty("health", round(living.getHealth()));
            result.add("pos", vec(living.getX(), living.getY(), living.getZ()));
        } else {
            result.addProperty("type", "none");
        }

        return result;
    }

    /** The living things around the agent, nearest first: this is how it finds something to hit or run from. */
    private static JsonArray entities(LocalPlayer player, int radius) {
        List<Entity> nearby = new ArrayList<>(
                player.level().getEntities(player, player.getBoundingBox().inflate(radius)));
        nearby.sort(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)));

        JsonArray array = new JsonArray();
        for (Entity entity : nearby) {
            if (array.size() >= MAX_ENTITIES) {
                break;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entry.addProperty("name", entity.getName().getString());
            entry.addProperty("distance", round(Math.sqrt(entity.distanceToSqr(player))));
            entry.add("pos", vec(entity.getX(), entity.getY(), entity.getZ()));
            if (entity instanceof LivingEntity living) {
                entry.addProperty("health", round(living.getHealth()));
                // "hostile" is what the agent uses to decide what is worth hitting and what is worth avoiding.
                entry.addProperty("hostile", living instanceof net.minecraft.world.entity.monster.Enemy);
            }
            array.add(entry);
        }

        return array;
    }

    private static JsonArray hotbar(LocalPlayer player) {
        JsonArray array = new JsonArray();
        for (int slot = 0; slot < 9; slot++) {
            array.add(name(player.getInventory().getItem(slot)));
        }
        return array;
    }

    private static JsonArray inventory(LocalPlayer player) {
        Map<String, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
            }
        }

        JsonArray array = new JsonArray();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_INVENTORY)
                .forEach(entry -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("item", entry.getKey());
                    item.addProperty("count", entry.getValue());
                    array.add(item);
                });

        return array;
    }

    /**
     * How the save is coming on: how many achievements the client knows about and how many are done.
     *
     * <p>This reads the same client-side data the achievements window draws from, so it stays right whatever
     * mod (or lack of one) is drawing that window.
     */
    public static JsonObject achievements(Minecraft client) {
        JsonObject result = new JsonObject();
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            result.addProperty("known", 0);
            result.addProperty("unlocked", 0);
            return result;
        }

        ClientAdvancements advancements = connection.getAdvancements();
        result.addProperty("known", advancements.getTree().nodes().size());

        int unlocked = 0;
        Map<String, Integer> perNamespace = new HashMap<>();
        Map<AdvancementHolder, AdvancementProgress> progress =
                ((ClientAdvancementsAccessor) advancements).mcp$progress();
        for (Map.Entry<AdvancementHolder, AdvancementProgress> entry : progress.entrySet()) {
            if (entry.getValue().isDone()) {
                unlocked++;
                perNamespace.merge(entry.getKey().id().getNamespace(), 1, Integer::sum);
            }
        }
        result.addProperty("unlocked", unlocked);

        // Who the unlocked ones belong to: vanilla, or one of the mods that brings its own catalogue.
        JsonObject byNamespace = new JsonObject();
        perNamespace.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> byNamespace.addProperty(entry.getKey(), entry.getValue()));
        result.add("byNamespace", byNamespace);
        return result;
    }

    private static void addChat(JsonObject state, List<String> chat) {
        JsonArray array = new JsonArray();
        synchronized (chat) {
            for (String line : chat) {
                array.add(line);
            }
        }
        state.add("chat", array);
    }

    /** How a stack is described to the agent: {@code minecraft:stone x64}, or {@code empty}. */
    public static String name(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    private static JsonArray vec(double x, double y, double z) {
        JsonArray array = new JsonArray();
        array.add(round(x));
        array.add(round(y));
        array.add(round(z));
        return array;
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
