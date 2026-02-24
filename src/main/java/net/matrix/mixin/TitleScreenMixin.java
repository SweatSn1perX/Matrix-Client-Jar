package net.matrix.mixin;

import net.matrix.gui.LoginScreen;
import net.matrix.systems.auth.AuthManager;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class TitleScreenMixin {

    @Unique
    private static boolean hwidCheckStarted = false;

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo info) {
        if (AuthManager.isLoggedIn()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen instanceof LoginScreen) return;

        // Direct to login, no auto-login
        client.setScreen(new LoginScreen());
    }
}
