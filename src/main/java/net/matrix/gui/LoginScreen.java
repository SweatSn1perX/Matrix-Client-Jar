package net.matrix.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.matrix.systems.auth.AuthManager;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.client.gui.Click;
import org.lwjgl.glfw.GLFW;

public class LoginScreen extends Screen {

    private TextFieldWidget usernameField;
    private TextFieldWidget passwordField;

    private boolean registerMode = false; // Show Login by default

    private String statusStr = "Please login to access Matrix Client.";
    private int statusColor = 0xFFCCCCCC; // Default light gray

    // Matrix aesthetic colors
    private static final int BG_COLOR = 0xFF120818;    // Very dark purple
    private static final int PANEL_BG = 0xFF2A153A;
    private static final int ACCENT_COLOR = 0xFF9D65FF;
    private static final int TOGGLE_COLOR = 0xFF7B8CFF; // Clickable link color

    private static final Identifier LOGO_ID = Identifier.of("matrix", "textures/gui/logo.png");

    // Toggle text bounds (for click detection)
    private int toggleTextX, toggleTextY, toggleTextW;

    public LoginScreen() {
        super(Text.of("Matrix Gatekeeper"));
    }

    @Override
    protected void init() {
        int panelW = 320;
        int panelH = 260;
        int centerX = width / 2;
        int panelY = (height - panelH) / 2;

        int fieldWidth = 240;
        int fieldX = centerX - (fieldWidth / 2);

        // Offset fields relative to panel
        int fieldY = panelY + 80;

        // Username Field
        this.usernameField = new TextFieldWidget(textRenderer, fieldX, fieldY, fieldWidth, 20,
                Text.of("Username"));
        this.usernameField.setMaxLength(32);
        this.addDrawableChild(usernameField);
        fieldY += 50;

        // Password Field
        this.passwordField = new TextFieldWidget(textRenderer, fieldX, fieldY, fieldWidth, 20,
                Text.of("Password"));
        this.passwordField.setMaxLength(64);
        this.addDrawableChild(passwordField);
        fieldY += 50;

        // Submit Button
        this.addDrawableChild(ButtonWidget.builder(Text.of(registerMode ? "Register" : "Login"), button -> {
            if (registerMode) {
                attemptRegister();
            } else {
                attemptLogin();
            }
        }).dimensions(fieldX, fieldY, fieldWidth, 20).build());

        // Focus username by default
        this.setInitialFocus(usernameField);
        usernameField.setFocused(true);
    }


    // ─── Logic ───────────────────────────

    private void attemptLogin() {
        String username = usernameField.getText();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            statusStr = "Username and Password cannot be empty.";
            statusColor = 0xFFFF5555;
            return;
        }

        statusStr = "Logging in...";
        statusColor = 0xFFFFFFAA;

        new Thread(() -> {
            String result = AuthManager.attemptLogin(username, password);
            if (this.client != null) {
                this.client.execute(() -> {
                    if ("SUCCESS".equals(result)) {
                        statusStr = "Welcome back! Loading Matrix...";
                        statusColor = 0xFF55FF55;
                        net.matrix.systems.config.ConfigManager.loadForUser(username);
                        this.client.setScreen(new net.minecraft.client.gui.screen.TitleScreen());
                    } else {
                        statusStr = result;
                        statusColor = 0xFFFF5555;
                    }
                });
            }
        }).start();
    }

    private void attemptRegister() {
        String username = usernameField.getText();
        String password = passwordField.getText();

        if (username.isEmpty() || password.isEmpty()) {
            statusStr = "Username and Password cannot be empty.";
            statusColor = 0xFFFF5555;
            return;
        }

        statusStr = "Registering...";
        statusColor = 0xFFFFFFAA;

        new Thread(() -> {
            String result = AuthManager.attemptRegister(username, password);
            if (this.client != null) {
                this.client.execute(() -> {
                    if ("SUCCESS".equals(result)) {
                        statusStr = "Account Created! Loading Matrix...";
                        statusColor = 0xFF55FF55;
                        net.matrix.systems.config.ConfigManager.loadForUser(username);
                        this.client.setScreen(new net.minecraft.client.gui.screen.TitleScreen());
                    } else {
                        statusStr = result;
                        statusColor = 0xFFFF5555;
                    }
                });
            }
        }).start();
    }

    // ─── Input Handling ──────────────────────

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.key() == GLFW.GLFW_KEY_ESCAPE) {
            return true; // Prevent escaping
        }

        if (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (registerMode) {
                attemptRegister();
            } else {
                attemptLogin();
            }
            return true;
        }

        return super.keyPressed(input);
    }

    @Override
    public boolean mouseClicked(Click click, boolean released) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            boolean hoveringToggle = mouseX >= toggleTextX && mouseX <= toggleTextX + toggleTextW && mouseY >= toggleTextY && mouseY <= toggleTextY + 10;
            if (hoveringToggle) {
                registerMode = !registerMode;
                statusStr = registerMode ? "Create a free Matrix account." : "Please login to access Matrix Client.";
                statusColor = 0xFFCCCCCC;
                this.clearAndInit();
                return true;
            }
        }
        return super.mouseClicked(click, released);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    // ─── Rendering ────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Background
        ctx.fill(0, 0, width, height, BG_COLOR);
        renderMatrixPattern(ctx);
        ctx.fill(0, 0, width, height, 0x66000000);

        // Calculate flexible Panel width based on text
        String title = registerMode ? "Matrix Client — Register" : "Matrix Client — Login";
        int statusW = textRenderer.getWidth(statusStr);
        int titleW = textRenderer.getWidth(title);
        
        int panelW = 320;
        panelW = Math.max(panelW, Math.max(statusW, titleW) + 60); // Flex width (with 30px padding on each side)
        
        int panelH = 260;
        int panelX = (width - panelW) / 2;
        int panelY = (height - panelH) / 2;

        drawBorder(ctx, panelX, panelY, panelW, panelH, ACCENT_COLOR, 2);
        ctx.fill(panelX + 2, panelY + 2, panelX + panelW - 2, panelY + panelH - 2, PANEL_BG);

        // Title
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, panelY + 20, ACCENT_COLOR);

        // Status Text
        ctx.drawCenteredTextWithShadow(textRenderer, statusStr, width / 2, panelY + 40, statusColor);

        // Labels
        ctx.drawTextWithShadow(textRenderer, "Username:", usernameField.getX(), usernameField.getY() - 12, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "Password:", passwordField.getX(), passwordField.getY() - 12, 0xFFFFFFFF);

        // Render widgets
        super.render(ctx, mouseX, mouseY, delta);

        // Custom password masking
        if (!passwordField.getText().isEmpty()) {
            ctx.fill(passwordField.getX() + 4, passwordField.getY() + 4,
                    passwordField.getX() + passwordField.getWidth() - 4,
                    passwordField.getY() + passwordField.getHeight() - 4, 0xFF000000);
            ctx.drawText(textRenderer, "*".repeat(passwordField.getText().length()), passwordField.getX() + 5,
                    passwordField.getY() + 6, 0xFFFFFFFF, false);
        }

        // Mode Toggle Link
        String toggleStr = registerMode ? "Already have an account? Login here" : "Need an account? Register here";
        toggleTextW = textRenderer.getWidth(toggleStr);
        toggleTextX = (width - toggleTextW) / 2;
        toggleTextY = (height - 260) / 2 + 260 - 30; // panelY + panelH - 30

        boolean hoveringToggle = mouseX >= toggleTextX && mouseX <= toggleTextX + toggleTextW && mouseY >= toggleTextY && mouseY <= toggleTextY + 10;
        int toggleColor = hoveringToggle ? 0xFFFFFFFF : TOGGLE_COLOR;
        ctx.drawTextWithShadow(textRenderer, toggleStr, toggleTextX, toggleTextY, toggleColor);
    }

    // ─── Background Pattern ──────────────────────

    private void renderMatrixPattern(DrawContext ctx) {
        int logoSize = 80;
        int spacing = logoSize + 16;
        long timeMs = System.currentTimeMillis();
        int yOffset = (int) ((timeMs / 30) % spacing);

        for (int col = -1; col <= (width / spacing) + 1; col++) {
            for (int row = -2; row <= (height / spacing) + 2; row++) {
                int drawX = col * spacing;
                int drawY = (row * spacing) + yOffset;
                ctx.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO_ID, drawX, drawY, 0.0f, 0.0f, logoSize, logoSize,
                        logoSize, logoSize);
            }
        }
    }

    private void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color, int thickness) {
        ctx.fill(x, y, x + w, y + thickness, color); // Top
        ctx.fill(x, y + h - thickness, x + w, y + h, color); // Bottom
        ctx.fill(x, y, x + thickness, y + h, color); // Left
        ctx.fill(x + w - thickness, y, x + w, y + h, color); // Right
    }
}
