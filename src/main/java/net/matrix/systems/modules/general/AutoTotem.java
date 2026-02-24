package net.matrix.systems.modules.general;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EntityStatuses;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.Random;

public class AutoTotem extends Module {

    private final Setting.DoubleSetting minDelay = new Setting.DoubleSetting("Min Delay (s)", "Minimum delay before swapping a totem", 0.1, 0.0, 5.0);
    private final Setting.DoubleSetting maxDelay = new Setting.DoubleSetting("Max Delay (s)", "Maximum delay before swapping a totem", 0.5, 0.0, 5.0);
    private final Setting.DoubleSetting forceEquipHealth = new Setting.DoubleSetting("Health Force Equip (HP)", "Force equips totem if health is below this, regardless of delay", 5.0, 0.0, 20.0);

    private final Random random = new Random();
    
    private boolean waitingForSwap = false;
    private long swapTimeMs = 0;

    public AutoTotem() {
        super(Category.GENERAL, "AutoTotem", "Automatically replaces totems in your offhand");
        settings.add(minDelay);
        settings.add(maxDelay);
        settings.add(forceEquipHealth);
    }

    @Override
    public void onActivate() {
        waitingForSwap = false;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.interactionManager == null) return;
        
        ItemStack offhandItem = mc.player.getOffHandStack();
        boolean hasTotemInOffhand = offhandItem.getItem() == Items.TOTEM_OF_UNDYING;
        
        // Critical force equip
        float health = mc.player.getHealth();
        if (health < forceEquipHealth.get() && !hasTotemInOffhand) {
            doSwap();
            return;
        }

        // Manage delayed swap
        if (!hasTotemInOffhand) {
            if (!waitingForSwap) {
                // Determine if we need to start waiting
                // Wait is started if the offhand is empty and we don't have a totem. Wait is triggered by EntityStatus 35 in event handler, but as a fallback:
                if (System.currentTimeMillis() - swapTimeMs > 5000) { // If it's been empty for a while with no event, queue a swap immediately if one exists
                     queueSwap();
                }
            } else {
                if (System.currentTimeMillis() >= swapTimeMs) {
                    doSwap();
                }
            }
        } else {
            waitingForSwap = false; // Reset if we have a totem
        }
    }

    public void onTotemPop() {
         if (!waitingForSwap) {
             queueSwap();
         }
    }

    private void queueSwap() {
        if (mc.player == null) return;
        // Check if we even have a totem to swap
        if (findTotemInInventory() == -1) return;

        double min = minDelay.get();
        double max = maxDelay.get();
        if (min > max) {
            double temp = min;
            min = max;
            max = temp;
        }

        double delayS;
        if (min == max) {
            delayS = min;
        } else {
            // Gaussian delay
            double val = random.nextGaussian();
            // clamp
            val = Math.max(-1.0, Math.min(1.0, val));
            // map -1 to 1 into min to max
            delayS = min + ((val + 1.0) / 2.0) * (max - min);
        }

        swapTimeMs = System.currentTimeMillis() + (long) (delayS * 1000.0);
        waitingForSwap = true;
    }

    private void doSwap() {
        waitingForSwap = false;
        if (mc.player == null || mc.interactionManager == null) return;

        int totemSlot = findTotemInInventory();
        if (totemSlot == -1) return;

        // Slot 45 is offhand.
        // The slot indices in interactManager are: 0-8 for hotbar, 9-35 for main inventory
        
        // Use standard inventory clicking to move the totem
        // 1. Pick up totem
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, totemSlot, 0, SlotActionType.PICKUP, mc.player);
        // 2. Put in offhand (slot 45)
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, 45, 0, SlotActionType.PICKUP, mc.player);
        // 3. Put whatever was in offhand back into the now-empty totem slot
        mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, totemSlot, 0, SlotActionType.PICKUP, mc.player);
    }

    private int findTotemInInventory() {
        for (int i = 9; i < 36; i++) { // Main inventory
            if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) return i;
        }
        for (int i = 0; i < 9; i++) { // Hotbar
            if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) return i + 36; // Hotbar slots in ScreenHandler are 36-44
        }
        return -1;
    }
}
