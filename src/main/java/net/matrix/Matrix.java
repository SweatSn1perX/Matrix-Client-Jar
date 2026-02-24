package net.matrix;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.matrix.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Matrix implements ClientModInitializer {
    public static final String MOD_ID = "matrix";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static Matrix instance;
    private Modules modules;

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("Initializing Matrix Mod (Meteor Layout)...");

        // Send early startup ping to catch who is launching the client
        net.matrix.systems.auth.AuthManager.sendStartupPing();

        modules = new Modules();
        modules.init();

        // Config is loaded per-user after login (see LoginScreen)

        // Register client tick event
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null && client.world != null) {
                modules.onTick();
                net.matrix.systems.config.ConfigManager.onTick();
            }
        });

        // Save config on shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(net.matrix.systems.config.ConfigManager::save));

        LOGGER.info("Matrix Mod Initialized.");
    }

    public static Matrix getInstance() {
        return instance;
    }

    public Modules getModules() {
        return modules;
    }
}
