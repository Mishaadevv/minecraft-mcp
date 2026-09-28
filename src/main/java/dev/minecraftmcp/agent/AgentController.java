package dev.minecraftmcp.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * Central controller for AI agent gameplay.
 *
 * <p>This class provides a unified interface for the AI agent to control the player.
 * It handles movement, looking, and actions in a way that feels natural and responsive.
 * All methods are designed to be called from the game thread.
 */
public final class AgentController {
    private static final AgentController INSTANCE = new AgentController();

    private AgentController() {
    }

    public static AgentController getInstance() {
        return INSTANCE;
    }

    /**
     * Makes the player walk in a direction.
     *
     * @param forward whether to walk forward
     * @param backward whether to walk backward
     * @param left whether to strafe left
     * @param right whether to strafe right
     * @param jump whether to jump
     * @param sneak whether to sneak
     * @param sprint whether to sprint
     */
    public void walk(boolean forward, boolean backward, boolean left, boolean right,
                     boolean jump, boolean sneak, boolean sprint) {
        Input input = AgentInput.of(forward, backward, left, right, jump, sneak, sprint);
        AgentInput.hold(input);
    }

    /**
     * Makes the player walk forward.
     */
    public void walkForward() {
        walk(true, false, false, false, false, false, false);
    }

    /**
     * Makes the player walk forward and sprint.
     */
    public void sprintForward() {
        walk(true, false, false, false, false, false, true);
    }

    /**
     * Makes the player walk forward and jump.
     */
    public void jumpForward() {
        walk(true, false, false, false, true, false, false);
    }

    /**
     * Makes the player stop moving.
     */
    public void stop() {
        AgentInput.release();
    }

    /**
     * Makes the player look in a direction.
     *
     * @param yaw the yaw angle in degrees
     * @param pitch the pitch angle in degrees
     */
    public void look(float yaw, float pitch) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.setYRot(yaw);
            client.player.setXRot(pitch);
        }
    }

    /**
     * Makes the player look at a position.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     * @param z the z coordinate
     */
    public void lookAt(double x, double y, double z) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            double dx = x - client.player.getX();
            double dy = y - client.player.getEyeY();
            double dz = z - client.player.getZ();
            double flat = Math.sqrt(dx * dx + dz * dz);

            client.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            client.player.setXRot((float) Math.toDegrees(-Math.atan2(dy, flat)));
        }
    }

    /**
     * Makes the player look at a block position.
     *
     * @param pos the block position
     */
    public void lookAtBlock(net.minecraft.core.BlockPos pos) {
        lookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /**
     * Gets the current movement vector.
     *
     * @return the movement vector
     */
    public Vec2 getMovementVector() {
        Input presses = AgentInput.presses();
        if (presses == null) {
            return new Vec2(0, 0);
        }
        return AgentInput.moveVector(presses);
    }

    /**
     * Checks if the player is moving.
     *
     * @return true if the player is moving
     */
    public boolean isMoving() {
        return AgentInput.presses() != null;
    }

    /**
     * Makes the player attack.
     */
    public void attack() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.gameMode != null) {
            client.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Makes the player use an item.
     */
    public void use() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.gameMode != null) {
            client.gameMode.useItem(client.player, net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Makes the player place a block.
     */
    public void place() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.gameMode != null) {
            var hit = client.player.pick(4.5D, 1.0F, false);
            if (hit instanceof net.minecraft.world.phys.BlockHitResult blockHit) {
                client.gameMode.useItemOn(client.player, net.minecraft.world.InteractionHand.MAIN_HAND, blockHit);
            }
        }
    }

    /**
     * Makes the player select a hotbar slot.
     *
     * @param slot the slot index (0-8)
     */
    public void selectSlot(int slot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && slot >= 0 && slot <= 8) {
            client.player.getInventory().setSelectedSlot(slot);
        }
    }

    /**
     * Gets the player's current health.
     *
     * @return the health
     */
    public float getHealth() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null ? client.player.getHealth() : 0;
    }

    /**
     * Gets the player's current food level.
     *
     * @return the food level
     */
    public int getFoodLevel() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null ? client.player.getFoodData().getFoodLevel() : 0;
    }

    /**
     * Checks if the player is dead.
     *
     * @return true if the player is dead
     */
    public boolean isDead() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.player.isDeadOrDying();
    }

    /**
     * Makes the player respawn.
     */
    public void respawn() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.respawn();
            client.setScreen(null);
        }
    }
}
