package dev.axiommcp.editor;

import com.google.gson.JsonObject;
import com.moulberry.axiom.AxiomClient;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.world_modification.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Stages helper edits, captures authoritative undo data, then uses Axiom's dispatcher. */
public final class WorldEdits {
    public static final int MAX_BLOCKS = 262144;
    public record Change(BlockState state, net.minecraft.nbt.CompoundTag nbt) {}
    public static final class Transaction {
        final LinkedHashMap<BlockPos, Change> changes = new LinkedHashMap<>();
        final Object level = Minecraft.getInstance().level;
        final String description;
        public Transaction(String description) { this.description = description; }
        public void set(BlockPos pos, BlockState state, net.minecraft.nbt.CompoundTag nbt) {
            var client = Minecraft.getInstance();
            if (client.level == null || client.level != level) throw new IllegalStateException("World changed during edit");
            if (Math.abs((long)pos.getX()) > 29999984 || Math.abs((long)pos.getZ()) > 29999984 || client.level.isOutsideBuildHeight(pos))
                throw new IllegalArgumentException("Position outside world/build bounds: " + pos);
            if (!client.level.hasChunkAt(pos)) throw new IllegalArgumentException("Chunk is not loaded: " + pos);
            if (nbt != null && !state.hasBlockEntity()) throw new IllegalArgumentException("NBT supplied for a block without a block entity");
            if (nbt != null && !AxiomClient.hasPermission(AxiomPermission.BUILD_NBT)) throw new IllegalStateException("Axiom BUILD_NBT permission is required");
            if (state == BlockBuffer.EMPTY_STATE) throw new IllegalArgumentException("minecraft:void_air is Axiom's empty sentinel; use minecraft:air");
            changes.put(pos.immutable(), new Change(state, nbt == null ? null : nbt.copy()));
            if (changes.size() > MAX_BLOCKS) throw new IllegalArgumentException("One helper transaction supports at most " + MAX_BLOCKS + " unique blocks");
        }
    }
    public static void requirePermission() {
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) throw new IllegalStateException("Join a world first");
        if (!AxiomClient.hasPermission(AxiomPermission.BUILD_SECTION)) throw new IllegalStateException("Axiom BUILD_SECTION permission is required");
    }
    public static CompletableFuture<JsonObject> commit(Transaction transaction) {
        requirePermission();
        var client = Minecraft.getInstance(); var server = client.getSingleplayerServer();
        if (server == null) throw new IllegalStateException("Helper transactions currently require singleplayer for authoritative undo snapshots; native Axiom APIs remain available via execute_code");
        var result = new CompletableFuture<JsonObject>();
        var dimension = client.level.dimension(); var player = client.player.getUUID();
        var changes = Map.copyOf(transaction.changes);
        server.execute(() -> {
            try {
                var world = server.getLevel(dimension);
                if (world == null || server.getPlayerList().getPlayer(player) == null) throw new IllegalStateException("World disconnected before edit");
                var forwards = new BlockBuffer(); var backwards = new BlockBuffer();
                var bytes = new ByteArrayOutputStream(); int count = 0;
                for (var entry : changes.entrySet()) {
                    BlockPos pos = entry.getKey(); Change change = entry.getValue();
                    if (!world.hasChunkAt(pos)) throw new IllegalStateException("Server chunk unloaded: " + pos);
                    BlockState old = world.getBlockState(pos);
                    if (old == change.state && change.nbt == null) continue;
                    forwards.set(pos.getX(), pos.getY(), pos.getZ(), change.state);
                    if (change.nbt != null) forwards.putBlockEntity(pos.getX(), pos.getY(), pos.getZ(), CompressedBlockEntity.compress(change.nbt, bytes));
                    backwards.set(pos.getX(), pos.getY(), pos.getZ(), old);
                    var entity = world.getBlockEntity(pos);
                    if (entity != null) backwards.putBlockEntity(pos.getX(), pos.getY(), pos.getZ(), CompressedBlockEntity.compress(entity.saveWithoutMetadata(world.registryAccess()), bytes));
                    count++;
                }
                int changed = count;
                client.execute(() -> {
                    try {
                        if (result.isCancelled()) return;
                        if (client.level != transaction.level || client.player == null || !client.player.getUUID().equals(player)) throw new IllegalStateException("World changed before commit");
                        requirePermission();
                        if (changed > 0) {
                            BlockPos origin = changes.keySet().iterator().next();
                            Dispatcher.push(new HistoryEntry<BlockOrBiomeBuffer>(forwards, backwards, origin, transaction.description,
                                    HistoryEntry.MODIFIER_CAN_BE_UNDONE_INGAME));
                        }
                        // Direct singleplayer dispatch schedules onto this same server executor.
                        server.execute(() -> {
                            var info = new JsonObject(); info.addProperty("changed", changed);
                            info.addProperty("description", transaction.description); info.addProperty("submitted", true);
                            info.addProperty("history", changed > 0); info.addProperty("serverBarrierPassed", true);
                            client.execute(() -> result.complete(info));
                        });
                    } catch (Throwable error) { result.completeExceptionally(error); }
                });
            } catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
}
