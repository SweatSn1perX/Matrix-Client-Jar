package net.matrix.systems.modules;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;

import net.matrix.events.EventBus;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public abstract class Module {
    protected final MinecraftClient mc = MinecraftClient.getInstance();

    public final String name;
    public final String description;
    public final Category category;

    private int key = -1;
    private boolean active;

    // Settings list — modules override getSettings() to expose configurable options
    protected final List<Setting<?>> settings = new ArrayList<>();

    public Module(Category category, String name, String description) {
        this.category = category;
        this.name = name;
        this.description = description;
    }

    @SuppressWarnings("null")
    public void toggle() {
        if (!net.matrix.systems.auth.AuthManager.isLoggedIn())
            return;

        if (active) {
            active = false;
            // Only show toggle messages if ChatLogs is active
            if (mc.player != null) {
                if (shouldShowChatMessages()) {
                    mc.player.sendMessage(Text.literal(name + " disabled").formatted(Formatting.RED), false);
                }
            }
            onDeactivate();
            EventBus.get().unsubscribe(this);
        } else {
            active = true;
            if (mc.player != null) {
                if (shouldShowChatMessages()) {
                    mc.player.sendMessage(Text.literal(name + " enabled").formatted(Formatting.GREEN), false);
                }
            }
            onActivate();
            EventBus.get().subscribe(this);
        }
        net.matrix.systems.config.ConfigManager.markDirty();
    }

    /**
     * Silently set the active state without sending chat messages or triggering
     * callbacks.
     * Used by ConfigManager when loading configs.
     */
    public void setActive(boolean state) {
        if (state && !active) {
            active = true;
            onActivate();
            EventBus.get().subscribe(this);
        } else if (!state && active) {
            active = false;
            onDeactivate();
            EventBus.get().unsubscribe(this);
        }
    }

    private boolean shouldShowChatMessages() {
        return net.matrix.systems.Screens.showToggleNotifications.get();
    }

    public void onActivate() {
    }

    public void onDeactivate() {
    }

    public void onTick() {
    }

    public boolean isActive() {
        return active;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    public int getKey() {
        return key;
    }

    public void setKey(int key) {
        this.key = key;
        net.matrix.systems.config.ConfigManager.markDirty();
    }

    /**
     * Set keybind without marking config dirty.
     * Used by ConfigManager during config load.
     */
    public void setKeyInternal(int key) {
        this.key = key;
    }

    /**
     * Returns the list of configurable settings for this module.
     * Override in subclasses to add module-specific settings.
     */
    public List<Setting<?>> getSettings() {
        return settings;
    }

    public double getReachDistance() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getReachDistance'");
    }

    /**
     * Sends a rotation packet to the server to ensure the current client-side
     * yaw and pitch are synced. This helps prevent flags for out-of-order
     * rotation/action packets.
     */
    @SuppressWarnings("null")
    protected void syncRotation() {
        if (mc.player != null && mc.getNetworkHandler() != null) {
            syncRotation(mc.player.getYaw(), mc.player.getPitch());
        }
    }

    /**
     * Sends a rotation packet to the server with specified yaw and pitch.
     * Useful for "silent" rotations where the client camera doesn't move.
     */
    @SuppressWarnings("null")
    protected void syncRotation(float yaw, float pitch) {
        if (mc.player != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler()
                    .sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(
                            yaw,
                            pitch,
                            mc.player.isOnGround(),
                            mc.player.horizontalCollision));
        }
    }
}
