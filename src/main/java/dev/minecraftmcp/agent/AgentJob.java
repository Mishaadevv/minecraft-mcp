package dev.minecraftmcp.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A long-running piece of play that the game itself carries out, one tick at a time.
 *
 * <p>This is the difference between the agent asking for a step and watching it happen, and the agent saying
 * "go and get me eight logs" and being free until the job is done. Jobs run on the game thread, react to the
 * world every tick - walking around holes, breaking what is in the way, picking the drops up - and keep a
 * short report of how they are getting on, which the agent can read whenever it likes.
 *
 * <p>A job stops itself the moment the player is in real trouble, so nothing keeps mining while a creeper
 * chews on the back of the player's head.
 */
public final class AgentJob {
    public enum Kind {
        WALK,
        GATHER
    }

    public enum Phase {
        SEEK,
        GOING,
        BREAKING,
        GRABBING,
        FINISHED
    }

    /** Below this much health a job gives up, whatever it was doing. */
    private static final int PANIC_HEALTH = 5;
    /** Below this much health, with something hostile nearby, the job runs away instead of working. */
    private static final float FLEE_HEALTH = 13.0F;
    /** How close a monster has to be to be worth running from. */
    private static final double DANGER = 12.0D;
    /** How far the run away goes: far enough to break line of sight over a hill. */
    private static final double FLEE_DISTANCE = 26.0D;
    /** Anything on this list gets eaten when the player is peckish. */
    private static final List<String> FOODS = List.of("minecraft:apple", "minecraft:golden_apple",
            "minecraft:bread", "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_chicken",
            "minecraft:cooked_mutton", "minecraft:cooked_rabbit", "minecraft:cooked_cod",
            "minecraft:cooked_salmon", "minecraft:beef", "minecraft:porkchop", "minecraft:chicken",
            "minecraft:mutton", "minecraft:rabbit", "minecraft:cod", "minecraft:salmon", "minecraft:carrot",
            "minecraft:potato", "minecraft:baked_potato", "minecraft:beetroot", "minecraft:beetroot_soup",
            "minecraft:mushroom_stew", "minecraft:rabbit_stew", "minecraft:cookie", "minecraft:melon_slice",
            "minecraft:sweet_berries", "minecraft:glow_berries", "minecraft:dried_kelp",
            "minecraft:pumpkin_pie", "minecraft:honey_bottle", "minecraft:rotten_flesh", "minecraft:tropical_fish");
    /** How many times a job may fail to reach one block before it accepts the world is not cooperating. */
    private static final int PATIENCE = 4;

    private final Kind kind;
    private final String why;
    private final double wanted;
    private final double radius;
    private final List<Block> blocks;
    private final double stop;

    private AgentWalk walker;
    private final AgentWorld.Breaker breaker = new AgentWorld.Breaker();

    private Phase phase = Phase.SEEK;
    private BlockPos target;
    private double startedWith;
    private double gathered;
    private int failures;
    private int ticks;
    private boolean seeded;
    private int deathGrace;
    private int eatCooldown;
    private AgentWalk flee;
    private AgentWalk grabber;
    private ItemEntity grabTarget;
    private String reason = "working";
    private String note = "";

    private AgentJob(Kind kind, String why, double wanted, double radius, List<Block> blocks, double stop) {
        this.kind = kind;
        this.why = why;
        this.wanted = wanted;
        this.radius = radius;
        this.blocks = blocks;
        this.stop = stop;
    }

    /** A job that walks the player to a spot and then stops. */
    public static AgentJob walkTo(double x, double z, double stop, String why) {
        AgentJob job = new AgentJob(Kind.WALK, why, 0, 0, List.of(), stop);
        job.walker = new AgentWalk(x, z, stop, why);
        job.phase = Phase.GOING;
        return job;
    }

    /** A job that finds the nearest blocks of these kinds, breaks them and picks up what falls out. */
    public static AgentJob gather(List<Block> blocks, int count, double radius, String why) {
        AgentJob job = new AgentJob(Kind.GATHER, why, count, radius, blocks, 3.2D);
        job.phase = Phase.SEEK;
        return job;
    }

    public Kind kind() {
        return kind;
    }

    public boolean finished() {
        return phase == Phase.FINISHED;
    }

    public String reason() {
        return reason;
    }

    public double gathered() {
        return gathered;
    }

    public double wanted() {
        return wanted;
    }

    /** One tick of the job. The bridge calls this every tick, straight on the game thread. */
    public void tick(Minecraft client) {
        ticks++;
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            stop(client, "the world went away");
            return;
        }

        if (safety(client, player, level)) {
            return;
        }
        if (player.getHealth() <= PANIC_HEALTH) {
            stop(client, "health is down to " + Math.round(player.getHealth()) + ", stopping to be safe");
            return;
        }

        switch (kind) {
            case WALK -> walkTick(client, player);
            case GATHER -> gatherTick(client, player, level);
            default -> stop(client, "nothing to do");
        }
    }

    /**
     * The reflexes that keep a job from ending in a death: get up again, run from monsters when hurt, and eat
     * when hungry. Returns true when it took the tick for itself.
     *
     * <p>This is the part that makes playing unattended safe. The agent is not watching every second, so the
     * decisions that cannot wait - a skeleton four blocks away and half a heart left - are made here, in the
     * game, on the tick they matter.
     */
    private boolean safety(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (player.isDeadOrDying()) {
            if (deathGrace > 0) {
                deathGrace--;
                return true;
            }
            deathGrace = 20;
            player.respawn();
            client.setScreen(null);
            note = "died and got up again";
            return true;
        }
        deathGrace = 0;

        // Drowning is the one emergency that has nothing to do with monsters: get the head up, now.
        if (player.isUnderWater()) {
            AgentInput.hold(AgentInput.of(true, false, false, false, true, false, false));
            reason = "swimming up for air";
            return true;
        }

        List<net.minecraft.world.entity.monster.Monster> monsters = level.getEntitiesOfClass(
                net.minecraft.world.entity.monster.Monster.class, player.getBoundingBox().inflate(DANGER));
        monsters.removeIf(monster -> !monster.isAlive());
        if (!monsters.isEmpty() && player.getHealth() <= FLEE_HEALTH) {
            net.minecraft.world.entity.monster.Monster worst = monsters.get(0);
            for (var monster : monsters) {
                if (monster.distanceTo(player) < worst.distanceTo(player)) {
                    worst = monster;
                }
            }
            if (flee == null) {
                double awayX = player.getX() - worst.getX();
                double awayZ = player.getZ() - worst.getZ();
                double length = Math.max(0.01D, Math.hypot(awayX, awayZ));
                flee = new AgentWalk(player.getX() + awayX / length * FLEE_DISTANCE,
                        player.getZ() + awayZ / length * FLEE_DISTANCE, 3.0D,
                        "away from the " + worst.getName().getString());
            }
            AgentWalk.Status ran = flee.tick(client);
            if (ran != AgentWalk.Status.WALKING) {
                flee = null;
            }
            reason = "running from a " + worst.getName().getString();
            return true;
        }
        flee = null;

        return hungry(client, player);
    }

    /** Eating is how a hurt player heals, so a hungry one stops working to do it. */
    private boolean hungry(Minecraft client, LocalPlayer player) {
        if (player.getFoodData().getFoodLevel() > 18 || client.gameMode == null) {
            return false;
        }
        if (eatCooldown-- > 0) {
            return true;
        }
        eatCooldown = 4;

        var inventory = player.getInventory();
        for (int slot = 0; slot < 9; slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()
                    && FOODS.contains(net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(stack.getItem()).toString())) {
                inventory.setSelectedSlot(slot);
                client.gameMode.useItem(player, net.minecraft.world.InteractionHand.MAIN_HAND);
                reason = "eating " + stack.getHoverName().getString();
                return true;
            }
        }
        return false;
    }

    private void walkTick(Minecraft client, LocalPlayer player) {
        if (walker == null) {
            stop(client, "no destination");
            return;
        }
        AgentWalk.Status status = walker.tick(client);
        if (status == AgentWalk.Status.ARRIVED) {
            stop(client, walker.why() + ": arrived");
        } else if (status == AgentWalk.Status.STUCK) {
            stop(client, walker.reason());
        }
    }

    private void gatherTick(Minecraft client, LocalPlayer player, ClientLevel level) {
        if (!seeded) {
            // Counting what the player already has makes "get me eight logs" mean eight more, not eight total.
            seeded = true;
            for (Block block : blocks) {
                startedWith += AgentWorld.count(player, block);
            }
            note = "started with " + (int) startedWith + " " + why;
        }

        int have = 0;
        for (Block block : blocks) {
            have += AgentWorld.count(player, block);
        }
        gathered = Math.max(0, have - startedWith);
        if (gathered >= wanted) {
            stop(client, "gathered " + (int) gathered + " " + why);
            return;
        }

        switch (phase) {
            case SEEK -> {
                target = AgentWorld.findNearest(client, blocks, radius);
                if (target == null) {
                    if (++failures > 2) {
                        stop(client, "no " + why + " left within " + (int) radius + " blocks");
                        return;
                    }
                    break;
                }
                walker = new AgentWalk(target.getX() + 0.5D, target.getZ() + 0.5D, stop,
                        "the nearest " + why);
                phase = Phase.GOING;
            }
            case GOING -> {
                if (target == null || !isTargetStillThere(level)) {
                    phase = Phase.SEEK;
                    return;
                }
                AgentWalk.Status status = walker.tick(client);
                if (status == AgentWalk.Status.ARRIVED) {
                    phase = Phase.BREAKING;
                    AgentInput.release();
                } else if (status == AgentWalk.Status.STUCK) {
                    if (++failures > PATIENCE) {
                        stop(client, "could not get to any " + why + ": " + walker.reason());
                        return;
                    }
                    phase = Phase.SEEK;
                }
            }
            case BREAKING -> {
                if (target == null || !isTargetStillThere(level)) {
                    phase = Phase.GRABBING;
                    return;
                }
                breakTick(client, player, level);
            }
            case GRABBING -> {
                List<ItemEntity> drops = AgentWorld.drops(client, 16);
                if (drops.isEmpty()) {
                    grabber = null;
                    grabTarget = null;
                    phase = Phase.SEEK;
                    return;
                }
                ItemEntity nearest = drops.get(0);
                if (nearest.distanceTo(player) < 1.6D) {
                    // The pickup happens by itself when the player stands on it; nothing to click.
                    grabber = null;
                    grabTarget = null;
                    phase = Phase.SEEK;
                    return;
                }

                // One walker per item, kept between ticks. A fresh one every tick would never remember that
                // it has been pushing against the same tree for a while, and so would never dig through.
                if (grabber == null || grabTarget != nearest) {
                    grabber = new AgentWalk(nearest.getX(), nearest.getZ(), 1.2D, "the drops");
                    grabTarget = nearest;
                }
                if (grabber.tick(client) != AgentWalk.Status.WALKING) {
                    grabber = null;
                    grabTarget = null;
                    if (++failures > PATIENCE * 3) {
                        phase = Phase.SEEK;
                        failures = 0;
                    }
                    return;
                }
            }
            default -> stop(client, "done");
        }
    }


    private boolean isTargetStillThere(ClientLevel level) {
        return target != null && !level.getBlockState(target).isAir();
    }

    /** Swings at the target block, clearing whatever is standing in the way first - leaves, usually. */
    private void breakTick(Minecraft client, LocalPlayer player, ClientLevel level) {
        BlockPos aiming = target;
        HitResult hit = player.pick(AgentWorld.REACH, 1.0F, false);
        if (!AgentWorld.inView(player, target)) {
            AgentWorld.lookAt(player, Vec3.atCenterOf(target));
            hit = player.pick(AgentWorld.REACH, 1.0F, false);
            if (hit instanceof BlockHitResult block && !block.getBlockPos().equals(target)) {
                aiming = block.getBlockPos();
            }
        }
        if (!breaker.breaking() || !aiming.equals(breaker.target())) {
            breaker.aim(client, aiming);
        }
        if (breaker.tick(client)) {
            phase = Phase.GRABBING;
        }
        if (breaker.ticks() > 400) {
            breaker.stop(client);
            phase = Phase.SEEK;
            failures++;
        }
    }

    /** Ends the job and lets the keys go: whoever started it reads the reason from {@link #status()}. */
    public void stop(Minecraft client, String why) {
        breaker.stop(client);
        AgentInput.release();
        reason = why;
        phase = Phase.FINISHED;
        if (note.isEmpty()) {
            note = why;
        }
    }

    /** A small report for the agent: what the job is, what it is doing and how far along it is. */
    public JsonObject status(Minecraft client) {
        JsonObject answer = new JsonObject();
        answer.addProperty("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
        answer.addProperty("phase", phase.name().toLowerCase(java.util.Locale.ROOT));
        answer.addProperty("looking", why);
        answer.addProperty("busy", !finished());
        answer.addProperty("ticks", ticks);
        answer.addProperty("reason", reason);
        if (kind == Kind.GATHER) {
            answer.addProperty("gathered", (int) gathered);
            answer.addProperty("wanted", (int) wanted);
        }
        if (target != null) {
            JsonArray pos = new JsonArray();
            pos.add(target.getX());
            pos.add(target.getY());
            pos.add(target.getZ());
            answer.add("target", pos);
        }
        if (walker != null && client.player != null) {
            answer.addProperty("distance", Math.round(walker.remaining(client.player) * 10.0D) / 10.0D);
        }
        if (!note.isEmpty() && finished()) {
            answer.addProperty("ended", note);
        }
        return answer;
    }
}
