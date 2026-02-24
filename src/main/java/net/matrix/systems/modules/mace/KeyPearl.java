package net.matrix.systems.modules.mace;

import net.matrix.mixin.PlayerInventoryAccessor;
import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;

import java.util.Random;

/**
 * KeyPearl — Silently swaps to an ender pearl and throws it where you're
 * looking.
 * 
 * Ghost Logic:
 * - Gaussian-randomized reaction delay before firing (simulates human
 * reaction time of pressing key → actually throwing).
 * - Randomized tick offset so the action never fires on the exact same
 * tick cadence.
 */
public class KeyPearl extends Module {
    private final Random random = new Random();

    private enum Phase {
        IDLE, WAITING, FIRE
    }

    private Phase phase = Phase.IDLE;
    private int reactionDelay = 0;

    public KeyPearl() {
        super(Category.MACE, "Key Pearl", "Silently throws an ender pearl where you look.");
    }

    @Override
    public void onActivate() {
        // Gaussian reaction delay: mean ~1.5 ticks, stddev 0.7 → usually 1-3 ticks
        reactionDelay = Math.max(1, (int) Math.round(random.nextGaussian() * 0.7 + 1.5));
        phase = Phase.WAITING;
    }

    @Override
    public void onDeactivate() {
        phase = Phase.IDLE;
        reactionDelay = 0;
    }

    @SuppressWarnings("null")
    @Override
    public void onTick() {
        if (mc.player == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        switch (phase) {
            case WAITING -> {
                if (reactionDelay > 0) {
                    reactionDelay--;
                    return;
                }
                phase = Phase.FIRE;
            }

            case FIRE -> {
                // Find ender pearl in hotbar (slots 0-8)
                int pearlSlot = -1;
                for (int i = 0; i < 9; i++) {
                    if (mc.player.getInventory().getStack(i).getItem() == Items.ENDER_PEARL) {
                        pearlSlot = i;
                        break;
                    }
                }

                if (pearlSlot == -1) {
                    toggle();
                    return;
                }

                int originalSlot = mc.player.getInventory().getSelectedSlot();

                // Silent swap to pearl slot (server-side and client-accessor)
                ((PlayerInventoryAccessor) mc.player.getInventory()).setSelectedSlot(pearlSlot);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(pearlSlot));

                // Use the item (throw the pearl)
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);

                // Silent swap back to original slot
                ((PlayerInventoryAccessor) mc.player.getInventory()).setSelectedSlot(originalSlot);
                mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));

                // Auto-disable
                toggle();
            }

            case IDLE -> {
                toggle();
            }
        }
    }
}
