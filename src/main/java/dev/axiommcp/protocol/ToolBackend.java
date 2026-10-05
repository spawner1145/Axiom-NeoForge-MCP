// Adapted from PhotonMCP-1.21 for Axiom; modifications under Apache-2.0.
package dev.axiommcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;

public interface ToolBackend {
    JsonArray tools();
    CompletableFuture<JsonObject> call(String name, JsonObject arguments);
}
