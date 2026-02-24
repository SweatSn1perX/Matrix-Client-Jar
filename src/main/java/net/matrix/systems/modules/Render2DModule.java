package net.matrix.systems.modules;

import net.minecraft.client.gui.DrawContext;

/**
 * Interface for modules that need to render 2D overlays on the HUD.
 */
public interface Render2DModule {
    /**
     * Called during HUD rendering.
     *
     * @param context   The draw context.
     * @param tickDelta The tick progress.
     */
    void onRender2D(DrawContext context, float tickDelta);
}
