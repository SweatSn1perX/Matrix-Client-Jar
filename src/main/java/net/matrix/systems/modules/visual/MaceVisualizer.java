package net.matrix.systems.modules.visual;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Render3DModule;
import net.matrix.systems.modules.Setting;
import net.matrix.utils.RenderUtils;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * MaceVisualizer — Visual aid for Mace combat.
 *
 * 1. Impact Circle: Bold 3D ring on predicted ground impact point, radius
 * scales with fall height. High-visibility with multiple rings + filled disc.
 * 2. Predictive Damage: Indicator near crosshair showing predicted slam damage.
 */
public class MaceVisualizer extends Module implements Render3DModule, net.matrix.systems.modules.Render2DModule {

    // ── Settings ──
    public final Setting.BooleanSetting impactCircle = new Setting.BooleanSetting(
            "Impact Circle", "Draws a 3D ring on the ground where you will land (Mace only).", true);
    public final Setting.BooleanSetting predictiveDamage = new Setting.BooleanSetting(
            "Predictive Damage", "Shows calculated damage numbers next to crosshair when falling with weapon.", true);
    public final Setting.IntSetting indicatorAlpha = new Setting.IntSetting(
            "Indicator Alpha", "Opacity of 3D overlays (50-255)", 220, 50, 255);
    public final Setting.ColorSetting textColor = new Setting.ColorSetting(
            "Text Color", "Color of the predictive damage text (ARGB)", 0xFFFFFF55);

    public MaceVisualizer() {
        super(Category.VISUAL, "Mace Visualizer", "Visual aids for Mace, Axe, and Sword combat.");
        settings.add(impactCircle);
        settings.add(predictiveDamage);
        settings.add(indicatorAlpha);
        settings.add(textColor);
    }

    @Override
    @SuppressWarnings("null")
    public void onRender3D(MatrixStack matrices, Vec3d cameraPos, float tickDelta) {
        if (mc.player == null || mc.world == null)
            return;

        net.minecraft.item.Item mainItem = mc.player.getMainHandStack().getItem();
        boolean isMace = mainItem == Items.MACE;
        boolean isWeapon = isMace || mainItem.getTranslationKey().contains("sword") || mainItem.getTranslationKey().contains("axe");
        
        if (!isWeapon)
            return;

        boolean isFalling = !mc.player.isOnGround() && mc.player.getVelocity().y < 0.0;
        double fallDistance = mc.player.fallDistance;

        int alpha = indicatorAlpha.get();

        // ═══════════════════════════════════════════════════════
        // 1. IMPACT CIRCLE
        // ═══════════════════════════════════════════════════════
        if (impactCircle.get() && isWeapon && isFalling && fallDistance > 1.5f) {
            renderImpactCircle(matrices, cameraPos, fallDistance, alpha, tickDelta);
        }
    }

    @Override
    public void onRender2D(net.minecraft.client.gui.DrawContext context, float tickDelta) {
        if (mc.player == null || mc.world == null)
            return;

        net.minecraft.item.Item mainItem = mc.player.getMainHandStack().getItem();
        boolean isMace = mainItem == Items.MACE;
        boolean isWeapon = isMace || mainItem.getTranslationKey().contains("sword") || mainItem.getTranslationKey().contains("axe");
        
        if (!isWeapon)
            return;

        boolean isFalling = !mc.player.isOnGround() && mc.player.getVelocity().y < 0.0;
        double fallDistance = mc.player.fallDistance;

        if (predictiveDamage.get() && isFalling && fallDistance > 1.5f) {
            // Get exact base damage, including weapons, enchantments, and potion effects
            double baseDamage = mc.player.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.ATTACK_DAMAGE);

            // Calculate mace smash bonus damage using vanilla 1.21 proper tiered formula:
            // First 3 blocks: +3 damage per block
            // Next 5 blocks (3-8): +1.5 damage per block
            // Beyond 8 blocks: +0.5 damage per block
            double fallBlocks = fallDistance;
            double smashBonus = 0;
            if (fallDistance > 1.5) { // Threshold for smash attack
                if (fallBlocks <= 3.0) {
                    smashBonus = fallBlocks * 3.0;
                } else if (fallBlocks <= 8.0) {
                    smashBonus = 3.0 * 3.0 + (fallBlocks - 3.0) * 1.5;
                } else {
                    smashBonus = 3.0 * 3.0 + 5.0 * 1.5 + (fallBlocks - 8.0) * 0.5;
                }
            }


            // Density enchantment: +0.5 damage per block per level
            int densityLevel = net.minecraft.enchantment.EnchantmentHelper.getLevel(
                    mc.player.getRegistryManager().getOrThrow(net.minecraft.registry.RegistryKeys.ENCHANTMENT)
                            .getOptional(net.minecraft.enchantment.Enchantments.DENSITY).orElse(null),
                    mc.player.getMainHandStack());
            if (densityLevel > 0) {
                smashBonus += fallBlocks * 0.5 * densityLevel;
            }

            double predictedDamage = baseDamage + smashBonus;

            int color = textColor.get();
            String text = String.format("%.1f HP", predictedDamage);

            // Calculate damage against Full Prot 4 Netherite
            double armorVal = 20.0;
            double toughnessVal = 12.0;
            double epf = 16.0;
            
            double damageAfterArmor = predictedDamage * (1.0 - Math.min(20.0, Math.max(armorVal / 5.0, armorVal - (4.0 * predictedDamage) / (toughnessVal + 8.0))) / 25.0);
            double finalDamage = damageAfterArmor * (1.0 - (epf * 0.04));
            String heartText = String.format("%.1f \u2764", finalDamage / 2.0);

            // Draw near crosshair (center of screen)
            int screenWidth = mc.getWindow().getScaledWidth();
            int screenHeight = mc.getWindow().getScaledHeight();
            
            int x = (screenWidth / 2) + 12;
            int y = (screenHeight / 2) - 4;

            context.drawTextWithShadow(mc.textRenderer, text, x, y, color);
            context.drawTextWithShadow(mc.textRenderer, heartText, x, y + 10, 0xFFFFAAAA);
        }
    }

    // ─── Impact Circle ───────────────────────────────────────

    @SuppressWarnings("null")
    private void renderImpactCircle(MatrixStack matrices, Vec3d cameraPos,
            double fallDistance, int alpha, float tickDelta) {
        // Raycast down to find ground
        Vec3d playerPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        Vec3d down = playerPos.add(0, -100, 0);

        HitResult result = mc.world.raycast(new RaycastContext(
                playerPos, down,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player));

        if (result.getType() == HitResult.Type.MISS)
            return;

        Vec3d groundPos = result.getPos();

        // Mace AoE damage radius scales with fall distance
        double radius = Math.min(fallDistance * 0.5, 5.0);

        // Camera-relative ground position
        double gx = groundPos.x - cameraPos.x;
        double gy = groundPos.y - cameraPos.y + 0.05;
        double gz = groundPos.z - cameraPos.z;

        // Color: Orange → Deep Red gradient based on height
        float heightFactor = (float) Math.min(1.0, fallDistance / 15.0);
        int r = 255;
        int g = (int) (120 * (1.0f - heightFactor));
        int b = 0;

        // Pulsing effect
        long time = System.currentTimeMillis();
        double pulse = (Math.sin(time / 150.0) + 1.0) / 2.0;
        int pulseAlpha = (int) Math.min(255, alpha * (0.7 + 0.3 * pulse));

        // === MAIN BOLD OUTER RING ===
        RenderUtils.drawCircle3D(matrices, gx, gy, gz, radius, 64, r, g, b, pulseAlpha);
        RenderUtils.drawCircle3D(matrices, gx, gy + 0.01, gz, radius * 0.98, 64, r, g, b, pulseAlpha);
        
        // Add a soft fill to the outer ring
        RenderUtils.drawFilledCircle3D(matrices, gx, gy, gz, radius, 64, r, g, b, (int)(pulseAlpha * 0.15));

        // === INNER WARNING RING ===
        int wr = 255;
        int wg = (int) (80 * (1.0f - heightFactor));
        int wb = 20;
        RenderUtils.drawCircle3D(matrices, gx, gy + 0.02, gz, radius * 0.6, 48, wr, wg, wb, pulseAlpha);
        RenderUtils.drawCircle3D(matrices, gx, gy + 0.03, gz, radius * 0.58, 48, wr, wg, wb, pulseAlpha);
        
        // Add a brighter fill to the inner ring
        RenderUtils.drawFilledCircle3D(matrices, gx, gy + 0.02, gz, radius * 0.6, 48, wr, wg, wb, (int)(pulseAlpha * 0.25));

        // === CROSSHAIR DOT at center ===
        double dotSize = 0.15;
        RenderUtils.drawCircle3D(matrices, gx, gy + 0.04, gz, dotSize, 16, 255, 255, 255, pulseAlpha);
        RenderUtils.drawFilledCircle3D(matrices, gx, gy + 0.04, gz, dotSize, 16, 255, 255, 255, (int)(pulseAlpha * 0.4));

        // === IMPACT CIRCLE ON NEAREST ENTITY FEET ===
        LivingEntity nearestTarget = findNearestTarget(radius + 2.0);
        if (nearestTarget != null) {
            double ex = MathHelper.lerp(tickDelta, nearestTarget.lastRenderX, nearestTarget.getX()) - cameraPos.x;
            double ey = MathHelper.lerp(tickDelta, nearestTarget.lastRenderY, nearestTarget.getY()) - cameraPos.y + 0.05;
            double ez = MathHelper.lerp(tickDelta, nearestTarget.lastRenderZ, nearestTarget.getZ()) - cameraPos.z;

            // Red warning ring at entity's feet
            RenderUtils.drawCircle3D(matrices, ex, ey, ez, 0.5, 24, 255, 30, 30, pulseAlpha);
            RenderUtils.drawCircle3D(matrices, ex, ey + 0.01, ez, 0.48, 24, 255, 30, 30, pulseAlpha);
        }
    }

    // ─── Utility ─────────────────────────────────────────────

    @SuppressWarnings("null")
    private LivingEntity findNearestTarget(double maxRange) {
        if (mc.player == null || mc.world == null)
            return null;

        LivingEntity nearest = null;
        double nearestDist = maxRange * maxRange;

        for (Entity entity : mc.world.getEntities()) {
            if (entity == mc.player)
                continue;
            if (!(entity instanceof LivingEntity living))
                continue;
            if (!living.isAlive())
                continue;

            double distSq = entity.squaredDistanceTo(mc.player);
            if (distSq < nearestDist) {
                nearestDist = distSq;
                nearest = living;
            }
        }

        return nearest;
    }
}
