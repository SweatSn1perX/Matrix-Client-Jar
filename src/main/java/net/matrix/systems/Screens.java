package net.matrix.systems;

import net.matrix.gui.ClickGuiScreen;
import net.matrix.gui.ColorSettingsScreen;
import net.matrix.gui.LoginScreen;
import net.matrix.systems.modules.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public enum Screens {
    CLICK_GUI(() -> new ClickGuiScreen()),
    COLOR_SETTINGS(() -> new ColorSettingsScreen(null)),
    LOGIN(() -> new LoginScreen());

    private final Supplier<Screen> screenSupplier;

    // --- ClickGUI Global Color Settings ---
    public static net.matrix.utils.Color accentColor = new net.matrix.utils.Color(250, 128, 114); // #FA8072

    public static final Setting.BooleanSetting showToggleNotifications = new Setting.BooleanSetting(
            "Show Toggle Notifications", "Toggle chat messages for module binds", true);
    public static final Setting.BooleanSetting saveChatToFile = new Setting.BooleanSetting(
            "Save Chat to File", "Logs all incoming chat messages to matrix/users/<you>/chat.log", false);
    public static final Setting.StringSetting commandPrefix = new Setting.StringSetting(
            "Command Prefix", "Prefix used for chat commands", "'");

    public static final Setting.StringSetting sidePanelBg = new Setting.StringSetting(
            "Side Panel Background", "Hex code for the left panel", "#E4A0F7");
    public static final Setting.StringSetting mainPaneBg = new Setting.StringSetting(
            "Main Panel Background", "Hex code for the right panel", "#7852A9");
    public static final Setting.StringSetting accentColorHex = new Setting.StringSetting(
            "Tint Color", "Hex code for buttons and accents", "#FA8072");
    public static final Setting.StringSetting categoryHeaderBg = new Setting.StringSetting(
            "Category Header Color", "Hex code for the category slot/tile", "#9874D3");
    public static final Setting.StringSetting categoryTextHex = new Setting.StringSetting(
            "Category Text Color", "Hex code for category labels", "#FFFFFF");

    public static final List<Setting<?>> clickGuiSettings = new ArrayList<>();

    static {
        clickGuiSettings.add(sidePanelBg.onChanged(v -> updateColors()));
        clickGuiSettings.add(mainPaneBg.onChanged(v -> updateColors()));
        clickGuiSettings.add(accentColorHex.onChanged(v -> updateColors()));
        clickGuiSettings.add(categoryHeaderBg.onChanged(v -> updateColors()));
        clickGuiSettings.add(categoryTextHex.onChanged(v -> updateColors()));
        clickGuiSettings.add(showToggleNotifications);
        clickGuiSettings.add(saveChatToFile.onChanged(v -> ChatLogManager.updateState()));
        clickGuiSettings.add(commandPrefix);
        updateColors();
    }

    public static void updateColors() {
        try {
            accentColor = net.matrix.utils.Color.fromHex(accentColorHex.get());
        } catch (Exception ignored) {
        }
    }

    public static void resetDefaults() {
        sidePanelBg.set("#E4A0F7");
        mainPaneBg.set("#7852A9");
        accentColorHex.set("#FA8072");
        categoryHeaderBg.set("#9874D3");
        categoryTextHex.set("#FFFFFF");
        showToggleNotifications.set(true);
        saveChatToFile.set(false);
        commandPrefix.set("'");
        updateColors();
    }
    // --------------------------------------

    Screens(Supplier<Screen> screenSupplier) {
        this.screenSupplier = screenSupplier;
    }

    public void open() {
        MinecraftClient.getInstance().setScreen(screenSupplier.get());
    }

    public Screen getScreen() {
        return screenSupplier.get();
    }
}
