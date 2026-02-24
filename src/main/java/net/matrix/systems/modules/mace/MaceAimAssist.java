package net.matrix.systems.modules.mace;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.Setting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Mace Aim Assist — Agreesive aim assistance when falling with a Mace.
 * Missing a mace hit means taking full fall damage. This module
 * forcibly LERPs onto the target's hitbox specifically when
 * mc.player.fallDistance > 3.0 to ensure a hit.
 */
public class MaceAimAssist extends Module {
    private final Random random = new Random();

    private final Setting.DoubleSetting rangeSetting = new Setting.DoubleSetting(
            "Range", "Max targeting reach", 4.5, 2.5, 6.0);
    private final Setting.DoubleSetting fov = new Setting.DoubleSetting(
            "FOV", "Horizontal FOV check", 120.0, 30.0, 360.0);
    private final Setting.DoubleSetting minFallDist = new Setting.DoubleSetting(
            "Min Fall Distance", "Fall distance to activate aggressive tracking", 3.0, 1.0, 10.0);
    private final Setting.DoubleSetting speed = new Setting.DoubleSetting(
            "Speed", "Fast aim factor (aggressive)", 0.65, 0.2, 1.0);
    private final Setting.DoubleSetting randomization = new Setting.DoubleSetting(
            "Randomization", "Gaussian hitbox jitter (lower for mace)", 0.1, 0.0, 0.5);
    private final Setting.BooleanSetting silentRotation = new Setting.BooleanSetting(
            "Silent Rotation", "Rotate server-side only", false);
    private final Setting.BooleanSetting clickAim = new Setting.BooleanSetting(
            "Click Aim", "Only aim when clicking mouse", false);
    public final Setting.BooleanSetting hitboxExpand = new Setting.BooleanSetting(
            "Click Hitbox", "Hits entities within 5 blocks when clicking", false);
    private final Setting.StringSetSetting targetEntities;

    private float currentSilentYaw, currentSilentPitch;

    // Internal state for smooth transitions
    private boolean isForcingAim = false;
    private Entity forcedTarget = null;
    private int forceAimTimer = 0;

    public MaceAimAssist() {
        super(Category.MACE, "Mace Aim Assist", "Aggressively locks onto targets when falling with a mace.");

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
        settings.add(fov);
        settings.add(minFallDist);
        settings.add(speed);
        settings.add(randomization);
        settings.add(silentRotation);
        settings.add(clickAim);
        settings.add(hitboxExpand);
        settings.add(targetEntities);
    }

    public void forceAim(Entity target) {
        if (target == null)
            return;
        this.forcedTarget = target;
        this.isForcingAim = true;
        this.forceAimTimer = 10; // Try to lock on for 10 ticks
    }

    @SuppressWarnings("null")
    @Override
    public void onActivate() {
        isForcingAim = false;
        forcedTarget = null;
        if (mc.player != null) {
            currentSilentYaw = mc.player.getYaw();
            currentSilentPitch = mc.player.getPitch();
        }
    }

    @Override
    public void onDeactivate() {
        isForcingAim = false;
        forcedTarget = null;
    }

    @SuppressWarnings("null")
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null)
            return;

        // Weapon check: Must be holding a mace
        if (mc.player.getMainHandStack().getItem() != Items.MACE) {
            isForcingAim = false;
            forcedTarget = null;
            return;
        }

        // Falling check: Must be falling, and beyond min fall distance
        if (mc.player.isOnGround() || mc.player.getVelocity().y > -0.1 || mc.player.fallDistance < minFallDist.get()) {
            isForcingAim = false;
            forcedTarget = null;
            return;
        }

        // AutoHit / AutoStunSlam priority: if they are doing their own aimed combo,
        // don't fight it
        AutoHit autoHit = Modules.get().get(AutoHit.class);
        if (autoHit != null && autoHit.isExecuting()) {
            isForcingAim = false;
            forcedTarget = null;
            return;
        }

        // Handle forced aim (from Click Hitbox)
        if (isForcingAim) {
            if (forcedTarget == null || !forcedTarget.isAlive() || forceAimTimer <= 0) {
                isForcingAim = false;
                forcedTarget = null;
            } else {
                forceAimTimer--;
                aimAtTarget(forcedTarget);
                // If looking close enough, click!
                if (isInFOV(forcedTarget, 10.0)) { // 10 degree tolerance for "flick click"
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
            return;
        }

        Entity target = getNearestTarget();
        if (target != null) {
            aimAtTarget(target);
        }
    }

    @SuppressWarnings("null")
    private void aimAtTarget(Entity target) {
        if (mc.player == null)
            return;

        // Predictive logic for hitting moving targets while you are falling
        Vec3d targetVel = target.getVelocity();
        double predictionFactor = 2.0;

        double predX = target.getX() + targetVel.x * predictionFactor;
        double predY = target.getY() + targetVel.y * predictionFactor;
        double predZ = target.getZ() + targetVel.z * predictionFactor;

        if (!target.isOnGround()) {
            predY -= 0.08 * predictionFactor;
        }

        // Add randomization for slight jitter
        double r = randomization.get();
        double jitterX = (random.nextDouble() - 0.5) * r;
        double jitterY = (random.nextDouble() - 0.5) * r;
        double jitterZ = (random.nextDouble() - 0.5) * r;

        Vec3d targetPos = new Vec3d(predX + jitterX, predY + target.getHeight() * 0.5 + jitterY, predZ + jitterZ);

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

        // Ghost mode: S-curve acceleration (slow→fast→slow)
        float maxAngle = Math.max(Math.abs(deltaYaw), Math.abs(deltaPitch));
        float speedFactor = net.matrix.utils.HumanPulse.accelerationCurve(maxAngle, (float) speed.get().floatValue());
        speedFactor = Math.max(0.2f, Math.min(1.0f, speedFactor));

        if (silentRotation.get()) {
            currentSilentYaw = currentYaw + deltaYaw * speedFactor;
            currentSilentPitch = currentPitch + deltaPitch * speedFactor;
            syncRotation(currentSilentYaw, currentSilentPitch);
        } else {
            mc.player.setYaw(currentYaw + deltaYaw * speedFactor);
            mc.player.setPitch(currentPitch + deltaPitch * speedFactor);
            currentSilentYaw = mc.player.getYaw();
            currentSilentPitch = mc.player.getPitch();
        }
    }

    @SuppressWarnings("null")
    private Entity getNearestTarget() {
        if (mc.player == null || mc.world == null)
            return null;

        double r = rangeSetting.get();
        double f = fov.get();
        Set<String> enabled = targetEntities.get();

        return mc.world.getEntitiesByClass(LivingEntity.class, mc.player.getBoundingBox().expand(r, 10.0, r), // expanded
                                                                                                              // vertical
                                                                                                              // range
                                                                                                              // for
                                                                                                              // mace
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
        double angle = Math.toDegrees(Math.acos(Math.min(1.0, dot)));
        return angle <= fov / 2.0;
    }
}
