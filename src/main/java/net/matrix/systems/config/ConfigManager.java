package net.matrix.systems.config;

import com.google.gson.*;
import net.matrix.Matrix;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.Setting;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-user config manager.
 * Saves to: matrix/users/<username>/config.json
 * Each authenticated user gets their own isolated config folder.
 */
public class ConfigManager {
    private static final Path BASE_DIR = Path.of(Matrix.MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean dirty = false;
    private static long lastSave = 0;
    private static final long SAVE_INTERVAL = 5000; // 5 seconds debounce
    private static String activeUser = null;

    public static void markDirty() {
        dirty = true;
    }

    public static void onTick() {
        if (dirty && System.currentTimeMillis() - lastSave > SAVE_INTERVAL) {
            save();
            dirty = false;
            lastSave = System.currentTimeMillis();
        }
    }

    /**
     * Returns the config directory for the current user.
     * Creates the directory structure if it doesn't exist.
     */
    private static Path getUserDir() {
        String username = activeUser != null ? activeUser : "default";
        // Sanitize username for filesystem safety
        String safe = username.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Path userDir = BASE_DIR.resolve("users").resolve(safe);
        try {
            if (!Files.exists(userDir)) {
                Files.createDirectories(userDir);
            }
        } catch (IOException e) {
            Matrix.LOGGER.error("Failed to create user config dir: " + userDir, e);
        }
        return userDir;
    }

    private static Path getConfigFile() {
        return getUserDir().resolve("config.json");
    }

    /**
     * Called after successful login from LoginScreen.
     * Sets the active user and loads their config.
     * If no config exists, creates a default one with ChatLogs ON.
     */
    public static void loadForUser(String username) {
        activeUser = username;
        Path configFile = getConfigFile();

        if (!Files.exists(configFile)) {
            // First-time user: create default config
            Matrix.LOGGER.info("[Matrix Config] No config for '{}', creating defaults.", username);
            save();
        } else {
            load();
        }
    }

    public static void save() {
        if (activeUser == null)
            return; // Don't save if no user is logged in

        try {
            Path configFile = getConfigFile();

            JsonArray modulesArray = new JsonArray();

            for (Module module : Modules.get().getAll()) {
                JsonObject moduleJson = new JsonObject();
                moduleJson.addProperty("name", module.getName());
                moduleJson.addProperty("enabled", module.isActive());
                moduleJson.addProperty("key", module.getKey());

                JsonObject settingsJson = new JsonObject();
                for (Setting<?> setting : module.getSettings()) {
                    if (setting instanceof Setting.BooleanSetting) {
                        settingsJson.addProperty(setting.name, ((Setting.BooleanSetting) setting).get());
                    } else if (setting instanceof Setting.IntSetting) {
                        settingsJson.addProperty(setting.name, ((Setting.IntSetting) setting).get());
                    } else if (setting instanceof Setting.DoubleSetting) {
                        settingsJson.addProperty(setting.name, ((Setting.DoubleSetting) setting).get());
                    } else if (setting instanceof Setting.StringSetting) {
                        settingsJson.addProperty(setting.name, ((Setting.StringSetting) setting).get());
                    } else if (setting instanceof Setting.StringSetSetting) {
                        JsonArray array = new JsonArray();
                        for (String s : ((Setting.StringSetSetting) setting).get()) {
                            array.add(s);
                        }
                        settingsJson.add(setting.name, array);
                    }
                }
                moduleJson.add("settings", settingsJson);
                modulesArray.add(moduleJson);
            }

            // Save Global GUI Settings
            JsonObject guiSettingsJson = new JsonObject();
            guiSettingsJson.addProperty("name", "MatrixGUI");
            guiSettingsJson.addProperty("isGlobalGui", true);
            JsonObject guiConfigJson = new JsonObject();
            for (Setting<?> setting : net.matrix.systems.Screens.clickGuiSettings) {
                if (setting instanceof Setting.StringSetting) {
                    guiConfigJson.addProperty(setting.name, ((Setting.StringSetting) setting).get());
                } else if (setting instanceof Setting.BooleanSetting) {
                    guiConfigJson.addProperty(setting.name, ((Setting.BooleanSetting) setting).get());
                }
            }
            guiSettingsJson.add("settings", guiConfigJson);
            modulesArray.add(guiSettingsJson);

            Files.writeString(configFile, GSON.toJson(modulesArray), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);

            Matrix.LOGGER.info("[Matrix Config] Saved config for user '{}'", activeUser);
        } catch (IOException e) {
            Matrix.LOGGER.error("Failed to save config", e);
        }
    }

    public static void load() {
        Path configFile = getConfigFile();
        if (!Files.exists(configFile))
            return;

        try {
            JsonArray modulesArray = JsonParser.parseString(Files.readString(configFile)).getAsJsonArray();

            for (JsonElement element : modulesArray) {
                JsonObject moduleJson = element.getAsJsonObject();
                String name = moduleJson.get("name").getAsString();

                if (moduleJson.has("isGlobalGui") && moduleJson.get("isGlobalGui").getAsBoolean()) {
                    if (moduleJson.has("settings")) {
                        JsonObject settingsJson = moduleJson.getAsJsonObject("settings");
                        for (Setting<?> setting : net.matrix.systems.Screens.clickGuiSettings) {
                            if (!settingsJson.has(setting.name))
                                continue;
                            JsonElement settingElement = settingsJson.get(setting.name);
                            if (setting instanceof Setting.StringSetting) {
                                ((Setting.StringSetting) setting).set(settingElement.getAsString());
                            } else if (setting instanceof Setting.BooleanSetting) {
                                ((Setting.BooleanSetting) setting).set(settingElement.getAsBoolean());
                            }
                        }
                    }
                    net.matrix.systems.ChatLogManager.updateState();
                    continue;
                }

                Module module = Modules.get().getAll().stream()
                        .filter(m -> m.getName().equals(name))
                        .findFirst()
                        .orElse(null);

                if (module == null)
                    continue;

                // Use setActive() for silent state restore (no chat messages)
                if (moduleJson.has("enabled")) {
                    module.setActive(moduleJson.get("enabled").getAsBoolean());
                }

                if (moduleJson.has("key")) {
                    module.setKeyInternal(moduleJson.get("key").getAsInt());
                }

                if (moduleJson.has("settings")) {
                    JsonObject settingsJson = moduleJson.getAsJsonObject("settings");
                    for (Setting<?> setting : module.getSettings()) {
                        if (!settingsJson.has(setting.name))
                            continue;

                        JsonElement settingElement = settingsJson.get(setting.name);

                        if (setting instanceof Setting.BooleanSetting) {
                            ((Setting.BooleanSetting) setting).set(settingElement.getAsBoolean());
                        } else if (setting instanceof Setting.IntSetting) {
                            ((Setting.IntSetting) setting).set(settingElement.getAsInt());
                        } else if (setting instanceof Setting.DoubleSetting) {
                            ((Setting.DoubleSetting) setting).set(settingElement.getAsDouble());
                        } else if (setting instanceof Setting.StringSetting) {
                            ((Setting.StringSetting) setting).set(settingElement.getAsString());
                        } else if (setting instanceof Setting.StringSetSetting) {
                            Set<String> set = new java.util.LinkedHashSet<>();
                            for (JsonElement e : settingElement.getAsJsonArray()) {
                                set.add(e.getAsString());
                            }
                            ((Setting.StringSetSetting) setting).set(set);
                        }
                    }
                }
            }

            Matrix.LOGGER.info("[Matrix Config] Loaded config for user '{}'", activeUser);
        } catch (IOException e) {
            Matrix.LOGGER.error("Failed to load config", e);
        }
    }

    // ─── Named Config Management ─────────────────────────────

    /**
     * Lists all saved config names in the user's folder (excluding the default
     * config.json).
     */
    public static List<String> listConfigs() {
        List<String> names = new ArrayList<>();
        Path userDir = getUserDir();
        try {
            if (Files.exists(userDir)) {
                Files.list(userDir)
                        .filter(p -> p.toString().endsWith(".json")
                                && !p.getFileName().toString().equals("config.json"))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            names.add(name.substring(0, name.length() - 5)); // strip .json
                        });
            }
        } catch (IOException e) {
            Matrix.LOGGER.error("Failed to list configs", e);
        }
        java.util.Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * Saves current module state as a named config.
     */
    public static void saveAs(String name) {
        if (activeUser == null || name == null || name.isBlank())
            return;
        String safeName = name.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Path original = getConfigFile();
        Path target = getUserDir().resolve(safeName + ".json");
        try {
            // Save current state first
            save();
            // Copy the current config as the named one
            Files.copy(original, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Matrix.LOGGER.info("[Matrix Config] Saved config as '{}'", safeName);
        } catch (IOException e) {
            Matrix.LOGGER.error("Failed to save config as '{}'", safeName, e);
        }
    }

    /**
     * Loads a named config file (replaces current state).
     */
    public static void loadConfig(String name) {
        if (activeUser == null || name == null)
            return;
        String safeName = name.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Path source = getUserDir().resolve(safeName + ".json");
        Path target = getConfigFile();
        if (!Files.exists(source))
            return;
        try {
            // Copy the named config over the active config
            Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            // Reload
            load();
            Matrix.LOGGER.info("[Matrix Config] Loaded config '{}'", safeName);
        } catch (IOException e) {
            Matrix.LOGGER.error("Failed to load config '{}'", safeName, e);
        }
    }

    /**
     * Deletes a named config file.
     */
    public static void deleteConfig(String name) {
        if (activeUser == null || name == null)
            return;
        String safeName = name.replaceAll("[^a-zA-Z0-9_.-]", "_");
        Path target = getUserDir().resolve(safeName + ".json");
        try {
            Files.deleteIfExists(target);
            Matrix.LOGGER.info("[Matrix Config] Deleted config '{}'", safeName);
        } catch (IOException e) {
            Matrix.LOGGER.error("Failed to delete config '{}'", safeName, e);
        }
    }
}
