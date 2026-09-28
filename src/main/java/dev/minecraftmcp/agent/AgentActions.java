package dev.minecraftmcp.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The verb list of the agent: one JSON object per thing to do, turned into the ticks of work it takes to carry
 * it out. Everything here runs on the game thread, one step per tick, so a single long action can never stall
 * a frame for longer than the game's own tick.
 *
 * <p>A step is deliberately coarse - "hold these keys for twenty ticks", "keep hitting until something dies",
 * "click slot 36" - because that is the granularity a player works in, and it keeps round trips small.
 */
public final class AgentActions {
    /** The longest a single request may hold the game: eleven minutes of ticks, a guard against a typo. */
    public static final int MAX_TICKS = 13_000;
    private static final double REACH = 4.5D;
    private static final double ATTACK_REACH = 3.0D;

    /** One tick of work. The bridge runs the first step of the list every tick and drops it once it is done. */
    public interface Step {
        /** Does one tick worth of the action and records what happened in {@link #report()}. */
        void tick(Minecraft client);

        boolean finished();

        /** How many ticks the step expects to need, used to give the request an honest deadline. */
        int plannedTicks();

        /** What this step did, as it will be handed back to the caller. */
        JsonObject report();
    }

    private AgentActions() {
    }

    /** Turns the {@code steps} array of a request into work. Throws {@link IllegalArgumentException} on junk. */
    public static List<Step> parse(JsonArray specs) {
        List<Step> steps = new ArrayList<>();
        int budget = 0;

        for (JsonElement element : specs) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("every step must be an object");
            }
            Step step = parseOne(element.getAsJsonObject());
            budget += step.plannedTicks();
            if (budget > MAX_TICKS) {
                throw new IllegalArgumentException("the request is longer than " + MAX_TICKS + " ticks");
            }
            steps.add(step);
        }

        return steps;
    }

    /** Total ticks a request expects to take, for the deadline the bridge hands the caller. */
    public static int ticks(List<Step> steps) {
        int total = 0;
        for (Step step : steps) {
            total += step.plannedTicks();
        }
        return total;
    }

    private static Step parseOne(JsonObject spec) {
        if (spec.has("look")) {
            JsonObject look = object(spec, "look");
            return new Once(report -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    return;
                }
                if (look.has("yaw")) {
                    player.setYRot((float) look.get("yaw").getAsDouble());
                }
                if (look.has("pitch")) {
                    player.setXRot((float) look.get("pitch").getAsDouble());
                }
                report.addProperty("action", "look");
                report.addProperty("yaw", Math.round(player.getYRot() * 100.0D) / 100.0D);
                report.addProperty("pitch", Math.round(player.getXRot() * 100.0D) / 100.0D);
            });
        }

        if (spec.has("look_at")) {
            JsonObject target = object(spec, "look_at");
            return new Once(report -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    return;
                }
                double dx = target.get("x").getAsDouble() - player.getX();
                double dy = target.get("y").getAsDouble() - player.getEyeY();
                double dz = target.get("z").getAsDouble() - player.getZ();
                double flat = Math.sqrt(dx * dx + dz * dz);

                player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                player.setXRot((float) Math.toDegrees(-Math.atan2(dy, flat)));
                report.addProperty("action", "look_at");
                report.addProperty("yaw", Math.round(player.getYRot() * 100.0D) / 100.0D);
                report.addProperty("pitch", Math.round(player.getXRot() * 100.0D) / 100.0D);
            });
        }

        if (spec.has("walk")) {
            JsonObject walk = object(spec, "walk");
            boolean forward = flag(walk, "forward");
            boolean backward = flag(walk, "backward");
            boolean left = flag(walk, "left");
            boolean right = flag(walk, "right");
            if (!forward && !backward && !left && !right) {
                forward = true;
            }
            Input presses = AgentInput.of(forward, backward, left, right,
                    flag(walk, "jump"), flag(walk, "sneak"), flag(walk, "sprint"));

            return new Hold(presses, ticks(walk, 20), "walk", describe(walk));
        }

        if (spec.has("wait")) {
            return new Wait(ticks(object(spec, "wait"), 20));
        }

        if (spec.has("attack")) {
            return new Attack(ticks(object(spec, "attack"), 1));
        }

        if (spec.has("mine")) {
            return new Mine(ticks(object(spec, "mine"), 80));
        }

        if (spec.has("scan")) {
            JsonObject scan = object(spec, "scan");
            int sweep = scan.has("step") ? Math.max(5, scan.get("step").getAsInt()) : 30;
            double reach = scan.has("reach") ? scan.get("reach").getAsDouble() : REACH;
            List<Float> pitches = new ArrayList<>();
            if (scan.has("pitches")) {
                for (JsonElement pitch : scan.getAsJsonArray("pitches")) {
                    pitches.add(pitch.getAsFloat());
                }
            } else {
                pitches.addAll(List.of(25.0F, 0.0F, -25.0F, -55.0F));
            }

            return new Once(report -> {
                Minecraft client = Minecraft.getInstance();
                LocalPlayer player = client.player;
                report.addProperty("action", "scan");
                if (player == null || client.level == null) {
                    return;
                }

                // The crosshair is the only eye the agent has, so this walks it around in a ring of
                // directions and keeps every distinct block that is actually in view. The head ends up back
                // where it started, so a scan costs no rotation.
                float wasYaw = player.getYRot();
                float wasPitch = player.getXRot();
                List<JsonObject> found = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();

                for (float pitch : pitches) {
                    for (int yaw = 0; yaw < 360; yaw += sweep) {
                        player.setYRot(yaw);
                        player.setXRot(pitch);
                        HitResult hit = player.pick(reach, 1.0F, false);
                        if (!(hit instanceof BlockHitResult block) || !seen.add(block.getBlockPos().toShortString())) {
                            continue;
                        }
                        BlockState state = client.level.getBlockState(block.getBlockPos());
                        JsonObject entry = new JsonObject();
                        entry.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                        entry.add("pos", blockPos(block.getBlockPos()));
                        entry.addProperty("yaw", yaw);
                        entry.addProperty("pitch", pitch);
                        entry.addProperty("distance", round(Math.sqrt(hit.distanceTo(player))));
                        found.add(entry);
                    }
                }

                player.setYRot(wasYaw);
                player.setXRot(wasPitch);
                found.sort(java.util.Comparator.comparingDouble(entry -> entry.get("distance").getAsDouble()));
                JsonArray blocks = new JsonArray();
                found.forEach(blocks::add);
                report.add("blocks", blocks);
                report.addProperty("count", blocks.size());
            });
        }

        if (spec.has("place")) {
            return new Once(report -> {
                Minecraft client = Minecraft.getInstance();
                LocalPlayer player = client.player;
                if (player == null || client.gameMode == null) {
                    return;
                }
                HitResult hit = player.pick(REACH, 1.0F, false);
                report.addProperty("action", "place");
                if (hit instanceof BlockHitResult block) {
                    report.addProperty("result", client.gameMode
                            .useItemOn(player, InteractionHand.MAIN_HAND, block).toString());
                } else {
                    report.addProperty("result", client.gameMode
                            .useItem(player, InteractionHand.MAIN_HAND).toString());
                }
            });
        }

        if (spec.has("use")) {
            return new Once(report -> {
                Minecraft client = Minecraft.getInstance();
                LocalPlayer player = client.player;
                if (player == null || client.gameMode == null) {
                    return;
                }
                report.addProperty("action", "use");
                report.addProperty("result", client.gameMode
                        .useItem(player, InteractionHand.MAIN_HAND).toString());
            });
        }

        if (spec.has("select")) {
            JsonObject select = object(spec, "select");
            int slot = select.get("slot").getAsInt();
            return new Once(report -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    return;
                }
                if (slot < 0 || slot > 8) {
                    throw new IllegalArgumentException("slot must be 0 to 8, the hotbar");
                }
                player.getInventory().setSelectedSlot(slot);
                report.addProperty("action", "select");
                report.addProperty("slot", slot);
            });
        }

        if (spec.has("slot")) {
            JsonObject slot = object(spec, "slot");
            int index = slot.get("index").getAsInt();
            int button = slot.has("button") ? slot.get("button").getAsInt() : 0;
            ClickType mode = ClickType.valueOf(
                    (slot.has("mode") ? slot.get("mode").getAsString() : "pickup").toUpperCase(Locale.ROOT));

            return new Once(report -> {
                Minecraft client = Minecraft.getInstance();
                LocalPlayer player = client.player;
                if (player == null || client.gameMode == null) {
                    return;
                }
                AbstractContainerMenu menu = player.containerMenu;
                report.addProperty("action", "slot");
                if (menu == null || index < 0 || index >= menu.slots.size()) {
                    report.addProperty("outcome", "no slot " + index + " in the open container");
                    return;
                }
                report.addProperty("was", AgentState.name(menu.slots.get(index).getItem()));
                report.addProperty("mode", mode.name().toLowerCase(Locale.ROOT));
                client.gameMode.handleInventoryMouseClick(menu.containerId, index, button, mode, player);
            });
        }

        if (spec.has("menu")) {
            JsonObject menu = object(spec, "menu");
            String open = menu.has("open") ? menu.get("open").getAsString() : "inventory";
            return new Once(report -> {
                Minecraft client = Minecraft.getInstance();
                LocalPlayer player = client.player;
                report.addProperty("action", "menu");
                if (player == null) {
                    return;
                }
                if (open.equalsIgnoreCase("inventory")) {
                    client.setScreen(new InventoryScreen(player));
                    report.addProperty("menu", "inventory");
                } else if (open.equalsIgnoreCase("close")) {
                    client.setScreen(null);
                    report.addProperty("menu", "closed");
                } else {
                    // Chests, furnaces, anvils and trades open themselves when the block or villager is used.
                    report.addProperty("outcome", "the game opens " + open + " itself - use the block instead");
                }
            });
        }

        if (spec.has("drop")) {
            boolean all = spec.get("drop").isJsonObject() && flag(spec.getAsJsonObject("drop"), "all");
            return new Once(report -> {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    return;
                }
                var stack = player.getInventory().getSelectedItem();
                report.addProperty("action", "drop");
                report.addProperty("dropped", AgentState.name(stack));
                report.addProperty("count", all ? stack.getCount() : Math.min(1, stack.getCount()));
                player.drop(stack, all);
            });
        }

        if (spec.has("chat")) {
            String text = string(spec, "chat", "text");
            return new Once(report -> {
                ClientPacketListener connection = Minecraft.getInstance().getConnection();
                if (connection == null) {
                    return;
                }
                report.addProperty("action", "chat");
                report.addProperty("text", text);
                if (text.startsWith("/")) {
                    connection.sendCommand(text.substring(1));
                } else {
                    connection.sendChat(text);
                }
            });
        }

        if (spec.has("screenshot")) {
            return new Once(report -> {
                Minecraft client = Minecraft.getInstance();
                Screenshot.grab(client.gameDirectory, client.getMainRenderTarget(), component -> {
                });
                report.addProperty("action", "screenshot");
                report.addProperty("path", client.gameDirectory + "\\screenshots");
            });
        }

        if (spec.has("respawn")) {
            return new Once(report -> {
                Minecraft client = Minecraft.getInstance();
                LocalPlayer player = client.player;
                if (player == null) {
                    return;
                }
                player.respawn();
                client.setScreen(null);
                report.addProperty("action", "respawn");
            });
        }

        if (spec.has("click")) {
            JsonObject click = object(spec, "click");
            int button = click.has("button") ? click.get("button").getAsInt() : 0;
            return new Once(report -> {
                Minecraft client = Minecraft.getInstance();
                Screen screen = client.screen;
                report.addProperty("action", "click");
                if (screen == null) {
                    report.addProperty("outcome", "no window is open");
                    return;
                }

                Double x = null;
                Double y = null;
                if (click.has("id")) {
                    AbstractWidget widget = widget(screen, click.get("id").getAsInt());
                    if (widget == null) {
                        report.addProperty("outcome", "no widget with that id");
                        return;
                    }
                    x = widget.getX() + widget.getWidth() / 2.0D;
                    y = widget.getY() + widget.getHeight() / 2.0D;
                    report.addProperty("label", widget.getMessage().getString());
                } else if (click.has("label")) {
                    String wanted = click.get("label").getAsString();
                    AbstractWidget widget = widget(screen, wanted);
                    if (widget == null) {
                        report.addProperty("outcome", "no button reads \"" + wanted + "\"");
                        return;
                    }
                    x = widget.getX() + widget.getWidth() / 2.0D;
                    y = widget.getY() + widget.getHeight() / 2.0D;
                    report.addProperty("label", widget.getMessage().getString());
                } else {
                    x = click.get("x").getAsDouble();
                    y = click.get("y").getAsDouble();
                }

                MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(button, 0));
                report.addProperty("handled", screen.mouseClicked(event, false));
                report.addProperty("screen", client.screen == null
                        ? "closed"
                        : client.screen.getClass().getSimpleName());
            });
        }

        if (spec.has("window")) {
            boolean open = !spec.get("window").isJsonObject() || flag(spec.getAsJsonObject("window"), "open");
            return new Once(report -> {
                Minecraft client = Minecraft.getInstance();
                ClientPacketListener connection = client.getConnection();
                report.addProperty("action", "window");
                if (open && connection != null) {
                    // The vanilla screen, on purpose: whatever mod replaces the achievements window gets its
                    // chance to swap it in, exactly as if the player had pressed the key.
                    client.setScreen(new AdvancementsScreen(connection.getAdvancements()));
                } else {
                    client.setScreen(null);
                }
                report.addProperty("screen", client.screen == null
                        ? "closed"
                        : client.screen.getClass().getSimpleName());
            });
        }

        throw new IllegalArgumentException("unknown step: " + spec.keySet());
    }

    // ------------------------------------------------------------------ the steps

    private abstract static class Base implements Step {
        private final JsonObject report = new JsonObject();

        @Override
        public JsonObject report() {
            return report;
        }

        protected void mark(String action, String detail) {
            report.addProperty("action", action);
            if (detail != null && !detail.isEmpty()) {
                report.addProperty("held", detail);
            }
        }
    }

    /** Work that happens in a single tick: looking, clicking, sending a line, opening a window. */
    private static final class Once extends Base {
        private final java.util.function.Consumer<JsonObject> work;
        private boolean done;

        private Once(java.util.function.Consumer<JsonObject> work) {
            this.work = work;
        }

        @Override
        public void tick(Minecraft client) {
            work.accept(report());
            done = true;
        }

        @Override
        public boolean finished() {
            return done;
        }

        @Override
        public int plannedTicks() {
            return 1;
        }
    }

    /** Keys held down for a number of ticks: walking, sprinting, sneaking, jumping. */
    private static final class Hold extends Base {
        private final Input presses;
        private final int planned;
        private final double startX;
        private final double startY;
        private final double startZ;
        private int remaining;

        private Hold(Input presses, int ticks, String action, String detail) {
            this.presses = presses;
            this.remaining = ticks;
            this.planned = ticks;
            mark(action, detail);

            LocalPlayer player = Minecraft.getInstance().player;
            this.startX = player == null ? 0.0D : player.getX();
            this.startY = player == null ? 0.0D : player.getY();
            this.startZ = player == null ? 0.0D : player.getZ();
        }

        @Override
        public void tick(Minecraft client) {
            AgentInput.hold(presses);
            remaining--;

            // The move is measured from where the step started, which is how the caller learns whether the
            // keys actually did anything (a wall, a closed world, a screen in the way).
            LocalPlayer player = client.player;
            if (player != null && finished()) {
                double dx = player.getX() - startX;
                double dz = player.getZ() - startZ;
                report().addProperty("moved", Math.round(Math.sqrt(dx * dx + dz * dz) * 100.0D) / 100.0D);
                report().addProperty("rose", Math.round((player.getY() - startY) * 100.0D) / 100.0D);
                report().addProperty("ticks", planned);
            }
        }

        @Override
        public boolean finished() {
            return remaining <= 0;
        }

        @Override
        public int plannedTicks() {
            return planned;
        }
    }

    /** Doing nothing at all, which is how the agent lets the world catch up - falling, cooking, waiting. */
    private static final class Wait extends Base {
        private int remaining;

        private Wait(int ticks) {
            this.remaining = ticks;
            mark("wait", "");
        }

        @Override
        public void tick(Minecraft client) {
            remaining--;
        }

        @Override
        public boolean finished() {
            return remaining <= 0;
        }

        @Override
        public int plannedTicks() {
            return Math.max(1, remaining);
        }
    }

    /** Left-click held down: swing at whatever the crosshair is on, or at the nearest hostile. */
    private static final class Attack extends Base {
        private final int planned;
        private int remaining;
        private int hits;
        private int kills;

        private Attack(int ticks) {
            this.planned = ticks;
            this.remaining = ticks;
            mark("attack", "");
        }

        @Override
        public void tick(Minecraft client) {
            remaining--;
            LocalPlayer player = client.player;
            if (player == null || client.gameMode == null) {
                return;
            }

            LivingEntity target = aim(client, player);
            if (target == null) {
                report().addProperty("outcome", "nothing within reach");
                return;
            }

            boolean wasAlive = target.isAlive();
            player.swing(InteractionHand.MAIN_HAND);
            client.gameMode.attack(player, target);
            hits++;
            if (wasAlive && !target.isAlive()) {
                kills++;
            }

            report().addProperty("target", target.getName().getString());
            report().addProperty("hits", hits);
            report().addProperty("kills", kills);
        }

        /** What to hit: whatever the crosshair is on, otherwise the nearest hostile, otherwise nothing. */
        private static LivingEntity aim(Minecraft client, LocalPlayer player) {
            HitResult hit = player.pick(ATTACK_REACH, 1.0F, false);
            if (hit instanceof EntityHitResult entity && entity.getEntity() instanceof LivingEntity living) {
                return living;
            }

            LivingEntity nearest = null;
            double best = Double.MAX_VALUE;
            for (Entity nearby : player.level().getEntities(player, player.getBoundingBox().inflate(ATTACK_REACH))) {
                if (!(nearby instanceof LivingEntity living)
                        || !(living instanceof net.minecraft.world.entity.monster.Enemy)
                        || !living.isAlive()) {
                    continue;
                }
                double distance = living.distanceToSqr(player);
                if (distance < best) {
                    best = distance;
                    nearest = living;
                }
            }

            // Face the new target, otherwise the swing lands on empty air behind the player.
            if (nearest != null) {
                double dx = nearest.getX() - player.getX();
                double dz = nearest.getZ() - player.getZ();
                double dy = nearest.getEyeY() - player.getEyeY();
                player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
                player.setXRot((float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
            }

            return nearest;
        }

        @Override
        public boolean finished() {
            return remaining <= 0;
        }

        @Override
        public int plannedTicks() {
            return planned;
        }
    }

    /** Left-click held on a block until it breaks, or until the ticks run out. */
    private static final class Mine extends Base {
        private final int planned;
        private int remaining;
        private BlockPos target;
        private Direction face = Direction.UP;
        private String blockId = "nothing";
        private boolean started;

        private Mine(int ticks) {
            this.planned = ticks;
            this.remaining = ticks;
            mark("mine", "");
        }

        @Override
        public void tick(Minecraft client) {
            remaining--;
            LocalPlayer player = client.player;
            if (player == null || client.gameMode == null || client.level == null) {
                return;
            }

            if (target == null) {
                HitResult hit = player.pick(REACH, 1.0F, false);
                if (!(hit instanceof BlockHitResult block)) {
                    report().addProperty("outcome", "nothing in reach");
                    remaining = 0;
                    return;
                }
                target = block.getBlockPos();
                face = block.getDirection();
                blockId = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(target).getBlock()).toString();
                report().addProperty("block", blockId);
            }

            BlockState state = client.level.getBlockState(target);
            if (state.isAir()) {
                report().addProperty("outcome", "broke " + blockId);
                report().addProperty("broken", true);
                if (started) {
                    client.gameMode.stopDestroyBlock();
                    started = false;
                }
                remaining = 0;
                return;
            }

            if (!started) {
                client.gameMode.startDestroyBlock(target, face);
                started = true;
            } else {
                client.gameMode.continueDestroyBlock(target, face);
            }
            report().addProperty("ticks", planned - remaining);
        }

        @Override
        public boolean finished() {
            if (remaining > 0) {
                return false;
            }
            if (started) {
                Minecraft.getInstance().gameMode.stopDestroyBlock();
                started = false;
            }
            if (!report().has("outcome")) {
                report().addProperty("outcome", "ran out of time on " + blockId);
            }
            return true;
        }

        @Override
        public int plannedTicks() {
            return planned;
        }
    }

    // ------------------------------------------------------------------ the window underneath

    /** The n-th clickable widget of a window, counted the way {@code AgentState} counts them. */
    private static AbstractWidget widget(Screen screen, int wanted) {
        int index = 0;
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof AbstractWidget widget) {
                if (index == wanted) {
                    return widget;
                }
                index++;
            }
        }
        return null;
    }

    /** The first widget whose label contains this text, case aside. */
    private static AbstractWidget widget(Screen screen, String wanted) {
        for (GuiEventListener listener : screen.children()) {
            if (listener instanceof AbstractWidget widget && widget.getMessage().getString()
                    .toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))) {
                return widget;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ loose json helpers

    private static JsonArray blockPos(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static JsonObject object(JsonObject spec, String key) {
        JsonElement value = spec.get(key);
        if (!value.isJsonObject()) {
            throw new IllegalArgumentException(key + " takes an object");
        }
        return value.getAsJsonObject();
    }

    private static String string(JsonObject spec, String key, String field) {
        JsonObject inner = object(spec, key);
        if (!inner.has(field)) {
            throw new IllegalArgumentException(key + " needs a \"" + field + "\"");
        }
        return inner.get(field).getAsString();
    }

    private static int ticks(JsonObject spec, int fallback) {
        return spec.has("ticks") ? spec.get("ticks").getAsInt() : fallback;
    }

    private static boolean flag(JsonObject spec, String key) {
        return spec.has(key) && spec.get(key).getAsBoolean();
    }

    private static String describe(JsonObject walk) {
        StringBuilder text = new StringBuilder();
        for (String key : List.of("forward", "backward", "left", "right", "jump", "sneak", "sprint")) {
            if (flag(walk, key)) {
                text.append(text.isEmpty() ? "" : "+").append(key);
            }
        }
        return text.toString();
    }
}
