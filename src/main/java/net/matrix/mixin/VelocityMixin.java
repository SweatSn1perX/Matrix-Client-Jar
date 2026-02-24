package net.matrix.mixin;

import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.general.Velocity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class VelocityMixin {

    @Unique
    private Vec3d matrix_explosionOldVelocity;

    @Inject(method = "onEntityVelocityUpdate", at = @At("TAIL"))
    private void onVelocityUpdate(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        if (player == null)
            return;

        if (packet.getEntityId() != player.getId())
            return;

        Velocity velocityModule = Modules.get().get(Velocity.class);
        if (velocityModule == null || !velocityModule.isActive())
            return;

        double hMultiplier = velocityModule.getHorizontalMultiplier();
        double vMultiplier = velocityModule.getVerticalMultiplier();
        Vec3d currentVelocity = player.getVelocity();

        // Vanilla sets the velocity from the packet. We override it.
        // If hMultiplier is 0, we zero out the velocity (cancel KB).
        player.setVelocity(
                currentVelocity.x * hMultiplier,
                currentVelocity.y * vMultiplier,
                currentVelocity.z * hMultiplier);
    }

    @Inject(method = "onExplosion", at = @At("HEAD"))
    private void onExplosionHead(ExplosionS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            matrix_explosionOldVelocity = mc.player.getVelocity();
        }
    }

    @Inject(method = "onExplosion", at = @At("TAIL"))
    private void onExplosionTail(ExplosionS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        if (player == null || matrix_explosionOldVelocity == null)
            return;

        Velocity velocityModule = Modules.get().get(Velocity.class);
        if (velocityModule == null || !velocityModule.isActive())
            return;

        double hMultiplier = velocityModule.getHorizontalMultiplier();
        double vMultiplier = velocityModule.getVerticalMultiplier();

        Vec3d newVelocity = player.getVelocity();
        // Calculate the velocity added by the explosion
        Vec3d addedVelocity = newVelocity.subtract(matrix_explosionOldVelocity);

        // Scale the added velocity
        double scaledX = addedVelocity.x * hMultiplier;
        double scaledY = addedVelocity.y * vMultiplier;
        double scaledZ = addedVelocity.z * hMultiplier;

        // Apply
        player.setVelocity(matrix_explosionOldVelocity.add(scaledX, scaledY, scaledZ));
    }
}
