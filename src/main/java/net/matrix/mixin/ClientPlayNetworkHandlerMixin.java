package net.matrix.mixin;

import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.general.AutoTotem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onEntityStatus", at = @At("RETURN"))
    private void onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        // 35 is the status code for totem popping
        if (packet.getStatus() == 35) {
            Entity entity = packet.getEntity(mc.world);
            if (entity != null && entity.getId() == mc.player.getId()) {
                AutoTotem autoTotem = Modules.get().get(AutoTotem.class);
                if (autoTotem != null && autoTotem.isActive()) {
                    autoTotem.onTotemPop();
                }
            }
        }
    }
}
