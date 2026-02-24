package net.matrix.utils;

import net.minecraft.registry.Registries;
import net.minecraft.item.Item;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class WeaponUtil {
    public static List<String> getAllWeaponIds() {
        return Arrays.asList("Sword", "Axe", "Trident", "Mace", "Spear");
    }

    public static Set<String> getDefaultWeapons() {
        Set<String> defaults = new LinkedHashSet<>();
        defaults.add("Sword");
        defaults.add("Axe");
        defaults.add("Mace");
        return defaults;
    }

    public static String getWeaponCategory(Item item) {
        String id = Registries.ITEM.getId(item).toString().toLowerCase();

        if (id.contains("sword")) {
            return "Sword";
        } else if (id.contains("axe") && !id.contains("pickaxe")) {
            return "Axe";
        } else if (id.contains("trident")) {
            return "Trident";
        } else if (id.contains("mace")) {
            return "Mace";
        } else if (id.contains("spear")) {
            return "Spear";
        }

        return null;
    }
}
