package net.matrix.systems.modules.mace;

import net.matrix.mixin.PlayerInventoryAccessor;
import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * PearlBurst — Ghost "Pearl-Burst" macro.
 *
 * On keybind press:
 * 1. (Optional) Rotate toward nearest entity using pearl trajectory math
 * 2. Silently or Non-Silently swap to pearl and throw it
 * 3. Track the pearl via teleport detection
 * 4. On teleport: look down → fire Wind Charge → swap to Mace
 * 5. Auto-disable
 */
public class PearlBurst extends Module {
    private final Random random = new Random();

    // ── Settings ──
    private final Setting.BooleanSetting silentMode = new Setting.BooleanSetting(
            "Silent Mode", "Instant rotate via packets (no screen movement)", true);
    private final Setting.DoubleSetting smoothness = new Setting.DoubleSetting(
            "Smoothness", "LERP factor per tick (non-silent only)", 0.40, 0.1, 1.0);
    private final Setting.IntSetting delayMin = new Setting.IntSetting(
            "Delay Min", "Min ticks after teleport before wind charge", 0, 0, 10);
    private final Setting.IntSetting delayMax = new Setting.IntSetting(
            "Delay Max", "Max ticks after teleport before wind charge", 1, 0, 10);
    private final Setting.BooleanSetting towardsEntity = new Setting.BooleanSetting(
            "Towards Entity", "Calculate perfect trajectory to hit nearest target", false);
    private final Setting.BooleanSetting jump = new Setting.BooleanSetting(
            "Jump", "Jumps instantly after throwing the wind charge", true);
    private final Setting.StringSetSetting targetEntities;

    // ── State machine ──
    private enum Phase {
        IDLE, LERP_TO_ENTITY, THROW_PEARL, TRACKING, DELAY, LERP_DOWN, FIRE_WC, SWAP_MACE
    }

    private Phase phase = Phase.IDLE;
    private int ticksRemaining = 0;
    private Vec3d lastTickPos = null;
    private Entity aimTarget = null;
    private float lastSentPitch;
    private float lastSentYaw;
    private int originalSlot = -1;
    private boolean isExecuting = false;

    private static final float PITCH_THRESHOLD = 4.0f;

    public PearlBurst() {
        super(Category.MACE, "Pearl Burst",
                "Throws pearl at target, then wind-charges down and equips Mace on landing.");

        List<String> allEntities = new ArrayList<>();
        Set<String> defaults = new LinkedHashSet<>();
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            String id = EntityType.getId(type).toString();
            allEntities.add(id);
            if (id.equals("minecraft:player")) {
                defaults.add(id);
            }
        }
        targetEntities = new Setting.StringSetSetting(
                "Target Entities", "Which entities to target", allEntities, defaults);

        settings.add(silentMode);
        settings.add(smoothness);
        settings.add(delayMin);
        settings.add(delayMax);
        settings.add(towardsEntity);
        settings.add(jump);
        settings.add(targetEntities);
    }

    @SuppressWarnings("null")
    @Override
    public void onActivate() {
        if (mc.player == null) {
            safeDisable();
            return;
        }

        // Prevent double triggers
        if (isExecuting) {
            return;
        }
        isExecuting = true;

        // Check required items
        if (findHotbarItem(Items.ENDER_PEARL) == -1 || findHotbarItem(Items.WIND_CHARGE) == -1
                || findHotbarItem(Items.MACE) == -1) {
            safeDisable();
            return;
        }

        lastSentPitch = mc.player.getPitch();
        lastSentYaw = mc.player.getYaw();
        originalSlot = mc.player.getInventory().getSelectedSlot();
        aimTarget = null;

        if (towardsEntity.get()) {
            aimTarget = getNearestTarget();
            if (aimTarget != null) {
                phase = Phase.LERP_TO_ENTITY;
            } else {
                // No target found in render distance: blue message and cancel
                mc.player.sendMessage(Text.literal("No Entity Found").formatted(Formatting.BLUE), false);
                safeDisable();
                return;
            }
        } else {
            phase = Phase.THROW_PEARL;
        }
    }

    @Override
    public void onDeactivate() {
        phase = Phase.IDLE;
        ticksRemaining = 0;
        lastTickPos = null;
        aimTarget = null;
        isExecuting = false;
        originalSlot = -1; // clear memory
    }

    private void safeDisable() {
        if (this.isActive()) {
            this.toggle(); // Turn off gracefully without breaking the system
        }
    }

    @SuppressWarnings("null")
    @Override
    public void onTick() {
        if (!isExecuting)
            return; // Guard clause

        if (mc.player == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            safeDisable();
            return;
        }

        switch (phase) {
            // ── Phase 1: Rotate toward entity before throwing pearl ──
            case LERP_TO_ENTITY -> {
                if (aimTarget == null || !aimTarget.isAlive()) {
                    // Target lost, throw where looking
                    phase = Phase.THROW_PEARL;
                    return;
                }

                float[] angles = calculatePearlTrajectory(aimTarget);
                // Fallback if unreachable
                if (angles == null) {
                    angles = calcAnglesTo(aimTarget);
                }

                float targetYaw = angles[0];
                float targetPitch = angles[1];

                if (silentMode.get()) {
                    // Instant silent rotation
                    syncRotation(targetYaw, targetPitch);
                    lastSentYaw = targetYaw;
                    lastSentPitch = targetPitch;
                    phase = Phase.THROW_PEARL;
                } else {
                    // LERP toward entity
                    float factor = smoothness.get().floatValue() + (float) (random.nextGaussian() * 0.04);
                    factor = Math.max(0.1f, Math.min(0.8f, factor));

                    float deltaYaw = MathHelper.wrapDegrees(targetYaw - mc.player.getYaw());
                    float deltaPitch = MathHelper.wrapDegrees(targetPitch - mc.player.getPitch());

                    mc.player.setYaw(mc.player.getYaw() + deltaYaw * factor);
                    mc.player.setPitch(mc.player.getPitch() + deltaPitch * factor);
                    lastSentYaw = mc.player.getYaw();
                    lastSentPitch = mc.player.getPitch();

                    // Check if close enough
                    if (Math.abs(deltaYaw) < PITCH_THRESHOLD && Math.abs(deltaPitch) < PITCH_THRESHOLD) {
                        syncRotation();
                        phase = Phase.THROW_PEARL;
                    }
                }
            }

            // ── Phase 2: Throw the ender pearl ──
            case THROW_PEARL -> {
                int pearlSlot = findHotbarItem(Items.ENDER_PEARL);
                if (pearlSlot == -1) {
                    safeDisable();
                    return;
                }

                if (silentMode.get()) {
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(pearlSlot));

                    float savedYaw = mc.player.getYaw();
                    float savedPitch = mc.player.getPitch();
                    mc.player.setYaw(lastSentYaw);
                    mc.player.setPitch(lastSentPitch);
                    syncRotation(lastSentYaw, lastSentPitch);

                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

                    mc.player.setYaw(savedYaw);
                    mc.player.setPitch(savedPitch);
                    syncRotation(savedYaw, savedPitch);
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                } else {
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(pearlSlot));
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                }

                // Start tracking for teleport
                lastTickPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                phase = Phase.TRACKING;
            }

            // ── Phase 3: Wait for ender pearl teleport ──
            case TRACKING -> {
                Vec3d currentPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

                boolean teleported = false;
                if (lastTickPos != null) {
                    // Calculate the difference between actual movement and expected movement (velocity).
                    // This handles point-blank teleports (difference easily > 0.15 blocks) and falling teleports.
                    Vec3d actualMovement = currentPos.subtract(lastTickPos);
                    Vec3d expectedMovement = mc.player.getVelocity();
                    
                    if (actualMovement.squaredDistanceTo(expectedMovement) > 0.15 || mc.player.hurtTime == 10) {
                        teleported = true;
                    }
                }

                if (teleported) {
                    // Teleport detected!
                    int min = delayMin.get();
                    int max = delayMax.get();
                    if (max < min)
                        max = min;
                    ticksRemaining = min + (max > min ? random.nextInt(max - min + 1) : 0);

                    if (ticksRemaining > 0) {
                        phase = Phase.DELAY;
                    } else {
                        phase = Phase.LERP_DOWN;
                    }
                }

                lastTickPos = currentPos;
            }

            // ── Phase 4: Post-teleport delay ──
            case DELAY -> {
                if (ticksRemaining > 0) {
                    ticksRemaining--;
                    return;
                }

                phase = Phase.LERP_DOWN;
            }

            // ── Phase 5: Look straight down ──
            case LERP_DOWN -> {
                float targetPitch = 90.0f;

                if (silentMode.get()) {
                    syncRotation(mc.player.getYaw(), targetPitch);
                    lastSentPitch = targetPitch;
                    phase = Phase.FIRE_WC;
                } else {
                    float factor = smoothness.get().floatValue() + (float) (random.nextGaussian() * 0.04);
                    factor = Math.max(0.1f, Math.min(0.8f, factor));

                    float nextPitch = mc.player.getPitch() + (targetPitch - mc.player.getPitch()) * factor;
                    mc.player.setPitch(nextPitch);
                    lastSentPitch = nextPitch;

                    if (Math.abs(nextPitch - targetPitch) < PITCH_THRESHOLD) {
                        syncRotation();
                        phase = Phase.FIRE_WC;
                    }
                }
            }

            // ── Phase 6: Fire Wind Charge downward ──
            case FIRE_WC -> {
                int wcSlot = findHotbarItem(Items.WIND_CHARGE);
                if (wcSlot == -1) {
                    safeDisable();
                    return;
                }

                setSlot(wcSlot);

                if (silentMode.get()) {
                    float savedPitch = mc.player.getPitch();
                    mc.player.setPitch(90.0f);
                    syncRotation(mc.player.getYaw(), 90.0f);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.setPitch(savedPitch);
                    syncRotation(mc.player.getYaw(), savedPitch);
                } else {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }

                if (jump.get()) {
                    mc.player.jump();
                }

                phase = Phase.SWAP_MACE;
            }

            // ── Phase 7: Swap to Mace ──
            case SWAP_MACE -> {
                int maceSlot = findHotbarItem(Items.MACE);
                if (maceSlot != -1) {
                    setSlot(maceSlot);
                }
                safeDisable(); // We're done
            }

            case IDLE -> {
            }
        }
    }

    // ── Helpers ──

    /**
     * Calculates the ballistic trajectory to hit the target with an Ender Pearl.
     * Velocity for Ender Pearl is 1.5, gravity is 0.03, drag is 0.99.
     * Returns [yaw, pitch] or null if impossible.
     */
    @SuppressWarnings("null")
    private float[] calculatePearlTrajectory(Entity target) {
        if (mc.player == null)
            return null;

        double targetX = target.getX();
        double targetY = target.getY(); // Aiming for their feet to teleport right on them
        double targetZ = target.getZ();

        double startX = mc.player.getX();
        double startY = mc.player.getEyeY() - 0.1; // Ender pearl throws from slightly below eye level
        double startZ = mc.player.getZ();

        double diffX = targetX - startX;
        double diffZ = targetZ - startZ;
        double diffY = targetY - startY;

        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0f;

        // Iterate through pitches to simulate trajectory
        // Sweep from 90 (down) to -90 (up) to favor lower, faster trajectories
        for (float pitch = 90; pitch >= -90; pitch -= 0.5f) {
            double pitchRad = Math.toRadians(pitch);
            double yawRad = Math.toRadians(yaw);

            // Pearl initial velocities (1.5 base velocity)
            double vx = -Math.sin(yawRad) * Math.cos(pitchRad) * 1.5;
            double vy = -Math.sin(pitchRad) * 1.5;
            double vz = Math.cos(yawRad) * Math.cos(pitchRad) * 1.5;

            double px = 0;
            double py = 0;
            double pz = 0;

            // Simulate up to 300 ticks to account for long-bomb extreme distances
            for (int tick = 0; tick < 300; tick++) {
                px += vx;
                py += vy;
                pz += vz;

                // Drag
                vx *= 0.99;
                vy *= 0.99;
                vz *= 0.99;

                // Gravity (Ender Pearl specific: 0.03)
                vy -= 0.03;

                // Check distance
                double dx = px - diffX;
                double dy = py - diffY;
                double dz = pz - diffZ;
                double distSq = dx * dx + dy * dy + dz * dz;

                // Precision check: if we are within ~0.5 blocks, this is a hit
                if (distSq < 0.25) {
                    return new float[] { yaw, pitch };
                }

                // Optimization: stop if we've passed the target vertically
                if (py < diffY && vy < 0) {
                    // Check if this final landing spot is accurate enough
                    if (distSq < 1.0) {
                        return new float[] { yaw, pitch };
                    }
                    break;
                }
            }
        }

        return null; // No short trajectory found
    }

    @SuppressWarnings("null")
    private float[] calcAnglesTo(Entity target) {
        double diffX = target.getX() - mc.player.getX();
        double diffY = (target.getY() + target.getHeight() * 0.5) - mc.player.getEyeY();
        double diffZ = target.getZ() - mc.player.getZ();
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(Math.atan2(diffY, dist) * 180.0 / Math.PI));
        return new float[] { yaw, pitch };
    }

    @SuppressWarnings("null")
    private Entity getNearestTarget() {
        if (mc.player == null || mc.world == null)
            return null;

        // Expanded absolute maximum radius to allow targeting universally loaded entities
        double r = 500.0;
        Set<String> enabled = targetEntities.get();

        return mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(r),
                e -> e != mc.player && e.isAlive() && !e.isSpectator()
                        && mc.player.distanceTo(e) <= r
                        && enabled.contains(EntityType.getId(e.getType()).toString()))
                .stream()
                .min(Comparator.comparingDouble(e -> mc.player.distanceTo(e)))
                .orElse(null);
    }

    @SuppressWarnings("null")
    private int findHotbarItem(net.minecraft.item.Item item) {
        if (mc.player == null)
            return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item)
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
