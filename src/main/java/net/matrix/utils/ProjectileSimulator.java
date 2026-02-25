package net.matrix.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.*;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulates projectile trajectories using Minecraft's actual physics constants.
 * Adapted from Meteor Client's ProjectileEntitySimulator for Matrix.
 */
public class ProjectileSimulator {

    /** Result of a trajectory simulation. */
    public static class SimulationResult {
        public final List<Vec3d> path;
        public final HitResult hitResult;
        public final Entity hitEntity;

        public SimulationResult(List<Vec3d> path, HitResult hitResult, Entity hitEntity) {
            this.path = path;
            this.hitResult = hitResult;
            this.hitEntity = hitEntity;
        }
    }

    /** Physics constants for different projectile types. Matches Meteor Client. */
    private record ProjectileInfo(double gravity, double drag, double power, float roll, double size) {
        // ThrownEntity physics (gravity -> drag -> position)
        static final ProjectileInfo ARROW     = new ProjectileInfo(0.05, 0.99, 1.0, 0.0f, 0.5);
        static final ProjectileInfo PEARL     = new ProjectileInfo(0.03, 0.99, 1.5, 0.0f, 0.25);
        static final ProjectileInfo POTION    = new ProjectileInfo(0.05, 0.99, 0.5, -20.0f, 0.25);
        static final ProjectileInfo EGG       = new ProjectileInfo(0.03, 0.99, 1.5, 0.0f, 0.25);
        static final ProjectileInfo SNOWBALL  = new ProjectileInfo(0.03, 0.99, 1.5, 0.0f, 0.25);
        static final ProjectileInfo XP_BOTTLE = new ProjectileInfo(0.07, 0.99, 0.7, -20.0f, 0.25);
        static final ProjectileInfo TRIDENT   = new ProjectileInfo(0.05, 0.99, 2.5, 0.0f, 0.5);
        static final ProjectileInfo WIND      = new ProjectileInfo(0.0, 1.0, 1.5, 0.0f, 0.3125);
        static final ProjectileInfo FIREWORK  = new ProjectileInfo(0.0, 1.0, 1.6, 0.0f, 0.25);
    }

    private static final double DEG_TO_RAD = Math.PI / 180.0;

    /**
     * Simulate a trajectory for the item held by the player.
     * Returns null if the player's held item is not a projectile weapon.
     *
     * Physics origin and direction exactly match Meteor Client's ProjectileEntitySimulator.set().
     */
    public static SimulationResult simulate(PlayerEntity player, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return null;

        ItemStack mainHand = player.getMainHandStack();
        ItemStack offHand = player.getOffHandStack();
        ItemStack stack = mainHand;

        ProjectileInfo info = getProjectileInfo(stack);
        if (info == null) {
            stack = offHand;
            info = getProjectileInfo(stack);
            if (info == null) return null;
        }

        // ── Origin: Interpolated entity position + eye height - 0.1 ──
        // Matches Meteor: Utils.set(pos, user, tickDelta).add(0, user.getEyeHeight(pose) - 0.1f, 0)
        double posX = MathHelper.lerp(tickDelta, player.lastRenderX, player.getX());
        double posY = MathHelper.lerp(tickDelta, player.lastRenderY, player.getY());
        double posZ = MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ());
        Vec3d startPos = new Vec3d(posX, posY + (double) player.getEyeHeight(player.getPose()) - 0.1, posZ);

        // ── Direction: Matches Meteor's exact formula ──
        // Meteor: x = -sin(yaw * 0.017453292) * cos(pitch * 0.017453292)
        //         y = -sin((pitch + roll) * 0.017453292)
        //         z = cos(yaw * 0.017453292) * cos(pitch * 0.017453292)
        float yaw = player.getYaw(tickDelta);
        float pitch = player.getPitch(tickDelta);

        double x = -Math.sin(yaw * DEG_TO_RAD) * Math.cos(pitch * DEG_TO_RAD);
        double y = -Math.sin((pitch + info.roll) * DEG_TO_RAD);
        double z = Math.cos(yaw * DEG_TO_RAD) * Math.cos(pitch * DEG_TO_RAD);

        double power = info.power;

        // Handle bows — power depends on charge
        if (stack.getItem() instanceof BowItem) {
            int useTicks = stack.getMaxUseTime(player) - player.getItemUseTimeLeft();
            float bowPower = BowItem.getPullProgress(useTicks);
            if (bowPower <= 0.1f) return null;
            power = bowPower * 3.0;
        }

        // Handle crossbows — always full power
        if (stack.getItem() instanceof CrossbowItem) {
            ChargedProjectilesComponent charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (charged == null || charged.isEmpty()) return null;
            ItemStack projectile = charged.getProjectiles().get(0);
            if (projectile.getItem() instanceof FireworkRocketItem) {
                info = ProjectileInfo.FIREWORK;
                power = 1.6;
            } else {
                power = 3.15;
            }
        }

        // Velocity: normalize direction then scale by power (matches Meteor)
        double len = Math.sqrt(x * x + y * y + z * z);
        Vec3d velocity = new Vec3d((x / len) * power, (y / len) * power, (z / len) * power);

        return runSimulation(mc, player, startPos, velocity, info, 500);
    }

    /**
     * Simulate the trajectory of an already-fired projectile entity.
     */
    public static SimulationResult simulateEntity(ProjectileEntity projectile, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return null;

        Vec3d pos = new Vec3d(
            MathHelper.lerp(tickDelta, projectile.lastRenderX, projectile.getX()),
            MathHelper.lerp(tickDelta, projectile.lastRenderY, projectile.getY()),
            MathHelper.lerp(tickDelta, projectile.lastRenderZ, projectile.getZ())
        );
        Vec3d velocity = projectile.getVelocity();

        // Determine physics from entity type
        ProjectileInfo info = ProjectileInfo.ARROW; // default
        String typeId = net.minecraft.entity.EntityType.getId(projectile.getType()).toString();
        if (typeId.contains("ender_pearl")) info = ProjectileInfo.PEARL;
        else if (typeId.contains("potion")) info = ProjectileInfo.POTION;
        else if (typeId.contains("egg")) info = ProjectileInfo.EGG;
        else if (typeId.contains("snowball")) info = ProjectileInfo.SNOWBALL;
        else if (typeId.contains("experience_bottle")) info = ProjectileInfo.XP_BOTTLE;
        else if (typeId.contains("trident")) info = ProjectileInfo.TRIDENT;
        else if (typeId.contains("wind_charge")) info = ProjectileInfo.WIND;
        else if (typeId.contains("firework")) info = ProjectileInfo.FIREWORK;

        return runSimulation(mc, mc.player, pos, velocity, info, 300);
    }

    @SuppressWarnings("null")
    private static SimulationResult runSimulation(MinecraftClient mc, PlayerEntity player,
            Vec3d startPos, Vec3d startVelocity, ProjectileInfo info, int maxTicks) {

        List<Vec3d> path = new ArrayList<>();
        Vec3d pos = startPos;
        Vec3d vel = startVelocity;

        path.add(pos);

        for (int i = 0; i < maxTicks; i++) {
            // ── Apply physics: gravity -> drag -> position (ThrownEntity order from Meteor) ──
            vel = vel.add(0, -info.gravity, 0);
            vel = vel.multiply(info.drag);
            Vec3d nextPos = pos.add(vel);

            // ─── Block Collision ─────────────────
            BlockHitResult blockHit = mc.world.raycast(new RaycastContext(
                    pos, nextPos,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player));

            if (blockHit.getType() != HitResult.Type.MISS) {
                path.add(blockHit.getPos());
                return new SimulationResult(path, blockHit, null);
            }

            // ─── Entity Collision ────────────────
            Box movementBox = createBoundingBox(pos, nextPos, info.size);
            Entity closestEntity = null;
            double closestDist = Double.MAX_VALUE;
            Vec3d closestHitPos = null;

            for (Entity entity : mc.world.getOtherEntities(player, movementBox)) {
                if (!(entity instanceof LivingEntity)) continue;
                if (!entity.isAlive()) continue;

                Box entityBox = entity.getBoundingBox();
                java.util.Optional<Vec3d> hitOpt = entityBox.raycast(pos, nextPos);
                if (hitOpt.isPresent()) {
                    Vec3d hitPoint = hitOpt.get();
                    // Ensure the hit point is in the forward direction
                    if (hitPoint.subtract(pos).dotProduct(vel) < 0) continue;

                    double dist = pos.squaredDistanceTo(hitPoint);
                    if (dist < closestDist) {
                        closestDist = dist;
                        closestEntity = entity;
                        closestHitPos = hitPoint;
                    }
                }
            }

            if (closestEntity != null) {
                path.add(closestHitPos);
                EntityHitResult entityHitResult = new EntityHitResult(closestEntity, closestHitPos);
                return new SimulationResult(path, entityHitResult, closestEntity);
            }

            pos = nextPos;
            path.add(pos);
        }

        return new SimulationResult(path, null, null);
    }

    private static Box createBoundingBox(Vec3d start, Vec3d end, double size) {
        double minX = Math.min(start.x, end.x) - size;
        double minY = Math.min(start.y, end.y) - size;
        double minZ = Math.min(start.z, end.z) - size;
        double maxX = Math.max(start.x, end.x) + size;
        double maxY = Math.max(start.y, end.y) + size;
        double maxZ = Math.max(start.z, end.z) + size;
        return new Box(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static ProjectileInfo getProjectileInfo(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof BowItem) return ProjectileInfo.ARROW;
        if (item instanceof CrossbowItem) {
            ChargedProjectilesComponent charged = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
            if (charged != null && !charged.isEmpty()) return ProjectileInfo.ARROW;
            return null;
        }
        if (item instanceof TridentItem) return ProjectileInfo.TRIDENT;
        if (item instanceof EnderPearlItem) return ProjectileInfo.PEARL;
        if (item instanceof SnowballItem) return ProjectileInfo.SNOWBALL;
        if (item instanceof EggItem) return ProjectileInfo.EGG;
        if (item instanceof ExperienceBottleItem) return ProjectileInfo.XP_BOTTLE;
        if (item instanceof WindChargeItem) return ProjectileInfo.WIND;
        if (item instanceof ThrowablePotionItem) return ProjectileInfo.POTION;
        return null;
    }
}
