package net.matrix.systems.modules.general;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.Setting;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class SelfDestruct extends Module {
    private final Setting.StringSetting ticks = new Setting.StringSetting("Gameticks",
            "Time in ticks before restoration", "0");
    private final Setting.StringSetting seconds = new Setting.StringSetting("Seconds",
            "Time in seconds before restoration", "0");
    private final Setting.StringSetting minutes = new Setting.StringSetting("Minutes",
            "Time in minutes before restoration", "0");

    private int remainingTicks = -1;

    public SelfDestruct() {
        super(Category.GENERAL, "Self Destruct",
                "Disables all modules and blocks keybinds for a specified duration.");
        settings.add(ticks);
        settings.add(seconds);
        settings.add(minutes);
    }

    @Override
    public void onActivate() {
        int t = 0;
        try {
            t += Integer.parseInt(ticks.get());
        } catch (Exception ignored) {
        }
        try {
            t += Integer.parseInt(seconds.get()) * 20;
        } catch (Exception ignored) {
        }
        try {
            t += Integer.parseInt(minutes.get()) * 1200;
        } catch (Exception ignored) {
        }

        if (t <= 0) {
            if (mc.player != null) {
                mc.player.sendMessage(
                        Text.literal("Error: Settings must specify a duration > 0 ticks.").formatted(Formatting.RED),
                        false);
            }
            toggle();
            return;
        }

        remainingTicks = t;

        // Force disable all other modules once
        for (Module m : Modules.get().getAll()) {
            if (m != this && m.isActive()) {
                m.toggle();
            }
        }

        if (mc.currentScreen instanceof net.matrix.gui.ClickGuiScreen) {
            mc.setScreen(null);
        }

        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("Self Destruct Activated. All functions locked for " + t + " ticks.")
                    .formatted(Formatting.GOLD), false);
        }
    }

    @Override
    public void onDeactivate() {
        if (remainingTicks > 0) {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("Self Destruct cancelled manually. Modules restored.")
                        .formatted(Formatting.AQUA), false);
            }
        }
        remainingTicks = -1;
    }

    @Override
    public void onTick() {
        if (remainingTicks > 0) {
            remainingTicks--;

            // Continuous lockdown: ensure no other module can be enabled
            for (Module m : Modules.get().getAll()) {
                if (m != this && m.isActive()) {
                    m.toggle();
                }
            }
        } else if (remainingTicks == 0) {
            remainingTicks = -1;
            toggle(); // This calls onDeactivate
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("Self Destruct expired. Keybinds and modules restored.")
                        .formatted(Formatting.GREEN), false);
            }
        }
    }

    public boolean isLocking() {
        return isActive() && remainingTicks > 0;
    }
}
