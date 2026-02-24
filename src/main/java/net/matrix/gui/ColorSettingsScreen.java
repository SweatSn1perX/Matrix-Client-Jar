package net.matrix.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.Click;
import net.minecraft.text.Text;

/**
 * Color customization screen for the Click GUI accent color.
 * Opened when right-clicking the ClickGUI module.
 * Features RGB sliders with live preview.
 */
public class ColorSettingsScreen extends Screen {
    private final Screen parent;

    private int panelX, panelY;
    private static final int PANEL_W = 220;
    private static final int PANEL_H = 160;

    // Temp color values for editing
    private int red, green, blue;

    // Which slider is being dragged (-1 = none)
    private int draggingSlider = -1;

    public ColorSettingsScreen(Screen parent) {
        super(Text.of("GUI Color Settings"));
        this.parent = parent;
        this.red = net.matrix.systems.Screens.accentColor.r;
        this.green = net.matrix.systems.Screens.accentColor.g;
        this.blue = net.matrix.systems.Screens.accentColor.b;
    }

    @Override
    protected void init() {
        super.init();
        panelX = (width - PANEL_W) / 2;
        panelY = (height - PANEL_H) / 2;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Dim background
        context.fill(0, 0, width, height, 0xAA000000);

        int previewColor = (255 << 24) | (red << 16) | (green << 8) | blue;

        // Panel background
        context.fill(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, 0xFF1A1A1A);

        // Header with live preview color
        context.fill(panelX + 1, panelY + 1, panelX + PANEL_W - 1, panelY + 22, previewColor);
        context.drawText(textRenderer, "GUI Colors", panelX + 8, panelY + 7, 0xFFFFFFFF, true);

        // Close [X]
        context.drawText(textRenderer, "X", panelX + PANEL_W - 14, panelY + 7, 0xFFFFFFFF, false);

        int y = panelY + 30;
        int sliderX = panelX + 50;
        int sliderW = PANEL_W - 60;

        // Handle dragging
        if (draggingSlider >= 0) {
            float progress = Math.max(0, Math.min(1, (float) (mouseX - sliderX) / sliderW));
            int val = (int) (progress * 255);
            switch (draggingSlider) {
                case 0 -> red = val;
                case 1 -> green = val;
                case 2 -> blue = val;
            }
            applyColor();
        }

        // ── Red Slider ──
        context.drawText(textRenderer, "Red:", panelX + 10, y + 2, 0xFFFF6666, false);
        context.fill(sliderX, y, sliderX + sliderW, y + 10, 0xFF333333);
        context.fill(sliderX, y, sliderX + (int) (sliderW * (red / 255.0f)), y + 10, 0xFFFF4444);
        context.drawText(textRenderer, String.valueOf(red), sliderX + sliderW + 5, y + 1, 0xFFAAAAAA, false);
        y += 24;

        // ── Green Slider ──
        context.drawText(textRenderer, "Green:", panelX + 10, y + 2, 0xFF66FF66, false);
        context.fill(sliderX, y, sliderX + sliderW, y + 10, 0xFF333333);
        context.fill(sliderX, y, sliderX + (int) (sliderW * (green / 255.0f)), y + 10, 0xFF44FF44);
        context.drawText(textRenderer, String.valueOf(green), sliderX + sliderW + 5, y + 1, 0xFFAAAAAA, false);
        y += 24;

        // ── Blue Slider ──
        context.drawText(textRenderer, "Blue:", panelX + 10, y + 2, 0xFF6666FF, false);
        context.fill(sliderX, y, sliderX + sliderW, y + 10, 0xFF333333);
        context.fill(sliderX, y, sliderX + (int) (sliderW * (blue / 255.0f)), y + 10, 0xFF4444FF);
        context.drawText(textRenderer, String.valueOf(blue), sliderX + sliderW + 5, y + 1, 0xFFAAAAAA, false);
        y += 30;

        // ── Color Preview Box ──
        context.drawText(textRenderer, "Preview:", panelX + 10, y + 4, 0xFFAAAAAA, false);
        context.fill(sliderX, y, sliderX + 50, y + 16, previewColor);
        context.fill(sliderX + 55, y, sliderX + sliderW, y + 16, 0xFF333333);

        // Reset button
        int resetX = sliderX + 55;
        context.fill(resetX, y, resetX + 50, y + 16, 0xFF552222);
        context.drawText(textRenderer, "Reset", resetX + 8, y + 4, 0xFFFFAAAA, false);

        // Border
        context.fill(panelX, panelY, panelX + PANEL_W, panelY + 1, previewColor);
        context.fill(panelX, panelY + PANEL_H - 1, panelX + PANEL_W, panelY + PANEL_H, previewColor);
        context.fill(panelX, panelY, panelX + 1, panelY + PANEL_H, previewColor);
        context.fill(panelX + PANEL_W - 1, panelY, panelX + PANEL_W, panelY + PANEL_H, previewColor);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(Click click, boolean released) {
        double mouseX = click.x();
        double mouseY = click.y();

        // Close button [X]
        if (mouseX >= panelX + PANEL_W - 18 && mouseX <= panelX + PANEL_W &&
                mouseY >= panelY + 2 && mouseY <= panelY + 20) {
            close();
            return true;
        }

        // Outside panel → close
        if (mouseX < panelX || mouseX > panelX + PANEL_W || mouseY < panelY || mouseY > panelY + PANEL_H) {
            close();
            return true;
        }

        int sliderX = panelX + 50;
        int sliderW = PANEL_W - 60;

        // Check which slider
        int y = panelY + 30;
        for (int i = 0; i < 3; i++) {
            if (mouseX >= sliderX && mouseX <= sliderX + sliderW && mouseY >= y && mouseY <= y + 10) {
                draggingSlider = i;
                float progress = Math.max(0, Math.min(1, (float) (mouseX - sliderX) / sliderW));
                int val = (int) (progress * 255);
                switch (i) {
                    case 0 -> red = val;
                    case 1 -> green = val;
                    case 2 -> blue = val;
                }
                applyColor();
                return true;
            }
            y += 24;
        }

        // Reset button
        y += 6; // after sliders
        int resetX = sliderX + 55;
        if (mouseX >= resetX && mouseX <= resetX + 50 && mouseY >= y && mouseY <= y + 16) {
            red = 45;
            green = 150;
            blue = 255;
            applyColor();
            return true;
        }

        return super.mouseClicked(click, released);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingSlider = -1;
        return super.mouseReleased(click);
    }

    private void applyColor() {
        net.matrix.systems.Screens.accentColorHex.set(String.format("#%02X%02X%02X", red, green, blue));
        net.matrix.systems.Screens.updateColors();
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
