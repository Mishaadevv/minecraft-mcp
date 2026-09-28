package dev.minecraftmcp.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The bits of the world a job needs: which block is where, whether the player can see it, and breaking it.
 *
 * <p>All of this reads the client's own view of the world - the same blocks the player is looking at - and
 * touches nothing off the game thread.
 */
public final class AgentWorld {
    /** How far the player can reach, the value the game itself uses. */
    public static final double REACH = 4.5D;

    private AgentWorld() {
    }

    /** Whether a block is there at all, as far as walking is concerned. */
    public static boolean solid(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    public static boolean water(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
    }

    public static boolean lava(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    /**
     * Whether this step is one no walker should take: lava, or water deep enough to swim in. Shallow water -
     * a puddle, a flooded path - is fine and is crossed like a player crosses it.
     */
    public static boolean deadlyOrWet(ClientLevel level, BlockPos feet) {
        if (lava(level, feet) || lava(level, feet.above())) {
            return true;
        }
        return water(level, feet) && water(level, feet.below());
    }

    /**
     * The nearest block of any of these kinds, out to {@code radius}, or null. The search walks outwards in
     * shells so the closest one wins without sorting the world, and it only looks at columns the client has.
     */
    public static BlockPos findNearest(Minecraft client, List<Block> wanted, double radius) {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || wanted.isEmpty()) {
            return null;
        }

        int fx = player.getBlockX();
        int fy = player.getBlockY();
        int fz = player.getBlockZ();
        int reach = (int) Math.ceil(radius);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int x = fx - reach; x <= fx + reach; x++) {
            for (int z = fz - reach; z <= fz + reach; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                double flat = Math.hypot(x - fx, z - fz);
                if (flat > reach || flat > bestDistance) {
                    continue;
                }
                for (int y = Math.max(level.getMinY(), fy - 10); y <= fy + 18; y++) {
                    cursor.set(x, y, z);
                    if (wanted.contains(level.getBlockState(cursor).getBlock())) {
                        double distance = Math.hypot(flat, y - fy);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = cursor.immutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    /** The same search, for a single block kind. */
    public static BlockPos findNearest(Minecraft client, Block block, double radius) {
        return findNearest(client, List.of(block), radius);
    }

    /**
     * Every block of these kinds within radius, nearest first, up to a limit: the answer to "where is the
     * nearest oak log", which the agent cannot work out for itself because it cannot see the world.
     */
    public static JsonArray findNearby(Minecraft client, List<Block> wanted, double radius, int limit) {
        JsonArray found = new JsonArray();
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || wanted.isEmpty()) {
            return found;
        }

        int reach = (int) Math.ceil(Math.min(radius, 64));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        List<double[]> hits = new ArrayList<>();
        List<BlockPos> places = new ArrayList<>();

        for (int x = player.getBlockX() - reach; x <= player.getBlockX() + reach; x++) {
            for (int z = player.getBlockZ() - reach; z <= player.getBlockZ() + reach; z++) {
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                double flat = Math.hypot(x - player.getBlockX(), z - player.getBlockZ());
                if (flat > reach) {
                    continue;
                }
                for (int y = Math.max(level.getMinY(), player.getBlockY() - 12);
                        y <= player.getBlockY() + 20; y++) {
                    cursor.set(x, y, z);
                    if (wanted.contains(level.getBlockState(cursor).getBlock())) {
                        places.add(cursor.immutable());
                        hits.add(new double[] {flat, y - player.getBlockY()});
                    }
                }
            }
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < places.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingDouble(index -> Math.hypot(hits.get(index)[0], hits.get(index)[1])));

        for (int i = 0; i < Math.min(limit, order.size()); i++) {
            BlockPos pos = places.get(order.get(i));
            double[] gap = hits.get(order.get(i));
            JsonObject entry = new JsonObject();
            entry.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
            JsonArray at = new JsonArray();
            at.add(pos.getX());
            at.add(pos.getY());
            at.add(pos.getZ());
            entry.add("pos", at);
            entry.addProperty("distance", Math.round(Math.hypot(gap[0], gap[1]) * 10.0D) / 10.0D);
            found.add(entry);
        }
        return found;
    }

    /** Every dropped item close by, nearest first - what a job walks over after breaking something. */
    public static List<ItemEntity> drops(Minecraft client, double radius) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return List.of();
        }
        AABB around = player.getBoundingBox().inflate(radius);
        List<ItemEntity> found = client.level.getEntitiesOfClass(ItemEntity.class, around);
        found.sort(Comparator.comparingDouble(item -> item.distanceToSqr(player)));
        return found;
    }

    /** Whether the crosshair is actually on this block: what makes breaking it the right thing to click. */
    public static boolean inView(LocalPlayer player, BlockPos pos) {
        HitResult hit = player.pick(REACH, 1.0F, false);
        return hit instanceof BlockHitResult block && block.getBlockPos().equals(pos);
    }

    public static void lookAt(LocalPlayer player, Vec3 target) {
        double dx = target.x - player.getX();
        double dy = target.y - player.getEyeY();
        double dz = target.z - player.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setXRot((float) Math.toDegrees(-Math.atan2(dy, flat)));
    }

    /** How many of this block's item the player is carrying, across the whole inventory. */
    public static int count(LocalPlayer player, Block block) {
        var item = block.asItem();
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static List<Block> byName(List<String> ids) {
        List<Block> blocks = new ArrayList<>();
        for (String id : ids) {
            for (Block block : BuiltInRegistries.BLOCK) {
                if (BuiltInRegistries.BLOCK.getKey(block).toString().equals(id)) {
                    blocks.add(block);
                    break;
                }
            }
        }
        return blocks;
    }

    /** The blocks that make up a tree, whatever kind it is - what a "chop" job swings at. */
    public static List<Block> logs() {
        return List.of(Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG, Blocks.JUNGLE_LOG,
                Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG,
                Blocks.PALE_OAK_LOG, Blocks.BAMBOO_BLOCK);
    }

    /** Breaking one block, one tick at a time, without needing the crosshair to stay put. */
    public static final class Breaker {
        private BlockPos target;
        private Direction face = Direction.UP;
        private String blockId = "nothing";
        private boolean started;
        private int ticks;

        public boolean breaking() {
            return target != null;
        }

        public BlockPos target() {
            return target;
        }

        public String blockId() {
            return blockId;
        }

        public void aim(Minecraft client, BlockPos pos) {
            stop(client);
            target = pos;
            BlockHitResult hit = pickFace(client, pos);
            face = hit == null ? Direction.UP : hit.getDirection();
            blockId = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString();
        }

        /** One tick of swinging. Returns true on the tick the block finally comes apart. */
        public boolean tick(Minecraft client) {
            if (target == null || client.player == null || client.gameMode == null || client.level == null) {
                return false;
            }
            ticks++;

            BlockState state = client.level.getBlockState(target);
            if (state.isAir()) {
                stop(client);
                return true;
            }
            if (inView(client.player, target)) {
                BlockHitResult hit = pickFace(client, target);
                if (hit != null) {
                    face = hit.getDirection();
                }
            }
            if (!started) {
                client.gameMode.startDestroyBlock(target, face);
                started = true;
            } else {
                client.gameMode.continueDestroyBlock(target, face);
            }
            return false;
        }

        public int ticks() {
            return ticks;
        }

        public void stop(Minecraft client) {
            if (started && client.gameMode != null) {
                client.gameMode.stopDestroyBlock();
            }
            started = false;
            target = null;
            ticks = 0;
        }

        private static BlockHitResult pickFace(Minecraft client, BlockPos pos) {
            // Look straight at the block so the face we swing at is the one the game would pick.
            AgentWorld.lookAt(client.player, Vec3.atCenterOf(pos));
            HitResult hit = client.player.pick(REACH, 1.0F, false);
            return hit instanceof BlockHitResult block ? block : null;
        }
    }
}
