package net.matrix.systems.commands;

import net.matrix.systems.Screens;
import net.matrix.systems.config.ConfigManager;
import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class CommandManager {
    
    public static boolean handleCommand(String message) {
        String prefix = Screens.commandPrefix.get();
        if (!message.startsWith(prefix)) {
            return false;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return true; // Consume if prefix matched but player null

        String commandLine = message.substring(prefix.length()).trim();
        if (commandLine.isEmpty()) return true;

        String[] args = commandLine.split(" ");
        String command = args[0].toLowerCase();

        switch (command) {
            case "bind":
                handleBindCommand(args, mc);
                break;
            case "config":
                handleConfigCommand(args, mc);
                break;
            case "self_destruct":
            case "selfdestruct":
                handleSelfDestruct(mc);
                break;
            default:
                sendMessage(mc, Formatting.RED + "Unknown command: " + command);
                break;
        }

        return true; // We intercepted and handled the command
    }

    private static void handleBindCommand(String[] args, MinecraftClient mc) {
        if (args.length < 3) {
            sendMessage(mc, Formatting.RED + "Usage: " + Screens.commandPrefix.get() + "bind <module> <key>");
            sendMessage(mc, Formatting.GRAY + "Example: " + Screens.commandPrefix.get() + "bind Mace Visualizer V");
            return;
        }

        // Last arg is always the key; everything between "bind" and the key is the module name
        String keyName = args[args.length - 1].toUpperCase();
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 1; i < args.length - 1; i++) {
            if (i > 1) nameBuilder.append(" ");
            nameBuilder.append(args[i]);
        }
        String moduleName = nameBuilder.toString();

        // Strip spaces for flexible matching (e.g. "macevisualizer" matches "Mace Visualizer")
        String moduleNameStripped = moduleName.replaceAll("\\s+", "").toLowerCase();

        Module module = null;
        for (Module m : Modules.get().getAll()) {
            if (m.getName().equalsIgnoreCase(moduleName)
                    || m.getName().replaceAll("\\s+", "").equalsIgnoreCase(moduleNameStripped)) {
                module = m;
                break;
            }
        }
        if (module == null) {
            sendMessage(mc, Formatting.RED + "Module not found: " + moduleName);
            // List similar matches as hints
            for (Module m : Modules.get().getAll()) {
                if (m.getName().toLowerCase().contains(moduleNameStripped)
                        || m.getName().replaceAll("\\s+", "").toLowerCase().contains(moduleNameStripped)) {
                    sendMessage(mc, Formatting.GRAY + "  Did you mean: " + Formatting.YELLOW + m.getName());
                }
            }
            return;
        }

        if (keyName.equalsIgnoreCase("NONE") || keyName.equalsIgnoreCase("UNBIND")) {
            module.setKey(InputUtil.UNKNOWN_KEY.getCode());
            sendMessage(mc, Formatting.GREEN + "Unbound " + module.getName());
            return;
        }

        try {
            InputUtil.Key key = InputUtil.fromTranslationKey("key.keyboard." + keyName.toLowerCase());
            
            if (key == InputUtil.UNKNOWN_KEY) {
                 sendMessage(mc, Formatting.RED + "Invalid key: " + keyName);
                 return;
            }

            module.setKey(key.getCode());
            sendMessage(mc, Formatting.GREEN + module.getName() + " has been set to " + key.getLocalizedText().getString());
        } catch (Exception e) {
            sendMessage(mc, Formatting.RED + "Error binding key: " + keyName);
        }
    }

    private static void handleConfigCommand(String[] args, MinecraftClient mc) {
        if (args.length < 3) {
            sendMessage(mc, Formatting.RED + "Usage: " + Screens.commandPrefix.get() + "config <load|save|update> <name>");
            return;
        }

        String action = args[1].toLowerCase();
        String profileName = args[2];

        switch (action) {
            case "load":
                ConfigManager.loadConfig(profileName);
                sendMessage(mc, Formatting.GREEN + "Loaded config profile: " + profileName);
                break;
            case "save":
            case "update": 
                ConfigManager.saveAs(profileName);
                sendMessage(mc, Formatting.GREEN + "Saved config profile: " + profileName);
                break;
            default:
                sendMessage(mc, Formatting.RED + "Unknown config action. Use load, save, or update.");
                break;
        }
    }

    private static void handleSelfDestruct(MinecraftClient mc) {
        sendMessage(mc, Formatting.YELLOW + "Initiating Self Destruct...");
        
        // Disable all modules
        for (Module module : Modules.get().getAll()) {
            if (module.isActive()) {
                module.toggle(); 
            }
            // Clear all binds
            module.setKey(InputUtil.UNKNOWN_KEY.getCode());
        }
        
        ConfigManager.markDirty(); 
        
        sendMessage(mc, Formatting.RED + "Self destruct complete. Modules disabled and unbound.");
    }

    private static void sendMessage(MinecraftClient mc, String text) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(Formatting.DARK_PURPLE + "[Matrix] " + Formatting.RESET + text), false);
        }
    }
}
