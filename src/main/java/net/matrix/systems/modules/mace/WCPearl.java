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
 * WCPearl — Throws a pearl straight up, then throws a wind charge upward
 * after a short delay. Auto-disables after both actions complete.
 *
 * Silent Mode: Instant server-side rotation (packets only).
 * Ghost Mode: LERP rotation over multiple ticks.
 */
public class WCPearl extends Module {
    private final Random random = new Random();

    private final Setting.BooleanSetting silentMode = new Setting.BooleanSetting(
            "Silent Mode", "Instant rotate + swap (no LERP)", true);
    private final Setting.IntSetting delayMin = new Setting.IntSetting(
            "Delay Min", "Min ticks between pearl and wind charge", 0, 0, 4);
    private final Setting.IntSetting delayMax = new Setting.IntSetting(
            "Delay Max", "Max ticks between pearl and wind charge", 1, 0, 4);
    private final Setting.DoubleSetting smoothness = new Setting.DoubleSetting(
            "Smoothness", "LERP factor per tick (Ghost Mode Only)", 0.40, 0.1, 1.0);

    private enum Phase {
        IDLE, LERP_UP, FIRE_PEARL, DELAY, FIRE_WC, LERP_BACK, DONE
    }

    private Phase phase = Phase.IDLE;
    private int ticksRemaining = 0;
    private float originalPitch;
    private float lastSentPitch;

    private static final float PITCH_THRESHOLD = 4.0f;

    public WCPearl() {
        super(Category.MACE, "WC Pearl", "Silently throws pearl up, then wind charge after delay.");
        settings.add(silentMode);
        settings.add(delayMin);
        settings.add(delayMax);
        settings.add(smoothness);
    }

    @SuppressWarnings("null")
    @Override
    public void onActivate() {
        if (mc.player == null) {
            toggle();
            return;
        }

        int pearlSlot = findHotbarItem(Items.ENDER_PEARL);
        int wcSlot = findHotbarItem(Items.WIND_CHARGE);

        if (pearlSlot == -1 || wcSlot == -1) {
            toggle();
            return;
        }

        originalPitch = mc.player.getPitch();
        lastSentPitch = originalPitch;
        phase = Phase.LERP_UP;
    }

    @Override
    public void onDeactivate() {
        phase = Phase.IDLE;
        ticksRemaining = 0;
    }

    @SuppressWarnings("null")
    @Override
    public void onTick() {
        if (mc.player == null || mc.interactionManager == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        switch (phase) {
            case LERP_UP -> {
                float target = -90.0f;
                if (silentMode.get()) {
                    syncRotation(mc.player.getYaw(), target);
                    lastSentPitch = target;
                    phase = Phase.FIRE_PEARL;
                    ticksRemaining = 0;
                } else {
                    float factor = smoothness.get().floatValue() + (float) (random.nextGaussian() * 0.04);
                    factor = Math.max(0.1f, Math.min(0.8f, factor));

                    float nextPitch = lastSentPitch + (target - lastSentPitch) * factor;
                    mc.player.setPitch(nextPitch);
                    lastSentPitch = nextPitch;

                    if (Math.abs(nextPitch - target) < PITCH_THRESHOLD) {
                        mc.player.setPitch(target);
                        syncRotation();
                        ticksRemaining = (int) Math.round(random.nextGaussian() * 0.5 + 1.0);
                        phase = Phase.FIRE_PEARL;
                    }
                }
            }

            case FIRE_PEARL -> {
                if (ticksRemaining > 0) {
                    if (silentMode.get()) {
                        syncRotation(mc.player.getYaw(), -90.0f);
                    }
                    ticksRemaining--;
                    return;
                }

                int pearlSlot = findHotbarItem(Items.ENDER_PEARL);
                if (pearlSlot == -1) {
                    phase = Phase.DONE;
                    return;
                }

                setSlot(pearlSlot);

                if (silentMode.get()) {
                    // Temporarily set client pitch so interactItem fires upward,
                    // then restore immediately — rendering is between ticks so
                    // the player never sees the rotation on screen.
                    float savedPitch = mc.player.getPitch();
                    mc.player.setPitch(-90.0f);
                    syncRotation(mc.player.getYaw(), -90.0f);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.setPitch(savedPitch);
                } else {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }

                int min = delayMin.get();
                int max = delayMax.get();
                if (max < min)
                    max = min;
                ticksRemaining = min + random.nextInt(max - min + 1);
                phase = Phase.DELAY;
            }

            case DELAY -> {
                if (ticksRemaining > 0) {
                    ticksRemaining--;
                    return;
                }
                phase = Phase.FIRE_WC;
            }

            case FIRE_WC -> {
                int wcSlot = findHotbarItem(Items.WIND_CHARGE);
                if (wcSlot == -1) {
                    phase = Phase.DONE;
                    return;
                }

                setSlot(wcSlot);

                if (silentMode.get()) {
                    float savedPitch = mc.player.getPitch();
                    mc.player.setPitch(-90.0f);
                    syncRotation(mc.player.getYaw(), -90.0f);
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.setPitch(savedPitch);
                } else {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                }

                ticksRemaining = Math.max(1, (int) Math.round(random.nextGaussian() * 0.4 + 1.0));
                lastSentPitch = -90.0f;
                phase = Phase.LERP_BACK;
            }

            case LERP_BACK -> {
                if (silentMode.get()) {
                    syncRotation(mc.player.getYaw(), originalPitch);
                    phase = Phase.DONE;
                    return;
                }

                if (ticksRemaining > 0) {
                    ticksRemaining--;
                    return;
                }

                float target = originalPitch;
                float factor = smoothness.get().floatValue() + (float) (random.nextGaussian() * 0.04);
                factor = Math.max(0.1f, Math.min(0.8f, factor));

                float nextPitch = lastSentPitch + (target - lastSentPitch) * factor;
                mc.player.setPitch(nextPitch);
                lastSentPitch = nextPitch;

                if (Math.abs(nextPitch - target) < PITCH_THRESHOLD) {
                    mc.player.setPitch(target);
                    syncRotation();
                    phase = Phase.DONE;
                }
            }

            case DONE, IDLE -> toggle();
        }
    }

    @SuppressWarnings("null")
    private int findHotbarItem(net.minecraft.item.Item item) {
        if (mc.player == null)
            return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item)
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
