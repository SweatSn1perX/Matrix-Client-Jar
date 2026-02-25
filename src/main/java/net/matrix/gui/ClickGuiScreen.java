package net.matrix.gui;

import net.matrix.systems.modules.Module;
import net.matrix.systems.modules.Modules;
import net.matrix.systems.modules.Setting;
import net.matrix.systems.Screens;

import net.matrix.systems.config.ConfigManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Premium two-pane Click GUI.
 * Left: scrollable module list (light purple) with search bar.
 * Right: settings panel (dark purple) with sliders, text input, keybind, entity
 * targeting.
 */
public class ClickGuiScreen extends Screen {

    // ── Layout constants ──
    private static final int PANEL_W = 460;
    private static final int PANEL_H = 310;
    private static final int SIDEBAR_W = 160;
    private static final int MODULE_ROW_H = 20;
    private static final int SEARCH_BAR_H = 22;
    private static final int BORDER_THICKNESS = 2;

    // ── Tab system ──
    public enum Tab {
        MODULES("Modules"), CONFIGURATIONS("Configurations"), SETTINGS("Settings");

        public final String name;

        Tab(String name) {
            this.name = name;
        }
    }

    private static final int TAB_H = 22;
    private Tab activeTab = Tab.MODULES;

    // ── Config tab state ──
    private java.util.List<String> savedConfigs = new java.util.ArrayList<>();
    private String newConfigName = "";
    private boolean configNameFocused = false;
    private double configScroll = 0;
    private String configStatusMsg = "";
    private long configStatusTime = 0;

    // ── Settings tab state ──
    private int settingsTabEditIndex = -1; // which color row is being edited (-1 = none)
    private String settingsTabEditText = "";
    private double settingsTabScroll = 0;

    // ── Colors (Dynamic) ──
    private int ACCENT;
    private int ACCENT_DIM;
    private int ACCENT_SUBTLE;
    private int SIDEBAR_BG;
    private int SIDEBAR_HOVER;
    private int SIDEBAR_SEL;
    private int SETTINGS_BG;
    private int CAT_HEADER_BG;
    private int CAT_TEXT_COLOR;
    private int SEARCH_BG;
    private int TEXT_COLOR;
    private static final int TEXT_WHITE = 0xFFFFFFFF;
    private static final int TEXT_DARK = 0xFF3A1A4A;
    private static final int CHECKBOX_OFF = 0xFF503070;
    private static final int SLIDER_TRACK = 0xFF503070;
    private int BTN_COLOR;
    private static final int ENTITY_ROW_BG = 0xFF654B8A;
    private static final int ENTITY_ROW_ALT = 0xFF5E4580;

    // ── State ──
    private List<Module> allModules;
    private List<Module> filteredModules;
    private Module selectedModule;
    private double sidebarScroll = 0;
    private double settingsScroll = 0;
    private boolean listeningForKey = false;

    // ── Toggle switch animation state ──
    private final java.util.Map<Module, Float> switchAnimations = new java.util.HashMap<>();
    private static final float SWITCH_ANIM_SPEED = 0.15f;
    private int draggingSlider = -1;

    // Category expansion state
    private final java.util.Map<net.matrix.systems.modules.Category, Boolean> categoryExpanded = new java.util.HashMap<>();
    private final List<net.matrix.systems.modules.Category> allCategories = new ArrayList<>();

    // Search
    private String searchQuery = "";
    private boolean searchFocused = false;

    // Text input for sliders
    private int editingSettingIndex = -1;
    private String editingText = "";

    // Entity targeting scroll (per setting)
    private Setting<?> currentOpenDropdown = null;
    private final java.util.Map<Setting<?>, Double> dropdownScrolls = new java.util.HashMap<>();

    // Entity search bar
    private String entitySearchQuery = "";
    private boolean entitySearchFocused = false;

    // Settings Scrollbar
    private boolean draggingSettingsScrollbar = false;
    private static final int SETTINGS_SCROLLBAR_W = 4;

    // ── Cached panel coordinates ──
    private int panelX, panelY;

    public ClickGuiScreen() {
        super(Text.of("GUI"));
    }

    @Override
    protected void init() {
        super.init();
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;

        // Initialize colors from GUI module settings

        SIDEBAR_BG = net.matrix.utils.Color.hexToPacked(Screens.sidePanelBg.get());
        SETTINGS_BG = net.matrix.utils.Color.hexToPacked(Screens.mainPaneBg.get());
        ACCENT = net.matrix.utils.Color.hexToPacked(Screens.accentColorHex.get());
        CAT_HEADER_BG = net.matrix.utils.Color.hexToPacked(Screens.categoryHeaderBg.get());
        CAT_TEXT_COLOR = net.matrix.utils.Color.hexToPacked(Screens.categoryTextHex.get());

        // Derive related colors
        SIDEBAR_HOVER = darken(SIDEBAR_BG, 0.1);
        SIDEBAR_SEL = darken(SIDEBAR_BG, 0.2);
        SEARCH_BG = darken(SETTINGS_BG, 0.2);
        ACCENT_DIM = (ACCENT & 0x00FFFFFF) | 0x99000000;
        ACCENT_SUBTLE = (ACCENT & 0x00FFFFFF) | 0x33000000;
        BTN_COLOR = ACCENT;
        TEXT_COLOR = isLight(SIDEBAR_BG) ? TEXT_DARK : TEXT_WHITE;

        allModules = new ArrayList<>(Modules.get().getAll());

        allModules.sort(Comparator.comparing(Module::getName, String.CASE_INSENSITIVE_ORDER));

        // Initialize categories
        allCategories.clear();
        // Add GENERAL first as requested
        allCategories.add(net.matrix.systems.modules.Category.GENERAL);
        allCategories.add(net.matrix.systems.modules.Category.MACE);
        allCategories.add(net.matrix.systems.modules.Category.VISUAL);

        // Add other categories if they have modules
        for (Module m : allModules) {
            net.matrix.systems.modules.Category c = m.getCategory();
            if (!allCategories.contains(c)) {
                allCategories.add(c);
            }
        }

        // Default to expanded
        if (categoryExpanded.isEmpty()) {
            for (net.matrix.systems.modules.Category c : allCategories) {
                categoryExpanded.put(c, true);
            }
        }

        rebuildFilteredModules();

        if (selectedModule == null && !filteredModules.isEmpty()) {
            selectedModule = filteredModules.get(0);
        }
    }

    private void rebuildFilteredModules() {
        if (searchQuery.isEmpty()) {
            filteredModules = new ArrayList<>(allModules);
        } else {
            String query = searchQuery.toLowerCase();
            filteredModules = new ArrayList<>();
            for (Module m : allModules) {
                if (m.getName().toLowerCase().contains(query) || m.getDescription().toLowerCase().contains(query)) {
                    filteredModules.add(m);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // RENDERING
    // ═══════════════════════════════════════════════════════

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Update dynamic colors from ClickGui module

        SIDEBAR_BG = net.matrix.utils.Color.hexToPacked(Screens.sidePanelBg.get());
        SETTINGS_BG = net.matrix.utils.Color.hexToPacked(Screens.mainPaneBg.get());
        ACCENT = net.matrix.utils.Color.hexToPacked(Screens.accentColorHex.get());
        CAT_HEADER_BG = net.matrix.utils.Color.hexToPacked(Screens.categoryHeaderBg.get());
        CAT_TEXT_COLOR = net.matrix.utils.Color.hexToPacked(Screens.categoryTextHex.get());

        // Derive related colors live
        SIDEBAR_HOVER = darken(SIDEBAR_BG, 0.1);
        SIDEBAR_SEL = darken(SIDEBAR_BG, 0.2);
        SEARCH_BG = darken(SETTINGS_BG, 0.2);
        ACCENT_DIM = (ACCENT & 0x00FFFFFF) | 0x99000000;
        ACCENT_SUBTLE = (ACCENT & 0x00FFFFFF) | 0x33000000;
        BTN_COLOR = ACCENT;
        TEXT_COLOR = ACCENT;

        // ── Frosted glass background overlay ──
        // Layer 1: Dark tint for contrast
        ctx.fill(0, 0, width, height, 0xBB000000);
        // Layer 2: Subtle colored tint matching accent
        int accentTint = (ACCENT & 0x00FFFFFF) | 0x0A000000;
        ctx.fill(0, 0, width, height, accentTint);

        int tabY = panelY - SEARCH_BAR_H - TAB_H - 4;

        // ── Tab bar ──
        int tabW = PANEL_W / Tab.values().length;
        for (int i = 0; i < Tab.values().length; i++) {
            int tx = panelX + i * tabW;
            int tw = (i == Tab.values().length - 1) ? (PANEL_W - i * tabW) : tabW; // last tab fills rest
            boolean isActive = (Tab.values()[i] == activeTab);
            boolean isHovered = mouseX >= tx && mouseX < tx + tw && mouseY >= tabY && mouseY < tabY + TAB_H;

            int tabTextColor = isActive ? TEXT_WHITE : ACCENT_DIM;

            // ── Glassmorphism tab ──
            if (isActive) {
                // Active tab: accent with slight transparency
                int activeTabBg = (ACCENT & 0x00FFFFFF) | 0xDD000000;
                fillRoundedRect(ctx, tx, tabY, tw, TAB_H, 4, activeTabBg);
                // Inner highlight (liquid glass edge)
                ctx.fill(tx + 1, tabY + 1, tx + tw - 1, tabY + 2, 0x33FFFFFF);
            } else if (isHovered) {
                // Hover: frosted glass tint
                int hoverBg = darken(SETTINGS_BG, 0.15);
                hoverBg = (hoverBg & 0x00FFFFFF) | 0xBB000000;
                fillRoundedRect(ctx, tx, tabY, tw, TAB_H, 4, hoverBg);
            } else {
                // Default: semi-transparent glass
                int defaultBg = (SETTINGS_BG & 0x00FFFFFF) | 0x88000000;
                fillRoundedRect(ctx, tx, tabY, tw, TAB_H, 4, defaultBg);
            }

            // Glass border — subtle accent
            ctx.fill(tx, tabY, tx + tw, tabY + 1, (ACCENT & 0x00FFFFFF) | 0x66000000); // top
            ctx.fill(tx, tabY, tx + 1, tabY + TAB_H, (ACCENT & 0x00FFFFFF) | 0x44000000); // left
            ctx.fill(tx + tw - 1, tabY, tx + tw, tabY + TAB_H, (ACCENT & 0x00FFFFFF) | 0x44000000); // right

            // Tab label centered
            int labelW = textRenderer.getWidth(Tab.values()[i].name);
            ctx.drawText(textRenderer, Tab.values()[i].name, tx + (tw - labelW) / 2, tabY + 7, tabTextColor, false);
        }

        int searchY = tabY + TAB_H;

        // ── Search bar (glassmorphism) ──
        int glassSearchBg = (SEARCH_BG & 0x00FFFFFF) | 0xAA000000;
        fillRoundedRect(ctx, panelX, searchY, PANEL_W, SEARCH_BAR_H, 0, glassSearchBg);
        drawGlassBorder(ctx, panelX, searchY, PANEL_W, SEARCH_BAR_H, ACCENT, 1);
        // Inner glow line
        ctx.fill(panelX + 1, searchY + 1, panelX + PANEL_W - 1, searchY + 2, 0x18FFFFFF);

        // Search text
        if (activeTab == Tab.MODULES) {
            String searchDisplay = searchQuery.isEmpty() && !searchFocused ? "Search modules..." : searchQuery;
            int searchTextColor = searchQuery.isEmpty() && !searchFocused ? ACCENT_DIM : TEXT_COLOR;
            ctx.drawText(textRenderer, searchDisplay + (searchFocused ? "_" : ""), panelX + 8, searchY + 7,
                    searchTextColor, false);
        } else if (activeTab == Tab.CONFIGURATIONS) {
            String display = configNameFocused || !newConfigName.isEmpty() ? newConfigName : "Enter config name...";
            int color = configNameFocused || !newConfigName.isEmpty() ? TEXT_COLOR : ACCENT_DIM;
            ctx.drawText(textRenderer, display + (configNameFocused ? "_" : ""), panelX + 8, searchY + 7, color, false);

            // Save button in search bar
            int saveBtnW = 40;
            int saveBtnX = panelX + PANEL_W - saveBtnW - 4;
            drawGlassPanel(ctx, saveBtnX, searchY + 3, saveBtnW, SEARCH_BAR_H - 6, BTN_COLOR, (BTN_COLOR >> 24) & 0xFF);
            int saveLabelW = textRenderer.getWidth("Save");
            ctx.drawText(textRenderer, "Save", saveBtnX + (saveBtnW - saveLabelW) / 2, searchY + 7, TEXT_WHITE, false);
        } else {
            ctx.drawText(textRenderer, "Client Settings", panelX + 8, searchY + 7, TEXT_COLOR, false);
        }

        // ── Main panel backgrounds ──
        if (activeTab == Tab.MODULES) {
            // ── Modules: 2-pane glassmorphism ──
            // Sidebar: match module background to prevent weird empty space
            int glassSidebar = SIDEBAR_BG;
            fillRoundedRect(ctx, panelX, panelY, SIDEBAR_W, PANEL_H, 6, glassSidebar);
            // Settings pane: darker frosted glass
            int glassSettings = (SETTINGS_BG & 0x00FFFFFF) | 0xCC000000;
            fillRoundedRect(ctx, panelX + SIDEBAR_W, panelY, PANEL_W - SIDEBAR_W, PANEL_H, 6, glassSettings);

            // Glass border around entire panel
            drawGlassBorder(ctx, panelX, panelY, PANEL_W, PANEL_H, ACCENT, BORDER_THICKNESS);
            // Inner glow highlights (iOS 26 liquid glass refraction)
            ctx.fill(panelX + BORDER_THICKNESS, panelY + BORDER_THICKNESS,
                    panelX + PANEL_W - BORDER_THICKNESS, panelY + BORDER_THICKNESS + 1, 0x22FFFFFF);
            ctx.fill(panelX + BORDER_THICKNESS, panelY + BORDER_THICKNESS,
                    panelX + BORDER_THICKNESS + 1, panelY + PANEL_H - BORDER_THICKNESS, 0x18FFFFFF);

            // Divider line (frosted)
            ctx.fill(panelX + SIDEBAR_W, panelY + 4, panelX + SIDEBAR_W + 1, panelY + PANEL_H - 4,
                    (ACCENT & 0x00FFFFFF) | 0x55000000);

            renderSidebar(ctx, mouseX, mouseY);
            if (selectedModule != null) {
                renderSettings(ctx, mouseX, mouseY);
            }
        } else {
            // ── Configs/Settings: single full-width glassmorphism pane ──
            int glassFull = (SETTINGS_BG & 0x00FFFFFF) | 0xCC000000;
            fillRoundedRect(ctx, panelX, panelY, PANEL_W, PANEL_H, 6, glassFull);
            drawGlassBorder(ctx, panelX, panelY, PANEL_W, PANEL_H, ACCENT, BORDER_THICKNESS);
            // Inner glow
            ctx.fill(panelX + BORDER_THICKNESS, panelY + BORDER_THICKNESS,
                    panelX + PANEL_W - BORDER_THICKNESS, panelY + BORDER_THICKNESS + 1, 0x22FFFFFF);
            ctx.fill(panelX + BORDER_THICKNESS, panelY + BORDER_THICKNESS,
                    panelX + BORDER_THICKNESS + 1, panelY + PANEL_H - BORDER_THICKNESS, 0x18FFFFFF);

            if (activeTab == Tab.CONFIGURATIONS) {
                renderConfigsTab(ctx, mouseX, mouseY);
            } else {
                renderSettingsTab(ctx, mouseX, mouseY);
            }
        }

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color, int thickness) {
        // Top
        ctx.fill(x, y, x + w, y + thickness, color);
        // Bottom
        ctx.fill(x, y + h - thickness, x + w, y + h, color);
        // Left
        ctx.fill(x, y, x + thickness, y + h, color);
        // Right
        ctx.fill(x + w - thickness, y, x + w, y + h, color);
    }

    /**
     * Glassmorphism border — accent-colored with slight transparency for glass edge
     * effect.
     */
    private void drawGlassBorder(DrawContext ctx, int x, int y, int w, int h, int color, int thickness) {
        int glassColor = (color & 0x00FFFFFF) | 0xBB000000;
        // Top
        ctx.fill(x, y, x + w, y + thickness, glassColor);
        // Bottom
        ctx.fill(x, y + h - thickness, x + w, y + h, glassColor);
        // Left
        ctx.fill(x, y, x + thickness, y + h, glassColor);
        // Right
        ctx.fill(x + w - thickness, y, x + w, y + h, glassColor);
    }

    /**
     * Simulated rounded rectangle using corner masking.
     * Fills the rect then masks corners with the background overlay color.
     */
    private void fillRoundedRect(DrawContext ctx, int x, int y, int w, int h, int radius, int color) {
        // Main fill
        ctx.fill(x, y, x + w, y + h, color);

        if (radius <= 0)
            return;

        // Mask corners with background (frosted overlay color)
        int maskColor = 0xBB000000;

        // Top-left corner
        ctx.fill(x, y, x + radius, y + 1, maskColor);
        ctx.fill(x, y, x + 1, y + radius, maskColor);
        if (radius >= 3) {
            ctx.fill(x, y + 1, x + (radius - 1), y + 2, maskColor);
            ctx.fill(x + 1, y, x + 2, y + (radius - 1), maskColor);
        }

        // Top-right corner
        ctx.fill(x + w - radius, y, x + w, y + 1, maskColor);
        ctx.fill(x + w - 1, y, x + w, y + radius, maskColor);
        if (radius >= 3) {
            ctx.fill(x + w - (radius - 1), y + 1, x + w, y + 2, maskColor);
            ctx.fill(x + w - 2, y, x + w - 1, y + (radius - 1), maskColor);
        }

        // Bottom-left corner
        ctx.fill(x, y + h - 1, x + radius, y + h, maskColor);
        ctx.fill(x, y + h - radius, x + 1, y + h, maskColor);
        if (radius >= 3) {
            ctx.fill(x, y + h - 2, x + (radius - 1), y + h - 1, maskColor);
            ctx.fill(x + 1, y + h - (radius - 1), x + 2, y + h, maskColor);
        }

        // Bottom-right corner
        ctx.fill(x + w - radius, y + h - 1, x + w, y + h, maskColor);
        ctx.fill(x + w - 1, y + h - radius, x + w, y + h, maskColor);
        if (radius >= 3) {
            ctx.fill(x + w - (radius - 1), y + h - 2, x + w, y + h - 1, maskColor);
            ctx.fill(x + w - 2, y + h - (radius - 1), x + w - 1, y + h, maskColor);
        }
    }

    /**
     * Draw a glass-effect panel background with inner highlight.
     * Used for sub-elements within the main glass panels.
     */
    private void drawGlassPanel(DrawContext ctx, int x, int y, int w, int h, int baseColor, int alpha) {
        int glassColor = (baseColor & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
        ctx.fill(x, y, x + w, y + h, glassColor);
        // Subtle top edge highlight
        ctx.fill(x, y, x + w, y + 1, 0x15FFFFFF);
    }

    private void renderSidebar(DrawContext ctx, int mouseX, int mouseY) {
        int sideX = panelX + BORDER_THICKNESS;
        int sideY = panelY + BORDER_THICKNESS;
        int sideRight = panelX + SIDEBAR_W;
        int sideBottom = panelY + PANEL_H - BORDER_THICKNESS;

        ctx.enableScissor(sideX, sideY, sideRight, sideBottom);

        int currentY = sideY - (int) sidebarScroll;

        // If searching, just show flat list
        if (!searchQuery.isEmpty()) {
            for (Module m : filteredModules) {
                if (currentY + MODULE_ROW_H < sideY) {
                    currentY += MODULE_ROW_H;
                    continue;
                }
                if (currentY > sideBottom)
                    break;

                renderModuleButton(ctx, m, sideX, currentY, sideRight, sideY, sideBottom, mouseX, mouseY);
                currentY += MODULE_ROW_H;
            }
        } else {
            // Group by category
            for (net.matrix.systems.modules.Category cat : allCategories) {
                // Check if any modules exist in this category
                List<Module> categoryModules = new ArrayList<>();
                for (Module mod : filteredModules) {
                    if (mod.getCategory() == cat) {
                        categoryModules.add(mod);
                    }
                }
                if (categoryModules.isEmpty() && cat != net.matrix.systems.modules.Category.MACE)
                    continue;

                // Render Category Header
                if (currentY + MODULE_ROW_H >= sideY && currentY <= sideBottom) {
                    ctx.fill(sideX, currentY, sideRight, currentY + MODULE_ROW_H, CAT_HEADER_BG);
                    boolean expanded = categoryExpanded.getOrDefault(cat, true);
                    String arrow = expanded ? "▼" : "▶";
                    ctx.drawText(textRenderer, arrow + " " + cat.name, sideX + 4, currentY + 6, CAT_TEXT_COLOR, false);
                }
                currentY += MODULE_ROW_H;

                // Render Modules if expanded
                if (categoryExpanded.getOrDefault(cat, true)) {
                    for (Module m : categoryModules) {
                        if (currentY + MODULE_ROW_H >= sideY && currentY <= sideBottom) {
                            renderModuleButton(ctx, m, sideX, currentY, sideRight, sideY, sideBottom, mouseX, mouseY);
                        }
                        currentY += MODULE_ROW_H;
                    }
                }
            }
        }

        ctx.disableScissor();
    }

    private void renderModuleButton(DrawContext ctx, Module m, int x, int y, int right, int top, int bottom, int mx,
            int my) {
        boolean isSelected = (m == selectedModule);
        boolean isHovered = mx >= x && mx < right
                && my >= y && my < y + MODULE_ROW_H
                && my >= top && my < bottom;

        int bg = isSelected ? SIDEBAR_SEL : (isHovered ? SIDEBAR_HOVER : SIDEBAR_BG);
        ctx.fill(x, y, right, y + MODULE_ROW_H, bg);

        // Left accent bar for selected module
        if (isSelected) {
            ctx.fill(x, y, x + 3, y + MODULE_ROW_H, ACCENT);
        }

        // Module name — always accent color, active state shown by switch
        ctx.drawText(textRenderer, m.getName(), x + 12, y + 6, TEXT_COLOR, false);

        // ── Toggle switch ──
        float target = m.isActive() ? 1.0f : 0.0f;
        float current = switchAnimations.getOrDefault(m, target);
        // Smooth interpolation toward target
        if (Math.abs(current - target) > 0.01f) {
            current += (target - current) * SWITCH_ANIM_SPEED;
            switchAnimations.put(m, current);
        } else {
            current = target;
            switchAnimations.put(m, current);
        }

        int switchW = 22;
        int switchH = 10;
        int switchX = right - switchW - 6; // right-aligned, all vertically aligned
        int switchY = y + (MODULE_ROW_H - switchH) / 2;
        int knobSize = switchH - 2;

        // Track fill — interpolate between off (dark) and on (green)
        int offColor = 0xFF503070;
        int onColor = 0xFF2ECC71;
        int trackColor = lerpColor(offColor, onColor, current);
        // Draw track (pill shape via filled rect + end caps)
        ctx.fill(switchX + 2, switchY, switchX + switchW - 2, switchY + switchH, trackColor);
        ctx.fill(switchX, switchY + 2, switchX + 2, switchY + switchH - 2, trackColor);
        ctx.fill(switchX + 1, switchY + 1, switchX + 2, switchY + switchH - 1, trackColor);
        ctx.fill(switchX + switchW - 2, switchY + 2, switchX + switchW, switchY + switchH - 2, trackColor);
        ctx.fill(switchX + switchW - 2, switchY + 1, switchX + switchW - 1, switchY + switchH - 1, trackColor);

        // Knob position — lerp from left to right
        int knobMinX = switchX + 1;
        int knobMaxX = switchX + switchW - knobSize - 1;
        int knobX = knobMinX + (int) ((knobMaxX - knobMinX) * current);
        int knobY = switchY + 1;
        // Draw knob (white circle approximation)
        ctx.fill(knobX + 1, knobY, knobX + knobSize - 1, knobY + knobSize, 0xFFFFFFFF);
        ctx.fill(knobX, knobY + 1, knobX + knobSize, knobY + knobSize - 1, 0xFFFFFFFF);

        // Thin separator
        ctx.fill(x + 4, y + MODULE_ROW_H - 1, right - 4, y + MODULE_ROW_H, ACCENT_SUBTLE);
    }

    /** Linearly interpolate between two packed ARGB colors. */
    private int lerpColor(int c1, int c2, float t) {
        int a = (int) (((c1 >> 24) & 0xFF) + (((c2 >> 24) & 0xFF) - ((c1 >> 24) & 0xFF)) * t);
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void renderSettings(DrawContext ctx, int mouseX, int mouseY) {
        int sx = panelX + SIDEBAR_W + 1;
        int settingsW = PANEL_W - SIDEBAR_W - 1 - BORDER_THICKNESS;
        int settingsTop = panelY + BORDER_THICKNESS;
        int settingsBottom = panelY + PANEL_H - BORDER_THICKNESS;

        ctx.enableScissor(sx, settingsTop, panelX + PANEL_W - BORDER_THICKNESS, settingsBottom);

        // Draw Scrollbar (Left side of settings)
        int contentHeight = getSettingsContentHeight();
        int viewportHeight = settingsBottom - settingsTop - 20; // -20 padding

        if (contentHeight > viewportHeight) {
            int scrollbarX = sx + 2; // Left side
            int scrollbarY = settingsTop + 5;
            int scrollbarH = viewportHeight;

            int thumbH = Math.max(20, (int) ((float) viewportHeight / contentHeight * viewportHeight));
            int thumbY = scrollbarY
                    + (int) ((float) settingsScroll / (contentHeight - viewportHeight) * (viewportHeight - thumbH));

            // Track
            ctx.fill(scrollbarX, scrollbarY, scrollbarX + SETTINGS_SCROLLBAR_W, scrollbarY + scrollbarH, 0x40000000);
            // Thumb
            ctx.fill(scrollbarX, thumbY, scrollbarX + SETTINGS_SCROLLBAR_W, thumbY + thumbH, ACCENT_DIM);
        }

        int y = settingsTop + 10 - (int) settingsScroll;

        // ── Module Name ──
        ctx.drawText(textRenderer, selectedModule.getName(), sx + 12, y, TEXT_COLOR, true);
        y += 16;

        // ── Enabled toggle ──
        if (true) {
            ctx.drawText(textRenderer, "Enabled", sx + 12, y + 2, TEXT_COLOR, false);
            int checkX = sx + settingsW - 30;
            int checkSize = 12;
            boolean active = selectedModule.isActive();
            ctx.fill(checkX, y + 1, checkX + checkSize, y + 1 + checkSize, active ? ACCENT : CHECKBOX_OFF);
            if (active) {
                ctx.drawText(textRenderer, "✓", checkX + 2, y + 2, TEXT_WHITE, false);
            }
            y += 20;
        }

        // ── Keybind ──
        {
            String keyName;
            if (listeningForKey) {
                keyName = "Press a key... (DEL = clear)";
            } else if (selectedModule.getKey() == -1) {
                keyName = "None";
            } else {
                keyName = GLFW.glfwGetKeyName(selectedModule.getKey(), 0);
                if (keyName == null)
                    keyName = "Key " + selectedModule.getKey();
                else
                    keyName = keyName.toUpperCase();
            }
            ctx.drawText(textRenderer, "Keybind: " + keyName, sx + 12, y + 2, TEXT_COLOR, false);

            // "Set Keybind" button
            int btnX = sx + settingsW - 70;
            int btnW = 60;
            int btnH = 14;
            int btnColor = listeningForKey ? 0xFFC06050 : BTN_COLOR;
            drawGlassPanel(ctx, btnX, y, btnW, btnH, btnColor, (btnColor >> 24) & 0xFF);
            String btnLabel = listeningForKey ? "Cancel" : "Set Key";
            int labelW = textRenderer.getWidth(btnLabel);
            ctx.drawText(textRenderer, btnLabel, btnX + (btnW - labelW) / 2, y + 3, TEXT_WHITE, false);
            y += 20;
        }

        // ── Description ──
        {
            List<net.minecraft.text.OrderedText> descLines = textRenderer.wrapLines(
                    Text.of(selectedModule.getDescription()), settingsW - 24);
            for (net.minecraft.text.OrderedText line : descLines) {
                ctx.drawTextWithShadow(textRenderer, line, sx + 12, y, ACCENT_DIM);
                y += 11;
            }
            y += 6;
        }

        // ── Separator ──
        ctx.fill(sx + 10, y, sx + settingsW - 10, y + 1, ACCENT_DIM);
        y += 10;

        // ── Settings ──
        List<Setting<?>> settings = selectedModule.getSettings();
        for (int i = 0; i < settings.size(); i++) {
            Setting<?> s = settings.get(i);

            if (s instanceof Setting.BooleanSetting boolSetting) {
                ctx.drawText(textRenderer, s.name, sx + 12, y + 3, TEXT_COLOR, false);
                int checkX = sx + settingsW - 30;
                int checkSize = 12;
                boolean val = boolSetting.get();
                ctx.fill(checkX, y + 2, checkX + checkSize, y + 2 + checkSize, val ? ACCENT : CHECKBOX_OFF);
                if (val) {
                    ctx.drawText(textRenderer, "✓", checkX + 2, y + 3, TEXT_WHITE, false);
                }
                y += 22;

            } else if (s instanceof Setting.IntSetting intSetting) {
                y = renderNumericSetting(ctx, mouseX, mouseY, sx, settingsW, y, i,
                        s.name, intSetting.get().doubleValue(), intSetting.min, intSetting.max, true);

            } else if (s instanceof Setting.DoubleSetting doubleSetting) {
                y = renderNumericSetting(ctx, mouseX, mouseY, sx, settingsW, y, i,
                        s.name, doubleSetting.get(), doubleSetting.min, doubleSetting.max, false);

            } else if (s instanceof Setting.StringSetSetting setS) {
                // Entity targeting section
                y = renderEntityTargeting(ctx, mouseX, mouseY, sx, settingsW, y, setS);
            } else if (s instanceof Setting.StringSetting stringSetting) {
                boolean isEditing = (editingSettingIndex == i);
                boolean isColor = s.name.toLowerCase().contains("hex") || s.name.toLowerCase().contains("color")
                        || s.name.toLowerCase().contains("bg");

                // Line 1: Name (Label)
                ctx.drawText(textRenderer, s.name + ": ", sx + 12, y + 2, TEXT_COLOR, false);

                // Line 2: Value + Preview + Button (move to next line for clarity and space)
                int intY = y + 14;
                String valStr = isEditing ? editingText + (System.currentTimeMillis() / 500 % 2 == 0 ? "_" : "")
                        : stringSetting.get();

                int valueX = sx + 20; // Indented for sub-options look

                if (isEditing) {
                    int tfW = Math.max(120, textRenderer.getWidth(valStr) + 12);
                    ctx.fill(valueX - 2, intY, valueX + tfW, intY + 12, 0xFF1A0A2A);
                    drawBorder(ctx, valueX - 2, intY, tfW + 2, 12, ACCENT, 1);
                    ctx.drawText(textRenderer, valStr, valueX, intY + 2, TEXT_WHITE, false);
                } else {
                    if (isColor) {
                        try {
                            int cVal = net.matrix.utils.Color.hexToPacked(stringSetting.get());
                            // Color preview box
                            ctx.fill(valueX, intY + 2, valueX + 10, intY + 10, cVal);
                            drawBorder(ctx, valueX, intY + 2, 10, 8, 0xFFFFFFFF, 1);
                            valueX += 14;
                        } catch (Exception ignored) {
                        }
                    }
                    ctx.drawText(textRenderer, valStr, valueX, intY + 2, TEXT_WHITE, false);
                }

                // Dedicated button on the right
                int btnW = isColor ? 65 : 45;
                int btnX = sx + settingsW - btnW - 10;
                int btnH = 13;
                int btnColor = isEditing ? 0xFFC06050 : BTN_COLOR;
                drawGlassPanel(ctx, btnX, intY, btnW, btnH, btnColor, (btnColor >> 24) & 0xFF);

                String btnLabel = isEditing ? "Save" : (isColor ? "Set Color" : "Set");
                int labelW = textRenderer.getWidth(btnLabel);
                ctx.drawText(textRenderer, btnLabel, btnX + (btnW - labelW) / 2, intY + 3, TEXT_WHITE, false);

                y += 32; // Vertical spacing for two-line layout
            }
        }

        ctx.disableScissor();
    }

    private int renderNumericSetting(DrawContext ctx, int mouseX, int mouseY,
            int sx, int settingsW, int y, int settingIndex,
            String name, double value, double min, double max, boolean isInt) {
        // Label + value / text input
        boolean isEditing = (editingSettingIndex == settingIndex);
        String valueStr = isEditing ? editingText + "_"
                : (isInt ? String.valueOf((int) value) : String.format("%.2f", value));

        ctx.drawText(textRenderer, name + ": ", sx + 12, y + 2, TEXT_COLOR, false);
        int valueX = sx + 12 + textRenderer.getWidth(name + ": ");

        if (isEditing) {
            // Editing text field background
            int tfW = Math.max(40, textRenderer.getWidth(valueStr) + 8);
            ctx.fill(valueX - 2, y, valueX + tfW, y + 12, 0xFF3A1A4A);
            drawBorder(ctx, valueX - 2, y, tfW + 2, 12, ACCENT, 1);
            ctx.drawText(textRenderer, valueStr, valueX, y + 2, TEXT_WHITE, false);
        } else {
            // Clickable value text
            ctx.drawText(textRenderer, valueStr, valueX, y + 2, TEXT_WHITE, false);
        }
        y += 14;

        // Slider
        int sliderX = sx + 12;
        int sliderW = settingsW - 24;
        float progress = (float) ((value - min) / (max - min));
        progress = Math.max(0, Math.min(1, progress));

        // Track background
        ctx.fill(sliderX, y + 1, sliderX + sliderW, y + 7, SLIDER_TRACK);
        // Filled portion
        int filledW = (int) (sliderW * progress);
        if (filledW > 0) {
            ctx.fill(sliderX, y + 1, sliderX + filledW, y + 7, ACCENT);
        }
        // Thumb
        int thumbX = sliderX + filledW - 4;
        int thumbLeft = Math.max(sliderX, thumbX);
        int thumbRight = Math.min(sliderX + sliderW, thumbX + 8);
        ctx.fill(thumbLeft, y - 1, thumbRight, y + 9, ACCENT);
        // Thumb highlight
        ctx.fill(thumbLeft + 1, y, thumbRight - 1, y + 8, 0xFFFFB0A0);
        y += 18;

        return y;
    }

    private int renderEntityTargeting(DrawContext ctx, int mouseX, int mouseY,
            int sx, int settingsW, int y,
            Setting.StringSetSetting setS) {
        // Header with expand/collapse toggle
        ctx.drawText(textRenderer, "▼ " + setS.name, sx + 12, y + 2, TEXT_COLOR, false);

        // Count display
        int enabledCount = setS.get().size();
        int totalCount = setS.allOptions.size();
        String countStr = enabledCount + "/" + totalCount;
        ctx.drawText(textRenderer, countStr, sx + settingsW - textRenderer.getWidth(countStr) - 10, y + 2, ACCENT_DIM,
                false);
        y += 16;

        if (currentOpenDropdown == setS) {
            // ── Entity Search Bar ──
            int searchBarH = 14;
            int searchBarX = sx + 8;
            int searchBarW = settingsW - 16;
            ctx.fill(searchBarX, y, searchBarX + searchBarW, y + searchBarH, SEARCH_BG);
            drawBorder(ctx, searchBarX, y, searchBarW, searchBarH, entitySearchFocused ? ACCENT : ACCENT_DIM, 1);
            String searchDisplay = entitySearchQuery.isEmpty() && !entitySearchFocused ? "Search..." : entitySearchQuery;
            int searchTextColor = entitySearchQuery.isEmpty() && !entitySearchFocused ? ACCENT_DIM : TEXT_COLOR;
            ctx.drawText(textRenderer, searchDisplay + (entitySearchFocused ? "_" : ""), searchBarX + 4, y + 3,
                    searchTextColor, false);
            y += searchBarH + 2;

            // Build filtered options
            List<String> options = setS.getSortedOptions();
            if (!entitySearchQuery.isEmpty()) {
                String query = entitySearchQuery.toLowerCase();
                List<String> filtered = new java.util.ArrayList<>();
                for (String opt : options) {
                    String display = opt.startsWith("minecraft:") ? opt.substring(10) : opt;
                    if (display.toLowerCase().contains(query)) {
                        filtered.add(opt);
                    }
                }
                options = filtered;
            }

            // Render visible entity rows (max ~8 visible at a time)
            int maxVisible = 8;
            int rowH = 14;
            double currentScroll = dropdownScrolls.getOrDefault(setS, 0.0);
            int listH = maxVisible * rowH;
            int listStart = y;

            ctx.enableScissor(sx + 8, listStart, sx + settingsW - 8, listStart + listH);

            for (int j = 0; j < options.size(); j++) {
                int rowY = listStart + (j * rowH) - (int) currentScroll;
                // Fix: allow rendering if partially visible
                if (rowY + rowH <= listStart || rowY >= listStart + listH)
                    continue;

                String opt = options.get(j);
                boolean enabled = setS.isEnabled(opt);
                int rowBg = (j % 2 == 0) ? ENTITY_ROW_BG : ENTITY_ROW_ALT;
                ctx.fill(sx + 8, rowY, sx + settingsW - 8, rowY + rowH, rowBg);

                // Checkbox
                int ckX = sx + 12;
                int ckSize = 8;
                ctx.fill(ckX, rowY + 3, ckX + ckSize, rowY + 3 + ckSize, enabled ? ACCENT : CHECKBOX_OFF);

                // Entity name (strip "minecraft:" prefix for readability)
                String displayName = opt.startsWith("minecraft:") ? opt.substring(10) : opt;
                ctx.drawText(textRenderer, displayName, ckX + ckSize + 4, rowY + 3, enabled ? TEXT_WHITE : ACCENT_DIM,
                        false);
            }

            ctx.disableScissor();

            // Scrollbar indicator
            if (options.size() > maxVisible) {
                int scrollBarH = Math.max(10, listH * maxVisible / options.size());
                double maxEntityScroll = Math.max(0, options.size() * rowH - listH);
                int scrollBarY = listStart
                        + (int) ((listH - scrollBarH) * (currentScroll / Math.max(1, maxEntityScroll)));
                ctx.fill(sx + settingsW - 12, scrollBarY, sx + settingsW - 9, scrollBarY + scrollBarH, ACCENT_DIM);
            }

            y = listStart + listH + 4;
        }

        return y;
    }

    // ═══════════════════════════════════════════════════════
    // CONFIGURATIONS TAB
    // ═══════════════════════════════════════════════════════

    private void renderConfigsTab(DrawContext ctx, int mouseX, int mouseY) {
        int cx = panelX + BORDER_THICKNESS + 10;
        int contentW = PANEL_W - BORDER_THICKNESS * 2 - 20;
        int top = panelY + BORDER_THICKNESS;
        int bottom = panelY + PANEL_H - BORDER_THICKNESS;

        ctx.enableScissor(panelX + BORDER_THICKNESS, top, panelX + PANEL_W - BORDER_THICKNESS, bottom);

        // Render scrollbar
        int contentHeight = getConfigsContentHeight();
        int viewportHeight = bottom - top - 16;
        if (contentHeight > viewportHeight) {
            int sbX = panelX + PANEL_W - BORDER_THICKNESS - 6;
            int sbY = top + 8;
            int sbH = viewportHeight;
            int thumbH = Math.max(20, (int) ((float) viewportHeight / contentHeight * viewportHeight));
            int thumbY = sbY + (int) ((float) configScroll / (contentHeight - viewportHeight) * (viewportHeight - thumbH));
            ctx.fill(sbX, sbY, sbX + 4, sbY + sbH, 0x40000000);
            ctx.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, ACCENT_DIM);
        }

        int y = top + 8 - (int) configScroll;

        // Title
        ctx.drawText(textRenderer, "Saved Configurations", cx, y, TEXT_COLOR, true);
        y += 16;

        // Status message (fades after 3s)
        if (!configStatusMsg.isEmpty() && System.currentTimeMillis() - configStatusTime < 3000) {
            ctx.drawText(textRenderer, configStatusMsg, cx, y, 0xFF55FF55, false);
            y += 14;
        }

        // Separator
        ctx.fill(cx, y, cx + contentW, y + 1, ACCENT_DIM);
        y += 8;

        // Config list
        if (savedConfigs.isEmpty()) {
            ctx.drawText(textRenderer, "No saved configs yet. Type a name above and click Save.", cx, y, ACCENT_DIM,
                    false);
            y += 20;
        } else {
            int ROW_H = 22;
            for (int i = 0; i < savedConfigs.size(); i++) {
                String name = savedConfigs.get(i);
                boolean isHovered = mouseX >= cx && mouseX < cx + contentW && mouseY >= y && mouseY < y + ROW_H;

                // Row background
                int rowBg = isHovered ? darken(SETTINGS_BG, 0.15)
                        : (i % 2 == 0 ? SETTINGS_BG : darken(SETTINGS_BG, 0.05));
                ctx.fill(cx, y, cx + contentW, y + ROW_H, rowBg);

                // Config name
                ctx.drawText(textRenderer, "  " + name, cx + 4, y + 7, TEXT_WHITE, false);

                // Update button
                int updBtnW = 40;
                int updBtnX = cx + contentW - updBtnW - 85;
                drawGlassPanel(ctx, updBtnX, y + 3, updBtnW, ROW_H - 6, 0x4CAF50, 0xFF); // Green-ish
                int updLabelW = textRenderer.getWidth("Upd");
                ctx.drawText(textRenderer, "Upd", updBtnX + (updBtnW - updLabelW) / 2, y + 7, TEXT_WHITE, false);

                // Load button
                int loadBtnW = 35;
                int loadBtnX = cx + contentW - loadBtnW - 45;
                drawGlassPanel(ctx, loadBtnX, y + 3, loadBtnW, ROW_H - 6, BTN_COLOR, (BTN_COLOR >> 24) & 0xFF);
                int loadLabelW = textRenderer.getWidth("Load");
                ctx.drawText(textRenderer, "Load", loadBtnX + (loadBtnW - loadLabelW) / 2, y + 7, TEXT_WHITE, false);

                // Delete button
                int delBtnW = 35;
                int delBtnX = cx + contentW - delBtnW - 5;
                drawGlassPanel(ctx, delBtnX, y + 3, delBtnW, ROW_H - 6, 0xC06050, 0xFF);
                int delLabelW = textRenderer.getWidth("Del");
                ctx.drawText(textRenderer, "Del", delBtnX + (delBtnW - delLabelW) / 2, y + 7, TEXT_WHITE, false);

                y += ROW_H;
            }
        }

        ctx.disableScissor();
    }

    // ═══════════════════════════════════════════════════════
    // SETTINGS TAB
    // ═══════════════════════════════════════════════════════

    private void renderSettingsTab(DrawContext ctx, int mouseX, int mouseY) {
        int cx = panelX + BORDER_THICKNESS + 10;
        int contentW = PANEL_W - BORDER_THICKNESS * 2 - 20;
        int top = panelY + BORDER_THICKNESS;
        int bottom = panelY + PANEL_H - BORDER_THICKNESS;

        ctx.enableScissor(panelX + BORDER_THICKNESS, top, panelX + PANEL_W - BORDER_THICKNESS, bottom);

        // Render scrollbar
        int contentHeight = getSettingsTabContentHeight();
        int viewportHeight = bottom - top - 16;
        if (contentHeight > viewportHeight) {
            int sbX = panelX + PANEL_W - BORDER_THICKNESS - 6;
            int sbY = top + 8;
            int sbH = viewportHeight;
            int thumbH = Math.max(20, (int) ((float) viewportHeight / contentHeight * viewportHeight));
            int thumbY = sbY + (int) ((float) settingsTabScroll / (contentHeight - viewportHeight) * (viewportHeight - thumbH));
            ctx.fill(sbX, sbY, sbX + 4, sbY + sbH, 0x40000000);
            ctx.fill(sbX, thumbY, sbX + 4, thumbY + thumbH, ACCENT_DIM);
        }

        int y = top + 8 - (int) settingsTabScroll;

        // ── GUI Colors Section ──
        ctx.drawText(textRenderer, "GUI Colors", cx, y, TEXT_COLOR, true);
        y += 18;

        Setting.StringSetting[] colorSettings = {
                Screens.sidePanelBg, Screens.mainPaneBg, Screens.accentColorHex,
                Screens.categoryHeaderBg, Screens.categoryTextHex
        };
        String[] colorLabels = {
                "Side Panel Background", "Main Pane Background", "Tint Color",
                "Category Header", "Category Text"
        };

        for (int i = 0; i < colorSettings.length; i++) {
            y = renderSettingsTabColor(ctx, cx, contentW, y, i, colorLabels[i], colorSettings[i], mouseX, mouseY);
        }

        y += 4;

        // Reset button
        int resetBtnW = 110;
        int resetBtnX = cx + contentW - resetBtnW;
        boolean resetHovered = mouseX >= resetBtnX && mouseX <= resetBtnX + resetBtnW && mouseY >= y
                && mouseY <= y + 16;
        ctx.fill(resetBtnX, y, resetBtnX + resetBtnW, y + 16, resetHovered ? darken(BTN_COLOR, 0.15) : BTN_COLOR);
        int labelW = textRenderer.getWidth("Reset to Defaults");
        ctx.drawText(textRenderer, "Reset to Defaults", resetBtnX + (resetBtnW - labelW) / 2, y + 4, TEXT_WHITE, false);
        y += 26;

        // Separator
        ctx.fill(cx, y, cx + contentW, y + 1, ACCENT_DIM);
        y += 14;

        // ── ChatLogs toggle ──
        ctx.drawText(textRenderer, "Chat Logging", cx, y, TEXT_COLOR, true);
        y += 18;

        boolean chatActive = Screens.showToggleNotifications.get();

        // Toggle row 1: Show Toggle Notifications
        boolean chatRowHovered = mouseX >= cx && mouseX < cx + contentW && mouseY >= y && mouseY < y + 22;
        if (chatRowHovered) {
            ctx.fill(cx, y, cx + contentW, y + 22, (SETTINGS_BG & 0x00FFFFFF) | 0x33000000);
        }

        ctx.drawText(textRenderer, "Show Toggle Notifications", cx + 4, y + 6, TEXT_WHITE, false);
        int checkX = cx + contentW - 24;
        int checkSize = 14;
        ctx.fill(checkX, y + 4, checkX + checkSize, y + 4 + checkSize, chatActive ? ACCENT : CHECKBOX_OFF);
        if (chatActive) {
            ctx.drawText(textRenderer, "✓", checkX + 3, y + 5, TEXT_WHITE, false);
        }
        y += 26;

        // Toggle row 2: Save Chat to File
        boolean saveActive = Screens.saveChatToFile.get();
        boolean saveRowHovered = mouseX >= cx && mouseX < cx + contentW && mouseY >= y && mouseY < y + 22;
        if (saveRowHovered) {
            ctx.fill(cx, y, cx + contentW, y + 22, (SETTINGS_BG & 0x00FFFFFF) | 0x33000000);
        }

        ctx.drawText(textRenderer, "Save Chat to File (.log)", cx + 4, y + 6, TEXT_WHITE, false);
        ctx.fill(checkX, y + 4, checkX + checkSize, y + 4 + checkSize, saveActive ? ACCENT : CHECKBOX_OFF);
        if (saveActive) {
            ctx.drawText(textRenderer, "✓", checkX + 3, y + 5, TEXT_WHITE, false);
        }
        y += 26;

        ctx.drawText(textRenderer, "Logs saved to: matrix/users/<you>/chat.log", cx + 4, y, ACCENT_DIM, false);
        y += 24;

        // ── Command System Toggle & Help ──
        ctx.drawText(textRenderer, "Command System", cx, y, TEXT_COLOR, true);
        
        // Prefix config button
        String currentPrefix = Screens.commandPrefix.get();
        int prefixBtnX = cx + contentW - 40;
        int prefixBtnY = y - 2;
        int prefixBtnW = 40;
        int prefixBtnH = 12;
        boolean prefixHovered = mouseX >= prefixBtnX && mouseX < prefixBtnX + prefixBtnW && mouseY >= prefixBtnY && mouseY < prefixBtnY + prefixBtnH;
        
        ctx.fill(prefixBtnX, prefixBtnY, prefixBtnX + prefixBtnW, prefixBtnY + prefixBtnH,
                prefixHovered ? 0xFFE07060 : ACCENT_DIM);
        String prefixLabel = settingsTabEditIndex == 999 ? currentPrefix + "_" : currentPrefix;
        int pw = textRenderer.getWidth(prefixLabel);
        ctx.drawText(textRenderer, prefixLabel, prefixBtnX + (prefixBtnW - pw) / 2, prefixBtnY + 2, TEXT_WHITE, false);
        
        y += 18;
        ctx.drawText(textRenderer, "In-game chat commands without opening GUI.", cx + 4, y, ACCENT_DIM, false);
        y += 14;
        ctx.drawText(textRenderer, "Bind Commands", cx + 4, y, TEXT_WHITE, false);
        y += 10;
        ctx.drawText(textRenderer, currentPrefix + "bind <module> <key>", cx + 8, y, ACCENT_DIM, false);
        y += 14;
        ctx.drawText(textRenderer, "Config Commands", cx + 4, y, TEXT_WHITE, false);
        y += 10;
        ctx.drawText(textRenderer, currentPrefix + "config <load|update|save> <name>", cx + 8, y, ACCENT_DIM, false);
        y += 14;
        ctx.drawText(textRenderer, "Self Destruct", cx + 4, y, TEXT_WHITE, false);
        y += 10;
        ctx.drawText(textRenderer, currentPrefix + "self_destruct", cx + 8, y, ACCENT_DIM, false);
        
        y += 20;

        ctx.disableScissor();
    }

    private int renderSettingsTabColor(DrawContext ctx, int cx, int contentW, int y, int index,
            String label, Setting.StringSetting setting, int mouseX, int mouseY) {
        boolean isEditing = (settingsTabEditIndex == index);
        String val = isEditing ? settingsTabEditText : setting.get();

        // Label
        ctx.drawText(textRenderer, label + ":", cx, y + 3, TEXT_WHITE, false);

        // Color preview box
        int previewX = cx + 130;
        try {
            int cVal = net.matrix.utils.Color.hexToPacked(isEditing ? settingsTabEditText : setting.get());
            ctx.fill(previewX, y + 3, previewX + 12, y + 13, cVal);
            drawBorder(ctx, previewX, y + 3, 12, 10, 0xFFFFFFFF, 1);
        } catch (Exception ignored) {
        }

        // Value text / edit field
        int valueX = previewX + 18;
        if (isEditing) {
            String display = val + (System.currentTimeMillis() / 500 % 2 == 0 ? "_" : "");
            int tfW = Math.max(80, textRenderer.getWidth(display) + 12);
            ctx.fill(valueX - 2, y + 1, valueX + tfW, y + 13, 0xFF1A0A2A);
            drawBorder(ctx, valueX - 2, y + 1, tfW + 2, 12, ACCENT, 1);
            ctx.drawText(textRenderer, display, valueX, y + 3, TEXT_WHITE, false);
        } else {
            ctx.drawText(textRenderer, val, valueX, y + 3, ACCENT_DIM, false);
        }

        // Set Color / Save button
        int btnW = isEditing ? 40 : 60;
        int btnX = cx + contentW - btnW - 4;
        int btnH = 14;
        boolean btnHovered = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= y + 1 && mouseY <= y + 1 + btnH;
        int btnColor = isEditing ? (btnHovered ? 0xFFD07060 : 0xFFC06050)
                : (btnHovered ? darken(BTN_COLOR, 0.15) : BTN_COLOR);
        ctx.fill(btnX, y + 1, btnX + btnW, y + 1 + btnH, btnColor);

        String btnLabel = isEditing ? "Save" : "Set Color";
        int btnLabelW = textRenderer.getWidth(btnLabel);
        ctx.drawText(textRenderer, btnLabel, btnX + (btnW - btnLabelW) / 2, y + 4, TEXT_WHITE, false);

        y += 22;
        return y;
    }

    private int getSettingsContentHeight() {
        if (selectedModule == null)
            return 0;

        // Base padding
        int h = 10;

        // Name
        h += 16;

        // Enabled toggle
        if (true) {
            h += 20;
        }

        // Keybind
        h += 20;

        int settingsW = PANEL_W - SIDEBAR_W - 1 - BORDER_THICKNESS;
        List<net.minecraft.text.OrderedText> descLines = textRenderer.wrapLines(
                Text.of(selectedModule.getDescription()), settingsW - 24);
        h += descLines.size() * 11 + 6;

        // Separator
        h += 10;

        List<Setting<?>> settings = selectedModule.getSettings();
        for (Setting<?> s : settings) {
            if (s instanceof Setting.BooleanSetting) {
                h += 22;
            } else if (s instanceof Setting.IntSetting || s instanceof Setting.DoubleSetting) {
                h += 14 + 18; // Label + Slider
            } else if (s instanceof Setting.StringSetSetting setS) {
                h += 16; // Header
                if (currentOpenDropdown == setS) {
                    h += 16; // Search bar
                    int maxVisible = 8;
                    int rowH = 14;
                    int listH = maxVisible * rowH;
                    h += listH + 4;
                }
            } else if (s instanceof Setting.StringSetting) {
                h += 32;
            }
        }

        return h + 10; // Bottom padding
    }

    private int getConfigsContentHeight() {
        int h = 8; // Top padding
        h += 16; // Title
        if (!configStatusMsg.isEmpty() && System.currentTimeMillis() - configStatusTime < 3000) {
            h += 14; // Status message
        }
        h += 8; // Separator
        if (savedConfigs.isEmpty()) {
            h += 20;
        } else {
            h += savedConfigs.size() * 22;
        }
        return h + 10; // Bottom padding
    }

    private int getSettingsTabContentHeight() {
        int h = 8; // Top padding
        h += 18; // GUI Colors title
        h += 5 * 22; // 5 color settings
        h += 4; // Padding
        h += 26; // Reset button
        h += 14; // Separator
        h += 18; // Chat Logging title
        h += 26; // Toggle 1
        h += 26; // Toggle 2
        h += 24; // Path help
        h += 18; // Command system title
        h += 18 + 14 + 10 + 14 + 10 + 14 + 10 + 20; // Command system help
        return h + 10; // Bottom padding
    }

    // ═══════════════════════════════════════════════════════
    // INPUT HANDLING
    // ═══════════════════════════════════════════════════════

    @Override
    public boolean mouseClicked(Click click, boolean released) {
        double mouseX = click.x();
        double mouseY = click.y();
        @SuppressWarnings("unused")
        int button = click.button();
        int tabY = panelY - SEARCH_BAR_H - TAB_H - 4;
        int searchY = tabY + TAB_H;

        // ── Tab bar click ──
        int tabW = PANEL_W / Tab.values().length;
        if (mouseY >= tabY && mouseY < tabY + TAB_H && mouseX >= panelX && mouseX < panelX + PANEL_W) {
            int clickedTabIndex = (int) ((mouseX - panelX) / tabW);
            clickedTabIndex = Math.min(clickedTabIndex, Tab.values().length - 1);
            Tab clickedTab = Tab.values()[clickedTabIndex];
            if (clickedTab != activeTab) {
                activeTab = clickedTab;
                searchFocused = false;
                configNameFocused = false;
                editingSettingIndex = -1;
                if (activeTab == Tab.CONFIGURATIONS) {
                    savedConfigs = ConfigManager.listConfigs();
                }
            }
            return true;
        }

        // ── Search bar click ──
        if (mouseX >= panelX && mouseX <= panelX + PANEL_W && mouseY >= searchY && mouseY <= searchY + SEARCH_BAR_H) {
            if (activeTab == Tab.MODULES) {
                searchFocused = true;
                configNameFocused = false;
                editingSettingIndex = -1;
            } else if (activeTab == Tab.CONFIGURATIONS) {
                configNameFocused = true;
                searchFocused = false;
                editingSettingIndex = -1;
                // Check save button click
                int saveBtnW = 40;
                int saveBtnX = panelX + PANEL_W - saveBtnW - 4;
                if (mouseX >= saveBtnX && mouseX <= saveBtnX + saveBtnW) {
                    if (!newConfigName.isBlank()) {
                        ConfigManager.saveAs(newConfigName.trim());
                        configStatusMsg = "Saved: " + newConfigName.trim();
                        configStatusTime = System.currentTimeMillis();
                        newConfigName = "";
                        savedConfigs = ConfigManager.listConfigs();
                    }
                    configNameFocused = false;
                    return true;
                }
            }
            return true;
        }

        // Clicking anywhere outside search/config unfocuses
        searchFocused = false;
        configNameFocused = false;

        // ── Outside the panel → close ──
        if (mouseX < panelX || mouseX > panelX + PANEL_W || mouseY < panelY || mouseY > panelY + PANEL_H) {
            close();
            return true;
        }

        // ── Config tab click handling ──
        if (activeTab == Tab.CONFIGURATIONS) {
            int sbX = panelX + PANEL_W - BORDER_THICKNESS - 10;
            if (mouseX >= sbX && mouseX <= panelX + PANEL_W && mouseY >= panelY + BORDER_THICKNESS && mouseY <= panelY + PANEL_H - BORDER_THICKNESS) {
                int ch = getConfigsContentHeight();
                int vh = PANEL_H - BORDER_THICKNESS * 2 - 16;
                if (ch > vh) {
                    draggingSettingsScrollbar = true;
                    return true;
                }
            }
            return handleConfigsTabClick((int) mouseX, (int) mouseY);
        }

        // ── Settings tab click handling ──
        if (activeTab == Tab.SETTINGS) {
            int sbX = panelX + PANEL_W - BORDER_THICKNESS - 10;
            if (mouseX >= sbX && mouseX <= panelX + PANEL_W && mouseY >= panelY + BORDER_THICKNESS && mouseY <= panelY + PANEL_H - BORDER_THICKNESS) {
                int ch = getSettingsTabContentHeight();
                int vh = PANEL_H - BORDER_THICKNESS * 2 - 16;
                if (ch > vh) {
                    draggingSettingsScrollbar = true;
                    return true;
                }
            }
            return handleSettingsTabClick((int) mouseX, (int) mouseY);
        }

        // ── Sidebar click ──
        if (mouseX >= panelX && mouseX < panelX + SIDEBAR_W && mouseY >= panelY && mouseY < panelY + PANEL_H) {
            int currentY = panelY + BORDER_THICKNESS - (int) sidebarScroll;
            int sideY = panelY + BORDER_THICKNESS;
            int sideBottom = panelY + PANEL_H - BORDER_THICKNESS;

            // If searching, flat list click logic
            if (!searchQuery.isEmpty()) {
                for (Module m : filteredModules) {
                    if (currentY + MODULE_ROW_H >= sideY && currentY <= sideBottom) {
                        if (mouseY >= currentY && mouseY < currentY + MODULE_ROW_H) {
                            if (mouseX >= panelX + SIDEBAR_W - 35) {
                                m.toggle();
                            } else {
                                selectedModule = m;
                                resetSelectedState();
                            }
                            return true;
                        }
                    }
                    currentY += MODULE_ROW_H;
                }
            } else {
                // Grouped list click logic
                for (net.matrix.systems.modules.Category cat : allCategories) {
                    List<Module> categoryModules = new ArrayList<>();
                    for (Module m : filteredModules) {
                        if (m.getCategory() == cat) {
                            categoryModules.add(m);
                        }
                    }
                    if (categoryModules.isEmpty() && cat != net.matrix.systems.modules.Category.MACE)
                        continue;

                    // Header Click
                    if (currentY + MODULE_ROW_H >= sideY && currentY <= sideBottom) {
                        if (mouseY >= currentY && mouseY < currentY + MODULE_ROW_H) {
                            categoryExpanded.put(cat, !categoryExpanded.getOrDefault(cat, true));
                            return true;
                        }
                    }
                    currentY += MODULE_ROW_H;

                    // Module Clicks
                    if (categoryExpanded.getOrDefault(cat, true)) {
                        for (Module m : categoryModules) {
                            if (currentY + MODULE_ROW_H >= sideY && currentY <= sideBottom) {
                                if (mouseY >= currentY && mouseY < currentY + MODULE_ROW_H) {
                                    if (mouseX >= panelX + SIDEBAR_W - 35) {
                                        m.toggle();
                                    } else {
                                        selectedModule = m;
                                        resetSelectedState();
                                    }
                                    return true;
                                }
                            }
                            currentY += MODULE_ROW_H;
                        }
                    }
                }
            }
            return true;
        }

        // ── Settings panel click ──
        if (selectedModule != null && mouseX >= panelX + SIDEBAR_W) {
            // Check scrollbar click
            int sx = panelX + SIDEBAR_W + 1;
            int settingsTop = panelY + BORDER_THICKNESS;
            int settingsBottom = panelY + PANEL_H - BORDER_THICKNESS;

            // Hitbox for scrollbar interaction (slightly wider)
            if (activeTab == Tab.MODULES) {
                if (mouseX >= sx && mouseX <= sx + 10 && mouseY >= settingsTop && mouseY <= settingsBottom) {
                    int contentHeight = getSettingsContentHeight();
                    int viewportHeight = settingsBottom - settingsTop - 20;
                    if (contentHeight > viewportHeight) {
                        draggingSettingsScrollbar = true;
                        return true;
                    }
                }
            }

            return handleSettingsClick((int) mouseX, (int) mouseY);
        }

        return super.mouseClicked(click, released);
    }

    private void resetSelectedState() {
        listeningForKey = false;
        draggingSlider = -1;
        editingSettingIndex = -1;
        settingsScroll = 0;
        currentOpenDropdown = null;
        currentOpenDropdown = null;
        dropdownScrolls.clear();
        draggingSettingsScrollbar = false;
    }

    private boolean handleSettingsClick(int mx, int my) {
        int sx = panelX + SIDEBAR_W + 1;
        int settingsW = PANEL_W - SIDEBAR_W - 1 - BORDER_THICKNESS;
        int settingsTop = panelY + BORDER_THICKNESS;

        int y = settingsTop + 10 - (int) settingsScroll;

        // Module name
        y += 16;

        // ── Enabled toggle ──
        if (true) {
            int checkX = sx + settingsW - 30;
            int checkSize = 12;
            if (mx >= checkX && mx <= checkX + checkSize && my >= y + 1 && my <= y + 1 + checkSize) {
                selectedModule.toggle();
                return true;
            }
            y += 20;
        }

        // ── Keybind button ──
        {
            int btnX = sx + settingsW - 70;
            int btnW = 60;
            int btnH = 14;
            if (mx >= btnX && mx <= btnX + btnW && my >= y && my <= y + btnH) {
                listeningForKey = !listeningForKey;
                return true;
            }
            y += 20;
        }

        // ── Description ──
        {
            List<net.minecraft.text.OrderedText> descLines = textRenderer.wrapLines(
                    Text.of(selectedModule.getDescription()), settingsW - 24);
            y += descLines.size() * 11 + 6;
        }

        // ── Separator ──
        y += 10;

        // ── Settings ──
        List<Setting<?>> settings = selectedModule.getSettings();
        for (int i = 0; i < settings.size(); i++) {
            Setting<?> s = settings.get(i);

            if (s instanceof Setting.BooleanSetting boolSetting) {
                int checkX = sx + settingsW - 30;
                int checkSize = 12;
                if (mx >= sx + 8 && mx <= checkX + checkSize && my >= y && my <= y + 16) {
                    boolSetting.toggle();
                    return true;
                }
                y += 22;

            } else if (s instanceof Setting.IntSetting intSetting) {
                // Check value text click for text editing
                int valueX = sx + 12 + textRenderer.getWidth(s.name + ": ");
                if (mx >= valueX - 2 && mx <= valueX + 60 && my >= y && my <= y + 12) {
                    editingSettingIndex = i;
                    editingText = String.valueOf(intSetting.get());
                    return true;
                }
                y += 14;

                // Slider
                int sliderX = sx + 12;
                int sliderW = settingsW - 24;
                if (mx >= sliderX && mx <= sliderX + sliderW && my >= y - 2 && my <= y + 10) {
                    float progress = (float) (mx - sliderX) / sliderW;
                    progress = Math.max(0, Math.min(1, progress));
                    int newVal = (int) (intSetting.min + progress * (intSetting.max - intSetting.min));
                    intSetting.set(newVal);
                    draggingSlider = i;
                    editingSettingIndex = -1;
                    return true;
                }
                y += 18;

            } else if (s instanceof Setting.DoubleSetting doubleSetting) {
                // Check value text click for text editing
                int valueX = sx + 12 + textRenderer.getWidth(s.name + ": ");
                if (mx >= valueX - 2 && mx <= valueX + 60 && my >= y && my <= y + 12) {
                    editingSettingIndex = i;
                    editingText = String.format("%.2f", doubleSetting.get());
                    return true;
                }
                y += 14;

                // Slider
                int sliderX = sx + 12;
                int sliderW = settingsW - 24;
                if (mx >= sliderX && mx <= sliderX + sliderW && my >= y - 2 && my <= y + 10) {
                    float progress = (float) (mx - sliderX) / sliderW;
                    progress = Math.max(0, Math.min(1, progress));
                    double newVal = doubleSetting.min + progress * (doubleSetting.max - doubleSetting.min);
                    doubleSetting.set(newVal);
                    draggingSlider = i;
                    editingSettingIndex = -1;
                    return true;
                }
                y += 18;

            } else if (s instanceof Setting.StringSetSetting setS) {
                // Header click → toggle expand
                if (mx >= sx + 8 && mx <= sx + settingsW - 8 && my >= y && my <= y + 14) {
                    Setting<?> prev = currentOpenDropdown;
                    currentOpenDropdown = (currentOpenDropdown == setS) ? null : setS;
                    if (currentOpenDropdown != prev) {
                        entitySearchQuery = "";
                        entitySearchFocused = false;
                    }
                    clampSettingsScroll();
                    return true;
                }
                y += 16;

                if (currentOpenDropdown == setS) {
                    // Search bar click
                    int searchBarH = 14;
                    if (mx >= sx + 8 && mx <= sx + settingsW - 8 && my >= y && my <= y + searchBarH) {
                        entitySearchFocused = true;
                        return true;
                    }
                    y += searchBarH + 2;

                    // Build filtered options (same as render)
                    List<String> options = setS.getSortedOptions();
                    if (!entitySearchQuery.isEmpty()) {
                        String query = entitySearchQuery.toLowerCase();
                        List<String> filtered = new java.util.ArrayList<>();
                        for (String opt : options) {
                            String display = opt.startsWith("minecraft:") ? opt.substring(10) : opt;
                            if (display.toLowerCase().contains(query)) {
                                filtered.add(opt);
                            }
                        }
                        options = filtered;
                    }

                    int maxVisible = 8;
                    int rowH = 14;
                    int listH = maxVisible * rowH;
                    int listStart = y;

                    if (mx >= sx + 8 && mx <= sx + settingsW - 8 && my >= listStart && my <= listStart + listH) {
                        double currentScroll = dropdownScrolls.getOrDefault(setS, 0.0);
                        int relY = (int) (my - listStart + currentScroll);
                        int idx = relY / rowH;
                        if (idx >= 0 && idx < options.size()) {
                            setS.toggle(options.get(idx));
                        }
                        return true;
                    }
                    y = listStart + listH + 4;
                }
            } else if (s instanceof Setting.StringSetting stringSetting) {
                boolean isEditing = (editingSettingIndex == i);
                boolean isColor = s.name.toLowerCase().contains("hex") || s.name.toLowerCase().contains("color")
                        || s.name.toLowerCase().contains("bg");
                int btnW = isColor ? 65 : 45;
                int btnX = sx + settingsW - btnW - 10;
                int btnH = 13;
                int intY = y + 14; // Corrected interaction line Y

                // Click on button or current value area on the second line
                if ((mx >= btnX && mx <= btnX + btnW && my >= intY && my <= intY + btnH) ||
                        (mx >= sx + 12 && mx <= btnX - 4 && my >= intY && my <= intY + btnH)) {
                    if (isEditing) {
                        applyTextEdit();
                    } else {
                        editingSettingIndex = i;
                        editingText = stringSetting.get();
                    }
                    return true;
                }
                y += 32;
            }
        }

        // Clicking settings area clears text editing if not on a text field
        editingSettingIndex = -1;
        return false;
    }

    private boolean handleConfigsTabClick(int mx, int my) {
        int cx = panelX + BORDER_THICKNESS + 10;
        int contentW = PANEL_W - BORDER_THICKNESS * 2 - 20;
        int top = panelY + BORDER_THICKNESS;

        int y = top + 8 - (int) configScroll;
        y += 16; // title

        // Skip status message line if visible
        if (!configStatusMsg.isEmpty() && System.currentTimeMillis() - configStatusTime < 3000) {
            y += 14;
        }

        y += 8; // separator

        if (!savedConfigs.isEmpty()) {
            int ROW_H = 22;
            for (int i = 0; i < savedConfigs.size(); i++) {
                // Update button
                int updBtnW = 40;
                int updBtnX = cx + contentW - updBtnW - 85;
                if (mx >= updBtnX && mx <= updBtnX + updBtnW && my >= y + 3 && my <= y + ROW_H - 3) {
                    ConfigManager.saveAs(savedConfigs.get(i));
                    configStatusMsg = "Updated: " + savedConfigs.get(i);
                    configStatusTime = System.currentTimeMillis();
                    return true;
                }

                // Load button
                int loadBtnW = 35;
                int loadBtnX = cx + contentW - loadBtnW - 45;
                if (mx >= loadBtnX && mx <= loadBtnX + loadBtnW && my >= y + 3 && my <= y + ROW_H - 3) {
                    ConfigManager.loadConfig(savedConfigs.get(i));
                    configStatusMsg = "Loaded: " + savedConfigs.get(i);
                    configStatusTime = System.currentTimeMillis();
                    return true;
                }

                // Delete button
                int delBtnW = 35;
                int delBtnX = cx + contentW - delBtnW - 5;
                if (mx >= delBtnX && mx <= delBtnX + delBtnW && my >= y + 3 && my <= y + ROW_H - 3) {
                    ConfigManager.deleteConfig(savedConfigs.get(i));
                    configStatusMsg = "Deleted: " + savedConfigs.get(i);
                    configStatusTime = System.currentTimeMillis();
                    savedConfigs = ConfigManager.listConfigs();
                    return true;
                }

                y += ROW_H;
            }
        }
        return false;
    }

    private boolean handleSettingsTabClick(int mx, int my) {
        int cx = panelX + BORDER_THICKNESS + 10;
        int contentW = PANEL_W - BORDER_THICKNESS * 2 - 20;
        int top = panelY + BORDER_THICKNESS;

        int y = top + 8 - (int) settingsTabScroll;
        y += 18; // "GUI Colors" header

        Setting.StringSetting[] colorSettings = {
                Screens.sidePanelBg, Screens.mainPaneBg, Screens.accentColorHex,
                Screens.categoryHeaderBg, Screens.categoryTextHex
        };

        // Color row buttons
        for (int i = 0; i < colorSettings.length; i++) {
            boolean isEditing = (settingsTabEditIndex == i);
            int btnW = isEditing ? 40 : 60;
            int btnX = cx + contentW - btnW - 4;
            int btnH = 14;

            if (mx >= btnX && mx <= btnX + btnW && my >= y + 1 && my <= y + 1 + btnH) {
                if (isEditing) {
                    // Save the edited color
                    String trimmed = settingsTabEditText.trim();
                    if (!trimmed.isEmpty()) {
                        colorSettings[i].set(trimmed);
                    }
                    settingsTabEditIndex = -1;
                    settingsTabEditText = "";
                } else {
                    // Start editing this color
                    settingsTabEditIndex = i;
                    settingsTabEditText = colorSettings[i].get();
                }
                return true;
            }
            y += 22;
        }

        y += 4;

        // Reset button
        int resetBtnW = 110;
        int resetBtnX = cx + contentW - resetBtnW;
        if (mx >= resetBtnX && mx <= resetBtnX + resetBtnW && my >= y && my <= y + 16) {
            Screens.resetDefaults();
            settingsTabEditIndex = -1;
            return true;
        }
        y += 26;
        y += 14; // separator

        y += 18; // "Chat Logging" header

        // Show Toggle Notifications
        if (mx >= cx && mx < cx + contentW && my >= y && my < y + 22) {
            Screens.showToggleNotifications.toggle();
            return true;
        }
        y += 26;

        // Save Chat to File
        if (mx >= cx && mx < cx + contentW && my >= y && my < y + 22) {
            Screens.saveChatToFile.toggle();
            return true;
        }
        y += 24;

        // Prefix bounds checking
        int prefixBtnX = cx + contentW - 40;
        int prefixBtnY = y - 2;
        int prefixBtnW = 40;
        int prefixBtnH = 12;
        if (mx >= prefixBtnX && mx < prefixBtnX + prefixBtnW && my >= prefixBtnY && my < prefixBtnY + prefixBtnH) {
            settingsTabEditIndex = 999; 
            settingsTabEditText = Screens.commandPrefix.get();
            return true;
        }

        // Click elsewhere clears editing
        settingsTabEditIndex = -1;
        return false;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        double mouseX = click.x();
        @SuppressWarnings("unused")
        double mouseY = click.y();
        @SuppressWarnings("unused")
        int button = click.button();

        if (draggingSettingsScrollbar) {
            int top = panelY + BORDER_THICKNESS + 8;
            int bottom = panelY + PANEL_H - BORDER_THICKNESS - 8;
            int viewportHeight = bottom - top;
            int contentHeight = 0;
            
            if (activeTab == Tab.MODULES) contentHeight = getSettingsContentHeight();
            else if (activeTab == Tab.CONFIGURATIONS) contentHeight = getConfigsContentHeight();
            else if (activeTab == Tab.SETTINGS) contentHeight = getSettingsTabContentHeight();

            if (contentHeight > viewportHeight) {
                float ratio = (float) deltaY / (viewportHeight
                        - Math.max(20, (int) ((float) viewportHeight / contentHeight * viewportHeight)));
                
                if (activeTab == Tab.MODULES) {
                    settingsScroll += ratio * (contentHeight - viewportHeight);
                    clampSettingsScroll();
                } else if (activeTab == Tab.CONFIGURATIONS) {
                    configScroll += ratio * (contentHeight - viewportHeight);
                    clampConfigsScroll();
                } else if (activeTab == Tab.SETTINGS) {
                    settingsTabScroll += ratio * (contentHeight - viewportHeight);
                    clampSettingsTabScroll();
                }
            }
            return true;
        }

        if (draggingSlider >= 0 && selectedModule != null) {
            List<Setting<?>> settings = selectedModule.getSettings();
            if (draggingSlider < settings.size()) {
                Setting<?> s = settings.get(draggingSlider);
                int sx = panelX + SIDEBAR_W + 1;
                int settingsW = PANEL_W - SIDEBAR_W - 1 - BORDER_THICKNESS;
                int sliderX = sx + 12;
                int sliderW = settingsW - 24;

                float progress = (float) (mouseX - sliderX) / sliderW;
                progress = Math.max(0, Math.min(1, progress));

                if (s instanceof Setting.IntSetting intSetting) {
                    int newVal = (int) (intSetting.min + progress * (intSetting.max - intSetting.min));
                    intSetting.set(newVal);
                } else if (s instanceof Setting.DoubleSetting doubleSetting) {
                    double newVal = doubleSetting.min + progress * (doubleSetting.max - doubleSetting.min);
                    doubleSetting.set(newVal);
                }
                return true;
            }
        }
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        @SuppressWarnings("unused")
        double mouseX = click.x();
        @SuppressWarnings("unused")
        double mouseY = click.y();
        @SuppressWarnings("unused")
        int button = click.button();

        draggingSlider = -1;
        draggingSettingsScrollbar = false;
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // Dropdown scroll
        if (currentOpenDropdown instanceof Setting.StringSetSetting setS && selectedModule != null) {
            if (mouseX >= panelX + SIDEBAR_W && mouseX < panelX + PANEL_W
                    && mouseY >= panelY && mouseY < panelY + PANEL_H) {

                int maxVisible = 8;
                int rowH = 14;
                // Account for filtered list
                List<String> scrollOptions = setS.getSortedOptions();
                if (!entitySearchQuery.isEmpty()) {
                    String sq = entitySearchQuery.toLowerCase();
                    scrollOptions = scrollOptions.stream().filter(o -> {
                        String d = o.startsWith("minecraft:") ? o.substring(10) : o;
                        return d.toLowerCase().contains(sq);
                    }).collect(java.util.stream.Collectors.toList());
                }
                int maxEntityScroll = Math.max(0, scrollOptions.size() * rowH - maxVisible * rowH);

                double currentScroll = dropdownScrolls.getOrDefault(setS, 0.0);
                currentScroll -= verticalAmount * 14;
                currentScroll = Math.max(0, Math.min(currentScroll, maxEntityScroll));

                dropdownScrolls.put(setS, currentScroll);
                return true;
            }
        }

        // Sidebar scroll (Modules tab only)
        if (activeTab == Tab.MODULES && mouseX >= panelX && mouseX < panelX + SIDEBAR_W
                && mouseY >= panelY && mouseY < panelY + PANEL_H) {
            sidebarScroll -= verticalAmount * 10;

            // Calculate total sidebar content height (Headers + Visible Modules)
            int totalHeight = 0;
            if (!searchQuery.isEmpty()) {
                totalHeight = filteredModules.size() * MODULE_ROW_H;
            } else {
                for (net.matrix.systems.modules.Category cat : allCategories) {
                    boolean hasModules = false;
                    int catCount = 0;
                    for (Module m : filteredModules) {
                        if (m.getCategory() == cat) {
                            catCount++;
                            hasModules = true;
                        }
                    }
                    if (hasModules || cat == net.matrix.systems.modules.Category.MACE) {
                        totalHeight += MODULE_ROW_H; // Header
                        if (categoryExpanded.getOrDefault(cat, true)) {
                            totalHeight += catCount * MODULE_ROW_H;
                        }
                    }
                }
            }

            double maxScroll = Math.max(0, totalHeight - PANEL_H + 20); // Small buffer
            sidebarScroll = Math.max(0, Math.min(sidebarScroll, maxScroll));
            return true;
        }

        // Settings panel scroll (Modules tab only)
        if (activeTab == Tab.MODULES && mouseX >= panelX + SIDEBAR_W && mouseX < panelX + PANEL_W
                && mouseY >= panelY && mouseY < panelY + PANEL_H) {
            settingsScroll -= verticalAmount * 12;
            clampSettingsScroll();
            return true;
        }

        // Config tab scroll
        if (activeTab == Tab.CONFIGURATIONS && mouseX >= panelX && mouseX < panelX + PANEL_W
                && mouseY >= panelY && mouseY < panelY + PANEL_H) {
            configScroll -= verticalAmount * 12;
            clampConfigsScroll();
            return true;
        }

        // Settings tab scroll
        if (activeTab == Tab.SETTINGS && mouseX >= panelX && mouseX < panelX + PANEL_W
                && mouseY >= panelY && mouseY < panelY + PANEL_H) {
            settingsTabScroll -= verticalAmount * 12;
            clampSettingsTabScroll();
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void clampSettingsScroll() {
        if (selectedModule == null) {
            settingsScroll = 0;
            return;
        }
        int contentHeight = getSettingsContentHeight();
        int viewportHeight = PANEL_H - BORDER_THICKNESS * 2 - 20;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        settingsScroll = Math.max(0, Math.min(settingsScroll, maxScroll));
    }

    private void clampConfigsScroll() {
        int contentHeight = getConfigsContentHeight();
        int viewportHeight = PANEL_H - BORDER_THICKNESS * 2 - 16;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        configScroll = Math.max(0, Math.min(configScroll, maxScroll));
    }

    private void clampSettingsTabScroll() {
        int contentHeight = getSettingsTabContentHeight();
        int viewportHeight = PANEL_H - BORDER_THICKNESS * 2 - 16;
        int maxScroll = Math.max(0, contentHeight - viewportHeight);
        settingsTabScroll = Math.max(0, Math.min(settingsTabScroll, maxScroll));
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        int keyCode = input.key();
        @SuppressWarnings("unused")
        int scanCode = input.scancode();
        @SuppressWarnings("unused")
        int modifiers = input.modifiers();

        // Text editing mode (Modules tab)
        if (editingSettingIndex >= 0 && selectedModule != null) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                applyTextEdit();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                editingSettingIndex = -1;
                editingText = "";
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!editingText.isEmpty()) {
                    editingText = editingText.substring(0, editingText.length() - 1);
                }
                return true;
            }
            // Other keys handled in charTyped
            return true;
        }

        // Settings tab color editing
        if (settingsTabEditIndex >= 0) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                settingsTabEditIndex = -1;
                settingsTabEditText = "";
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!settingsTabEditText.isEmpty()) {
                    settingsTabEditText = settingsTabEditText.substring(0, settingsTabEditText.length() - 1);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                // Apply the color edit
                Setting.StringSetting[] colorSettings = {
                        net.matrix.systems.Screens.sidePanelBg, net.matrix.systems.Screens.mainPaneBg,
                        net.matrix.systems.Screens.accentColorHex,
                        net.matrix.systems.Screens.categoryHeaderBg, net.matrix.systems.Screens.categoryTextHex
                };
                if (settingsTabEditIndex >= 0 && settingsTabEditIndex < colorSettings.length) {
                    String trimmed = settingsTabEditText.trim();
                    if (!trimmed.isEmpty()) {
                        colorSettings[settingsTabEditIndex].set(trimmed);
                    }
                }
                settingsTabEditIndex = -1;
                settingsTabEditText = "";
                return true;
            }
            return true;
        }

        // Keybind listening
        if (listeningForKey) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                listeningForKey = false;
            } else if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                // Delete/Backspace clears keybind
                selectedModule.setKey(-1);
                listeningForKey = false;
            } else {
                selectedModule.setKey(keyCode);
                listeningForKey = false;
            }
            return true;
        }

        // Entity search bar input
        if (entitySearchFocused) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                entitySearchFocused = false;
                entitySearchQuery = "";
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!entitySearchQuery.isEmpty()) {
                    entitySearchQuery = entitySearchQuery.substring(0, entitySearchQuery.length() - 1);
                    dropdownScrolls.clear();
                }
                return true;
            }
            return true;
        }

        // Search bar input
        if (searchFocused) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    rebuildFilteredModules();
                    sidebarScroll = 0;
                }
                return true;
            }
            return true;
        }

        // Config name input
        if (configNameFocused) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                configNameFocused = false;
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                if (!newConfigName.isEmpty()) {
                    newConfigName = newConfigName.substring(0, newConfigName.length() - 1);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                if (!newConfigName.isBlank()) {
                    ConfigManager.saveAs(newConfigName.trim());
                    configStatusMsg = "Saved: " + newConfigName.trim();
                    configStatusTime = System.currentTimeMillis();
                    newConfigName = "";
                    savedConfigs = ConfigManager.listConfigs();
                }
                configNameFocused = false;
                return true;
            }
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }

        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharInput input) {
        char chr = (char) input.codepoint();
        @SuppressWarnings("unused")
        int modifiers = input.modifiers();

        // Text editing for slider value (Modules tab)
        if (editingSettingIndex >= 0) {
            // If it's a hex color setting, allow # and hex digits
            Setting<?> s = selectedModule.getSettings().get(editingSettingIndex);
            if (s.name.toLowerCase().contains("hex") || s.name.toLowerCase().contains("color")
                    || s.name.toLowerCase().contains("bg")) {
                if (chr == '#' || (chr >= '0' && chr <= '9') || (chr >= 'a' && chr <= 'f')
                        || (chr >= 'A' && chr <= 'F')) {
                    if (editingText.length() < 9) { // # + 8 hex digits max
                        editingText += chr;
                    }
                }
            } else if (chr == '-' || chr == '.' || (chr >= '0' && chr <= '9')) {
                editingText += chr;
            }
            return true;
        }

        // Settings tab color editing
        if (settingsTabEditIndex >= 0) {
            if (chr == '#' || (chr >= '0' && chr <= '9') || (chr >= 'a' && chr <= 'f')
                    || (chr >= 'A' && chr <= 'F')) {
                if (settingsTabEditText.length() < 9) {
                    settingsTabEditText += chr;
                }
            }
            return true;
        }

        // Entity search bar
        if (entitySearchFocused) {
            if (chr >= 32) {
                entitySearchQuery += chr;
                dropdownScrolls.clear();
            }
            return true;
        }

        // Search bar
        if (searchFocused) {
            if (chr >= 32) { // printable characters
                searchQuery += chr;
                rebuildFilteredModules();
                sidebarScroll = 0;
            }
            return true;
        }

        // Config name input
        if (configNameFocused) {
            if (chr >= 32 && newConfigName.length() < 30) {
                newConfigName += chr;
            }
            return true;
        }

        return super.charTyped(input);
    }

    private void applyTextEdit() {
        if (editingSettingIndex < 0 || selectedModule == null)
            return;

        List<Setting<?>> settings = selectedModule.getSettings();
        if (editingSettingIndex >= settings.size()) {
            editingSettingIndex = -1;
            return;
        }

        Setting<?> s = settings.get(editingSettingIndex);
        try {
            if (s instanceof Setting.IntSetting intSetting) {
                int val = Integer.parseInt(editingText.trim());
                // val = Math.max(intSetting.min, Math.min(intSetting.max, val));
                intSetting.set(val);
            } else if (s instanceof Setting.DoubleSetting doubleSetting) {
                double val = Double.parseDouble(editingText.trim());
                // val = Math.max(doubleSetting.min, Math.min(doubleSetting.max, val));
                doubleSetting.set(val);
            } else if (s instanceof Setting.StringSetting stringSetting) {
                stringSetting.set(editingText.trim());
            }
        } catch (NumberFormatException ignored) {
            // Invalid input — just ignore
        }

        editingSettingIndex = -1;
        editingText = "";
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ── Helper methods ──

    private int darken(int color, double amount) {
        int a = (color >> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * (1.0 - amount));
        int g = (int) (((color >> 8) & 0xFF) * (1.0 - amount));
        int b = (int) ((color & 0xFF) * (1.0 - amount));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private boolean isLight(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double darkness = 1 - (0.299 * r + 0.587 * g + 0.114 * b) / 255;
        return darkness < 0.5;
    }
}
