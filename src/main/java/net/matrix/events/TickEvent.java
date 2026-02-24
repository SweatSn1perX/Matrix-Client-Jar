package net.matrix.events;

/** Fired every client tick from the main game loop. */
public class TickEvent {
    public static final TickEvent INSTANCE = new TickEvent();
    private TickEvent() {}
}
