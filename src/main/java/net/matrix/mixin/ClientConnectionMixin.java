package net.matrix.mixin;

import net.matrix.events.EventBus;
import net.matrix.events.PacketEvent;
import net.matrix.systems.commands.CommandManager;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class ClientConnectionMixin {
    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        // Fire PacketEvent.Send through EventBus
        PacketEvent.Send event = new PacketEvent.Send(packet);
        EventBus.get().post(event);
        if (event.isCancelled()) {
            ci.cancel();
            return;
        }

        // Legacy: Command interception for chat packets
        if (packet instanceof ChatMessageC2SPacket chatPacket) {
            String message = chatPacket.chatMessage();
            if (CommandManager.handleCommand(message)) {
                ci.cancel();
            }
        }
    }
}
