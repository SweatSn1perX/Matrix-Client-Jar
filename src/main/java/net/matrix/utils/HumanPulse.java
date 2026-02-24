package net.matrix.utils;

import java.util.Random;

/**
 * HumanPulse — Shared humanization engine for Ghost-level combat modules.
 * Provides Bézier smoothing, acceleration curves, and CPS drop patterns
 * that mimic real human motor behavior.
 */
public class HumanPulse {
    private static final Random random = new Random();

    // ── Burst/Fatigue state for CPS drop patterns ──
    private static int burstTicksRemaining = 0;
    private static boolean inBurst = true;

    /**
     * Cubic Bézier ease-in-out (S-curve).
     * Input t: 0.0 → 1.0 (progress from start to target)
     * Output: slow at edges, fast in the middle — like a human wrist flick.
     *
     * The curve: 3t² - 2t³ (Hermite smoothstep)
     */
    public static double bezierEase(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Attempt at a more aggressive S-curve with steeper middle.
     * Ken Perlin's improved smoothstep: 6t⁵ - 15t⁴ + 10t³
     */
    public static double perlinEase(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    /**
     * Acceleration Curve — The core of ghost-level aim.
     *
     * Given the angle delta (how far the crosshair is from the target)
     * and a maximum speed factor, returns the ideal rotation speed:
     *
     * - Far away (delta > 40°): SLOW (human reaction delay, initial acquisition)
     * - Mid range (10-40°): FAST (the "flick" — confident correction)
     * - Close (delta < 10°): SLOW (fine adjustment, micro-corrections)
     *
     * This is what separates ghost clients from blatant ones.
     *
     * @param angleDelta Absolute angle difference in degrees
     * @param maxSpeed   The module's configured speed factor (0.0 - 1.0)
     * @return Adjusted speed factor for this tick
     */
    public static float accelerationCurve(float angleDelta, float maxSpeed) {
        float absDelta = Math.abs(angleDelta);

        // Normalize to a 0-1 range (0 = on target, 1 = far away)
        // We use 90° as the "max meaningful distance"
        float normalized = Math.min(absDelta / 90.0f, 1.0f);

        // The S-curve: slow at both extremes, fast in the middle
        // We invert because normalized=0 means "on target" (should be slow)
        // and normalized=1 means "far away" (should also be slow at the start)
        float curve;
        if (normalized < 0.15f) {
            // Close to target: fine adjustment (slow down significantly)
            curve = normalized / 0.15f * 0.4f; // Ramps up to 0.4
        } else if (normalized < 0.6f) {
            // Mid range: the flick zone (full speed)
            curve = 0.4f + (normalized - 0.15f) / 0.45f * 0.6f; // 0.4 → 1.0
        } else {
            // Far away: initial acquisition (medium speed, ramping down)
            curve = 1.0f - (normalized - 0.6f) / 0.4f * 0.3f; // 1.0 → 0.7
        }

        // Apply Gaussian micro-jitter (±5%) to prevent robotic consistency
        float jitter = 1.0f + (float) (random.nextGaussian() * 0.05);

        return maxSpeed * curve * jitter;
    }

    /**
     * CPS Drop Pattern — Simulates the human inability to maintain perfect CPS.
     *
     * Instead of a steady delay, this creates a "burst/fatigue" cycle:
     * - BURST phase (3-8 ticks): Clicks are fast (above base CPS)
     * - FATIGUE phase (2-5 ticks): Clicks slow down briefly (below base CPS)
     *
     * The result: CPS fluctuates naturally between e.g. 9-14 instead of a
     * robotic 12.0 every single tick.
     *
     * @param baseDelayTicks The module's configured attack delay in ticks
     * @return Randomized delay for the next click
     */
    public static int nextClickDelay(double baseDelayTicks) {
        // Manage burst/fatigue cycle
        if (burstTicksRemaining <= 0) {
            inBurst = !inBurst;
            if (inBurst) {
                burstTicksRemaining = 3 + random.nextInt(6); // Burst: 3-8 ticks
            } else {
                burstTicksRemaining = 2 + random.nextInt(4); // Fatigue: 2-5 ticks
            }
        }
        burstTicksRemaining--;

        double modifier;
        if (inBurst) {
            // Burst: clicks are 10-25% faster
            modifier = 0.75 + random.nextDouble() * 0.15; // 0.75 to 0.90
        } else {
            // Fatigue: clicks are 15-35% slower
            modifier = 1.15 + random.nextDouble() * 0.20; // 1.15 to 1.35
        }

        // Apply Gaussian noise on top (±10%)
        modifier += random.nextGaussian() * 0.10;
        modifier = Math.max(0.5, Math.min(2.0, modifier)); // Safety clamp

        int delay = (int) Math.round(baseDelayTicks * modifier);
        return Math.max(1, delay); // Minimum 1 tick
    }
}
