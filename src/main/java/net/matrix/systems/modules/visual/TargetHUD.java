package net.matrix.systems.modules.visual;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;

import java.util.*;

/**
 * TargetHUD — Sleek glassmorphic panel displaying attack target info.
 * Shows player skin head or mob initial, name, health bar with gradient,
 * armor bar, and animated health drain.
 */
public class TargetHUD extends Module {

    private LivingEntity target;
    private float animatedHealth;

    // ── Target Entities Setting ──
    private final Setting.StringSetSetting targetEntities;

    // Colors
    private static final int BG_PRIMARY = 0xCC0D0D15;
    private static final int BG_HIGHLIGHT_TOP = 0x22FFFFFF;
    private static final int BG_HIGHLIGHT_LEFT = 0x11FFFFFF;
    private static final int BORDER_COLOR = 0x1AFFFFFF;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFF94A3B8;
    private static final int HEALTH_DRAIN = 0xFFFF4444;
    private static final int ARMOR_BLUE = 0xFF3B82F6;
    private static final int ARMOR_TRACK = 0xFF1E1E30;
    private static final int HEALTH_TRACK = 0xFF1E1E30;

    public TargetHUD() {
        super(Category.VISUAL, "TargetHUD", "Sleek glassmorphic panel displaying attack target.");

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
                "Target Entities", "Which entities to show the HUD for", allEntities, defaults);
        settings.add(targetEntities);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;

        net.minecraft.entity.LivingEntity newTarget = null;

        // 1. Try default precise vanilla crosshair trace
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.ENTITY) {
            net.minecraft.util.hit.EntityHitResult hit = (net.minecraft.util.hit.EntityHitResult) mc.crosshairTarget;
            if (hit.getEntity() instanceof net.minecraft.entity.LivingEntity living && living.isAlive()) {
                newTarget = living;
            }
        }

        // 2. Fallback: Robust fat raycast/angle check for vertical up/down or clipping overlap
        if (newTarget == null) {
            double closestDistance = 6.0; // 6 blocks max reach tracking
            net.minecraft.util.math.Vec3d headVec = mc.player.getCameraPosVec(1.0f);
            net.minecraft.util.math.Vec3d lookVec = mc.player.getRotationVec(1.0f);
            net.minecraft.util.math.Vec3d endVec = headVec.add(lookVec.multiply(6.0));

            for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
                if (e != mc.player && e instanceof net.minecraft.entity.LivingEntity living && living.isAlive()) {
                    double dist = mc.player.distanceTo(living);
                    if (dist > closestDistance) continue;

                    // Expand bounding box slightly to catch edge glances and vertical view clipping overlaps
                    java.util.Optional<net.minecraft.util.math.Vec3d> boxHit = living.getBoundingBox().expand(0.5).raycast(headVec, endVec);

                    // Also verify angle dot product as a backup for inside-hitbox physics
                    net.minecraft.util.math.Vec3d targetVec = new net.minecraft.util.math.Vec3d(living.getX(), living.getY() + living.getHeight() / 2.0, living.getZ());
                    net.minecraft.util.math.Vec3d diff = targetVec.subtract(headVec).normalize();
                    double dot = diff.dotProduct(lookVec);
                    double reqDot = 1.0 - (0.3 / Math.max(1.0, dist)); 

                    if (boxHit.isPresent() || dot >= reqDot) {
                        // Ensure clear line of sight (no blocks between player eyes and entity center)
                        net.minecraft.util.hit.BlockHitResult blockHit = mc.world.raycast(
                            new net.minecraft.world.RaycastContext(headVec, targetVec, net.minecraft.world.RaycastContext.ShapeType.COLLIDER, net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player)
                        );
                        if (blockHit.getType() == net.minecraft.util.hit.HitResult.Type.MISS) {
                            closestDistance = dist;
                            newTarget = living;
                        }
                    }
                }
            }
        }

        // 3. Process the found target
        if (newTarget != null) {
            String entityId = net.minecraft.entity.EntityType.getId(newTarget.getType()).toString();
            if (targetEntities.get().contains(entityId)) {
                if (target != newTarget) {
                    animatedHealth = newTarget.getHealth();
                }
                target = newTarget;
                return;
            }
        }

        // Deselect if looking away
        target = null;
    }

    public void render(DrawContext context, float tickDelta) {
        if (target == null || mc.player == null)
            return;

        // Window metrics
        int width = mc.getWindow().getScaledWidth();
        int height = mc.getWindow().getScaledHeight();

        // HUD Dimensions — compact and sleek
        int hudW = 160;
        int hudH = 52;
        int x = (width / 2) + 10;
        int y = (height / 2) + 10;
        int cornerR = 6;

        // Animated health — dynamic speed based on damage/heal magnitude
        float targetHealth = target.getHealth();
        float maxHealth = target.getMaxHealth();
        float diff = Math.abs(animatedHealth - targetHealth);

        if (diff > 0.01f) {
            // Speed scales with damage magnitude: bigger hits = faster drain, always ~1 second
            // At 20 TPS, we want diff / speed ≈ 20 ticks → speed = diff / 20
            float speed = Math.max(diff / 20.0f, 0.05f);
            // Apply tick delta for frame-rate independence
            float step = speed * tickDelta;

            if (animatedHealth > targetHealth) {
                animatedHealth -= step;
                if (animatedHealth < targetHealth) animatedHealth = targetHealth;
            } else {
                animatedHealth += step;
                if (animatedHealth > targetHealth) animatedHealth = targetHealth;
            }
        }

        // ── Background — Glassmorphism ──
        // Main dark fill
        context.fill(x, y, x + hudW, y + hudH, BG_PRIMARY);
        // Rounded corner mask (cut corners with transparent-looking fills)
        int mask = 0xCC000000; // Match the alpha roughly
        // Top-left
        context.fill(x, y, x + cornerR, y + 1, mask);
        context.fill(x, y, x + 1, y + cornerR, mask);
        // Top-right
        context.fill(x + hudW - cornerR, y, x + hudW, y + 1, mask);
        context.fill(x + hudW - 1, y, x + hudW, y + cornerR, mask);
        // Bottom-left
        context.fill(x, y + hudH - 1, x + cornerR, y + hudH, mask);
        context.fill(x, y + hudH - cornerR, x + 1, y + hudH, mask);
        // Bottom-right
        context.fill(x + hudW - cornerR, y + hudH - 1, x + hudW, y + hudH, mask);
        context.fill(x + hudW - 1, y + hudH - cornerR, x + hudW, y + hudH, mask);

        // Inner highlight edges (glass refraction effect)
        context.fill(x + 1, y + 1, x + hudW - 1, y + 2, BG_HIGHLIGHT_TOP);
        context.fill(x + 1, y + 2, x + 2, y + hudH - 1, BG_HIGHLIGHT_LEFT);

        // Border
        context.fill(x, y, x + hudW, y + 1, BORDER_COLOR); // top
        context.fill(x, y + hudH - 1, x + hudW, y + hudH, BORDER_COLOR); // bottom
        context.fill(x, y, x + 1, y + hudH, BORDER_COLOR); // left
        context.fill(x + hudW - 1, y, x + hudW, y + hudH, BORDER_COLOR); // right

        // ── 3D Entity Model Renderer ──
        int faceSize = 28;
        int entityModelX = x + 20; // Center offset horizontally inside the portrait frame
        int entityModelY = y + 40; // Base anchor offset (feet of the mob mostly)
        float sizeScaling = 15.0f; // Baseline scale
        
        // Adjust scaling down for taller mobs (like Enderman) dynamically based on their height
        if (target.getHeight() > 2.0f) {
            sizeScaling = 15.0f * (2.0f / target.getHeight());
        }

        // Draw the full entity model using pure actual shading and textures
        // Bounds MUST be ordered correctly (x1 < x2, y1 < y2) to prevent OpenGL 1281 Invalid Value crash
        InventoryScreen.drawEntity(
                context, 
                entityModelX - 15,   // x1
                entityModelY - 35,   // y1 (top edge)
                entityModelX + 15,   // x2
                entityModelY + 5,    // y2 (bottom edge)
                (int) Math.max(1, sizeScaling), // size (must be >= 1 to avoid /by zero or zero-size FBO bugs)
                0f,                  // mouse X Look direction
                0f,                  // mouse Y Look direction
                0f,                  // head Yaw
                target               // entity being drawn
        );


        // ── Name ──
        String name = target.getDisplayName().getString();
        if (name.length() > 16) name = name.substring(0, 15) + "…";
        int nameX = x + 6 + 28 + 6; // Start past the portrait
        context.drawText(mc.textRenderer, name, nameX, y + 6, TEXT_WHITE, true);

        // ── Distance ──
        double dist = mc.player.distanceTo(target);
        String distText = String.format("%.1fm", dist);
        context.drawText(mc.textRenderer, distText, nameX, y + 16, TEXT_DIM, false);

        // ── Health Bar ──
        int barX = nameX;
        int barY = y + 28;
        int barW = hudW - faceSize - 18;
        int barH = 4;

        float hpPercent = Math.min(targetHealth / maxHealth, 1.0f);
        float hpPercentAnim = Math.min(animatedHealth / maxHealth, 1.0f);

        // Track
        context.fill(barX, barY, barX + barW, barY + barH, HEALTH_TRACK);

        // Damage drain (red, animated)
        int animW = (int) (barW * hpPercentAnim);
        if (animW > 0) {
            context.fill(barX, barY, barX + animW, barY + barH, HEALTH_DRAIN);
        }

        // Current health (gradient: red → yellow → green)
        int hpW = (int) (barW * hpPercent);
        int r = (int) (255 * (1.0f - hpPercent));
        int g = (int) (255 * hpPercent);
        int b = 50;
        int hpColor = (0xFF << 24) | (r << 16) | (g << 8) | b;
        if (hpW > 0) {
            context.fill(barX, barY, barX + hpW, barY + barH, hpColor);
        }

        // Health text
        String hpText = String.format("%.0f/%.0f", targetHealth, maxHealth);
        int hpTextW = mc.textRenderer.getWidth(hpText);
        context.drawText(mc.textRenderer, hpText, barX + barW - hpTextW, barY - 9, TEXT_DIM, false);

        // ── Armor Bar ──
        int armorBarY = barY + barH + 3;
        int armorVal = target.getArmor();
        if (armorVal > 0) {
            float armorPercent = Math.min(armorVal / 20.0f, 1.0f);
            context.fill(barX, armorBarY, barX + barW, armorBarY + 3, ARMOR_TRACK);
            int armorW = (int) (barW * armorPercent);
            if (armorW > 0) {
                context.fill(barX, armorBarY, barX + armorW, armorBarY + 3, ARMOR_BLUE);
            }
        }
    }

}
