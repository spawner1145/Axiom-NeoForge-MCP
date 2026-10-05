package dev.axiommcp.mixin;

import com.moulberry.axiom.editor.windows.MainMenuBar;
import dev.axiommcp.ui.McpMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MainMenuBar.class, remap = false)
public final class MainMenuBarMixin {
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Limgui/moulberry92/ImGui;endMainMenuBar()V"))
    private static void axiomMcpMenu(CallbackInfo ci) { McpMenu.menu(); }
    @Inject(method = "render", at = @At("RETURN"))
    private static void axiomMcpPanel(CallbackInfo ci) { McpMenu.panel(); }
}
