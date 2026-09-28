package dev.minecraftmcp.agent;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

/**
 * The keys the agent is holding down this tick.
 *
 * <p>Movement goes through the game's own input rather than teleporting the player: {@code KeyboardInputMixin}
 * writes these presses into the client input right after the game has read the keyboard, and
 * {@code LocalPlayerInputMixin} hands the matching movement vector to the player's own tick, so walking uses
 * the normal physics, collision, food and server sync - the path a player's fingers take.
 */
public final class AgentInput {
    private static volatile Input presses;

    private AgentInput() {
    }

    /** Hold this combination until {@link #release()}. */
    public static void hold(Input input) {
        presses = input;
    }

    public static void release() {
        presses = null;
    }

    /** What to force onto the client input, or {@code null} while the agent is not touching the keys. */
    public static Input presses() {
        return presses;
    }

    /** The seven keys of a vanilla input record, in the order the game declares them. */
    public static Input of(boolean forward, boolean backward, boolean left, boolean right,
                           boolean jump, boolean shift, boolean sprint) {
        return new Input(forward, backward, left, right, jump, shift, sprint);
    }

    /**
     * The movement vector of a set of presses, worked out the way {@code KeyboardInput} works it out: forward
     * minus backward on one axis, left minus right on the other, then normalised so a diagonal is not faster.
     */
    public static Vec2 moveVector(Input presses) {
        return new Vec2(impulse(presses.left(), presses.right()),
                impulse(presses.forward(), presses.backward())).normalized();
    }

    private static float impulse(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }
}
