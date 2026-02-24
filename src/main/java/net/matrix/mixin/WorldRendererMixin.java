package net.matrix.mixin;

import net.matrix.events.EventBus;
import net.matrix.events.Render3DEvent;
import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Render3DModule;
import net.matrix.utils.RenderUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Mixin into WorldRenderer to provide a 3D render hook after
 * world rendering but before HUD.  Fires Render3DEvent and
 * manages the batched RenderUtils lifecycle (begin → dispatch → flush).
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    @Inject(method = "render", at = @At("RETURN"))
    private void onWorldRenderPost(
            ObjectAllocator allocator,
            RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera,
            Matrix4f positionMatrix,
            Matrix4f projectionMatrix,
            Matrix4f viewMatrix,
            GpuBufferSlice fogBufferSlice,
            Vector4f fogColor,
            boolean profilerActive,
            CallbackInfo ci) {

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null)
            return;

        float tickDelta = tickCounter.getTickProgress(true);
        Vec3d cameraPos = camera.getCameraPos();

        // Build a MatrixStack carrying the world position matrix
        MatrixStack matrices = new MatrixStack();
        matrices.multiplyPositionMatrix(positionMatrix);

        // Cache projection for RenderUtils UBO
        RenderUtils.projection = projectionMatrix;

        // Push model-view matrix so the position matrix is applied during flush
        RenderSystem.getModelViewStack().pushMatrix();
        RenderSystem.getModelViewStack().mul(positionMatrix);

        // ── Begin batched render phase ──
        RenderUtils.begin();

        // Fire the EventBus event
        EventBus.get().post(new Render3DEvent(matrices, cameraPos, tickDelta));

        // Dispatch to Render3DModule implementors
        for (Module module : Modules.get().getAll()) {
            if (module.isActive() && module instanceof Render3DModule render3d) {
                render3d.onRender3D(matrices, cameraPos, tickDelta);
            }
        }

        // ── Flush all batched geometry through the GPU pipeline ──
        RenderUtils.render(matrices);

        // Pop model-view matrix
        RenderSystem.getModelViewStack().popMatrix();
    }

}
