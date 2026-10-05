package dev.axiommcp.mixin;

import com.moulberry.axiom.RayCaster;
import com.moulberry.axiom.tools.Tool;
import dev.axiommcp.editor.NativeToolInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Tool.class, remap = false)
public interface ToolInputMixin {
    @Inject(method = "raycastBlock(ZZZ)Lcom/moulberry/axiom/RayCaster$RaycastResult;", at = @At("HEAD"), cancellable = true)
    private static void axiomMcpHit(boolean selection, boolean nonSolid, boolean fluids, CallbackInfoReturnable<RayCaster.RaycastResult> ci) {
        var input = NativeToolInput.current();
        if (input != null) ci.setReturnValue(input.hit);
    }
    @Inject(method = "isMouseDown", at = @At("HEAD"), cancellable = true)
    private static void axiomMcpRelease(int button, CallbackInfoReturnable<Boolean> ci) {
        if (NativeToolInput.current() != null) ci.setReturnValue(false);
    }
    @Inject(method = "isShiftDown", at = @At("HEAD"), cancellable = true)
    private static void axiomMcpShift(CallbackInfoReturnable<Boolean> ci) {
        var input = NativeToolInput.current();
        if (input != null) ci.setReturnValue(input.shift);
    }
}
