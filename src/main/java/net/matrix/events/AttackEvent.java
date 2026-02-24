package net.matrix.events;

import net.minecraft.entity.Entity;

/** Fired when the player attacks (left-clicks). */
public class AttackEvent {
    public final Entity target;

    public AttackEvent(Entity target) {
        this.target = target;
    }
}
