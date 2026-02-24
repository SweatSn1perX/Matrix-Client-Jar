package net.matrix.events;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

/** Fired during world rendering for 3D overlay drawing. */
public class Render3DEvent {
    public final MatrixStack matrices;
    public final Vec3d cameraPos;
    public final float tickDelta;

    public Render3DEvent(MatrixStack matrices, Vec3d cameraPos, float tickDelta) {
        this.matrices = matrices;
        this.cameraPos = cameraPos;
        this.tickDelta = tickDelta;
    }
}
