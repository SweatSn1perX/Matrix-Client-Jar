package net.matrix.mixin;

import net.matrix.utils.RenderUtils;
import net.minecraft.client.gl.ShaderLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into ShaderLoader.apply() to precompile Matrix's custom render pipelines.
 * Without this, the GPU device cannot locate modded shader resources.
 */
@Mixin(ShaderLoader.class)
public abstract class ShaderLoaderMixin {
    @Inject(method = "apply(Lnet/minecraft/client/gl/ShaderLoader$Definitions;Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/util/profiler/Profiler;)V", at = @At("TAIL"))
    private void matrix$reloadPipelines(CallbackInfo info) {
        RenderUtils.precompilePipelines();
    }
}
