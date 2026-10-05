package dev.axiommcp.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.axiommcp.editor.ScreenshotService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {
    @Inject(method = "blitToScreen(II)V", at = @At("TAIL"))
    private void axiomMcpCapture(int width, int height, CallbackInfo ci) {
        ScreenshotService.onBlit((RenderTarget)(Object)this);
    }
}
