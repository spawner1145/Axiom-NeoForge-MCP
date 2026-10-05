package dev.axiommcp.editor;

import com.google.gson.*;
import com.moulberry.axiom.clipboard.Selection;
import com.moulberry.axiom.tools.Tool;
import com.moulberry.axiom.tools.ToolManager;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;

/** Bound as mcp: native creative operations submit immediately; block helpers stage until script success. */
public final class BuildApi {
    private final WorldEdits.Transaction transaction;
    private boolean nativeUsed;
    public BuildApi(WorldEdits.Transaction transaction) { this.transaction = transaction; }
    public boolean nativeUsed() { return nativeUsed; }
    private JsonObject nativeArgs(java.util.Map<?,?> options) {
        if(transaction==null)throw new IllegalStateException("Native creation helpers require execute_code");
        if(!transaction.changes.isEmpty())throw new IllegalStateException("Use separate calls for native tools and staged block helpers");
        nativeUsed=true;return new Gson().toJsonTree(options).getAsJsonObject();
    }
    public JsonObject stroke(java.util.Map<?,?> options) throws Exception { return NativeTools.stroke(nativeArgs(options)); }
    public JsonObject nativeShape(java.util.Map<?,?> options) throws Exception { return NativeTools.shape(nativeArgs(options)); }
    public JsonObject nativePath(java.util.Map<?,?> options) throws Exception { return NativeTools.path(nativeArgs(options)); }
    public JsonObject nativeMesh(java.util.Map<?,?> options) throws Exception { return NativeTools.mesh(nativeArgs(options)); }
    public JsonObject stamp(java.util.Map<?,?> options) throws Exception { return NativeTools.stamp(nativeArgs(options)); }
    public BlockState block(String specification) throws Exception {
        return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), specification, false).blockState();
    }
    public BlockPos pos(int x, int y, int z) { return new BlockPos(x, y, z); }
    public void setBlock(int x, int y, int z, String state) throws Exception { setBlock(pos(x,y,z), block(state), null); }
    public void setBlock(BlockPos pos, BlockState state) { setBlock(pos, state, null); }
    public void setBlock(BlockPos pos, BlockState state, CompoundTag nbt) {
        if(nativeUsed)throw new IllegalStateException("Use separate calls for native tools and staged block helpers");
        if (transaction == null) throw new IllegalStateException("No active helper transaction");
        transaction.set(pos, state, nbt);
    }
    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, String state) throws Exception {
        fill(pos(x1,y1,z1), pos(x2,y2,z2), block(state), false, null);
    }
    public void fill(BlockPos a, BlockPos b, BlockState state, boolean hollow, BlockState replace) {
        Bounds bounds = Bounds.of(a,b); bounds.checkSize(WorldEdits.MAX_BLOCKS);
        BlockPos.betweenClosed(bounds.min, bounds.max).forEach(pos -> {
            boolean edge = pos.getX() == bounds.min.getX() || pos.getX() == bounds.max.getX()
                    || pos.getY() == bounds.min.getY() || pos.getY() == bounds.max.getY()
                    || pos.getZ() == bounds.min.getZ() || pos.getZ() == bounds.max.getZ();
            if ((!hollow || edge) && (replace == null || getBlock(pos) == replace)) setBlock(pos, state);
        });
    }
    public BlockState getBlock(BlockPos pos) {
        if (transaction != null && transaction.changes.containsKey(pos)) return transaction.changes.get(pos).state();
        var level = Minecraft.getInstance().level;
        if (level == null || !level.hasChunkAt(pos)) throw new IllegalArgumentException("Position is not loaded: " + pos);
        return level.getBlockState(pos);
    }
    public Tool tool(int index) { return ToolManager.getToolByIndex(index); }
    public JsonObject state() { return EditorBridge.state(); }
    public void select(int x1,int y1,int z1,int x2,int y2,int z2) {
        var bounds = Bounds.of(pos(x1,y1,z1), pos(x2,y2,z2)); bounds.checkSize(WorldEdits.MAX_BLOCKS);
        Selection.clearSelection(); Selection.addAABB(bounds.min, bounds.max);
    }
    public record Bounds(BlockPos min, BlockPos max) {
        public static Bounds of(BlockPos a, BlockPos b) {
            return new Bounds(new BlockPos(Math.min(a.getX(),b.getX()),Math.min(a.getY(),b.getY()),Math.min(a.getZ(),b.getZ())),
                    new BlockPos(Math.max(a.getX(),b.getX()),Math.max(a.getY(),b.getY()),Math.max(a.getZ(),b.getZ())));
        }
        public long volume() { return Math.multiplyExact(Math.multiplyExact((long)max.getX()-min.getX()+1, (long)max.getY()-min.getY()+1), (long)max.getZ()-min.getZ()+1); }
        public void checkSize(int maxBlocks) { if (volume() > maxBlocks) throw new IllegalArgumentException("Requested area exceeds " + maxBlocks + " blocks"); }
    }
}
