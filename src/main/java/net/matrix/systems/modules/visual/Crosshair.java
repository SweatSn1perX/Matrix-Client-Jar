package net.matrix.systems.modules.visual;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import net.matrix.systems.modules.general.AimAssist;
import net.matrix.systems.modules.mace.AutoHit;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

public class Crosshair extends Module {
    public final Setting.BooleanSetting dot = new Setting.BooleanSetting("Dot", "Render a center dot", true);
    public final Setting.BooleanSetting lines = new Setting.BooleanSetting("Lines", "Render crosshair lines", true);
    public final Setting.BooleanSetting gap = new Setting.BooleanSetting("Dynamic Gap",
            "Gap expands when attacking/moving", true);
    public final Setting.BooleanSetting hitMarker = new Setting.BooleanSetting("Hit Marker",
            "Show a marker when hitting an entity", true);

    public final Setting.DoubleSetting length = new Setting.DoubleSetting("Length", "Line length", 5.0, 1.0, 20.0);
    public final Setting.DoubleSetting thickness = new Setting.DoubleSetting("Thickness", "Line thickness", 1.0, 0.5,
            5.0);
    public final Setting.DoubleSetting baseGap = new Setting.DoubleSetting("Base Gap", "Minimum gap size", 2.0, 0.0,
            10.0);

    // Animation state
    private float currentGap = 2.0f;
    private long lastHitTime = 0;

    public Crosshair() {
        super(Category.VISUAL, "Crosshair", "Customizable professional crosshair.");
        settings.add(dot);
        settings.add(lines);
        settings.add(gap);
        settings.add(hitMarker);
        settings.add(length);
        settings.add(thickness);
        settings.add(baseGap);
    }

    public void onAttack() {
        lastHitTime = System.currentTimeMillis();
        if (gap.get()) {
            currentGap = baseGap.get().floatValue() + 6.0f; // Expand on attack
        }
    }

    @SuppressWarnings("null")
    public void render(DrawContext context, float tickDelta) {
        if (mc.player == null)
            return;

        int width = mc.getWindow().getScaledWidth();
        int height = mc.getWindow().getScaledHeight();
        float cx = width / 2.0f;
        float cy = height / 2.0f;

        // Animation updates
        float targetGap = baseGap.get().floatValue();
        if (gap.get()) {
            // Expand slightly based on movement
            @SuppressWarnings("null")
            double speed = mc.player.getVelocity().horizontalLength();
            targetGap += (float) (Math.min(speed * 10.0, 5.0));
            // Add attack expansion
            long timeSinceHit = System.currentTimeMillis() - lastHitTime;
            if (timeSinceHit < 300) {
                targetGap += 6.0f * (1.0f - (timeSinceHit / 300.0f));
            }
        }

        // Smooth lerp gap
        currentGap += (targetGap - currentGap) * 0.2f * tickDelta;

        int color = 0xFFFFFFFF; // White

        // Optional: Turn red if targeting an entity
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            EntityHitResult hit = (EntityHitResult) mc.crosshairTarget;
            if (hit.getEntity() instanceof LivingEntity living && living.isAlive()) {
                color = 0xFFFF5555; // Light Red
            }
        }

        int thick = Math.max(1, thickness.get().intValue());
        int halfThick = thick / 2;
        float len = length.get().floatValue();
        int lineLen = Math.round(len);
        int gapPx = Math.round(currentGap);

        // Use integer center for pixel-perfect symmetry
        int icx = width / 2;
        int icy = height / 2;

        // Center Dot
        if (dot.get()) {
            context.fill(icx - halfThick, icy - halfThick, icx - halfThick + thick,
                    icy - halfThick + thick, color);
        }

        // Cross lines — measure gap from the DOT EDGES, not from the raw center pixel.
        // This ensures all four prongs are exactly equidistant from the dot.
        if (lines.get()) {
            // Dot occupies [icx - halfThick, icx - halfThick + thick] horizontally
            // and    [icy - halfThick, icy - halfThick + thick] vertically
            int dotTop = icy - halfThick;
            int dotBottom = icy - halfThick + thick;
            int dotLeft = icx - halfThick;
            int dotRight = icx - halfThick + thick;

            // Top prong: from (dotTop - gapPx - lineLen) to (dotTop - gapPx)
            context.fill(icx - halfThick, dotTop - gapPx - lineLen,
                    icx - halfThick + thick, dotTop - gapPx, color);
            // Bottom prong: from (dotBottom + gapPx) to (dotBottom + gapPx + lineLen)
            context.fill(icx - halfThick, dotBottom + gapPx,
                    icx - halfThick + thick, dotBottom + gapPx + lineLen, color);
            // Left prong: from (dotLeft - gapPx - lineLen) to (dotLeft - gapPx)
            context.fill(dotLeft - gapPx - lineLen, icy - halfThick,
                    dotLeft - gapPx, icy - halfThick + thick, color);
            // Right prong: from (dotRight + gapPx) to (dotRight + gapPx + lineLen)
            context.fill(dotRight + gapPx, icy - halfThick,
                    dotRight + gapPx + lineLen, icy - halfThick + thick, color);
        }

        // Hitmarker
        if (hitMarker.get()) {
            long timeSinceHit = System.currentTimeMillis() - lastHitTime;
            if (timeSinceHit < 500) {
                float alpha = 1.0f - (timeSinceHit / 500.0f);
                int a = (int) (alpha * 255);
                int hmColor = (a << 24) | 0xFFFFFF; // White tinted

                int off = (int) (currentGap + 2);
                int hmLen = 4;

                // Draw X
                context.fill((int) (cx - off - hmLen), (int) (cy - off - thick), (int) (cx - off), (int) (cy - off),
                        hmColor);
                context.fill((int) (cx + off), (int) (cy - off - thick), (int) (cx + off + hmLen), (int) (cy - off),
                        hmColor);
                context.fill((int) (cx - off - hmLen), (int) (cy + off), (int) (cx - off), (int) (cy + off + thick),
                        hmColor);
                context.fill((int) (cx + off), (int) (cy + off), (int) (cx + off + hmLen), (int) (cy + off + thick),
                        hmColor);
                // (Very simplified X hitmarker due to fill() limitations, usually standard
                // lines look better but fill is safer here)
            }
        }
    }
}
