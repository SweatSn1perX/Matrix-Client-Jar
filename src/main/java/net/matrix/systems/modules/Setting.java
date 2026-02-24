package net.matrix.systems.modules;

import net.matrix.systems.config.ConfigManager;
import java.util.*;

import java.util.function.Consumer;

/**
 * Base setting class for module-configurable options.
 * Inspired by Meteor's Settings system.
 */
public abstract class Setting<T> {
    public final String name;
    public final String description;
    protected T value;
    protected final T defaultValue;
    private Consumer<T> onChanged;

    public Setting(String name, String description, T defaultValue) {
        this.name = name;
        this.description = description;
        this.value = defaultValue;
        this.defaultValue = defaultValue;
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
        if (onChanged != null)
            onChanged.accept(value);
        ConfigManager.markDirty();
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public void reset() {
        set(defaultValue);
    }

    public Setting<T> onChanged(Consumer<T> onChanged) {
        this.onChanged = onChanged;
        return this;
    }

    // ── Concrete setting types ──────────────────────────────

    public static class BooleanSetting extends Setting<Boolean> {
        public BooleanSetting(String name, String description, boolean defaultValue) {
            super(name, description, defaultValue);
        }

        public void toggle() {
            set(!get());
        }
    }

    public static class IntSetting extends Setting<Integer> {
        public final int min, max;

        public IntSetting(String name, String description, int defaultValue, int min, int max) {
            super(name, description, defaultValue);
            this.min = min;
            this.max = max;
        }
    }

    public static class DoubleSetting extends Setting<Double> {
        public final double min, max;

        public DoubleSetting(String name, String description, double defaultValue, double min, double max) {
            super(name, description, defaultValue);
            this.min = min;
            this.max = max;
        }
    }

    /**
     * A setting that stores a set of enabled strings from a predefined list of
     * options.
     * Used for entity targeting — each option is an entity type identifier.
     */
    public static class StringSetSetting extends Setting<Set<String>> {
        public final List<String> allOptions;

        public StringSetSetting(String name, String description, List<String> allOptions, Set<String> defaultEnabled) {
            super(name, description, new LinkedHashSet<>(defaultEnabled));
            this.allOptions = Collections.unmodifiableList(new ArrayList<>(allOptions));
        }

        public boolean isEnabled(String option) {
            return get().contains(option);
        }

        public void toggle(String option) {
            Set<String> current = get();
            if (current.contains(option)) {
                current.remove(option);
            } else {
                current.add(option);
            }
            set(current);
        }

        public List<String> getSortedOptions() {
            List<String> sorted = new ArrayList<>(allOptions);
            Set<String> enabled = get();
            sorted.sort((a, b) -> {
                boolean aEnabled = enabled.contains(a);
                boolean bEnabled = enabled.contains(b);
                if (aEnabled && !bEnabled)
                    return -1;
                if (!aEnabled && bEnabled)
                    return 1;
                return a.compareToIgnoreCase(b);
            });
            return sorted;
        }
    }

    public static class StringSetting extends Setting<String> {
        public StringSetting(String name, String description, String defaultValue) {
            super(name, description, defaultValue);
        }
    }

    public static class ColorSetting extends Setting<Integer> {
        public ColorSetting(String name, String description, int defaultColor) {
            super(name, description, defaultColor);
        }
        
        public int getRed() { return (get() >> 16) & 0xFF; }
        public int getGreen() { return (get() >> 8) & 0xFF; }
        public int getBlue() { return get() & 0xFF; }
        public int getAlpha() { return (get() >> 24) & 0xFF; }
    }
}
