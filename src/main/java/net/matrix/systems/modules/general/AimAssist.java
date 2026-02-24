package net.matrix.systems.modules.general;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;

import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.*;
import java.util.Comparator;
import java.util.Random;

public class AimAssist extends Module {
    private final Random random = new Random();

    // ── Configurable settings (Ghost Logic: Range < 4.5, Smoothing 0.05-0.2) ──
    private final Setting.DoubleSetting speed = new Setting.DoubleSetting(
            "Speed", "Aim smoothing factor", 0.08, 0.01, 1.0);
    private final Setting.DoubleSetting range = new Setting.DoubleSetting(
            "Range", "Targeting range", 3.8, 1.0, 6.0);
    private final Setting.DoubleSetting fov = new Setting.DoubleSetting(
            "FOV", "Horizontal FOV check", 80.0, 10.0, 360.0);
    private final Setting.DoubleSetting randomization = new Setting.DoubleSetting(
            "Randomization", "Gaussian hitbox jitter", 0.3, 0.0, 0.8);
    private final Setting.BooleanSetting bezierSmoothing = new Setting.BooleanSetting(
            "Bezier Smoothing", "Use S-curve acceleration (Ghost mode)", true);
    private final Setting.BooleanSetting clickAim = new Setting.BooleanSetting(
            "Click Aim", "Only aim when clicking mouse", false);
    public final Setting.BooleanSetting hitboxExpand = new Setting.BooleanSetting(
            "Click Hitbox", "Hits entities within 5 blocks when clicking", false);
    private final Setting.StringSetSetting weaponFilter;

    private final Setting.StringSetSetting targetEntities;

    // Internal state for smooth transitions
    private Entity currentTarget;
    private int reactionTimer = 0;

    public AimAssist() {
        super(Category.GENERAL, "AimAssist", "Smoothly assists your aim using linear interpolation.");

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

        // Weapon filter
        weaponFilter = new Setting.StringSetSetting(
                "Allowed Weapons", "Only active when holding these items",
                net.matrix.utils.WeaponUtil.getAllWeaponIds(),
                net.matrix.utils.WeaponUtil.getDefaultWeapons());

        settings.add(speed);
        settings.add(range);
        settings.add(fov);
        settings.add(randomization);
        settings.add(bezierSmoothing);
        settings.add(clickAim);
        settings.add(hitboxExpand);
        settings.add(weaponFilter); // Replaces Boolean weaponOnly
        settings.add(targetEntities);
    }

    // Drift variables
    private Vec3d aimOffset = Vec3d.ZERO;
    private Vec3d targetOffsetDest = Vec3d.ZERO;
    private int offsetTimer = 0;

    // Force aim variables
    private boolean isForcingAim = false;
    private Entity forcedTarget = null;
    private int forceAimTimer = 0;

    public void forceAim(Entity target) {
        if (target == null)
            return;
        this.forcedTarget = target;
        this.isForcingAim = true;
        this.forceAimTimer = 10; // Try to lock on for 10 ticks
    }

    @SuppressWarnings("null")
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null)
            return;

        // Weapon check
        if (!weaponFilter.get().isEmpty() && !isHoldingWeapon()) {
            currentTarget = null;
            forcedTarget = null;
            isForcingAim = false;
            return;
        }

        // Handle forced aim (from Click Aim)
        if (isForcingAim) {
            if (forcedTarget == null || !forcedTarget.isAlive() || forceAimTimer <= 0) {
                isForcingAim = false;
                forcedTarget = null;
            } else {
                forceAimTimer--;
                aim(forcedTarget);
                // If looking close enough, click!
                if (isLookingAt(forcedTarget, 10.0)) { // 10 degree tolerance for "flick click"
                    if (mc.player.getAttackCooldownProgress(0.5f) >= 1.0f) {
                        syncRotation(); // Sync rotations before attack
                        mc.interactionManager.attackEntity(mc.player, forcedTarget);
                        mc.player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
                        isForcingAim = false; // Done
                    }
                }
                return; // Prioritize forced aim
            }
        }

        // Click check for normal assist
        if (clickAim.get() && !mc.options.attackKey.isPressed()) {
            currentTarget = null;
            return;
        }

        Entity target = getNearestTarget();
        if (target != null) {
            // Reaction time simulation
            if (target != currentTarget) {
                if (reactionTimer > 0) {
                    reactionTimer--;
                    return;
                }
                reactionTimer = 2 + random.nextInt(3);
                currentTarget = target;
            }

            aim(target);
        } else {
            currentTarget = null;
            reactionTimer = 0;
            // Slowly center offset when no target to reset drift
            aimOffset = aimOffset.multiply(0.9);
        }
    }

    @SuppressWarnings("null")
    private void aim(Entity target) {
        if (mc.player == null)
            return;

        // Update drift offset
        if (offsetTimer <= 0) {
            offsetTimer = 10 + random.nextInt(20);
            // Pick a new spot on the hitbox
            double r = randomization.get();
            targetOffsetDest = new Vec3d(
                    (random.nextDouble() - 0.5) * r,
                    (random.nextDouble() - 0.5) * r + target.getHeight() * 0.5, // Center mass bias
                    (random.nextDouble() - 0.5) * r);
        }
        offsetTimer--;

        // Smoothly move current offset to destination
        aimOffset = lerpVec(aimOffset, targetOffsetDest, 0.1);

        Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ()).add(aimOffset);

        @SuppressWarnings("null")
        double diffX = targetPos.x - mc.player.getX();
        @SuppressWarnings("null")
        double diffY = targetPos.y - mc.player.getEyeY();
        @SuppressWarnings("null")
        double diffZ = targetPos.z - mc.player.getZ();
        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(Math.atan2(diffY, dist) * 180.0 / Math.PI));

        @SuppressWarnings("null")
        float currentYaw = mc.player.getYaw();
        @SuppressWarnings("null")
        float currentPitch = mc.player.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(yaw - currentYaw);
        float deltaPitch = MathHelper.wrapDegrees(pitch - currentPitch);

        float speedFactor;
        if (bezierSmoothing.get()) {
            // Ghost mode: S-curve acceleration (slow→fast→slow)
            float maxAngle = Math.max(Math.abs(deltaYaw), Math.abs(deltaPitch));
            speedFactor = net.matrix.utils.HumanPulse.accelerationCurve(maxAngle, (float) speed.get().floatValue());
        } else {
            // Legacy linear mode
            speedFactor = (float) (speed.get() * (0.9 + random.nextGaussian() * 0.15));
        }
        // Boost speed slightly if forcing aim, but keep it human-like
        if (isForcingAim)
            speedFactor *= 1.5f;

        speedFactor = Math.max(0.01f, Math.min(1.0f, speedFactor));

        // Apply smoothing
        float yawChange = deltaYaw * speedFactor;
        float pitchChange = deltaPitch * speedFactor;

        mc.player.setYaw(currentYaw + yawChange);
        mc.player.setPitch(currentPitch + pitchChange);
    }

    private Vec3d lerpVec(Vec3d start, Vec3d end, double t) {
        return start.add(end.subtract(start).multiply(t));
    }

    private boolean isLookingAt(Entity target, double fov) {
        return isInFOV(target, fov);
    }

    @SuppressWarnings("null")
    private Entity getNearestTarget() {
        if (mc.player == null || mc.world == null)
            return null;

        double r = range.get();
        double f = fov.get();
        Set<String> enabled = targetEntities.get();

        return mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(r),
                e -> e != mc.player && e.isAlive() && !e.isSpectator()
                        && mc.player.distanceTo(e) <= r
                        && isInFOV(e, f)
                        && enabled.contains(EntityType.getId(e.getType()).toString()))
                .stream()
                .min(Comparator.comparingDouble(e -> mc.player.distanceTo(e)))
                .orElse(null);
    }

    private boolean isInFOV(Entity entity, double fov) {
        if (mc.player == null)
            return false;

        Vec3d entityPos = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
        @SuppressWarnings("null")
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d vec = entityPos.subtract(playerPos).normalize();
        @SuppressWarnings("null")
        Vec3d view = mc.player.getRotationVec(1.0f).normalize();
        double dot = view.dotProduct(vec);
        double angle = Math.toDegrees(Math.acos(dot));
        return angle <= fov / 2.0;
    }

    private boolean isHoldingWeapon() {
        if (mc.player == null)
            return false;
        String category = net.matrix.utils.WeaponUtil.getWeaponCategory(mc.player.getMainHandStack().getItem());
        if (category == null)
            return false;
        return weaponFilter.get().contains(category);
    }
}
