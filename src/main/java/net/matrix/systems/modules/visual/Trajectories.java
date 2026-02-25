package net.matrix.systems.modules.visual;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Render3DModule;
import net.matrix.systems.modules.Setting;
import net.matrix.utils.Color;
import net.matrix.utils.ProjectileSimulator;
import net.matrix.utils.RenderUtils;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Trajectories — Renders predicted projectile paths in 3D.
 *
 * Closely follows Meteor Client's Trajectories implementation.
 */
public class Trajectories extends Module implements Render3DModule {

    // ── General Settings ──
    public final Setting.BooleanSetting firedProjectiles = new Setting.BooleanSetting(
            "Fired Projectiles", "Show trajectories for already-fired projectiles", false);

    public final Setting.BooleanSetting otherPlayers = new Setting.BooleanSetting(
            "Other Players", "Calculate trajectories for other players", false);

    // ── Render Settings ──
    public final Setting.StringSetting lineColor = new Setting.StringSetting(
            "Line Color", "Trajectory line color (hex)", "#FF9600");

    public final Setting.IntSetting lineAlpha = new Setting.IntSetting(
            "Line Alpha", "Trajectory line opacity", 255, 50, 255);

    public final Setting.BooleanSetting renderHitQuad = new Setting.BooleanSetting(
            "Hit Marker", "Render impact marker quad at hit position", true);

    public final Setting.BooleanSetting renderEntityBox = new Setting.BooleanSetting(
            "Entity Box", "Highlight entities that will be hit", true);

    /**
     * Number of initial path ticks to skip rendering for the local player.
     * Matches Meteor's "ignoreFirstTicks" (default 3).
     * This prevents visual artifacts near the camera caused by the offset between
     * the view-bobbed camera position and the un-bobbed physics origin.
     */
    private static final int IGNORE_FIRST_TICKS = 3;

    public Trajectories() {
        super(Category.VISUAL, "Trajectories", "Predicts projectile paths for throwable and shootable items.");
        settings.add(firedProjectiles);
        settings.add(otherPlayers);
        settings.add(lineColor);
        settings.add(lineAlpha);
        settings.add(renderHitQuad);
        settings.add(renderEntityBox);
    }

    @Override
    @SuppressWarnings("null")
    public void onRender3D(MatrixStack matrices, Vec3d cameraPos, float tickDelta) {
        if (mc.player == null || mc.world == null)
            return;

        Color c = Color.fromHex(lineColor.get());
        int r = c.r, g = c.g, b = c.b, a = lineAlpha.get();

        RenderUtils.setLineWidth(0.5f);

        // ── Player trajectories ──
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!otherPlayers.get() && player != mc.player) continue;

            ProjectileSimulator.SimulationResult result = ProjectileSimulator.simulate(player, tickDelta);
            if (result == null) continue;

            // Skip first ticks for local player to avoid near-camera bobbing artifacts
            // This is exactly what Meteor Client does with ignoreFirstTicks
            int startIdx = (player == mc.player) ? IGNORE_FIRST_TICKS : 0;
            renderPath(matrices, cameraPos, result, r, g, b, a, startIdx, tickDelta);
        }

        // ── Fired projectile trajectories ──
        if (firedProjectiles.get()) {
            for (Entity entity : mc.world.getEntities()) {
                if (entity instanceof ProjectileEntity projectile) {
                    ProjectileSimulator.SimulationResult firedResult =
                            ProjectileSimulator.simulateEntity(projectile, tickDelta);
                    if (firedResult != null) {
                        renderPath(matrices, cameraPos, firedResult, r, g, b,
                                (int) (a * 0.6), 0, tickDelta);
                    }
                }
            }
        }
    }

    private void renderPath(MatrixStack matrices, Vec3d cameraPos,
                            ProjectileSimulator.SimulationResult result,
                            int r, int g, int b, int a, int startIdx, float tickDelta) {

        List<Vec3d> path = result.path;
        if (path.size() < 2) return;

        // Clamp startIdx to valid range
        if (startIdx >= path.size() - 1) startIdx = 0;

        // ── Draw path line segments ──
        // Matches Meteor's Path.render() — simple iteration, no anchoring hacks
        Vec3d lastPoint = null;
        for (int i = startIdx; i < path.size(); i++) {
            Vec3d point = path.get(i).subtract(cameraPos);

            if (lastPoint != null) {
                float progress = (float) i / path.size();
                int segAlpha = (int) (a * (1.0f - progress * 0.4f));
                RenderUtils.drawLine3D(matrices, lastPoint, point, r, g, b, segAlpha);
            }

            lastPoint = point;
        }

        // ── Draw impact marker ──
        if (result.hitResult != null && renderHitQuad.get()) {
            if (result.hitResult.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) result.hitResult;
                Vec3d hitPos = blockHit.getPos().subtract(cameraPos);
                Direction side = blockHit.getSide();

                double hs = 0.25; // half-size of the impact quad

                if (side == Direction.UP || side == Direction.DOWN) {
                    // Horizontal flat quad — filled + outlined circle for clarity
                    double y = hitPos.y + (side == Direction.UP ? 0.01 : -0.01);
                    RenderUtils.drawFilledCircle3D(matrices, hitPos.x, y, hitPos.z,
                            hs, 32, 255, 50, 50, 80);
                    RenderUtils.drawCircle3D(matrices, hitPos.x, y, hitPos.z,
                            hs, 32, 255, 50, 50, 220);
                } else if (side == Direction.NORTH || side == Direction.SOUTH) {
                    // Vertical on Z axis
                    Box hitBox = new Box(
                            hitPos.x - hs, hitPos.y - hs, hitPos.z - 0.01,
                            hitPos.x + hs, hitPos.y + hs, hitPos.z + 0.01);
                    RenderUtils.drawBox3D(matrices, hitBox, 255, 50, 50, 180);
                } else {
                    // Vertical on X axis (EAST/WEST)
                    Box hitBox = new Box(
                            hitPos.x - 0.01, hitPos.y - hs, hitPos.z - hs,
                            hitPos.x + 0.01, hitPos.y + hs, hitPos.z + hs);
                    RenderUtils.drawBox3D(matrices, hitBox, 255, 50, 50, 180);
                }
            }
        }

        // ── Highlight entity that will be hit ──
        if (result.hitEntity != null && renderEntityBox.get()
                && result.hitResult != null
                && result.hitResult.getType() == HitResult.Type.ENTITY) {

            Entity hitEntity = result.hitEntity;

            // Interpolate entity position for smoother rendering
            double ix = MathHelper.lerp(tickDelta, hitEntity.lastRenderX, hitEntity.getX());
            double iy = MathHelper.lerp(tickDelta, hitEntity.lastRenderY, hitEntity.getY());
            double iz = MathHelper.lerp(tickDelta, hitEntity.lastRenderZ, hitEntity.getZ());

            Box box = hitEntity.getBoundingBox();
            // Offset box by interpolated delta
            double dx = ix - hitEntity.getX();
            double dy = iy - hitEntity.getY();
            double dz = iz - hitEntity.getZ();

            Box renderBox = new Box(
                    box.minX + dx - cameraPos.x, box.minY + dy - cameraPos.y, box.minZ + dz - cameraPos.z,
                    box.maxX + dx - cameraPos.x, box.maxY + dy - cameraPos.y, box.maxZ + dz - cameraPos.z);
            RenderUtils.drawBox3D(matrices, renderBox, 255, 80, 80, 150);
        }
    }
}
