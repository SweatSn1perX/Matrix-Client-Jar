package net.matrix.events;

/** Fired when a keyboard key is pressed or released. */
public class KeyEvent {
    public final int key;
    public final int action;

    public KeyEvent(int key, int action) {
        this.key = key;
        this.action = action;
    }
}
