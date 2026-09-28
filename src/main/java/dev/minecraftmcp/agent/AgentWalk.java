package dev.minecraftmcp.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/**
 * Walking somewhere without falling into anything.
 *
 * <p>A tick at a time, the walker looks at the ground a step and a half ahead before taking that step. Flat
 * ground and a one-block step are fine; a two-block drop is not, and neither is water, lava or a wall. When
 * the way ahead is no good it tries the same step angled a little to either side, the way a player edges
 * around a hole, and only gives up when nothing nearby is walkable at all.
 *
 * <p>Everything it does is real key presses - forward, sprint, jump - through {@link AgentInput}, so the
 * player walks with the game's own physics, gets hungry doing it, and can still be shoved off a ledge.
 */
public final class AgentWalk {
    public enum Status {
        WALKING,
        ARRIVED,
        STUCK
    }

    /** How far in front the ground is tested: the step about to be taken, one block away. */
    private static final double PROBE = 1.0D;
    /** How far the search fans out when the way ahead is blocked, in degrees. */
    private static final int[] FAN = {0, 18, -18, 36, -36, 54, -54, 72, -72, 90, -90};
    /** Ticks without gaining a block of ground before the walk is declared stuck. */
    private static final int STALL_LIMIT = 60;
    /** Ticks without gaining a block before the walk gives up. Digging out is allowed to take a while. */
    private static final int IDLE_LIMIT = 400;
    /** Ticks without progress before jumping is added, which climbs one-block steps and shallow holes. */
    private static final int JUMP_AFTER = 8;
    /** How many dead ends to try digging through before accepting that there is no way. */
    private static final int DIGS_LIMIT = 20;

    private final double targetX;
    private final double targetZ;
    private final double stop;
    private final String why;

    private final AgentWorld.Breaker digger = new AgentWorld.Breaker();

    private double lastX = Double.NaN;
    private double lastZ;
    private int idle;
    private int boxed;
    private int digs;
    private int ticks;
    private String reason = "walking";

    public AgentWalk(double targetX, double targetZ, double stop, String why) {
        this.targetX = targetX;
        this.targetZ = targetZ;
        this.stop = stop;
        this.why = why;
    }

    public String why() {
        return why;
    }

    public String reason() {
        return reason;
    }

    public int ticks() {
        return ticks;
    }

    public double remaining(LocalPlayer player) {
        return Math.hypot(targetX - player.getX(), targetZ - player.getZ());
    }

    /** One tick of walking. The caller runs this every tick until it stops being {@code WALKING}. */
    public Status tick(Minecraft client) {
        ticks++;
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            AgentInput.release();
            reason = "there is no world to walk in";
            return Status.STUCK;
        }

        double toX = targetX - player.getX();
        double toZ = targetZ - player.getZ();
        if (Math.hypot(toX, toZ) <= stop) {
            AgentInput.release();
            reason = "arrived";
            return Status.ARRIVED;
        }

        // Progress is counted in whole blocks: the shoving about at a wall does not count as walking. The
        // patience here is deliberately generous, because digging out takes a while and is the fix.
        if (Double.isNaN(lastX)) {
            lastX = player.getX();
            lastZ = player.getZ();
        }
        if (Math.hypot(player.getX() - lastX, player.getZ() - lastZ) > 1.0D) {
            lastX = player.getX();
            lastZ = player.getZ();
            idle = 0;
        } else if (++idle > IDLE_LIMIT) {
            AgentInput.release();
            reason = "went nowhere for " + IDLE_LIMIT + " ticks at "
                    + Math.round(player.getX()) + ", " + Math.round(player.getZ());
            return Status.STUCK;
        }

        // Swimming is its own thing: there is no ground to read, so swim at the target and keep the head up.
        if (player.isInWater()) {
            player.setYRot((float) Math.toDegrees(Math.atan2(-toX, toZ)));
            AgentInput.hold(AgentInput.of(true, false, false, false, true, false, false));
            return Status.WALKING;
        }

        double bearing = Math.toDegrees(Math.atan2(-toX, toZ));
        double heading = choose(level, player, bearing);
        if (Double.isNaN(heading)) {
            // Nowhere to walk. Break the block that is in the way - a player in a hole digs out - and jump on
            // the spot meanwhile, which is how one-block dips and single steps are left behind.
            AgentInput.hold(AgentInput.of(true, false, false, false, true, false, false));
            boxed++;
            return digOut(client, level, player, bearing);
        }

        boxed = 0;
        player.setYRot((float) heading);
        boolean climb = probe(level, player, heading) == 1;

        // Pressing forward into a step that will not budge means something is in the way. Enough of that and
        // breaking it is the sensible thing, exactly as a player would.
        if (idle > JUMP_AFTER * 4) {
            return digOut(client, level, player, heading);
        }

        AgentInput.hold(AgentInput.of(true, false, false, false, climb || idle > JUMP_AFTER, false, !climb));
        return Status.WALKING;
    }

    /**
     * Breaks the block blocking the way, feet first and then head height, and starts the walk again once it
     * is gone. A player in a one-block hole has to dig out, and so does this one.
     */
    private Status digOut(Minecraft client, ClientLevel level, LocalPlayer player, double bearing) {
        BlockPos blocking = blocking(level, player, bearing);
        if (blocking == null) {
            // Nothing solid in the way and still not walkable: the ground itself is missing, so this is a pit
            // and not a wall. Give the fan another chance before calling it stuck.
            if (++digs > DIGS_LIMIT) {
                AgentInput.release();
                reason = "no way through towards (" + Math.round(targetX) + ", " + Math.round(targetZ) + ")";
                return Status.STUCK;
            }
            return Status.WALKING;
        }

        if (!digger.breaking() || !blocking.equals(digger.target())) {
            digger.aim(client, blocking);
        }
        if (digger.tick(client)) {
            // A way through: forget how stuck we felt and start reading the ground again.
            boxed = 0;
            idle = 0;
            digs = 0;
            lastX = player.getX();
            lastZ = player.getZ();
        } else if (digger.ticks() > 200) {
            // Something this walker cannot break at all. Stop rather than stand there swinging.
            AgentInput.release();
            reason = "could not break the " + digger.blockId() + " in the way";
            return Status.STUCK;
        }
        return Status.WALKING;
    }

    /**
     * The block to break to make this heading walkable: the first one along the way, at the feet before head
     * height. It checks cell by cell from the player outwards, because the block that stops a player is
     * usually the one right beside them rather than the one a whole block away.
     */
    private static BlockPos blocking(ClientLevel level, LocalPlayer player, double bearing) {
        double radians = Math.toRadians(bearing);
        BlockPos feet = player.blockPosition();

        for (double reach = 0.6D; reach <= PROBE + 0.5D; reach += 0.4D) {
            int x = (int) Math.floor(player.getX() - Math.sin(radians) * reach);
            int z = (int) Math.floor(player.getZ() + Math.cos(radians) * reach);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            BlockPos low = new BlockPos(x, feet.getY(), z);
            if (AgentWorld.solid(level, low)) {
                // Only dig where there will still be a floor afterwards. Breaking a block over nothing is
                // how a walk turns into a fall, and a fall is exactly what this walker is here to avoid.
                if (AgentWorld.solid(level, low.below())) {
                    return low;
                }
                continue;
            }
            BlockPos high = low.above();
            if (AgentWorld.solid(level, high)) {
                return high;
            }
        }
        return null;
    }

    /** The heading to walk in: straight at the target if that is walkable, otherwise the closest that is. */
    private static double choose(ClientLevel level, LocalPlayer player, double bearing) {
        double stepUp = Double.NaN;
        for (int offset : FAN) {
            double candidate = bearing + offset;
            int verdict = probe(level, player, candidate);
            if (verdict == 0) {
                return candidate;
            }
            if (verdict == 1 && Double.isNaN(stepUp)) {
                stepUp = candidate;
            }
        }
        return stepUp;
    }

    /**
     * What is a step and a half ahead in this direction: 0 for walkable, 1 for a step up, -1 for no.
     *
     * <p>The rule that matters is the drop. If the ground under the next step is more than a block lower than
     * the feet, the answer is no and the walker looks somewhere else - that is the whole defence against
     * walking off a cliff or into a ravine, and it is why this reads the world instead of trusting the keys.
     */
    private static int probe(ClientLevel level, LocalPlayer player, double heading) {
        double radians = Math.toRadians(heading);
        BlockPos feet = player.blockPosition();
        int x = (int) Math.floor(player.getX() - Math.sin(radians) * PROBE);
        int z = (int) Math.floor(player.getZ() + Math.cos(radians) * PROBE);

        if (!level.hasChunk(x >> 4, z >> 4)) {
            return -1;
        }

        int floor = feet.getY();
        if (AgentWorld.deadlyOrWet(level, new BlockPos(x, floor, z))) {
            return -1;
        }

        int surface = Integer.MIN_VALUE;
        for (int y = floor + 1; y >= floor - 3; y--) {
            if (AgentWorld.solid(level, new BlockPos(x, y, z))) {
                surface = y + 1;
                break;
            }
        }
        if (surface == Integer.MIN_VALUE) {
            return -1;      // nothing to stand on within three blocks: a hole
        }

        int rise = surface - floor;
        if (rise > 1 || rise < -1) {
            return -1;      // a wall, or a drop of two or more
        }
        if (!passable(level, x, surface, z) || !passable(level, x, surface + 1, z)) {
            return -1;      // no room for the player's two blocks of height
        }
        return rise == 1 ? 1 : 0;
    }

    private static boolean passable(ClientLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return !AgentWorld.solid(level, pos) && !AgentWorld.lava(level, pos);
    }
}
