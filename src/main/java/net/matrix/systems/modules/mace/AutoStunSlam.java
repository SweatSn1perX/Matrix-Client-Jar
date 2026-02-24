package net.matrix.systems.modules.mace;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.matrix.mixin.PlayerInventoryAccessor;

import java.util.*;

/**
 * Auto Stun Slam — While falling with a mace, detects if a nearby opponent
 * is blocking with a shield. When within striking range, swaps to an
 * axe to disable the shield, then swaps to the mace after a short delay to
 * land the slam hit before touching the ground.
 *
 * Ghost Logic:
 * - Swap delay uses Gaussian distribution (not static setting value).
 * - Attack reach check has Gaussian jitter (not exact static distance).
 * - Gaussian reaction delay before the axe swing (simulates human).
 * - All timing varies per-cycle to break pattern analysis.
 * - Uses LERP for aiming (no snaps), even in Panic Mode.
 */
public class AutoStunSlam extends Module {
    private final Random random = new Random();

    private final Setting.DoubleSetting rangeSetting = new Setting.DoubleSetting(
            "Range", "Max distance to detect blocking target", 4.0, 3.0, 6.0);
    private final Setting.IntSetting swapDelay = new Setting.IntSetting(
            "Swap Delay", "Ticks between axe stun and mace swap", 2, 0, 4);
    private final Setting.DoubleSetting smoothness = new Setting.DoubleSetting(
            "Smoothness", "LERP factor per tick — lower = smoother", 0.35, 0.1, 1.0);
    private final Setting.DoubleSetting aimTolerance = new Setting.DoubleSetting(
            "Aim Tolerance", "Degrees from target to trigger attack", 6.0, 2.0, 15.0);
    private final Setting.IntSetting maxCPS = new Setting.IntSetting(
            "Max CPS", "Maximum clicks per second (Locked at 15 for bypass)", 15, 1, 15);
    private final Setting.BooleanSetting silentSetting = new Setting.BooleanSetting(
            "Silent Swap", "Switch weapons silently (no hotbar animation)", true);
    private final Setting.BooleanSetting breachSwap = new Setting.BooleanSetting(
            "Breach Swap", "Transfer mace attributes to axe hits (OP)", true);
    private final Setting.BooleanSetting silentRotation = new Setting.BooleanSetting(
            "Silent Rotation", "Rotate server-side only (no screen movement)", true);
    private final Setting.BooleanSetting oneHit = new Setting.BooleanSetting(
            "One Hit", "Only hit once per fall (prevents spam/lag)", true);
    private final Setting.IntSetting reactionDelayMin = new Setting.IntSetting(
            "Reaction Min", "Min delay before the first hit", 0, 0, 10);
    private final Setting.IntSetting reactionDelayMax = new Setting.IntSetting(
            "Reaction Max", "Max delay before the first hit", 1, 0, 10);
    private final Setting.DoubleSetting minFallDistance = new Setting.DoubleSetting(
            "Min Fall Distance", "Minimum total jump distance to trigger (2 = ignores small hops)", 2.0, 0.5, 10.0);
    private final Setting.StringSetSetting targetEntities;

    // State machine
    private enum Phase {
        WATCHING, LERP_TO_TARGET, REACTION_DELAY, AXE_HIT, WAITING, MACE_SWAP, DONE
    }

    private Phase phase = Phase.WATCHING;
    private int ticksRemaining = 0;
    private Entity lockedTarget = null;
    private int ticksToGround = 100;
    private long lastAttackTime = 0;
    private float currentSilentYaw, currentSilentPitch;
    private boolean hasHit = false;

    public AutoStunSlam() {
        super(Category.MACE, "Auto Stun Slam",
                "Stuns shield with axe then swaps to mace while falling.");

        // Build entity list from registry
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

        settings.add(rangeSetting);
        settings.add(reactionDelayMin);
        settings.add(reactionDelayMax);
        settings.add(swapDelay);
        settings.add(smoothness);
        settings.add(aimTolerance);
        settings.add(maxCPS);
        settings.add(silentSetting);
        settings.add(silentRotation);
        settings.add(oneHit);
        settings.add(minFallDistance);
        settings.add(breachSwap);
        settings.add(targetEntities);
    }

    @SuppressWarnings("null")
    @Override
    public void onActivate() {
        phase = Phase.WATCHING;
        ticksRemaining = 0;
        lockedTarget = null;
        hasHit = false;
        if (mc.player != null) {
            currentSilentYaw = mc.player.getYaw();
            currentSilentPitch = mc.player.getPitch();
        }
    }

    @Override
    public void onDeactivate() {
        phase = Phase.WATCHING;
        ticksRemaining = 0;
        lockedTarget = null;
    }

    @SuppressWarnings("null")
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.getNetworkHandler() == null) {
            return;
        }

        // Update ground prediction
        ticksToGround = predictImpactTicks();

        if (mc.player.isOnGround() || mc.player.getVelocity().y > 0.4) {
            hasHit = false;
        }

        // Panic Mode: Impact imminent (< 5 ticks).
        // We must rush the combo.
        boolean panicMode = ticksToGround <= 5;

        // Pro logic: loop and allow multiple phase transitions if in panic mode
        int maxTransitions = panicMode ? 3 : 1;
        for (int i = 0; i < maxTransitions; i++) {
            Phase startPhase = phase;
            if (processPhase(panicMode))
                break;
            if (phase == startPhase)
                break;
        }
    }

    @SuppressWarnings("null")
    private boolean processPhase(boolean panicMode) {
        switch (phase) {
            case WATCHING -> {
                // --- Condition 1: Player must be holding a mace ---
                if (findHotbarItem(Items.MACE) == -1)
                    return true;

                // --- Condition One Hit ---
                if (oneHit.get() && hasHit)
                    return true;

                // --- Condition 2: Player must be falling ---
                if (mc.player.isOnGround())
                    return true;

                // --- JUMP FILTER ---
                // Use Minecraft's own fallDistance — tracks downward distance fallen.
                // Regular jump: ~1.25 blocks. Wind charge: 5+. Mace damage scales on this.
                double vy = mc.player.getVelocity().y;

                // Must be clearly falling, not at jump apex or ascending
                if (vy > -0.1)
                    return true;

                // Must exceed minimum fall distance (default 2 = ignores small hops)
                if (mc.player.fallDistance < minFallDistance.get())
                    return true;

                // --- Condition 4: Find a blocking opponent in range ---
                lockedTarget = findBlockingTarget();

                if (lockedTarget != null) {
                    phase = Phase.LERP_TO_TARGET;

                    // --- HIGH SPEED PRE-SWITCH ---
                    // Pull out axe INSTANTLY upon target detection while in air
                    int axeSlot = findAxeSlot();
                    if (axeSlot != -1) {
                        setSlot(axeSlot);
                    }
                    return false;
                }
                return true;
            }

            case LERP_TO_TARGET -> {
                if (lockedTarget == null || !lockedTarget.isAlive() || lockedTarget.isRemoved()) {
                    resetPhase();
                    return true;
                }

                // Fail-safe: If target stops blocking, skip axe and go to mace immediately
                if (!((LivingEntity) lockedTarget).isBlocking()) {
                    phase = Phase.MACE_SWAP;
                    return false;
                }

                aimAtTarget(lockedTarget, panicMode);

                @SuppressWarnings("null")
                double dist = mc.player.distanceTo(lockedTarget);
                // Mace Detection: Advanced 3.0 - 3.8 reach with Gaussian jitter
                double attackReach = 3.4 + random.nextGaussian() * 0.15;
                attackReach = Math.max(3.0, Math.min(3.8, attackReach));

                boolean aimed = isLookingAt(lockedTarget, aimTolerance.get());
                boolean inRange = dist <= attackReach;

                // If panic mode, we force the hit immediately to guarantee the slam
                if ((aimed || panicMode) && inRange) {
                    if (panicMode) {
                        phase = Phase.AXE_HIT;
                    } else {
                        int min = reactionDelayMin.get();
                        int max = reactionDelayMax.get();
                        ticksRemaining = min + (max > min ? random.nextInt(max - min + 1) : 0);
                        phase = Phase.REACTION_DELAY;
                    }
                    return false;
                }
                return true;
            }

            case REACTION_DELAY -> {
                if (panicMode)
                    ticksRemaining = 0;

                if (ticksRemaining > 0) {
                    ticksRemaining--;
                    if (lockedTarget != null)
                        aimAtTarget(lockedTarget, panicMode);
                    return true;
                }

                if (lockedTarget == null || !lockedTarget.isAlive() || lockedTarget.isRemoved()) {
                    resetPhase();
                    return true;
                }

                // Proceed to Axe Hit
                phase = Phase.AXE_HIT;
                return false;
            }

            case AXE_HIT -> {
                if (lockedTarget == null) {
                    resetPhase();
                    return true;
                }

                aimAtTarget(lockedTarget, panicMode);

                // Find axe in hotbar
                int axeSlot = findAxeSlot();
                if (axeSlot == -1) {
                    resetPhase();
                    return true;
                }

                // Visible switch to axe checks
                if (mc.player.getInventory().getSelectedSlot() != axeSlot) {
                    setSlot(axeSlot);
                }

                // Attack to disable shield
                if (silentRotation.get()) {
                    syncRotation(currentSilentYaw, currentSilentPitch);
                } else {
                    syncRotation(); // Perfect aim sync
                }

                int maceSlot = findHotbarItem(Items.MACE);
                if (breachSwap.get() && maceSlot != -1) {
                    // CPS Cap check
                    long now = System.currentTimeMillis();
                    long delayMs = 1000 / maxCPS.get();
                    if (now - lastAttackTime < delayMs) {
                        return true;
                    }

                    // 1. INSTANT packet swap to Mace (Attribute Transfer: Axe -> Mace)
                    mc.getNetworkHandler().sendPacket(
                            new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(maceSlot));

                    mc.interactionManager.attackEntity(mc.player, lockedTarget);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    lastAttackTime = now;

                    transitionAfterAxe(panicMode);
                    return false;
                } else {
                    // CPS Cap check
                    long now = System.currentTimeMillis();
                    long delayMs = 1000 / maxCPS.get();
                    if (now - lastAttackTime < delayMs) {
                        return true;
                    }

                    // Standard axe hit
                    mc.interactionManager.attackEntity(mc.player, lockedTarget);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    lastAttackTime = now;
                }

                // Track that we used the axe
                transitionAfterAxe(panicMode);
                return false;
            }

            case WAITING -> {
                if (panicMode)
                    ticksRemaining = 0; // Rush!

                if (ticksRemaining > 0) {
                    ticksRemaining--;
                    if (lockedTarget != null)
                        aimAtTarget(lockedTarget, panicMode);
                    return true;
                }
                phase = Phase.MACE_SWAP;
                return false;
            }

            case MACE_SWAP -> {
                if (lockedTarget == null || !lockedTarget.isAlive()) {
                    resetPhase();
                    return true;
                }

                aimAtTarget(lockedTarget, panicMode);

                // Swap to mace and attack
                int maceSlot = findHotbarItem(Items.MACE);
                if (maceSlot != -1) {
                    // CPS Cap check
                    long now = System.currentTimeMillis();
                    long delayMs = 1000 / maxCPS.get();
                    if (now - lastAttackTime < delayMs) {
                        return true;
                    }

                    setSlot(maceSlot);
                    if (silentRotation.get()) {
                        syncRotation(currentSilentYaw, currentSilentPitch);
                    } else {
                        syncRotation(); // Sync rotations before attack
                    }
                    mc.interactionManager.attackEntity(mc.player, lockedTarget);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    lastAttackTime = now;
                    hasHit = true;
                }

                phase = Phase.DONE;
                return false;
            }

            case DONE -> {
                resetPhase();
                return true;
            }
        }
        return true;
    }

    // ─── Physics Prediction ───────────────────────────────────

    @SuppressWarnings("null")
    private int predictImpactTicks() {
        if (mc.player == null || mc.world == null)
            return 100;
        if (mc.player.isOnGround())
            return 0;

        Vec3d pos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d vel = mc.player.getVelocity();
        double y = pos.y;
        double vy = vel.y;

        for (int i = 0; i < 60; i++) {
            y += vy;
            vy = (vy - 0.08) * 0.98;

            if (!mc.world.getBlockCollisions(mc.player, mc.player.getBoundingBox().offset(0, y - mc.player.getY(), 0))
                    .iterator().hasNext()) {
                // No collision yet
            } else {
                return i;
            }

            HitResult result = mc.world.raycast(new RaycastContext(
                    new Vec3d(pos.x, y + 1, pos.z),
                    new Vec3d(pos.x, y - 1, pos.z),
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    mc.player));

            if (result.getType() != HitResult.Type.MISS) {
                if (result.getPos().y >= y)
                    return i;
            }
        }
        return 60;
    }

    // ─── Targeting ────────────────────────────────────────────

    /**
     * Aim at the target.
     * 
     * @param panicMode If true, uses fast LERP to rush aim (no snap).
     */
    @SuppressWarnings("null")
    private void aimAtTarget(Entity target, boolean panicMode) {
        if (mc.player == null || target == null)
            return;

        // Pro logic: Predict future position based on target velocity + gravity
        Vec3d targetVel = target.getVelocity();
        // High-end prediction: 2.5 ticks (avg of 2-3) to intercept high-speed targets
        // (Wind/Elytra)
        double predictionFactor = 2.5;

        double predX = target.getX() + targetVel.x * predictionFactor;
        double predY = target.getY() + targetVel.y * predictionFactor;
        double predZ = target.getZ() + targetVel.z * predictionFactor;

        // If target is in air, account for gravity (~0.08)
        if (!target.isOnGround()) {
            predY -= 0.08 * predictionFactor;
        }

        Vec3d targetPos = new Vec3d(predX, predY + target.getHeight() * 0.5, predZ);

        double diffX = targetPos.x - mc.player.getX();
        double diffY = targetPos.y - mc.player.getEyeY();
        double diffZ = targetPos.z - mc.player.getZ();
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(Math.atan2(diffY, dist) * 180.0 / Math.PI));

        float currentYaw = mc.player.getYaw();
        float currentPitch = mc.player.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(yaw - currentYaw);
        float deltaPitch = MathHelper.wrapDegrees(pitch - currentPitch);

        // Pro settings: S-curve acceleration for ghost-level aim
        // If panicMode, we go fast but never instant (max 0.82)
        float speedFactor;
        if (panicMode) {
            speedFactor = 0.82f;
        } else {
            // Ghost mode: use acceleration curve (slow→fast→slow)
            float maxAngle = Math.max(Math.abs(deltaYaw), Math.abs(deltaPitch));
            speedFactor = net.matrix.utils.HumanPulse.accelerationCurve(maxAngle, smoothness.get().floatValue());
        }

        // Dynamic smoothness: closer targets require faster microadjustments
        double distanceToTarget = mc.player.distanceTo(target);
        if (distanceToTarget < 3.0) {
            speedFactor = Math.max(speedFactor, 0.65f);
        }

        // Human-like jitter: more jitter when further away or moving fast
        double jitterStrength = distanceToTarget > 5.0 ? 0.04 : 0.025;
        speedFactor += (float) (random.nextGaussian() * jitterStrength);
        speedFactor = Math.max(0.12f, Math.min(0.92f, speedFactor));

        if (silentRotation.get()) {
            currentSilentYaw = currentYaw + deltaYaw * speedFactor;
            currentSilentPitch = currentPitch + deltaPitch * speedFactor;
        } else {
            mc.player.setYaw(currentYaw + deltaYaw * speedFactor);
            mc.player.setPitch(currentPitch + deltaPitch * speedFactor);
            currentSilentYaw = mc.player.getYaw();
            currentSilentPitch = mc.player.getPitch();
        }
    }

    private void transitionAfterAxe(boolean panicMode) {
        phase = Phase.WAITING;

        // Gaussian swap delay
        if (panicMode) {
            ticksRemaining = 0; // Rush!
        } else {
            double mean = swapDelay.get();
            double stddev = 0.8;
            ticksRemaining = (int) Math.round(mean + random.nextGaussian() * stddev);
            ticksRemaining = Math.max(0, Math.min(swapDelay.get() + 2, ticksRemaining));
        }
    }

    // ─── FOV Check ────────────────────────────────────────────

    @SuppressWarnings("null")
    private boolean isLookingAt(Entity entity, double toleranceDegrees) {
        if (mc.player == null || entity == null)
            return false;

        Vec3d entityPos = new Vec3d(entity.getX(), entity.getY() + entity.getHeight() * 0.5, entity.getZ());
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
        Vec3d vec = entityPos.subtract(playerPos).normalize();

        Vec3d view;
        if (silentRotation.get()) {
            float f = currentSilentPitch * 0.017453292F;
            float g = -currentSilentYaw * 0.017453292F;
            float h = MathHelper.cos(g);
            float i = MathHelper.sin(g);
            float j = MathHelper.cos(f);
            float k = MathHelper.sin(f);
            view = new Vec3d((double) (i * j), (double) (-k), (double) (h * j));
        } else {
            view = mc.player.getRotationVec(1.0f).normalize();
        }

        double dot = view.dotProduct(vec);
        double angle = Math.toDegrees(Math.acos(Math.min(1.0, dot)));
        return angle <= toleranceDegrees;
    }

    @SuppressWarnings("null")
    private int findHotbarItem(net.minecraft.item.Item item) {
        if (mc.player == null)
            return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("null")
    private int findAxeSlot() {
        if (mc.player == null)
            return -1;
        for (int i = 0; i < 9; i++) {
            net.minecraft.item.Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.WOODEN_AXE || item == Items.STONE_AXE
                    || item == Items.IRON_AXE || item == Items.GOLDEN_AXE
                    || item == Items.DIAMOND_AXE || item == Items.NETHERITE_AXE) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("null")
    private void setSlot(int targetSlot) {
        if (targetSlot < 0 || targetSlot > 8)
            return;
        if (mc.player != null && mc.getNetworkHandler() != null) {
            // SYNC HOTBAR IMMEDIATELY (Pro logic: instant packet sync)
            if (mc.player.getInventory().getSelectedSlot() != targetSlot) {
                if (!silentSetting.get()) {
                    ((PlayerInventoryAccessor) mc.player.getInventory()).setSelectedSlot(targetSlot);
                }
                mc.getNetworkHandler()
                        .sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(targetSlot));
            }
        }
    }

    @SuppressWarnings("null")
    private LivingEntity findBlockingTarget() {
        if (mc.world == null || mc.player == null)
            return null;

        double horizontalRange = 10.0;
        double verticalRange = 20.0;
        Set<String> enabled = targetEntities.get();

        return mc.world.getEntitiesByClass(
                LivingEntity.class,
                mc.player.getBoundingBox().expand(horizontalRange, verticalRange, horizontalRange),
                e -> e != mc.player
                        && e.isAlive()
                        && !e.isRemoved()
                        && e.isBlocking()
                        && e.getActiveItem().isOf(Items.SHIELD)
                        // Facing check: Shield only blocks if they face within 90 degrees of us
                        && isTargetFacingUs(e)
                        // Distance check: Allow far vertical, keep horizontal tight
                        && Math.abs(e.getY() - mc.player.getY()) <= verticalRange
                        && Math.sqrt(e.squaredDistanceTo(mc.player)) <= Math.max(horizontalRange, verticalRange)
                        && mc.player.canSee(e)
                        && enabled.contains(EntityType.getId(e.getType()).toString()))
                .stream()
                .min((a, b) -> Double.compare(
                        mc.player.distanceTo(a), mc.player.distanceTo(b)))
                .orElse(null);
    }

    private boolean isTargetFacingUs(LivingEntity target) {
        if (mc.player == null)
            return false;
        Vec3d lookVec = target.getRotationVec(1.0f);
        @SuppressWarnings("null")
        Vec3d toPlayerVec = new Vec3d(mc.player.getX() - target.getX(), mc.player.getY() - target.getY(),
                mc.player.getZ() - target.getZ()).normalize();
        double dot = lookVec.dotProduct(toPlayerVec);
        // dot > 0 means they are facing generally towards us
        return dot > 0.3; // Roughly 70 degrees fov coverage
    }

    private void resetPhase() {
        phase = Phase.WATCHING;
        ticksRemaining = 0;
        lockedTarget = null;
    }
}
