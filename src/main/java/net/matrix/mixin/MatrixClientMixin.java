package net.matrix.mixin;

import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.general.AimAssist;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public class MatrixClientMixin {
    @SuppressWarnings("null")
    @Inject(method = "doAttack", at = @At("HEAD"))
    private void onAttack(CallbackInfoReturnable<Boolean> cir) {
        AimAssist aimAssist = Modules.get().get(AimAssist.class);
        net.matrix.systems.modules.mace.MaceAimAssist maceAimAssist = Modules.get()
                .get(net.matrix.systems.modules.mace.MaceAimAssist.class);

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() == HitResult.Type.MISS) {
            if (aimAssist != null && aimAssist.isActive() && aimAssist.hitboxExpand.get()) {
                Entity target = findEntityInDirection(mc, 5.0);
                if (target != null) {
                    aimAssist.forceAim(target);
                }
            } else if (maceAimAssist != null && maceAimAssist.isActive() && maceAimAssist.hitboxExpand.get()) {
                Entity target = findEntityInDirection(mc, 5.0);
                if (target != null) {
                    maceAimAssist.forceAim(target);
                }
            }
        }
    }

    private Entity findEntityInDirection(MinecraftClient mc, double range) {
        if (mc.player == null || mc.world == null)
            return null;

        @SuppressWarnings("null")
        Vec3d start = mc.player.getCameraPosVec(1.0f);
        @SuppressWarnings("null")
        Vec3d direction = mc.player.getRotationVec(1.0f);
        Vec3d end = start.add(direction.multiply(range));
        @SuppressWarnings("null")
        Box box = mc.player.getBoundingBox().stretch(direction.multiply(range)).expand(1.0, 1.0, 1.0);

        EntityHitResult hit = ProjectileUtil.raycast(
                mc.player,
                start,
                end,
                box,
                (entity) -> !entity.isSpectator() && entity.isAlive() && entity != mc.player,
                range * range);

        return hit != null ? hit.getEntity() : null;
    }
}
