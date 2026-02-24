package net.matrix.systems.modules.mace;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.matrix.mixin.PlayerInventoryAccessor;

import java.util.Random;

/**
 * KeyWindCharge — Throws a wind charge at your feet.
 *
 * Silent Mode: Instant server-side rotation (packets only).
 * Ghost Mode: LERP rotation over multiple ticks.
 */
public class KeyWindCharge extends Module {
    private final Random random = new Random();

    private final Setting.BooleanSetting silentMode = new Setting.BooleanSetting(
            "Silent Mode", "Instant server-side rotation (No LERP)", true);
    private final Setting.BooleanSetting jump = new Setting.BooleanSetting(
            "Jump", "Jumps instantly after throwing the wind charge", false);
    private final Setting.DoubleSetting smoothness = new Setting.DoubleSetting(
            "Smoothness", "LERP factor per tick (Ghost Mode Only)", 0.45, 0.1, 1.0);
    private final Setting.IntSetting reactionDelay = new Setting.IntSetting(
            "Reaction Delay", "Ticks before throw (Ghost mode)", 2, 0, 6);

    private enum Phase {
        IDLE, ROTATING_DOWN, FIRING, ROTATING_BACK, DONE
    }

    private Phase phase = Phase.IDLE;
    private float originalPitch;
    private float targetPitch;
    private int phaseDelay = 0;
    private float lastSentPitch;

    private static final float PITCH_THRESHOLD = 3.0f;

    public KeyWindCharge() {
        super(Category.MACE, "Key Wind Charge", "Silently throws a wind charge at your feet.");
        settings.add(silentMode);
        settings.add(jump);
        settings.add(smoothness);
        settings.add(reactionDelay);
    }

    @Override
    public void onActivate() {
        if (mc.player == null) {
            toggle();
            return;
        }

        originalPitch = mc.player.getPitch();
        lastSentPitch = originalPitch;
        targetPitch = 90.0f;

        phase = Phase.ROTATING_DOWN;
        phaseDelay = 0;
    }

    @Override
    public void onDeactivate() {
        phase = Phase.IDLE;
        phaseDelay = 0;
    }

    @SuppressWarnings("null")
    @Override
    public void onTick() {
        if (mc.player == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        switch (phase) {
            case ROTATING_DOWN -> {
                if (silentMode.get()) {
                    syncRotation(mc.player.getYaw(), targetPitch);
                    lastSentPitch = targetPitch;
                    phase = Phase.FIRING;
                    phaseDelay = 0;
                } else {
                    float factor = smoothness.get().floatValue() + (float) (random.nextGaussian() * 0.04);
                    factor = Math.max(0.1f, Math.min(0.92f, factor));

                    float nextPitch = lastSentPitch + (targetPitch - lastSentPitch) * factor;
                    mc.player.setPitch(nextPitch);
                    lastSentPitch = nextPitch;

                    if (Math.abs(nextPitch - targetPitch) < PITCH_THRESHOLD) {
                        syncRotation();
                        phaseDelay = (int) Math.round(reactionDelay.get() + random.nextGaussian() * 0.7);
                        phase = Phase.FIRING;
                    }
                }
            }

            case FIRING -> {
                if (phaseDelay > 0) {
                    if (silentMode.get()) {
                        syncRotation(mc.player.getYaw(), targetPitch);
                    }
                    phaseDelay--;
                    return;
                }

                int wcSlot = findWCSlot();
                if (wcSlot == -1) {
                    phase = Phase.DONE;
                    return;
                }

                @SuppressWarnings("null")
                int originalSlot = mc.player.getInventory().getSelectedSlot();

                setSlot(wcSlot);

                if (silentMode.get()) {
                    float savedPitch = mc.player.getPitch();
                    mc.player.setPitch(targetPitch);
                    syncRotation(mc.player.getYaw(), targetPitch);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.setPitch(savedPitch);
                    syncRotation(mc.player.getYaw(), savedPitch);
                } else {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }

                if (jump.get()) {
                    mc.player.jump();
                }

                setSlot(originalSlot);

                lastSentPitch = targetPitch;
                phase = Phase.ROTATING_BACK;
                phaseDelay = 0;
            }

            case ROTATING_BACK -> {
                if (silentMode.get()) {
                    syncRotation(mc.player.getYaw(), originalPitch);
                    phase = Phase.DONE;
                    return;
                }

                float factor = smoothness.get().floatValue() + (float) (random.nextGaussian() * 0.04);
                factor = Math.max(0.1f, Math.min(0.92f, factor));

                float nextPitch = lastSentPitch + (originalPitch - lastSentPitch) * factor;
                mc.player.setPitch(nextPitch);
                lastSentPitch = nextPitch;

                if (Math.abs(nextPitch - originalPitch) < PITCH_THRESHOLD) {
                    syncRotation();
                    phase = Phase.DONE;
                }
            }

            case DONE, IDLE -> toggle();
        }
    }

    @SuppressWarnings("null")
    private int findWCSlot() {
        if (mc.player == null)
            return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE)
                return i;
        }
        return -1;
    }

    @SuppressWarnings("null")
    private void setSlot(int targetSlot) {
        if (targetSlot < 0 || targetSlot > 8)
            return;
        if (mc.player != null && mc.getNetworkHandler() != null) {
            // CRITICAL FIX: Update client-side selectedSlot so interactItem uses the
            // correct item
            ((PlayerInventoryAccessor) mc.player.getInventory()).setSelectedSlot(targetSlot);
            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(targetSlot));
        }
    }
}
