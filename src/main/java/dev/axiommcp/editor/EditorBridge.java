package dev.axiommcp.editor;

import com.google.gson.*;
import com.moulberry.axiom.*;
import com.moulberry.axiom.clipboard.*;
import com.moulberry.axiom.custom_blocks.CustomBlockState;
import com.moulberry.axiom.editor.EditorUI;
import com.moulberry.axiom.editor.palette.ActiveBlockHistory;
import com.moulberry.axiom.operations.*;
import com.moulberry.axiom.operations.smooth.SmoothSnow;
import com.moulberry.axiom.restrictions.AxiomPermission;
import com.moulberry.axiom.tools.*;
import com.moulberry.axiom.utils.EntityDataUtils;
import com.moulberry.axiom.world_modification.Dispatcher;
import dev.axiommcp.AxiomMcp;
import dev.axiommcp.protocol.*;
import dev.axiommcp.ui.McpMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class EditorBridge implements ToolBackend {
    private final CodeRunner scripts = new CodeRunner();
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    @Override public JsonArray tools() { return ToolCatalog.tools(); }
    @Override public synchronized CompletableFuture<JsonObject> call(String name, JsonObject arguments) {
        var result = new CompletableFuture<JsonObject>();
        // Serialize asynchronous calls as well as game-thread work, preserving edit/history order.
        tail = tail.handle((value,error) -> null).thenCompose(value -> {
            Minecraft.getInstance().execute(() -> {
                if (result.isDone()) return;
                try {
                    if (name.equals("axiom_screenshot")) { ScreenshotService.request(arguments, result); return; }
                    var pending = dispatch(name, arguments);
                    result.whenComplete((ignored,exception) -> { if (result.isCancelled()) pending.cancel(false); });
                    pending.whenComplete((data,error) -> {
                        if (error == null) result.complete(ToolResults.text(data));
                        else result.complete(ToolResults.failure(cause(error).toString()));
                    });
                } catch (Throwable error) {
                    AxiomMcp.LOGGER.warn("MCP {} failed: {}", name, error.toString());
                    result.complete(ToolResults.failure(errorText(error)));
                }
            });
            return result;
        });
        return result;
    }
    private static Throwable cause(Throwable error) { while (error.getCause() != null) error = error.getCause(); return error; }
    private static String errorText(Throwable error) {
        if(error instanceof com.moulberry.axiom.mask.CmdStringConverter.CmdStringParseException parse)
            return "Mask parse error at " + parse.start + ".." + parse.end + ": " + parse.message;
        return error.toString();
    }
    private static CompletableFuture<JsonObject> ready(JsonObject value) { return CompletableFuture.completedFuture(value); }
    private static JsonObject ok() { var result = new JsonObject(); result.addProperty("ok", true); return result; }
    private CompletableFuture<JsonObject> dispatch(String name, JsonObject args) throws Exception {
        switch (name) {
            case "axiom_get_server_status": return ready(AxiomMcp.get().server.status());
            case "axiom_get_editor_state": return ready(state());
            case "axiom_open_editor":
                requireWorld(); Minecraft.getInstance().setScreen(null); EditorUI.enable();
                if (!EditorUI.isEnabled()) throw new IllegalStateException("Axiom did not enable; check spectator/editor permissions and handshake");
                if (bool(args,"settings",false)) McpMenu.show(); return ready(state());
            case "axiom_close_editor": EditorUI.disable(); return ready(state());
            case "axiom_execute_code": return execute(args);
            case "axiom_api_lookup": {
                Class<?> type;
                if (args.has("class")) type = Class.forName(required(args,"class"), false, EditorBridge.class.getClassLoader());
                else if (args.has("expression")) {
                    Object value = scripts.evaluate(required(args,"expression"), context(new BuildApi(null), new JsonObject()));
                    if (value == null) throw new IllegalArgumentException("Expression returned null");
                    type = value instanceof Class<?> klass ? klass : value.getClass();
                } else throw new IllegalArgumentException("Provide class or expression");
                return ready(ApiIntrospection.describe(type, string(args,"filter",""), bool(args,"inherited",true)));
            }
            case "axiom_list_api_classes": return ready(ApiClasses.list(Minecraft.getInstance().gameDirectory.toPath().resolve("mods"), string(args,"prefix","com.moulberry.axiom."), string(args,"filter","")));
            case "axiom_list_tools": return ready(listTools());
            case "axiom_get_tool": return ready(toolInfo(tool(args), integer(args,"index",-1),true));
            case "axiom_select_tool": {
                Tool tool = tool(args);
                if (!AxiomClient.hasPermissions(tool.requiredPermissions())) throw new IllegalStateException("Server denied required tool permissions");
                ToolManager.setCurrentToolIndex(integer(args,"index",-1)); ToolManager.setToolSelected(bool(args,"active",true)); return ready(state());
            }
            case "axiom_update_tool": {
                Tool tool = tool(args); CompoundTag settings = new CompoundTag(); tool.writeSettings(settings);
                if (args.has("snbt")) settings = TagParser.parseTag(required(args,"snbt"));
                if (args.has("patch")) settings = NbtJson.merge(settings,args.getAsJsonObject("patch"));
                if (!args.has("snbt") && !args.has("patch")) throw new IllegalArgumentException("Provide patch or snbt");
                tool.loadSettings(settings); return ready(toolInfo(tool,integer(args,"index",-1),true));
            }
            case "axiom_tool_stroke": return nativeResult(NativeTools.stroke(args));
            case "axiom_native_shape": return nativeResult(NativeTools.shape(args));
            case "axiom_native_path": return nativeResult(NativeTools.path(args));
            case "axiom_native_mesh": return nativeResult(NativeTools.mesh(args));
            case "axiom_clipboard_to_brush": return ready(NativeTools.clipboardBrush(args));
            case "axiom_stamp_clipboard": return nativeResult(NativeTools.stamp(args));
            case "axiom_editor_action": {
                requireWorld(); var action = UserAction.valueOf(required(args,"action").toUpperCase(Locale.ROOT));
                Object value = args.has("value") ? scripts.evaluate(required(args,"value"),context(new BuildApi(null), new JsonObject())) : null;
                var response = ok(); response.addProperty("action",action.name()); response.addProperty("result",action.call(value).name()); return ready(response);
            }
            case "axiom_get_selection": return ready(selection());
            case "axiom_set_selection": {
                requireWorld(); var bounds = bounds(args); bounds.checkSize(WorldEdits.MAX_BLOCKS);
                switch (string(args,"mode","replace")) {
                    case "replace" -> { Selection.clearSelection(); Selection.addAABB(bounds.min(),bounds.max()); }
                    case "add" -> Selection.addAABB(bounds.min(),bounds.max());
                    case "subtract" -> Selection.subtractAABB(bounds.min(),bounds.max());
                    case "intersect" -> Selection.intersectAABB(bounds.min(),bounds.max());
                    default -> throw new IllegalArgumentException("Unknown selection mode");
                } return ready(selection());
            }
            case "axiom_clear_selection": Selection.clearSelection(); return ready(selection());
            case "axiom_move_selection": { var offset=pos(args,"offset"); Selection.move(offset.getX(),offset.getY(),offset.getZ(),true); return ready(selection()); }
            case "axiom_get_blocks": return readBlocks(args);
            case "axiom_list_blocks": return ready(listBlocks(args));
            case "axiom_set_active_block": {
                var state = new BuildApi(null).block(required(args,"block"));
                ((ActiveBlockHistory)ApiIntrospection.read(EditorUI.class,"activeBlockHistory")).setActive((CustomBlockState)state);
                return ready(state());
            }
            case "axiom_set_blocks", "axiom_fill", "axiom_build_shape", "axiom_batch": {
                WorldEdits.requirePermission();
                var transaction = new WorldEdits.Transaction(string(args,"description","MCP " + name.substring(6)));
                var api = new BuildApi(transaction);
                if (name.equals("axiom_batch")) {
                    var operations=args.getAsJsonArray("operations");
                    if (operations == null || operations.isEmpty() || operations.size() > 256) throw new IllegalArgumentException("operations count must be 1..256");
                    for (var entry : operations) {
                        var operation=entry.getAsJsonObject(); stage(api,required(operation,"tool"),operation.getAsJsonObject("arguments"));
                    }
                } else stage(api,name.substring(6),args);
                return WorldEdits.commit(transaction);
            }
            case "axiom_get_history": return ready(history());
            case "axiom_undo_redo": {
                WorldEdits.requirePermission(); String action=required(args,"action");
                if (!Set.of("undo","redo").contains(action)) throw new IllegalArgumentException("action must be undo or redo");
                int steps=integer(args,"steps",1); if(steps<1||steps>100) throw new IllegalArgumentException("steps must be 1..100");
                for(int i=0;i<steps;i++) Dispatcher.callAction(action.equals("undo")?UserAction.UNDO:UserAction.REDO,null);
                return barrier().thenApply(info -> { info.add("history",history()); return info; });
            }
            case "axiom_native_operation": nativeOperation(args); return barrier();
            case "axiom_get_clipboard": return ready(clipboard());
            case "axiom_copy_selection": return copy(args);
            case "axiom_transform_clipboard": {
                requireClipboard(); Axis axis=Axis.valueOf(required(args,"axis").toUpperCase(Locale.ROOT));
                switch(required(args,"action")) {
                    case "rotate" -> Clipboard.INSTANCE.rotate(axis,Math.floorMod(integer(args,"quarter_turns",1),4));
                    case "flip" -> Clipboard.INSTANCE.flip(axis);
                    default -> throw new IllegalArgumentException("action must be rotate or flip");
                } return ready(clipboard());
            }
            case "axiom_paste_clipboard": {
                WorldEdits.requirePermission(); var clip=requireClipboard();
                Placement.INSTANCE.pasteAir=bool(args,"paste_air",true); Placement.INSTANCE.keepExisting=bool(args,"keep_existing",false);
                Placement.INSTANCE.startPlacement(pos(args,"pos"),clip.blockRegion(),clip.blockEntities(),clip.entities(),clip.containsAir(),"MCP paste");
                if (!bool(args,"preview",false)) Placement.INSTANCE.pastePlacement();
                return barrier().thenApply(info -> { info.add("clipboard",clipboard()); return info; });
            }
            case "axiom_import_schematic": {
                requirePermission(AxiomPermission.CAN_IMPORT_BLOCKS);
                var tag=NbtIo.readCompressed(path(required(args,"path")),NbtAccounter.unlimitedHeap());
                if(tag==null) throw new IllegalArgumentException("Empty schematic");
                while(tag.getAllKeys().size()==1 && tag.get(tag.getAllKeys().iterator().next()) instanceof CompoundTag inner) tag=inner;
                var clip=tag.contains("Regions")?com.moulberry.axiom.editor.schematic.SchematicLoader.loadLitematic(tag):
                        tag.contains("Version")?com.moulberry.axiom.editor.schematic.SchematicLoader.loadSponge(tag):
                                com.moulberry.axiom.editor.schematic.SchematicLoader.loadLegacy(tag);
                Clipboard.INSTANCE.setClipboard(clip); return ready(clipboard());
            }
            case "axiom_export_structure": {
                requirePermission(AxiomPermission.CAN_EXPORT_BLOCKS); var clip=requireClipboard(); Path file=path(required(args,"path"));
                Files.createDirectories(file.getParent()); NbtIo.writeCompressed(com.moulberry.axiom.vanilla_structure_file.VanillaStructureHelper.toStructureNbt(clip),file);
                var info=ok(); info.addProperty("path",file.toString()); return ready(info);
            }
            case "axiom_export_schematic": {
                requirePermission(AxiomPermission.CAN_EXPORT_BLOCKS); var clip=requireClipboard();
                BuildApi.Bounds.of(clip.blockRegion().min(),clip.blockRegion().max()).checkSize(WorldEdits.MAX_BLOCKS);
                Path file=path(required(args,"path")); Files.createDirectories(file.getParent());
                var action=new com.moulberry.axiom.editor.windows.save_world.SaveSchematicAction(file,clip.blockRegion(),clip.blockEntities(),null,
                        string(args,"name","MCP building"),string(args,"author",Minecraft.getInstance().player.getScoreboardName()),null);
                if (!action.run()) throw new IllegalStateException("Axiom's native export needs conflict resolution in its UI");
                var info=ok();info.addProperty("path",file.toString());info.addProperty("format","Sponge v2");return ready(info);
            }
            case "axiom_editor_windows": {
                if(args.has("window")) {
                    var window=com.moulberry.axiom.editor.EditorWindowType.valueOf(required(args,"window").toUpperCase(Locale.ROOT));
                    if(!args.has("open"))throw new IllegalArgumentException("Provide open when setting a window");window.setOpen(bool(args,"open",true));
                }
                var windows=new JsonArray();for(var window:com.moulberry.axiom.editor.EditorWindowType.values()) {
                    var info=new JsonObject();info.addProperty("window",window.name());info.addProperty("open",window.isOpen());windows.add(info);
                }var info=new JsonObject();info.add("windows",windows);return ready(info);
            }
            case "axiom_get_mask": return ready(mask());
            case "axiom_update_mask": {
                requireWorld();int mode=integer(args,"mode",com.moulberry.axiom.mask.MaskManager.mode[0]);
                if(mode<0||mode>2)throw new IllegalArgumentException("mode must be 0..2");
                if(args.has("expression")) {
                    String expression=required(args,"expression");
                    var element=expression.isBlank()?null:com.moulberry.axiom.mask.CmdStringConverter.fromCmdString(expression);
                    com.moulberry.axiom.mask.MaskManager.setConfiguredMask(element,true,true);
                }
                if(args.has("lua")) {var tag=new CompoundTag();tag.putString("lua",required(args,"lua"));
                    com.moulberry.axiom.editor.windows.global_mask.ToolMaskWindow.loadSettingsScript(tag);}
                com.moulberry.axiom.mask.MaskManager.mode[0]=mode;
                if(args.has("use_lua"))com.moulberry.axiom.mask.MaskManager.useLuaScript=bool(args,"use_lua",false);
                if(args.has("enabled"))com.moulberry.axiom.editor.EditorWindowType.TOOL_MASKS.setOpen(bool(args,"enabled",true));
                return ready(mask());
            }
            case "axiom_set_view": setView(args); return ready(state());
            case "axiom_flush": return barrier();
            default: throw new IllegalArgumentException("Unknown tool: " + name);
        }
    }
    private CompletableFuture<JsonObject> execute(JsonObject args) throws Exception {
        if (bool(args,"reset_context",false)) scripts.reset();
        var transaction=new WorldEdits.Transaction(string(args,"description","MCP script"));
        var api=new BuildApi(transaction);
        var response=scripts.execute(required(args,"code"),context(api,args.has("args")?args.getAsJsonObject("args"):new JsonObject()));
        if(transaction.changes.isEmpty()) return api.nativeUsed()?nativeResult(response):ready(response);
        var commit = WorldEdits.commit(transaction);
        var result = commit.thenApply(edit -> { response.add("edit",edit); return response; });
        result.whenComplete((value,error) -> { if (result.isCancelled()) commit.cancel(false); });
        return result;
    }
    private static CompletableFuture<JsonObject> nativeResult(JsonObject response) {
        return barrier().thenApply(info -> {response.add("dispatch",info);response.addProperty("historyPosition",Dispatcher.getHistoryPosition(false));return response;});
    }
    private Map<String,Object> context(BuildApi api,JsonObject args) {
        var client=Minecraft.getInstance(); var bindings=new HashMap<String,Object>();
        bindings.put("client",client); bindings.put("world",client.level); bindings.put("player",client.player);
        bindings.put("axiom",Axiom.getInstance()); bindings.put("configuration",Axiom.configuration);
        bindings.put("selection",Selection.class); bindings.put("clipboard",Clipboard.INSTANCE); bindings.put("placement",Placement.INSTANCE);
        bindings.put("tools",ToolManager.getTools()); bindings.put("tool",ToolManager.getCurrentTool());
        bindings.put("mcp",api); bindings.put("api",ApiIntrospection.class); bindings.put("args",new Gson().fromJson(args,Map.class));
        return bindings;
    }
    public static JsonObject state() {
        var client=Minecraft.getInstance(); var info=new JsonObject();
        info.addProperty("worldLoaded",client.level!=null); info.addProperty("singleplayer",client.getSingleplayerServer()!=null);
        info.addProperty("editorEnabled",EditorUI.isEnabled()); info.addProperty("editorActive",EditorUI.isActive());
        info.addProperty("serverSupportsAxiom",ClientEvents.serverSupportsAxiom); info.addProperty("handshakeProcessed",ClientEvents.processedServerSupportsAxiom);
        info.addProperty("allowedOnServer",ClientEvents.allowedOnServer); info.addProperty("activeBlock",net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(EditorUI.getActiveBlock()));
        if(client.level!=null) info.addProperty("dimension",client.level.dimension().location().toString());
        if(client.player!=null) { info.add("position",vector(client.player.position())); info.addProperty("yaw",client.player.getYRot()); info.addProperty("pitch",client.player.getXRot()); }
        info.add("selection",selection()); info.add("clipboard",clipboard());
        info.addProperty("currentTool",ToolManager.getCurrentToolIndex()); info.addProperty("toolActive",ToolManager.isToolActive());
        info.addProperty("historyPosition",Dispatcher.getHistoryPosition(false)); info.addProperty("historyCount",Dispatcher.getHistoryDataCount());
        var permissions=new JsonObject(); for(var permission:AxiomPermission.values()) permissions.addProperty(permission.name(),AxiomClient.hasPermission(permission));
        info.add("permissions",permissions); info.addProperty("mcpSettingsOpen",McpMenu.isOpen()); info.addProperty("mcpSettingsRenderedFrames",McpMenu.renderedFrames());
        return info;
    }
    public static JsonObject selection() {
        var buffer=Selection.getSelectionBuffer(); var info=new JsonObject(); info.addProperty("count",buffer.size());
        info.add("min",position(buffer.min())); info.add("max",position(buffer.max())); info.add("center",position(buffer.center()));
        info.addProperty("render",Selection.shouldRenderSelection()); return info;
    }
    public static JsonObject clipboard() {
        var info=new JsonObject(); var clip=Clipboard.INSTANCE.getClipboard(); info.addProperty("empty",clip==null); info.addProperty("placing",Placement.INSTANCE.isPlacing());
        if(clip!=null) { info.addProperty("count",clip.blockRegion().count()); info.add("min",position(clip.blockRegion().min())); info.add("max",position(clip.blockRegion().max()));
            info.addProperty("entities",clip.entities()==null?0:clip.entities().size()); info.addProperty("blockEntities",clip.blockEntities()==null?0:clip.blockEntities().size()); info.addProperty("containsAir",clip.containsAir()); }
        return info;
    }
    private static JsonObject history() {
        var info=new JsonObject(); info.addProperty("position",Dispatcher.getHistoryPosition(false)); info.addProperty("bytes",Dispatcher.getHistoryBytes());
        var entries=new JsonArray(); for(int i=0;i<Dispatcher.getHistoryDataCount();i++) { var entry=Dispatcher.getHistoryData(i); if(entry==null)continue;
            var item=new JsonObject(); item.addProperty("index",i); item.addProperty("position",entry.position()); item.addProperty("selection",entry.isActiveSelection());
            item.addProperty("description",entry.entry().description()); entries.add(item); } info.add("entries",entries); return info;
    }
    private static JsonObject mask() {
        var tag=new CompoundTag();com.moulberry.axiom.editor.windows.global_mask.ToolMaskWindow.writeSettings(tag);
        var info=new JsonObject();info.addProperty("expression",tag.getString("mask"));info.addProperty("lua",com.moulberry.axiom.mask.MaskManager.luaScript);
        info.addProperty("useLua",com.moulberry.axiom.mask.MaskManager.useLuaScript);info.addProperty("mode",com.moulberry.axiom.mask.MaskManager.mode[0]);
        info.addProperty("enabled",com.moulberry.axiom.editor.EditorWindowType.TOOL_MASKS.isOpen());return info;
    }
    private static Tool tool(JsonObject args) {
        int index=integer(args,"index",-1); if(index<0||index>=ToolManager.getTools().size()) throw new IllegalArgumentException("Invalid tool index: "+index);
        return ToolManager.getToolByIndex(index);
    }
    private static JsonObject toolInfo(Tool tool,int index,boolean detail) {
        var info=new JsonObject(); info.addProperty("index",index); info.addProperty("name",tool.name()); info.addProperty("class",tool.getClass().getName());
        info.addProperty("keybind",tool.keybindId()); info.addProperty("allowed",AxiomClient.hasPermissions(tool.requiredPermissions()));
        info.add("automation",NativeTools.capabilities(tool));
        var tag=new CompoundTag(); tool.writeSettings(tag); info.add("settings",NbtJson.json(tag));
        if(detail) { info.addProperty("snbt",tag.toString()); info.add("schema",NbtJson.describe(tag)); }
        return info;
    }
    private static JsonObject listTools() {
        var array=new JsonArray(); var tools=ToolManager.getTools(); for(int i=0;i<tools.size();i++)array.add(toolInfo(tools.get(i),i,false));
        var info=new JsonObject(); info.add("tools",array); info.addProperty("count",array.size()); return info;
    }
    private static void requireWorld() { if(Minecraft.getInstance().player==null||Minecraft.getInstance().level==null) throw new IllegalStateException("Join a world first"); }
    private static void requirePermission(AxiomPermission permission) { requireWorld(); if(!AxiomClient.hasPermission(permission))throw new IllegalStateException("Missing Axiom permission: "+permission.name()); }
    private static ClipboardObject requireClipboard() { requireWorld(); var clipboard=Clipboard.INSTANCE.getClipboard(); if(clipboard==null)throw new IllegalStateException("Clipboard is empty"); return clipboard; }
    public static String required(JsonObject args,String key) { if(!args.has(key))throw new IllegalArgumentException("Missing argument: "+key); return args.get(key).getAsString(); }
    public static String string(JsonObject args,String key,String fallback) { return args.has(key)?args.get(key).getAsString():fallback; }
    public static boolean bool(JsonObject args,String key,boolean fallback) { return args.has(key)?args.get(key).getAsBoolean():fallback; }
    public static int integer(JsonObject args,String key,int fallback) { return args.has(key)?exactInteger(args.get(key),key):fallback; }
    private static int exactInteger(JsonElement value,String key) {
        try { return value.getAsBigDecimal().intValueExact(); }
        catch(RuntimeException error) { throw new IllegalArgumentException(key+" must be a 32-bit integer",error); }
    }
    public static float number(JsonObject args,String key,float fallback) { float value=args.has(key)?args.get(key).getAsFloat():fallback; if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite "+key); return value; }
    public static BlockPos pos(JsonObject args,String key) { var array=args.getAsJsonArray(key); if(array==null||array.size()!=3)throw new IllegalArgumentException(key+" must have three integer coordinates"); return new BlockPos(exactInteger(array.get(0),key),exactInteger(array.get(1),key),exactInteger(array.get(2),key)); }
    public static BuildApi.Bounds bounds(JsonObject args) { return BuildApi.Bounds.of(pos(args,"min"),pos(args,"max")); }
    private static JsonElement position(BlockPos pos) { if(pos==null)return JsonNull.INSTANCE; var array=new JsonArray(); array.add(pos.getX());array.add(pos.getY());array.add(pos.getZ());return array; }
    private static JsonArray vector(Vec3 pos) { var array=new JsonArray();array.add(pos.x);array.add(pos.y);array.add(pos.z);return array; }
    private static Path path(String text) { Path path=Path.of(text); return (path.isAbsolute()?path:Minecraft.getInstance().gameDirectory.toPath().resolve(path)).toAbsolutePath().normalize(); }
    private static CompletableFuture<JsonObject> barrier() {
        var client=Minecraft.getInstance(); var server=client.getSingleplayerServer(); var result=new CompletableFuture<JsonObject>();
        Runnable complete=() -> { try {
            var info=ok(); int pending=((List<?>)ApiIntrospection.read(Dispatcher.class,"dispatchTasks")).size();
            info.addProperty("pendingDispatchTasks",pending); info.addProperty("queueEmpty",pending==0); info.addProperty("serverBarrier",server!=null); result.complete(info);
        }catch(Throwable error){result.completeExceptionally(error);} };
        if(server!=null)server.execute(() -> client.execute(complete));else complete.run(); return result;
    }
    private static void stage(BuildApi api,String kind,JsonObject args) throws Exception {
        switch(kind) {
            case "set_blocks" -> {
                var blocks=args.getAsJsonArray("blocks"); if(blocks==null||blocks.isEmpty()||blocks.size()>WorldEdits.MAX_BLOCKS)throw new IllegalArgumentException("blocks must contain 1..262144 entries");
                for(var value:blocks) { var block=value.getAsJsonObject(); api.setBlock(pos(block,"pos"),api.block(required(block,"block")),block.has("nbt")?TagParser.parseTag(required(block,"nbt")):null); }
            }
            case "fill" -> {
                var state=api.block(required(args,"block")); var replace=args.has("replace")?api.block(required(args,"replace")):null;
                if(args.has("min")||args.has("max")) { var box=bounds(args); api.fill(box.min(),box.max(),state,bool(args,"hollow",false),replace); }
                else {
                    var selection=Selection.getSelectionBuffer(); if(selection.isEmpty())throw new IllegalStateException("No selection and no bounds");
                    if(selection.size()>WorldEdits.MAX_BLOCKS)throw new IllegalArgumentException("Selection too large");
                    boolean hollow=bool(args,"hollow",false);
                    selection.forEach((x,y,z) -> { var position=new BlockPos(x,y,z);
                        boolean edge=!selection.contains(x+1,y,z)||!selection.contains(x-1,y,z)||!selection.contains(x,y+1,z)||!selection.contains(x,y-1,z)||!selection.contains(x,y,z+1)||!selection.contains(x,y,z-1);
                        if((!hollow||edge)&&(replace==null||api.getBlock(position)==replace))api.setBlock(position,state); });
                }
            }
            case "build_shape" -> shape(api,args);
            default -> throw new IllegalArgumentException("Batch accepts set_blocks, fill and build_shape only");
        }
    }
    private static void shape(BuildApi api,JsonObject args) throws Exception {
        var box=bounds(args);box.checkSize(WorldEdits.MAX_BLOCKS); var state=api.block(required(args,"block"));
        String shape=required(args,"shape");boolean hollow=bool(args,"hollow",false);
        if(shape.equals("box")){api.fill(box.min(),box.max(),state,hollow,null);return;}
        if(shape.equals("line")) {
            var start=pos(args,"min");var end=pos(args,"max");int dx=end.getX()-start.getX(),dy=end.getY()-start.getY(),dz=end.getZ()-start.getZ();
            int steps=Math.max(Math.max(Math.abs(dx),Math.abs(dy)),Math.abs(dz));
            for(int i=0;i<=steps;i++){double t=steps==0?0:(double)i/steps;api.setBlock(new BlockPos((int)Math.round(start.getX()+dx*t),(int)Math.round(start.getY()+dy*t),(int)Math.round(start.getZ()+dz*t)),state);}return;
        }
        if(!Set.of("ellipsoid","cylinder","pyramid").contains(shape))throw new IllegalArgumentException("Unknown shape: "+shape);
        double cx=(box.min().getX()+box.max().getX())/2.0,cy=(box.min().getY()+box.max().getY())/2.0,cz=(box.min().getZ()+box.max().getZ())/2.0;
        double rx=(box.max().getX()-box.min().getX()+1)/2.0,ry=(box.max().getY()-box.min().getY()+1)/2.0,rz=(box.max().getZ()-box.min().getZ()+1)/2.0;
        java.util.function.Predicate<BlockPos> inside=p -> {
            if(p.getX()<box.min().getX()||p.getX()>box.max().getX()||p.getY()<box.min().getY()||p.getY()>box.max().getY()||p.getZ()<box.min().getZ()||p.getZ()>box.max().getZ())return false;
            double nx=(p.getX()-cx)/rx,ny=(p.getY()-cy)/ry,nz=(p.getZ()-cz)/rz;
            if(shape.equals("pyramid")){double taper=1.0-(p.getY()-box.min().getY())/Math.max(1.0,(double)box.max().getY()-box.min().getY()+1);return Math.abs(nx)<=taper&&Math.abs(nz)<=taper;}
            return nx*nx+nz*nz+(shape.equals("ellipsoid")?ny*ny:0)<=1;
        };
        for(var pos:BlockPos.betweenClosed(box.min(),box.max()))if(inside.test(pos)) {
            boolean edge=false;for(var direction:net.minecraft.core.Direction.values())if(!inside.test(pos.relative(direction))){edge=true;break;}
            if(!hollow||edge)api.setBlock(pos,state);
        }
    }
    private static CompletableFuture<JsonObject> readBlocks(JsonObject args) {
        requireWorld();var bounds=bounds(args);bounds.checkSize(32768);var client=Minecraft.getInstance();
        var result=new CompletableFuture<JsonObject>();var server=client.getSingleplayerServer();var dimension=client.level.dimension();
        var currentLevel=client.level;
        Runnable read=() -> { try {
            Level level=server==null?currentLevel:server.getLevel(dimension);if(level==null)throw new IllegalStateException("World disappeared");
            var blocks=new JsonArray();boolean includeAir=bool(args,"include_air",false),nbt=bool(args,"nbt",true);
            for(var pos:BlockPos.betweenClosed(bounds.min(),bounds.max())) {
                if(!level.hasChunkAt(pos))throw new IllegalArgumentException("Chunk not loaded: "+pos);
                var state=level.getBlockState(pos);if(!includeAir&&state.isAir())continue;
                var block=new JsonObject();block.add("pos",position(pos));block.addProperty("block",net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state));
                var entity=nbt?level.getBlockEntity(pos):null;if(entity!=null)block.addProperty("nbt",entity.saveWithoutMetadata(level.registryAccess()).toString());blocks.add(block);
            }
            var info=new JsonObject();info.add("blocks",blocks);info.addProperty("count",blocks.size());info.addProperty("authoritative",server!=null);result.complete(info);
        }catch(Throwable error){result.completeExceptionally(error);} };
        if(server!=null)server.execute(read);else read.run();return result;
    }
    private static JsonObject listBlocks(JsonObject args) {
        String filter=string(args,"filter","");int limit=integer(args,"limit",200);if(limit<1||limit>2000)throw new IllegalArgumentException("limit must be 1..2000");
        var entries=new JsonArray(); for(var block:BuiltInRegistries.BLOCK) {
            String id=BuiltInRegistries.BLOCK.getKey(block).toString();if(!id.contains(filter))continue;
            var info=new JsonObject();info.addProperty("id",id);var properties=new JsonObject();
            for(var property:block.getStateDefinition().getProperties()) {var values=new JsonArray();for(var value:property.getPossibleValues())values.add(value.toString());properties.add(property.getName(),values);}
            info.add("properties",properties);info.addProperty("default",net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(block.defaultBlockState()));entries.add(info);if(entries.size()>=limit)break;
        }
        var result=new JsonObject();result.add("blocks",entries);result.addProperty("count",entries.size());return result;
    }
    private static void nativeOperation(JsonObject args) throws Exception {
        WorldEdits.requirePermission();if(Selection.getSelectionBuffer().isEmpty())throw new IllegalStateException("Select blocks first");
        if(Selection.selectedBlockCount()>WorldEdits.MAX_BLOCKS)throw new IllegalArgumentException("Selection too large for one call");
        int amount=integer(args,"amount",1);if(amount<0||amount>64)throw new IllegalArgumentException("amount must be 0..64");
        var api=new BuildApi(null);
        switch(required(args,"operation")) {
            case "drain" -> DrainOperation.drain();case "waterlog" -> WaterlogOperation.waterlog();case "hollow" -> HollowOperation.hollow();
            case "fill_gaps" -> HollowOperation.fillGaps(api.block(required(args,"block")));
            case "fill_unreachable" -> HollowOperation.fillUnreachable(api.block(required(args,"block")));
            case "fill_nearest" -> FillNearestOperation.fillNearest();case "gravity" -> SimulateGravityOperation.gravity();
            case "update" -> UpdateBlocksOperation.updateBlocks();case "expand" -> ExpandSelection.expand(amount);case "shrink" -> ExpandSelection.shrink(amount);
            case "distort" -> DistortSelection.distort(number(args,"noise_radius",8),number(args,"stddev",1));
            case "smooth_selection" -> DistortSelection.smooth(number(args,"stddev",1),number(args,"threshold",0.5f));case "smooth_snow" -> SmoothSnow.smoothSnow();
            case "set_biome" -> SetBiomeOperation.setBiome(ResourceKey.create(Registries.BIOME,ResourceLocation.parse(required(args,"biome"))),bool(args,"fill_vertically",false));
            default -> throw new IllegalArgumentException("Unknown native operation; use API lookup for other native operations");
        }
    }
    private static CompletableFuture<JsonObject> copy(JsonObject args) {
        requireWorld();var selection=Selection.getSelectionBuffer();if(selection.isEmpty())throw new IllegalStateException("Selection empty");
        if(selection.size()>WorldEdits.MAX_BLOCKS)throw new IllegalArgumentException("Selection too large");
        boolean air=bool(args,"include_air",false),entities=bool(args,"include_entities",false);
        Object level=Minecraft.getInstance().level;var result=new CompletableFuture<JsonObject>();var ids=new ArrayList<UUID>();
        if(entities)for(var entity:Minecraft.getInstance().level.entitiesForRendering())if(entity.getType().canSerialize()&&selection.contains(entity.getBlockX(),entity.getBlockY(),entity.getBlockZ()))ids.add(entity.getUUID());
        selection.callCopy(false,air).whenComplete((copy,error) -> Minecraft.getInstance().execute(() -> {
            if(error!=null){result.completeExceptionally(error);return;}
            if(copy==null||copy.chunkedBlockRegion().isEmpty()){result.completeExceptionally(new IllegalStateException("Copy is empty"));return;}
            java.util.function.Consumer<List<CompoundTag>> finish=tags -> {
                if(result.isDone())return;
                if(Minecraft.getInstance().level!=level){result.completeExceptionally(new IllegalStateException("World changed before copy finished"));return;}
                Clipboard.INSTANCE.setClipboard(copy.chunkedBlockRegion(),copy.blockEntities(),tags,"MCP copy",135,air);result.complete(clipboard());
            };
            if(ids.isEmpty())finish.accept(List.of());
            else Dispatcher.requestEntityData(ids,data -> {var tags=new ArrayList<>(data.values());for(var tag:tags)EntityDataUtils.offsetEntityRecursive(tag,Vec3.atLowerCornerOf(copy.realOffset()));finish.accept(tags);});
        }));return result;
    }
    private static void setView(JsonObject args) {
        requireWorld();var client=Minecraft.getInstance();var player=client.player;
        if(args.has("position")){var coords=args.getAsJsonArray("position");if(coords.size()!=3)throw new IllegalArgumentException("position must have three numbers");
            double x=coords.get(0).getAsDouble(),y=coords.get(1).getAsDouble(),z=coords.get(2).getAsDouble();
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)||Math.abs(x)>29999984||Math.abs(z)>29999984)throw new IllegalArgumentException("Invalid camera position");player.setPos(x,y,z);}
        float yaw=number(args,"yaw",player.getYRot()),pitch=number(args,"pitch",player.getXRot());
        if(args.has("look_at")){var coords=args.getAsJsonArray("look_at");if(coords.size()!=3)throw new IllegalArgumentException("look_at must have three numbers");
            double dx=coords.get(0).getAsDouble()-player.getX(),dy=coords.get(1).getAsDouble()-player.getEyeY(),dz=coords.get(2).getAsDouble()-player.getZ();
            yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));pitch=(float)-Math.toDegrees(Math.atan2(dy,Math.sqrt(dx*dx+dz*dz)));}
        if(!Float.isFinite(yaw)||!Float.isFinite(pitch))throw new IllegalArgumentException("Invalid camera rotation");
        player.setYRot(yaw);player.setXRot(Math.max(-90,Math.min(90,pitch)));player.yRotO=player.getYRot();player.xRotO=player.getXRot();
    }
}
