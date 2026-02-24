package net.matrix.systems.modules.general;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class KillAura extends Module {
    private final Random random = new Random();
    private int attackDelay = 0;

    // ── Configurable settings ──
    private final Setting.DoubleSetting rangeSetting = new Setting.DoubleSetting(
            "Range", "Maximum attack distance", 3.2, 3.0, 6.0);
    private final Setting.DoubleSetting delaySetting = new Setting.DoubleSetting(
            "Attack Delay", "Ticks between attacks (lower = faster)", 10.0, 2.0, 20.0);
    private final Setting.DoubleSetting rotationSmoothness = new Setting.DoubleSetting(
            "Smoothness", "Camera LERP factor (lower = smoother)", 0.3, 0.05, 1.0);
    private final Setting.BooleanSetting onlyCrits = new Setting.BooleanSetting(
            "Only Crits", "Only attack when falling (critical hit)", false);
    private final Setting.DoubleSetting fovSetting = new Setting.DoubleSetting(
            "FOV", "Maximum angle to target", 180.0, 10.0, 360.0);
    private final Setting.BooleanSetting raytrace = new Setting.BooleanSetting(
            "Raytrace", "Only target visible entities", true);
    private final Setting.DoubleSetting jitterSetting = new Setting.DoubleSetting(
            "Jitter", "Random rotation noise", 0.5, 0.0, 5.0);
    private final Setting.BooleanSetting humanClick = new Setting.BooleanSetting(
            "Human Click", "Use burst/fatigue CPS patterns (Ghost)", true);
    private final Setting.BooleanSetting bezierSmoothing = new Setting.BooleanSetting(
            "Bezier Smoothing", "Use S-curve rotation (Ghost)", true);
    private final Setting.StringSetSetting targetEntities;
    private final Setting.StringSetSetting weaponFilter;

    public KillAura() {
        super(Category.GENERAL, "KillAura", "Automatically attacks entities around you.");
        setKey(82); // Default key: R (replacing AimAssist default or similar)

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

        settings.add(rangeSetting);
        settings.add(delaySetting);
        settings.add(rotationSmoothness);
        settings.add(fovSetting);
        settings.add(jitterSetting);
        settings.add(humanClick);
        settings.add(bezierSmoothing);
        settings.add(raytrace);
        settings.add(onlyCrits);
        settings.add(targetEntities);
        settings.add(weaponFilter);
    }

    @Override
    public void onActivate() {
        attackDelay = 0;
    }

    @SuppressWarnings("null")
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null)
            return;

        // Weapon filter check
        // Weapon filter check
        if (!weaponFilter.get().isEmpty()) {
            String category = net.matrix.utils.WeaponUtil.getWeaponCategory(mc.player.getMainHandStack().getItem());
            if (category == null || !weaponFilter.get().contains(category))
                return;
        }

        // Attack delay
        if (attackDelay > 0) {
            attackDelay--;
            return;
        }

        // Cooldown check (1.9+)
        if (mc.player.getAttackCooldownProgress(0.5f) < 1.0f)
            return;

        // Critical check
        if (onlyCrits.get() && !isFileCritConditionMet())
            return;

        Entity target = getTarget();
        if (target != null) {
            // Rotate towards target
            rotate(target);

            // Attack
            syncRotation(); // Sync rotations before attack
            mc.interactionManager.attackEntity(mc.player, target);
            mc.player.swingHand(Hand.MAIN_HAND);

            // Reset delay with randomization
            if (humanClick.get()) {
                // Ghost mode: burst/fatigue CPS drop patterns
                attackDelay = net.matrix.utils.HumanPulse.nextClickDelay(delaySetting.get());
            } else {
                // Legacy mode: simple Gaussian noise
                double delayBase = delaySetting.get();
                double variance = 2.0;
                attackDelay = (int) (delayBase + random.nextGaussian() * variance);
            }
        }
    }

    @SuppressWarnings("null")
    private boolean isFileCritConditionMet() {
        if (mc.player == null)
            return false;
        // Simple crit check: falling, not on ground, not in water/lava/web
        return !mc.player.isOnGround() && mc.player.fallDistance > 0.0f
                && !mc.player.isTouchingWater() && !mc.player.isInLava()
                && !mc.player.isClimbing() && !mc.player.hasVehicle();
    }

    @SuppressWarnings("null")
    private Entity getTarget() {
        if (mc.player == null || mc.world == null)
            return null;
        net.minecraft.client.network.ClientPlayerEntity player = mc.player;

        double range = rangeSetting.get();
        Set<String> enabled = targetEntities.get();
        double fovLimit = fovSetting.get();

        return mc.world.getEntitiesByClass(LivingEntity.class, player.getBoundingBox().expand(range),
                entity -> entity != player && entity.isAlive() && !entity.isRemoved()
                        && enabled.contains(EntityType.getId(entity.getType()).toString()))
                .stream()
                .filter(e -> player.distanceTo(e) <= range)
                .filter(e -> {
                    // FOV Check
                    if (fovLimit >= 360)
                        return true;
                    double angle = getAngleToEntity(e);
                    return angle <= fovLimit / 2.0;
                })
                .filter(e -> {
                    // Raytrace Check
                    if (!raytrace.get())
                        return true;
                    return player.canSee(e);
                })
                .min(Comparator.comparingDouble(e -> player.distanceTo(e)))
                .orElse(null);
    }

    private double getAngleToEntity(Entity entity) {
        if (mc.player == null)
            return 0;
        @SuppressWarnings("null")
        Vec3d diff = entity.getBoundingBox().getCenter().subtract(mc.player.getEyePos());
        double diffX = diff.x;
        double diffZ = diff.z;
        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0f;
        @SuppressWarnings("null")
        float deltaYaw = MathHelper.wrapDegrees(yaw - mc.player.getYaw());
        return Math.abs(deltaYaw);
    }

    // Drift variables
    private Vec3d aimOffset = Vec3d.ZERO;
    private Vec3d targetOffsetDest = Vec3d.ZERO;
    private int offsetTimer = 0;

    private void rotate(Entity target) {
        if (mc.player == null)
            return;
        net.minecraft.client.network.ClientPlayerEntity player = mc.player;

        // Update drift offset
        if (offsetTimer <= 0) {
            offsetTimer = 5 + random.nextInt(15);
            // Pick a new spot on the hitbox
            // KillAura can be slightly snappier than aim assist
            double r = 0.5;
            targetOffsetDest = new Vec3d(
                    (random.nextDouble() - 0.5) * r,
                    (random.nextDouble() - 0.5) * r + target.getHeight() * 0.5,
                    (random.nextDouble() - 0.5) * r);
        }
        offsetTimer--;

        // Smoothly move current offset to destination
        aimOffset = lerpVec(aimOffset, targetOffsetDest, 0.2); // Faster lerp for KillAura

        Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ()).add(aimOffset);

        double diffX = targetPos.x - player.getX();
        double diffY = targetPos.y - player.getEyeY();
        double diffZ = targetPos.z - player.getZ();

        double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
        float yaw = (float) (Math.atan2(diffZ, diffX) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(Math.atan2(diffY, dist) * 180.0 / Math.PI));

        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(yaw - currentYaw);
        float deltaPitch = MathHelper.wrapDegrees(pitch - currentPitch);

        double smooth;
        if (bezierSmoothing.get()) {
            // Ghost mode: S-curve acceleration (slow→fast→slow)
            float maxAngle = Math.max(Math.abs(deltaYaw), Math.abs(deltaPitch));
            smooth = net.matrix.utils.HumanPulse.accelerationCurve(maxAngle,
                    (float) rotationSmoothness.get().floatValue());
        } else {
            // Legacy LERP with slight randomization
            smooth = rotationSmoothness.get() * (0.8 + random.nextGaussian() * 0.1);
        }
        smooth = Math.max(0.05, Math.min(1.0, smooth));

        // Add jitter
        float jitter = (float) (jitterSetting.get() * random.nextGaussian());

        player.setYaw(currentYaw + (float) (deltaYaw * smooth) + jitter);
        player.setPitch(currentPitch + (float) (deltaPitch * smooth) + jitter);
    }

    private Vec3d lerpVec(Vec3d start, Vec3d end, double t) {
        return start.add(end.subtract(start).multiply(t));
    }
}
