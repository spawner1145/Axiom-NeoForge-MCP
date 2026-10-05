package dev.axiommcp.editor;

import com.moulberry.axiom.RayCaster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Scoped to the client thread during native tool initialization/release; never synthetic OS input. */
public final class NativeToolInput implements AutoCloseable {
    private static final ThreadLocal<NativeToolInput> CURRENT = new ThreadLocal<>();
    public final RayCaster.RaycastResult hit;
    public final boolean shift;
    private NativeToolInput(BlockPos point, Direction face, boolean shift) {
        if (CURRENT.get() != null) throw new IllegalStateException("Nested native tool input");
        this.hit = new RayCaster.RaycastResult(point, Vec3.atCenterOf(point), face, false);
        this.shift = shift;
        CURRENT.set(this);
    }
    public static NativeToolInput begin(BlockPos point, Direction face, boolean shift) { return new NativeToolInput(point, face, shift); }
    public static NativeToolInput current() { return CURRENT.get(); }
    @Override public void close() { CURRENT.remove(); }
}
