package dev.axiommcp.editor;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.moulberry.axiom.editor.EditorUI;
import dev.axiommcp.protocol.ToolResults;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Reads the actual final Axiom composite, after a complete frame and before swap. */
public final class ScreenshotService {
    private record Pending(JsonObject args, CompletableFuture<JsonObject> result, long frame) {}
    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private static long frame;
    public static void request(JsonObject args, CompletableFuture<JsonObject> result) {
        var region = EditorBridge.string(args, "region", "editor");
        if (!Set.of("editor", "screen", "scene", "ui").contains(region)) throw new IllegalArgumentException("region must be editor, screen, scene or ui");
        if (!region.equals("screen") && !EditorUI.isActive()) throw new IllegalStateException("Open the Axiom editor first");
        int max = EditorBridge.integer(args, "max_size", 1280);
        if (max < 64 || max > 4096) throw new IllegalArgumentException("max_size must be 64..4096");
        int waitFrames = EditorBridge.integer(args, "wait_frames", 10);
        if (waitFrames < 2 || waitFrames > 120) throw new IllegalArgumentException("wait_frames must be 2..120");
        pending.add(new Pending(args.deepCopy(), result, frame + waitFrames));
    }
    public static void onBlit(RenderTarget finalTarget) {
        frame++;
        while (!pending.isEmpty() && pending.peek().frame <= frame) {
            var capture = pending.remove(); if (capture.result.isDone()) continue;
            try {
                String region = EditorBridge.string(capture.args, "region", "editor");
                if (!region.equals("screen") && !EditorUI.isActive()) throw new IllegalStateException("Editor closed before capture");
                var target = region.equals("scene") ? Minecraft.getInstance().getMainRenderTarget() :
                        region.equals("ui") ? EditorUI.compositeOnTop : finalTarget;
                if (target == null) throw new IllegalStateException("Editor UI has no rendered texture");
                var nativeImage = Screenshot.takeScreenshot(target);
                Path directory = Minecraft.getInstance().gameDirectory.toPath();
                Thread.startVirtualThread(() -> {
                    try (nativeImage) {
                        if (capture.result.isDone()) return;
                        var buffered = new BufferedImage(nativeImage.getWidth(), nativeImage.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        buffered.setRGB(0, 0, nativeImage.getWidth(), nativeImage.getHeight(), nativeImage.makePixelArray(), 0, nativeImage.getWidth());
                        int max = EditorBridge.integer(capture.args, "max_size", 1280);
                        int largest = Math.max(buffered.getWidth(), buffered.getHeight());
                        if (largest > max) {
                            var resized = new BufferedImage(Math.max(1, buffered.getWidth() * max / largest), Math.max(1, buffered.getHeight() * max / largest), BufferedImage.TYPE_INT_ARGB);
                            var graphics = resized.createGraphics();
                            try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC); graphics.drawImage(buffered, 0, 0, resized.getWidth(), resized.getHeight(), null); }
                            finally { graphics.dispose(); } buffered = resized;
                        }
                        var output = new ByteArrayOutputStream(); ImageIO.write(buffered, "png", output); var bytes = output.toByteArray();
                        if (capture.args.has("path")) {
                            Path path = Path.of(capture.args.get("path").getAsString());
                            path = (path.isAbsolute() ? path : directory.resolve(path)).toAbsolutePath().normalize();
                            Files.createDirectories(path.getParent()); Files.write(path, bytes);
                        }
                        capture.result.complete(ToolResults.image(Base64.getEncoder().encodeToString(bytes), buffered.getWidth(), buffered.getHeight()));
                    } catch (Exception error) { capture.result.complete(ToolResults.failure(error.toString())); }
                });
            } catch (Exception error) { capture.result.complete(ToolResults.failure(error.toString())); }
        }
    }
}
