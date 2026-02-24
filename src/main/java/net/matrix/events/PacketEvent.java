package net.matrix.events;

import net.minecraft.network.packet.Packet;

/**
 * Packet events for intercepting network traffic.
 * Both Send and Receive are cancellable.
 */
public class PacketEvent {

    /** Fired before a packet is sent to the server. */
    public static class Send implements Cancellable {
        public final Packet<?> packet;
        private boolean cancelled;

        public Send(Packet<?> packet) {
            this.packet = packet;
        }

        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }

    /** Fired when a packet is received from the server. */
    public static class Receive implements Cancellable {
        public final Packet<?> packet;
        private boolean cancelled;

        public Receive(Packet<?> packet) {
            this.packet = packet;
        }

        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    }
}
