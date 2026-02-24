package net.matrix.events;

import net.minecraft.client.gui.DrawContext;

/** Fired during HUD rendering for 2D overlay drawing. */
public class Render2DEvent {
    public final DrawContext context;
    public final float tickDelta;

    public Render2DEvent(DrawContext context, float tickDelta) {
        this.context = context;
        this.tickDelta = tickDelta;
    }
}
