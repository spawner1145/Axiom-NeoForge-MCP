package dev.axiommcp;

import dev.axiommcp.editor.EditorBridge;
import dev.axiommcp.protocol.McpServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;

@Mod(value = "axiom_mcp", dist = Dist.CLIENT)
public final class AxiomMcp {
    public static final Logger LOGGER = LoggerFactory.getLogger("Axiom MCP");
    private static AxiomMcp instance;
    private final Path configPath = FMLPaths.CONFIGDIR.get().resolve("axiom-mcp.json");
    public final EditorBridge bridge = new EditorBridge();
    public final McpServer server = new McpServer(bridge);
    private McpConfig config = new McpConfig("127.0.0.1", 8766);
    private String startupError = "";

    public AxiomMcp() {
        instance = this;
        try { config = McpConfig.load(configPath); start(); }
        catch (Exception error) { startupError = error.toString(); LOGGER.error("Could not start Axiom MCP", error); }
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> server.close());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> TestWorldLauncher.tick());
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "axiom-mcp-shutdown"));
    }
    public static AxiomMcp get() { return instance; }
    public McpConfig config() { return config; }
    public String startupError() { return startupError; }
    public void configure(String host, int port) throws Exception {
        var previous = config;
        var next = new McpConfig(host.trim(), port);
        try {
            server.start(next); next.save(configPath); config = next; startupError = "";
        } catch (Exception error) {
            startupError = error.toString();
            try { server.start(previous); } catch (Exception restartError) { error.addSuppressed(restartError); }
            throw error;
        }
    }
    public void start() throws Exception {
        try { server.start(config); startupError = ""; LOGGER.info("Listening on {}", config.endpoint()); }
        catch (Exception error) { startupError = error.toString(); throw error; }
    }
}
