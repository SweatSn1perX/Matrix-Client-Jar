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
 * Auto Hit — While falling with a mace, aim-assist locks onto the nearest
 * opponent and performs a sword→mace attribute-swap combo before landing.
 *
 * Ghost Logic (Adaptive):
 * - Normally smooth LERP and human-like delays.
 * - If ground impact is imminent, rushes the combo using FAST LERP (no snaps)
 * to ensure 100% hit rate.
 */
public class AutoHit extends Module {
    private final Random random = new Random();

    // ── Settings ──
    private final Setting.DoubleSetting rangeSetting = new Setting.DoubleSetting(
            "Range", "Max attack reach", 4.0, 2.5, 6.0);
    private final Setting.BooleanSetting maceOnly = new Setting.BooleanSetting(
            "Mace Only", "Only hit with mace (no sword swap)", false);
    private final Setting.IntSetting delayMin = new Setting.IntSetting(
            "Delay Min", "Min ticks between sword and mace hits", 1, 0, 4);
    private final Setting.IntSetting delayMax = new Setting.IntSetting(
            "Delay Max", "Max ticks between sword and mace hits", 3, 0, 4);
    private final Setting.DoubleSetting smoothness = new Setting.DoubleSetting(
            "Smoothness", "LERP factor per tick — lower = smoother", 0.35, 0.1, 1.0);
    private final Setting.DoubleSetting aimTolerance = new Setting.DoubleSetting(
            "Aim Tolerance", "Degrees from target to trigger attack", 6.0, 2.0, 15.0);
    private final Setting.IntSetting maxCPS = new Setting.IntSetting(
            "Max CPS", "Maximum clicks per second (Locked at 15 for bypass)", 15, 1, 15);
    private final Setting.BooleanSetting silentSetting = new Setting.BooleanSetting(
            "Silent Swap", "Switch weapons silently (no hotbar animation)", true);
    private final Setting.BooleanSetting breachSwap = new Setting.BooleanSetting(
            "Breach Swap", "Transfer mace attributes to sword hits (OP)", true);
    private final Setting.BooleanSetting silentRotation = new Setting.BooleanSetting(
            "Silent Rotation", "Rotate server-side only (no screen movement)", true);
    private final Setting.BooleanSetting multiHit = new Setting.BooleanSetting(
            "Multi-Hit", "Perform multiple combos in one fall (requires Wind Burst/Bounce)", false);
    private final Setting.BooleanSetting oneHit = new Setting.BooleanSetting(
            "One Hit", "Only hit once per fall (prevents spam/lag)", true);
    private final Setting.DoubleSetting minFallDistance = new Setting.DoubleSetting(
            "Min Fall Distance", "Minimum total jump distance to trigger (2 = ignores small hops)", 2.0, 0.5, 10.0);
    private final Setting.IntSetting reactionDelayMin = new Setting.IntSetting(
            "Reaction Min", "Min delay before the first hit", 0, 0, 10);
    private final Setting.IntSetting reactionDelayMax = new Setting.IntSetting(
            "Reaction Max", "Max delay before the first hit", 1, 0, 10);
    private final Setting.StringSetSetting targetEntities;

    // ── State machine ──
    private enum Phase {
        WATCHING, LERP_TO_TARGET, REACTION_DELAY, SWORD_HIT, DELAY, MACE_HIT, DONE
    }

    private Phase phase = Phase.WATCHING;

    /**
     * Returns true if AutoHit is actively executing a combo (not idle/watching).
     * Used by other modules (e.g. WindChargeClutch) to avoid interfering mid-combo.
     */
    public boolean isExecuting() {
        return isActive() && phase != Phase.WATCHING;
    }

    private int ticksRemaining = 0;
    private Entity lockedTarget = null;
    private int ticksToGround = 100; // Track predicted impact time
    private long lastAttackTime = 0; // ms timer for CPS cap
    private int comboCount = 0; // Track successful combos per sequence
    private float currentSilentYaw, currentSilentPitch;

    public AutoHit() {
        super(Category.MACE, "Auto Hit",
                "Aim-assist combo: sword → mace attribute swap while falling.");

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

        maceOnly.onChanged(val -> {
            if (val && breachSwap.get())
                breachSwap.set(false);
            else if (!val && !breachSwap.get())
                breachSwap.set(true);
        });
        breachSwap.onChanged(val -> {
            if (val && maceOnly.get())
                maceOnly.set(false);
            else if (!val && !maceOnly.get())
                maceOnly.set(true);
        });
        multiHit.onChanged(val -> {
            if (val && oneHit.get())
                oneHit.set(false);
            else if (!val && !oneHit.get())
                oneHit.set(true);
        });
        oneHit.onChanged(val -> {
            if (val && multiHit.get())
                multiHit.set(false);
            else if (!val && !multiHit.get())
                multiHit.set(true);
        });

        settings.add(rangeSetting);
        settings.add(maceOnly);
        settings.add(breachSwap);
        settings.add(reactionDelayMin);
        settings.add(reactionDelayMax);
        settings.add(delayMin);
        settings.add(delayMax);
        settings.add(smoothness);
        settings.add(aimTolerance);
        settings.add(maxCPS);
        settings.add(silentSetting);
        settings.add(silentRotation);
        settings.add(multiHit);
        settings.add(oneHit);
        settings.add(minFallDistance);
        settings.add(targetEntities);
    }

    @SuppressWarnings("null")
    @Override
    public void onActivate() {
        phase = Phase.WATCHING;
        ticksRemaining = 0;
        lockedTarget = null;
        comboCount = 0;
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
        if (mc.player == null || mc.world == null
                || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            return;
        }

        if (mc.player.isOnGround() || mc.player.getVelocity().y > 0.4) {
            comboCount = 0;
        }

        // Always update ground prediction
        ticksToGround = predictImpactTicks();

        // Safety override: if we are close to ground and not done,
        // rush the combo. Increased threshold to 8 ticks for reliability.
        boolean panicMode = ticksToGround <= 8;

        // Pro logic: loop and allow multiple phase transitions
        // In Multi-Hit or Panic mode, we increase transitions to keep the combo
        // frame-perfect
        int maxTransitions = (panicMode || multiHit.get()) ? 4 : 2;
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

            // ═══════════════════════════════════════
            // WATCHING — wait for all conditions
            // ═══════════════════════════════════════
            case WATCHING -> {
                // Must have mace AND (sword or maceOnly) in hotbar
                int maceSlot = findHotbarItem(Items.MACE);
                int swordSlot = findSwordSlot();

                if (maceSlot == -1)
                    return true;
                if (!maceOnly.get() && swordSlot == -1)
                    return true;

                // Must be in the air
                if (mc.player.isOnGround())
                    return true;

                // --- JUMP FILTER ---
                // Use Minecraft's own fallDistance — tracks downward distance fallen.
                // Regular jump: ~1.25 blocks. Wind charge: 5+. Mace damage scales on this.
                double vy = mc.player.getVelocity().y;

                if (comboCount == 0) {
                    // First combo: strict checks
                    if (mc.player.fallDistance < minFallDistance.get())
                        return true;
                    // Must be clearly falling, not at jump apex
                    if (vy > -0.1)
                        return true;
                } else if (multiHit.get()) {
                    // Mid-sequence (after first hit): allow lower falls for Wind Burst combos
                    // But still require some fall distance for damage
                    if (mc.player.fallDistance < 0.5 && !isHeightSufficient(0.65))
                        return true;
                } else {
                    return true;
                }

                // Find nearest target in range (Horizontal: 10.0, Vertical: 20.0)
                double horizontalRange = 10.0;
                double verticalRange = 20.0;
                LivingEntity target = findNearestTarget(horizontalRange, verticalRange);

                if (target != null) {
                    lockedTarget = target;
                    phase = Phase.LERP_TO_TARGET;

                    // --- HIGH SPEED PRE-SWITCH ---
                    // Pull out sword INSTANTLY upon target detection while in air
                    if (!maceOnly.get() && swordSlot != -1) {
                        setSlot(swordSlot);
                    }
                    return false;
                }
                return true;
            }

            // ═══════════════════════════════════════
            // LERP_TO_TARGET — smooth aim toward target
            // ═══════════════════════════════════════
            case LERP_TO_TARGET -> {
                if (lockedTarget == null || !lockedTarget.isAlive() || lockedTarget.isRemoved()) {
                    // Dynamic target switching: if target dies, find next one immediately
                    lockedTarget = findNearestTarget(10.0, 20.0);
                    if (lockedTarget == null) {
                        resetPhase();
                        return true;
                    }
                }

                if (mc.player.isOnGround()) {
                    resetPhase();
                    return true;
                }

                aimAtTarget(lockedTarget, panicMode);

                // Check if we're focused enough or properly panicked
                @SuppressWarnings("null")
                double dist = mc.player.distanceTo(lockedTarget);
                double attackReach = getJitteredReach();

                boolean aimed = isLookingAt(lockedTarget, aimTolerance.get());
                boolean inRange = dist <= attackReach;

                // If panic mode, we FORCE the hit if in range, assuming aimAtTarget fast-LERPed
                // us
                if ((aimed || panicMode) && inRange) {
                    if (panicMode) {
                        if (maceOnly.get() || (oneHit.get() && !breachSwap.get())) {
                            phase = Phase.MACE_HIT;
                        } else {
                            phase = Phase.SWORD_HIT;
                        }
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

            // ═══════════════════════════════════════
            // REACTION_DELAY — human-like pause
            // ═══════════════════════════════════════
            case REACTION_DELAY -> {
                // If panic, skip delay
                if (panicMode)
                    ticksRemaining = 0;

                if (ticksRemaining > 0) {
                    ticksRemaining--;
                    if (lockedTarget != null && lockedTarget.isAlive())
                        aimAtTarget(lockedTarget, panicMode);
                    return true;
                }

                if (lockedTarget == null || !lockedTarget.isAlive() || lockedTarget.isRemoved()) {
                    // Dynamic target switching
                    lockedTarget = findNearestTarget(10.0, 20.0);
                    if (lockedTarget == null) {
                        resetPhase();
                        return true;
                    }
                }

                // If mace only, OR (oneHit AND not breachSwap) skip to MACE_HIT
                if (maceOnly.get() || (oneHit.get() && !breachSwap.get())) {
                    phase = Phase.MACE_HIT;
                } else {
                    phase = Phase.SWORD_HIT;
                }
                return false;
            }

            // ═══════════════════════════════════════
            // SWORD_HIT — swap to sword, attack
            // ═══════════════════════════════════════
            case SWORD_HIT -> {
                if (lockedTarget == null || !lockedTarget.isAlive() || lockedTarget.isRemoved()) {
                    lockedTarget = findNearestTarget(rangeSetting.get() + 1.0, 10.0);
                    if (lockedTarget == null) {
                        resetPhase();
                        return true;
                    }
                }

                aimAtTarget(lockedTarget, panicMode);

                int swordSlot = findSwordSlot();
                int maceSlot = findHotbarItem(Items.MACE);

                if (swordSlot != -1) {
                    // CPS Cap check - STRICT 15 CPS
                    long now = System.currentTimeMillis();
                    long delayMs = 1000 / maxCPS.get();
                    if (now - lastAttackTime < delayMs) {
                        return true; // Wait for next tick/interval
                    }

                    setSlot(swordSlot);
                    if (silentRotation.get()) {
                        syncRotation(currentSilentYaw, currentSilentPitch);
                    } else {
                        syncRotation();
                    }

                    // --- ENHANCED BREACH SWAP ---
                    if (breachSwap.get() && maceSlot != -1) {
                        // Instant packet swap for attribute inheritance (Sword -> Mace)
                        mc.getNetworkHandler().sendPacket(
                                new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(maceSlot));

                        mc.interactionManager.attackEntity(mc.player, lockedTarget);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        lastAttackTime = now;

                        transitionAfterSword(panicMode);
                        return false;
                    } else {
                        mc.interactionManager.attackEntity(mc.player, lockedTarget);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        lastAttackTime = now;
                    }
                }

                // Normal sequence continues if no Breach Swap occurs
                transitionAfterSword(panicMode);
                return false;
            }

            case DELAY -> {
                if (panicMode) {
                    phase = Phase.MACE_HIT;
                    return false;
                }

                if (ticksRemaining > 0) {
                    ticksRemaining--;
                    if (lockedTarget != null && lockedTarget.isAlive())
                        aimAtTarget(lockedTarget, false);
                    return true;
                }
                phase = Phase.MACE_HIT;
                return false;
            }

            // ═══════════════════════════════════════
            // MACE_HIT — swap to mace, attack
            // ═══════════════════════════════════════
            case MACE_HIT -> {
                if (lockedTarget == null || !lockedTarget.isAlive() || lockedTarget.isRemoved()) {
                    // Final attempt to hit SOMETHING in range
                    lockedTarget = findNearestTarget(rangeSetting.get() + 1.0, 5.0);
                    if (lockedTarget == null) {
                        resetPhase();
                        return true;
                    }
                }

                // Validated Aim (Fast LERP if panic)
                aimAtTarget(lockedTarget, panicMode);

                int maceSlot = findHotbarItem(Items.MACE);
                if (maceSlot != -1) {
                    // Only send slot packet if we're not already there (prevents breach swap
                    // redundancy)
                    // If breachSwap was used, we already sent the packet in SWORD_HIT
                    if (!breachSwap.get() || maceOnly.get()) {
                        setSlot(maceSlot);
                    }

                    if (silentRotation.get()) {
                        syncRotation(currentSilentYaw, currentSilentPitch);
                    } else {
                        syncRotation(); // Sync rotations before attack
                    }
                    mc.interactionManager.attackEntity(mc.player, lockedTarget);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    comboCount++;
                }

                // --- MULTI-HIT LOOP ---
                // Limit to 2 combos per fall to prevent anti-cheat flags
                if (!oneHit.get() && multiHit.get() && comboCount < 2 && mc.player != null && !mc.player.isOnGround()
                        && lockedTarget != null && lockedTarget.isAlive()) {
                    phase = Phase.LERP_TO_TARGET;
                } else {
                    phase = Phase.DONE;
                }
                return false;
            }

            // ═══════════════════════════════════════
            // DONE — Reset
            // ═══════════════════════════════════════
            case DONE -> {
                resetPhase();
                return true;
            }
        }
        return true;
    }

    @SuppressWarnings("null")
    private boolean isHeightSufficient(double minHeight) {
        if (mc.player == null || mc.world == null)
            return false;
        Vec3d pos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        HitResult result = mc.world.raycast(new RaycastContext(
                pos,
                pos.add(0, -minHeight, 0),
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player));
        return result.getType() == HitResult.Type.MISS;
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

        // Simple iteration simulation
        // Gravity ~0.08 per tick, drag ~0.98.
        // This is an approximation of vanilla physics.
        for (int i = 0; i < 60; i++) {
            y += vy;
            // Apply gravity and drag (simplified)
            vy = (vy - 0.08) * 0.98;

            // Check for collision at new Y
            // We use a raycast downwards from current pos to predicted pos
            // Or simpler: check block collision at feet
            if (!mc.world.getBlockCollisions(mc.player, mc.player.getBoundingBox().offset(0, y - mc.player.getY(), 0))
                    .iterator().hasNext()) {
                // No collision yet
            } else {
                return i;
            }

            // Hardcast floor check if block collision fails (e.g. standard blocks)
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
        return 60; // > 3 seconds
    }

    // ─── Targeting ────────────────────────────────────────────

    /**
     * Aim at the target.
     * 
     * @param panicMode If true, uses fast LERP to rush aim (no snap).
     */
    @SuppressWarnings("null")
    private void aimAtTarget(Entity target, boolean panicMode) {
        if (mc.player == null)
            return;

        // Pro logic: Predict future position based on target velocity + gravity
        Vec3d targetVel = target.getVelocity();

        // Calculate relative speed
        double speed = targetVel.length();

        // Dynamic prediction: faster targets need more prediction ahead
        // But cap it so we don't predict too far into the future (max 4 ticks)
        double predictionFactor = Math.min(4.0, 1.0 + (speed * 3.0));

        // If panicMode, we need to aim *exactly* where they are going to be to
        // guarantee the hit
        if (panicMode) {
            predictionFactor += 0.5;
        }

        double predX = target.getX() + targetVel.x * predictionFactor;
        double predY = target.getY() + targetVel.y * predictionFactor;
        double predZ = target.getZ() + targetVel.z * predictionFactor;

        // Account for gravity (~0.08) if target is in air (falling)
        if (!target.isOnGround() && targetVel.y < 0) {
            predY -= 0.08 * predictionFactor;
        }

        Vec3d targetPos = new Vec3d(predX, predY + target.getHeight() * 0.5, predZ);

        // Account for our own velocity so we aim where we *will* be relative to them
        Vec3d myVel = mc.player.getVelocity();
        double myPredX = mc.player.getX() + myVel.x * predictionFactor;
        double myPredY = mc.player.getEyeY() + myVel.y * predictionFactor;
        double myPredZ = mc.player.getZ() + myVel.z * predictionFactor;

        double diffX = targetPos.x - myPredX;
        double diffY = targetPos.y - myPredY;
        double diffZ = targetPos.z - myPredZ;
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

        // Human-like jitter: more jitter when further away (less control)
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

    // ─── Reach ────────────────────────────────────────────────

    private double getJitteredReach() {
        // Use user-defined range with a tiny variation for bypass
        double base = rangeSetting.get();
        double jittered = base + (random.nextGaussian() * 0.05);
        return Math.max(2.5, Math.min(6.0, jittered));
    }

    // ─── FOV Check ────────────────────────────────────────────

    @SuppressWarnings("null")
    private boolean isLookingAt(Entity entity, double toleranceDegrees) {
        if (mc.player == null)
            return false;

        // Predict their position for the FOV check as well
        Vec3d targetVel = entity.getVelocity();
        double predictionFactor = 2.5;

        double predX = entity.getX() + targetVel.x * predictionFactor;
        double predY = entity.getY() + targetVel.y * predictionFactor;
        double predZ = entity.getZ() + targetVel.z * predictionFactor;

        if (!entity.isOnGround()) {
            predY -= 0.08 * predictionFactor;
        }

        Vec3d entityPos = new Vec3d(predX, predY + entity.getHeight() * 0.5, predZ);
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getEyeY(), mc.player.getZ());
        Vec3d vec = entityPos.subtract(playerPos).normalize();

        Vec3d view;
        if (silentRotation.get()) {
            // Use currentSilentYaw and currentSilentPitch for silent rotations
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

    private void transitionAfterSword(boolean panicMode) {
        if (panicMode) {
            ticksRemaining = 0;
            phase = Phase.MACE_HIT;
        } else {
            int min = delayMin.get();
            int max = delayMax.get();
            ticksRemaining = min + (max > min ? random.nextInt(max - min + 1) : 0);

            // Multi-Hit optimization: reduce delay if the target is still in combo range
            if (multiHit.get() && ticksRemaining > 1)
                ticksRemaining--;

            phase = Phase.DELAY;
        }
    }

    // ─── Target Selection ─────────────────────────────────────

    @SuppressWarnings("null")
    private LivingEntity findNearestTarget(double horizontalRange, double verticalRange) {
        if (mc.player == null || mc.world == null)
            return null;

        Set<String> enabled = targetEntities.get();

        return mc.world.getEntitiesByClass(
                LivingEntity.class,
                mc.player.getBoundingBox().expand(horizontalRange, verticalRange, horizontalRange),
                e -> e != mc.player
                        && e.isAlive()
                        && !e.isRemoved()
                        && !e.isSpectator()
                        && Math.abs(e.getY() - mc.player.getY()) <= verticalRange
                        && mc.player.canSee(e)
                        && enabled.contains(EntityType.getId(e.getType()).toString()))
                .stream()
                .min(Comparator.comparingDouble(e -> mc.player.distanceTo(e)))
                .orElse(null);
    }

    // ─── Hotbar Utilities ─────────────────────────────────────

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
    private int findSwordSlot() {
        if (mc.player == null)
            return -1;
        for (int i = 0; i < 9; i++) {
            net.minecraft.item.Item item = mc.player.getInventory().getStack(i).getItem();
            if (item == Items.WOODEN_SWORD || item == Items.STONE_SWORD
                    || item == Items.IRON_SWORD || item == Items.GOLDEN_SWORD
                    || item == Items.DIAMOND_SWORD || item == Items.NETHERITE_SWORD) {
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

    private void resetPhase() {
        phase = Phase.WATCHING;
        ticksRemaining = 0;
        lockedTarget = null;
    }

    @Override
    public List<Setting<?>> getSettings() {
        List<Setting<?>> visibleSettings = new ArrayList<>();
        for (Setting<?> setting : super.getSettings()) {
            // Rule 1: Breach Swap & Mace Only cannot be enabled simultaneously.
            if (setting == breachSwap && maceOnly.get())
                continue;
            if (setting == maceOnly && breachSwap.get())
                continue;

            // Rule 2: One Hit & Multi Hit cannot be enabled simultaneously.
            if (setting == oneHit && multiHit.get())
                continue;
            if (setting == multiHit && oneHit.get())
                continue;

            visibleSettings.add(setting);
        }
        return visibleSettings;
    }
}
