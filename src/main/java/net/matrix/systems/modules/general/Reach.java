package net.matrix.systems.modules.general;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import java.util.Random;

public class Reach extends Module {
    private final Random random = new Random();

    // ── Configurable settings ──
    private final Setting.DoubleSetting reachDistance = new Setting.DoubleSetting(
            "Reach Distance", "Target mean distance", 3.4, 3.0, 5.0);
    private final Setting.DoubleSetting jitter = new Setting.DoubleSetting(
            "Jitter", "Gaussian fluctuation variance (Ghost)", 0.2, 0.0, 0.5);
    private final Setting.BooleanSetting misplace = new Setting.BooleanSetting(
            "Misplace", "Position desync to mask extended reach", false);
    private final Setting.DoubleSetting misplaceStrength = new Setting.DoubleSetting(
            "Misplace Strength", "How far to nudge position (blocks)", 0.15, 0.05, 0.30);

    public Reach() {
        super(Category.GENERAL, "Reach", "Extends interaction distance using dynamic reach jitter.");

        settings.add(reachDistance);
        settings.add(jitter);
        settings.add(misplace);
        settings.add(misplaceStrength);
    }

    public double getReachDistance() {
        double base = reachDistance.get();
        double variance = jitter.get();
        // Prestige-style Gaussian fluctuation
        double varied = base + (random.nextGaussian() * variance);

        // Clamped between 3.0 and 3.8 to remain 'Ghostly' and undetectable
        return Math.max(3.0, Math.min(3.8, varied));
    }

    /**
     * Misplace: Sends a subtle position offset packet toward the target
     * just before an attack, making it look like natural movement jitter.
     * Call this from KillAura/AimAssist right before attacking.
     */
    @SuppressWarnings("null")
    public void applyMisplace(Entity target) {
        if (!misplace.get() || !isActive())
            return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null || target == null)
            return;

        // Calculate direction toward target
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
        Vec3d direction = targetPos.subtract(playerPos).normalize();

        // Nudge strength with slight randomization
        double strength = misplaceStrength.get() * (0.8 + random.nextDouble() * 0.4);

        // Calculate nudged position
        double nudgeX = playerPos.x + direction.x * strength;
        double nudgeZ = playerPos.z + direction.z * strength;

        // Send the nudged position to the server (looks like natural movement)
        mc.getNetworkHandler().sendPacket(
                new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.PositionAndOnGround(
                        nudgeX, playerPos.y, nudgeZ, mc.player.isOnGround(), mc.player.horizontalCollision));

        // Immediately send real position back (the server sees a tiny "jitter")
        mc.getNetworkHandler().sendPacket(
                new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.PositionAndOnGround(
                        playerPos.x, playerPos.y, playerPos.z, mc.player.isOnGround(), mc.player.horizontalCollision));
    }
}
