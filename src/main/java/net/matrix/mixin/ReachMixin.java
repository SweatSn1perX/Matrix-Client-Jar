package net.matrix.mixin;

import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.general.Reach;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public class ReachMixin {
    @Inject(method = "getEntityInteractionRange", at = @At("HEAD"), cancellable = true)
    private void onGetEntityInteractionRange(CallbackInfoReturnable<Double> cir) {
        Reach reachModule = Modules.get().get(Reach.class);

        if (reachModule != null && reachModule.isActive()) {
            cir.setReturnValue(reachModule.getReachDistance());
        }
    }

    @Inject(method = "getBlockInteractionRange", at = @At("HEAD"), cancellable = true)
    private void onGetBlockInteractionRange(CallbackInfoReturnable<Double> cir) {
        Reach reachModule = Modules.get().get(Reach.class);

        if (reachModule != null && reachModule.isActive()) {
            cir.setReturnValue(reachModule.getReachDistance());
        }
    }
}
