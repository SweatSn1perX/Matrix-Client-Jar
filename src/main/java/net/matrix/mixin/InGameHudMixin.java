package net.matrix.mixin;

import net.matrix.events.EventBus;
import net.matrix.events.Render2DEvent;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.DrawContext;
import net.matrix.systems.modules.visual.Crosshair;
import net.matrix.systems.modules.visual.TargetHUD;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void onRenderCrosshair(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter,
            CallbackInfo ci) {
        Crosshair mod = net.matrix.systems.modules.Modules.get().get(Crosshair.class);
        if (mod != null && mod.isActive()) {
            mod.render(context, tickCounter.getTickProgress(true));
            ci.cancel(); // Don't render vanilla crosshair
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderEnd(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter,
            CallbackInfo ci) {
        float tickDelta = tickCounter.getTickProgress(true);

        // Legacy: TargetHUD still called directly (needs special render method)
        TargetHUD targetHUD = net.matrix.systems.modules.Modules.get().get(TargetHUD.class);
        if (targetHUD != null && targetHUD.isActive()) {
            targetHUD.render(context, tickDelta);
        }

        // Fire Render2DEvent through EventBus
        EventBus.get().post(new Render2DEvent(context, tickDelta));

        // Legacy fallback: still dispatch to Render2DModule implementors
        for (net.matrix.systems.modules.Module module : net.matrix.systems.modules.Modules.get().getAll()) {
            if (module.isActive() && module instanceof net.matrix.systems.modules.Render2DModule render2d) {
                render2d.onRender2D(context, tickDelta);
            }
        }
    }
}
