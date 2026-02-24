package net.matrix.systems.modules.mace;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.Setting;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.matrix.mixin.PlayerInventoryAccessor;

import java.util.Random;

/**
 * WindChargeClutch — Automatically throws a wind charge at the ground to negate fall
 * damage.
 *
 * Triggers when:
 * - Player is falling from a height ≥ minFallHeight
 * - Player is within throwHeight blocks of the ground
 * - AutoHit is NOT mid-combo (avoids breaking mace attacks)
 *
 * Uses silent rotation to look straight down (pitch 90), throws the wind
 * charge,
 * then restores rotation — identical to KeyWindCharge's silent throw logic.
 */
public class WindChargeClutch extends Module {
    private final Random random = new Random();

    // ── Settings ──
    private final Setting.DoubleSetting minFallHeight = new Setting.DoubleSetting(
            "Min Fall Height", "Minimum fall height (blocks) to activate", 4.0, 2.0, 30.0);
    private final Setting.DoubleSetting throwHeight = new Setting.DoubleSetting(
            "Throw Height", "Blocks above ground to throw the wind charge", 3.0, 1.0, 10.0);
    private final Setting.BooleanSetting silentMode = new Setting.BooleanSetting(
            "Silent Mode", "Instant server-side rotation (no visual movement)", true);
    private final Setting.DoubleSetting smoothness = new Setting.DoubleSetting(
            "Smoothness", "LERP factor per tick (Ghost Mode only)", 0.45, 0.1, 1.0);

    // ── State ──
    private enum Phase {
        WATCHING, // Waiting for fall conditions
        ROTATING_DOWN, // Rotating to pitch 90 (ghost mode)
        FIRING, // Throwing the wind charge
        ROTATING_BACK, // Restoring rotation (ghost mode)
        COOLDOWN // Brief cooldown after throw
    }

    private Phase phase = Phase.WATCHING;
    private float originalPitch;
    private float lastSentPitch;
    private int phaseDelay = 0;
    private int cooldownTicks = 0;

    private static final float PITCH_THRESHOLD = 3.0f;

    public WindChargeClutch() {
        super(Category.MACE, "Wind Charge Clutch", "Throws down a wind charge when falling from a lethal height.");
        settings.add(minFallHeight);
        settings.add(throwHeight);
        settings.add(silentMode);
        settings.add(smoothness);
    }

    @Override
    public void onActivate() {
        phase = Phase.WATCHING;
        phaseDelay = 0;
        cooldownTicks = 0;
    }

    @Override
    public void onDeactivate() {
        phase = Phase.WATCHING;
        phaseDelay = 0;
        cooldownTicks = 0;
    }

    @SuppressWarnings("null")
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null
                || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        switch (phase) {

            // ═══════════════════════════════════════
            // WATCHING — Check for fall damage conditions
            // ═══════════════════════════════════════
            case WATCHING -> {
                if (mc.player.isOnGround())
                    return;

                // Must be falling, not ascending
                double vy = mc.player.getVelocity().y;
                if (vy > -0.1)
                    return;

                // Use Minecraft's own fallDistance for reliable fall detection
                if (mc.player.fallDistance < minFallHeight.get())
                    return;

                // Must have wind charges
                if (findWCSlot() == -1)
                    return;

                // Check height above ground — must be within throw window
                double heightAboveGround = getHeightAboveGround();
                if (heightAboveGround > throwHeight.get())
                    return; // Too high — wait

                // DON'T interfere with AutoHit mid-combo
                AutoHit autoHit = Modules.get().get(AutoHit.class);
                if (autoHit != null && autoHit.isExecuting())
                    return;

                // All conditions met — start throw sequence
                originalPitch = mc.player.getPitch();
                lastSentPitch = originalPitch;

                if (silentMode.get()) {
                    phase = Phase.FIRING;
                    phaseDelay = 0;
                } else {
                    phase = Phase.ROTATING_DOWN;
                }
            }

            // ═══════════════════════════════════════
            // ROTATING_DOWN (Ghost mode) — LERP pitch to 90
            // ═══════════════════════════════════════
            case ROTATING_DOWN -> {
                float factor = smoothness.get().floatValue() + (float) (random.nextGaussian() * 0.04);
                factor = Math.max(0.1f, Math.min(0.92f, factor));

                float nextPitch = lastSentPitch + (90.0f - lastSentPitch) * factor;
                mc.player.setPitch(nextPitch);
                lastSentPitch = nextPitch;

                if (Math.abs(nextPitch - 90.0f) < PITCH_THRESHOLD) {
                    mc.player.setPitch(90.0f);
                    syncRotation();
                    phase = Phase.FIRING;
                    phaseDelay = 0;
                }
            }

            // ═══════════════════════════════════════
            // FIRING — Throw the wind charge straight down
            // ═══════════════════════════════════════
            case FIRING -> {
                if (phaseDelay > 0) {
                    if (silentMode.get()) {
                        syncRotation(mc.player.getYaw(), 90.0f);
                    }
                    phaseDelay--;
                    return;
                }

                int wcSlot = findWCSlot();
                if (wcSlot == -1) {
                    phase = Phase.WATCHING;
                    return;
                }

                int originalSlot = mc.player.getInventory().getSelectedSlot();

                // Swap to wind charge slot
                setSlot(wcSlot);

                if (silentMode.get()) {
                    // Temporarily set client pitch to 90 so interactItem fires downward
                    float savedPitch = mc.player.getPitch();
                    mc.player.setPitch(90.0f);
                    syncRotation(mc.player.getYaw(), 90.0f);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.setPitch(savedPitch);
                } else {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }

                // Restore slot
                setSlot(originalSlot);

                if (silentMode.get()) {
                    // Restore server rotation
                    syncRotation(mc.player.getYaw(), originalPitch);
                    phase = Phase.WATCHING;
                    cooldownTicks = 20; // 1 second cooldown
                } else {
                    lastSentPitch = 90.0f;
                    phase = Phase.ROTATING_BACK;
                }
            }

            // ═══════════════════════════════════════
            // ROTATING_BACK (Ghost mode) — Restore pitch
            // ═══════════════════════════════════════
            case ROTATING_BACK -> {
                float factor = smoothness.get().floatValue() + (float) (random.nextGaussian() * 0.04);
                factor = Math.max(0.1f, Math.min(0.92f, factor));

                float nextPitch = lastSentPitch + (originalPitch - lastSentPitch) * factor;
                mc.player.setPitch(nextPitch);
                lastSentPitch = nextPitch;

                if (Math.abs(nextPitch - originalPitch) < PITCH_THRESHOLD) {
                    mc.player.setPitch(originalPitch);
                    syncRotation();
                    phase = Phase.WATCHING;
                    cooldownTicks = 20;
                }
            }

            case COOLDOWN -> {
                // Handled by cooldownTicks above
                phase = Phase.WATCHING;
            }
        }
    }

    // ─── Height Detection ────────────────────────────────────

    @SuppressWarnings("null")
    private double getHeightAboveGround() {
        if (mc.player == null || mc.world == null)
            return 100;

        Vec3d pos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d down = pos.add(0, -100, 0);
        HitResult result = mc.world.raycast(new RaycastContext(
                pos, down,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player));

        if (result.getType() == HitResult.Type.MISS)
            return 100;

        return pos.y - result.getPos().y;
    }

    // ─── Utility ─────────────────────────────────────────────

    @SuppressWarnings("null")
    private int findWCSlot() {
        if (mc.player == null)
            return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE)
                return i;
        }
        return -1;
    }

    @SuppressWarnings("null")
    private void setSlot(int targetSlot) {
        if (targetSlot < 0 || targetSlot > 8)
            return;
        if (mc.player != null && mc.getNetworkHandler() != null) {
            ((PlayerInventoryAccessor) mc.player.getInventory()).setSelectedSlot(targetSlot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(targetSlot));
        }
    }
}
