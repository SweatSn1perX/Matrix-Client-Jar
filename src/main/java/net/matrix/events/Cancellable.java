package net.matrix.events;

/**
 * Interface for events that can be cancelled. When cancelled,
 * the original action (e.g. sending a packet) is suppressed.
 */
public interface Cancellable {
    boolean isCancelled();
    void setCancelled(boolean cancelled);
}
