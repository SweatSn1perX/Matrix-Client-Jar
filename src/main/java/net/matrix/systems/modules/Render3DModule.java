package net.matrix.systems.modules;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

/**
 * Interface for modules that want to render 3D overlays in world space.
 * Implement this on any Module subclass to receive the render callback.
 * All world coordinates should be made camera-relative by subtracting
 * cameraPos.
 */
public interface Render3DModule {
    void onRender3D(MatrixStack matrices, Vec3d cameraPos, float tickDelta);
}
