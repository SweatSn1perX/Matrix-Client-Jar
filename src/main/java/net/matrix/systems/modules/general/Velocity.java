package net.matrix.systems.modules.general;

import net.matrix.systems.modules.Category;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Setting;
import java.util.Random;

public class Velocity extends Module {
    private final Random random = new Random();

    // ── Configurable settings ──
    private final Setting.DoubleSetting horizontalMultiplier = new Setting.DoubleSetting(
            "Horizontal Multiplier", "Horizontal velocity reduction", 0.9, 0.0, 1.0);
    private final Setting.DoubleSetting verticalMultiplier = new Setting.DoubleSetting(
            "Vertical Multiplier", "Vertical velocity reduction", 1.0, 0.0, 1.0);
    private final Setting.DoubleSetting randomization = new Setting.DoubleSetting(
            "Randomization", "Gaussian variance (+/-)", 0.02, 0.0, 0.1);
    private final Setting.DoubleSetting chance = new Setting.DoubleSetting(
            "Chance", "Probability to engage", 0.95, 0.0, 1.0);

    public Velocity() {
        super(Category.GENERAL, "Velocity", "Reduces knockback with human-like inconsistency.");
        settings.add(horizontalMultiplier);
        settings.add(verticalMultiplier);
        settings.add(randomization);
        settings.add(chance);
    }

    public double getHorizontalMultiplier() {
        if (random.nextDouble() > chance.get()) {
            return 1.0; // Fail to activate (take full KB)
        }
        double base = horizontalMultiplier.get();
        double variance = randomization.get();
        double varied = base + (random.nextGaussian() * variance);

        return Math.max(0.0, Math.min(1.0, varied));
    }

    public double getVerticalMultiplier() {
        if (random.nextDouble() > chance.get()) {
            return 1.0;
        }
        double base = verticalMultiplier.get();
        double variance = randomization.get();
        double varied = base + (random.nextGaussian() * variance);

        return Math.max(0.0, Math.min(1.0, varied));
    }
}
