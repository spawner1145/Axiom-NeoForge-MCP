package dev.axiommcp.editor;

import com.google.gson.*;

public final class ToolCatalog {
    static void add(JsonArray tools, String suffix, String description, String properties, String... required) {
        var tool = new JsonObject(); tool.addProperty("name", "axiom_" + suffix); tool.addProperty("description", description);
        var schema = new JsonObject(); schema.addProperty("type", "object"); schema.addProperty("additionalProperties", false);
        schema.add("properties", JsonParser.parseString("{" + properties + "}"));
        var fields = new JsonArray(); for (var name : required) fields.add(name); schema.add("required", fields);
        tool.add("inputSchema", schema); tools.add(tool);
    }
    private static final String POSITION = "{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":3,\"maxItems\":3}";
    private static final String BOUNDS = "\"min\":" + POSITION + ",\"max\":" + POSITION;
    public static JsonArray tools() {
        var tools = new JsonArray();
        NativeToolCatalog.append(tools);
        add(tools,"get_server_status","MCP endpoint, recent clients and request statistics.","");
        add(tools,"get_editor_state","World/player, Axiom permissions, current tool, selection, clipboard and history state.","");
        add(tools,"open_editor","Enable Axiom editor; optional settings opens MCP panel in the native ImGui UI.","\"settings\":{\"type\":\"boolean\"}");
        add(tools,"close_editor","Disable editor and return through Axiom's normal game-mode transition.","");
        add(tools,"execute_code","Run Groovy/JVM on Minecraft's main thread with native Axiom API bindings. mcp.setBlock/fill changes commit as one undo step after success; native/files/UI side effects are not automatically rolled back. Never sleep or wait for futures on the game thread.","\"code\":{\"type\":\"string\"},\"args\":{\"type\":\"object\"},\"reset_context\":{\"type\":\"boolean\"},\"description\":{\"type\":\"string\"}","code");
        add(tools,"api_lookup","Reflect real installed classes or an expression referencing live objects, including constructors, methods and fields.","\"class\":{\"type\":\"string\"},\"expression\":{\"type\":\"string\"},\"filter\":{\"type\":\"string\"},\"inherited\":{\"type\":\"boolean\"}");
        add(tools,"list_api_classes","Discover installed Axiom classes and nested dependency classes.","\"prefix\":{\"type\":\"string\"},\"filter\":{\"type\":\"string\"}");
        add(tools,"list_tools","List every currently registered Axiom tool with native class, permissions and serialized settings.","");
        add(tools,"get_tool","Describe a registered tool, exact settings JSON/SNBT and NBT types.","\"index\":{\"type\":\"integer\",\"minimum\":0}","index");
        add(tools,"select_tool","Select a real Axiom tool and activate its slot.","\"index\":{\"type\":\"integer\",\"minimum\":0},\"active\":{\"type\":\"boolean\"}","index");
        add(tools,"update_tool","Deep-merge exact serialized settings into a tool. Query get_tool before choosing keys.","\"index\":{\"type\":\"integer\",\"minimum\":0},\"patch\":{\"type\":\"object\"},\"snbt\":{\"type\":\"string\"}","index");
        add(tools,"editor_action","Dispatch a native UserAction (COPY, CUT, PASTE, ENTER, ESCAPE, ROTATE_PLACEMENT, etc.). Optional value is a Groovy expression for native typed arguments.","\"action\":{\"type\":\"string\"},\"value\":{\"type\":\"string\"}","action");
        add(tools,"get_selection","Selection bounds, block count and rendering state.","");
        add(tools,"set_selection","Replace/add/subtract/intersect a cuboid in Axiom's native selection.",BOUNDS+",\"mode\":{\"enum\":[\"replace\",\"add\",\"subtract\",\"intersect\"]}","min","max");
        add(tools,"clear_selection","Clear native selection.","");
        add(tools,"move_selection","Move selected volume by a block offset.","\"offset\":"+POSITION,"offset");
        add(tools,"get_blocks","Read loaded blocks and optional block-entity SNBT. Singleplayer reads the authoritative server world; limit 32768 blocks.",BOUNDS+",\"include_air\":{\"type\":\"boolean\"},\"nbt\":{\"type\":\"boolean\"}","min","max");
        add(tools,"list_blocks","Search installed block IDs with exact state properties.","\"filter\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2000}");
        add(tools,"set_active_block","Select the active Axiom palette block, including block-state properties.","\"block\":{\"type\":\"string\"}","block");
        add(tools,"set_blocks","Stage explicit blocks into one Axiom undo step. Singleplayer; up to 262144 unique blocks. Optional nbt is SNBT without coordinates.","\"blocks\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":262144,\"items\":{\"type\":\"object\",\"properties\":{\"pos\":"+POSITION+",\"block\":{\"type\":\"string\"},\"nbt\":{\"type\":\"string\"}},\"required\":[\"pos\",\"block\"]}},\"description\":{\"type\":\"string\"}","blocks");
        add(tools,"fill","Fill a cuboid or current selection; optional hollow and exact-state replacement. Singleplayer; one native history step.",BOUNDS+",\"block\":{\"type\":\"string\"},\"hollow\":{\"type\":\"boolean\"},\"replace\":{\"type\":\"string\"},\"description\":{\"type\":\"string\"}","block");
        add(tools,"build_shape","Generate box, ellipsoid, cylinder, line or pyramid with native undo; bounds inclusive. Cylinder axis is Y.",BOUNDS+",\"shape\":{\"enum\":[\"box\",\"ellipsoid\",\"cylinder\",\"line\",\"pyramid\"]},\"block\":{\"type\":\"string\"},\"hollow\":{\"type\":\"boolean\"},\"description\":{\"type\":\"string\"}","min","max","shape","block");
        add(tools,"batch","Stage set_blocks/fill/build_shape operations, validate all, then commit one history step. No partial helper commit on validation failure.","\"operations\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":256,\"items\":{\"type\":\"object\",\"properties\":{\"tool\":{\"enum\":[\"set_blocks\",\"fill\",\"build_shape\"]},\"arguments\":{\"type\":\"object\"}},\"required\":[\"tool\",\"arguments\"]}},\"description\":{\"type\":\"string\"}","operations");
        add(tools,"get_history","List native Axiom history entries and current position.","");
        add(tools,"undo_redo","Undo/redo native history or selection steps; uses standard Axiom action behavior.","\"action\":{\"enum\":[\"undo\",\"redo\"]},\"steps\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}","action");
        add(tools,"native_operation","Run a native selection operation: drain, waterlog, hollow, fill_gaps, fill_unreachable, fill_nearest, gravity, update, expand, shrink, distort, smooth_selection, smooth_snow, set_biome.","\"operation\":{\"type\":\"string\"},\"block\":{\"type\":\"string\"},\"amount\":{\"type\":\"integer\"},\"noise_radius\":{\"type\":\"number\"},\"stddev\":{\"type\":\"number\"},\"threshold\":{\"type\":\"number\"},\"biome\":{\"type\":\"string\"},\"fill_vertically\":{\"type\":\"boolean\"}","operation");
        add(tools,"get_clipboard","Native clipboard bounds/count, entities and paste preview status.","");
        add(tools,"copy_selection","Copy native selection into clipboard, awaiting Axiom's chunk/NBT data request.","\"include_air\":{\"type\":\"boolean\"},\"include_entities\":{\"type\":\"boolean\"}");
        add(tools,"transform_clipboard","Rotate quarter-turns or mirror clipboard along x/y/z.","\"action\":{\"enum\":[\"rotate\",\"flip\"]},\"axis\":{\"enum\":[\"x\",\"y\",\"z\"]},\"quarter_turns\":{\"type\":\"integer\"}","action","axis");
        add(tools,"paste_clipboard","Start Axiom native placement at target; optionally confirm immediately. Preserves clipboard entities/NBT and native paste history.","\"pos\":"+POSITION+",\"preview\":{\"type\":\"boolean\"},\"paste_air\":{\"type\":\"boolean\"},\"keep_existing\":{\"type\":\"boolean\"}","pos");
        add(tools,"import_schematic","Load Sponge, Litematic or legacy schematic into native clipboard from a local path.","\"path\":{\"type\":\"string\"}","path");
        add(tools,"export_structure","Export current clipboard as compressed vanilla structure NBT.","\"path\":{\"type\":\"string\"}","path");
        add(tools,"export_schematic","Export clipboard with Axiom's native Sponge v2 writer for the current Minecraft data version.","\"path\":{\"type\":\"string\"},\"name\":{\"type\":\"string\"},\"author\":{\"type\":\"string\"}","path");
        add(tools,"editor_windows","List native editor windows or set one window's visibility.","\"window\":{\"type\":\"string\"},\"open\":{\"type\":\"boolean\"}");
        add(tools,"get_mask","Read native tool-mask expression, Lua script, mode and window visibility.","");
        add(tools,"update_mask","Configure native command-expression or Lua tool mask. Helpers set_blocks/fill ignore tool masks; native tools use them. mode: 0 both, 1 source only, 2 destination only.","\"expression\":{\"type\":\"string\"},\"lua\":{\"type\":\"string\"},\"use_lua\":{\"type\":\"boolean\"},\"mode\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2},\"enabled\":{\"type\":\"boolean\"}");
        add(tools,"set_view","Move camera/player and set yaw/pitch, or point at a target. Editor spectator camera follows the player.","\"position\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":3,\"maxItems\":3},\"look_at\":{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":3,\"maxItems\":3},\"yaw\":{\"type\":\"number\"},\"pitch\":{\"type\":\"number\"}");
        add(tools,"screenshot","Capture final composited editor/screen, world scene or UI layer after complete frames; returns PNG image. Default waits 10 frames; increase after a large camera move or edit.","\"region\":{\"enum\":[\"editor\",\"screen\",\"scene\",\"ui\"]},\"max_size\":{\"type\":\"integer\",\"minimum\":64,\"maximum\":4096},\"wait_frames\":{\"type\":\"integer\",\"minimum\":2,\"maximum\":120},\"path\":{\"type\":\"string\"}");
        add(tools,"flush","Pass a singleplayer server/main-thread barrier; reports pending Axiom dispatch tasks. Wait and retry if queue is not empty.","");
        return tools;
    }
}
