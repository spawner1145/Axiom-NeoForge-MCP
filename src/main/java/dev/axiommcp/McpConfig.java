package dev.axiommcp;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;

public record McpConfig(String host, int port) {
    public McpConfig {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Host must not be empty");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be 1..65535");
    }
    public static McpConfig load(Path path) throws IOException {
        if (!Files.exists(path)) { var config = new McpConfig("127.0.0.1", 8766); config.save(path); return config; }
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        return new McpConfig(json.get("host").getAsString(), json.get("port").getAsInt());
    }
    public void save(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        var json = new JsonObject(); json.addProperty("host", host); json.addProperty("port", port);
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "axiom-mcp-", ".json");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n");
            Files.move(temporary, path.toAbsolutePath(), StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    public String endpoint() { return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port + "/mcp"; }
}
