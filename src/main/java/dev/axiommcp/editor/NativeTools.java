package dev.axiommcp.editor;

import com.google.gson.*;
import com.moulberry.axiom.AxiomClient;
import com.moulberry.axiom.UserAction;
import com.moulberry.axiom.brush_shapes.BrushShape;
import com.moulberry.axiom.clipboard.Clipboard;
import com.moulberry.axiom.editor.EditorUI;
import com.moulberry.axiom.editor.palette.ActiveBlockHistory;
import com.moulberry.axiom.editor.widgets.BrushWidget;
import com.moulberry.axiom.gizmo.Gizmo;
import com.moulberry.axiom.pather.async.*;
import com.moulberry.axiom.rasterization.Rasterization3D;
import com.moulberry.axiom.tools.*;
import com.moulberry.axiom.tools.modelling.ModellingTool;
import com.moulberry.axiom.tools.path.PathTool;
import com.moulberry.axiom.tools.shape.ShapeTool;
import com.moulberry.axiom.tools.stamp.StampTool;
import com.moulberry.axiom.world_modification.Dispatcher;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import java.lang.reflect.*;
import java.util.*;
import static dev.axiommcp.editor.EditorBridge.*;

/** Adapts path/control-point input to the installed Axiom tools, including their own completion/history logic. */
public final class NativeTools {
    private static final Set<String> STROKES = Set.of("FreehandSelect", "PainterTool", "NoisePainterTool", "BiomePainterTool",
            "GradientPainterTool", "ScriptBrushTool", "FreehandDraw", "RockTool", "WeldTool", "MeltTool", "SmoothTool", "DistortTool", "BlendTool", "ShatterTool");
    private static final int MAX_SAMPLES = 8192;

    public static boolean supportsStroke(Tool tool) { return STROKES.contains(tool.getClass().getSimpleName()); }
    public static JsonObject capabilities(Tool tool) {
        var info = new JsonObject(); info.addProperty("stroke", supportsStroke(tool));
        info.addProperty("shape", tool instanceof ShapeTool); info.addProperty("path", tool instanceof PathTool);
        info.addProperty("mesh", tool instanceof ModellingTool); info.addProperty("stamp", tool instanceof StampTool);
        return info;
    }
    private static Tool resolve(JsonObject args, String fallback) {
        if (args.has("index")) {
            Tool tool = ToolManager.getToolByIndex(integer(args,"index",-1));
            if (tool == null) throw new IllegalArgumentException("Unknown tool index");
            return tool;
        }
        String id = string(args,"tool",fallback);
        if (id == null) return ToolManager.getCurrentTool();
        for (Tool tool : ToolManager.getTools()) if (id.equals(tool.keybindId()) || id.equals(tool.getClass().getSimpleName())) return tool;
        throw new IllegalArgumentException("Unknown native tool: " + id + "; query axiom_list_tools");
    }
    private static void require(Tool tool, JsonObject args) throws Exception {
        WorldEdits.requirePermission();
        if (!EditorUI.isActive() || Minecraft.getInstance().screen != null) throw new IllegalStateException("Open the Axiom editor first");
        if (!AxiomClient.hasPermissions(tool.requiredPermissions())) throw new IllegalStateException("Axiom denied native tool permissions");
        if (args.has("block")) ((ActiveBlockHistory)ApiIntrospection.read(EditorUI.class,"activeBlockHistory"))
                .setActive((com.moulberry.axiom.custom_blocks.CustomBlockState)new BuildApi(null).block(required(args,"block")));
    }
    private static Tool cloneTool(Tool source, JsonObject args) throws Exception {
        var settings = new CompoundTag(); source.writeSettings(settings);
        // Some native tools (notably Script Brush) omit the brush widget from their presets.
        try {((BrushWidget)ApiIntrospection.read(source,"brushWidget")).writeSettings(settings);}catch(NoSuchFieldException ignored){}
        if (args.has("settings")) settings = NbtJson.merge(settings,args.getAsJsonObject("settings"));
        Tool result = source.getClass().getDeclaredConstructor().newInstance(); result.loadSettings(settings);
        try {((BrushWidget)ApiIntrospection.read(result,"brushWidget")).loadSettings(settings);}catch(NoSuchFieldException ignored){}
        if(result.getClass().getSimpleName().equals("ScriptBrushTool")) {
            String script=settings.getString("Script");invoke(result,"updateCustomArguments",script);ApiIntrospection.write(result,"checkedScript",script);
        }
        return result;
    }
    public static Object invoke(Object instance, String methodName, Object... args) throws Exception {
        for (Class<?> type = instance.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(methodName) || method.getParameterCount() != args.length) continue;
                method.setAccessible(true);
                try { return method.invoke(instance,args); }
                catch (InvocationTargetException error) {
                    if (error.getCause() instanceof Exception exception) throw exception;
                    if (error.getCause() instanceof Error fatal) throw fatal;
                    throw error;
                }
            }
        }
        throw new NoSuchMethodException(methodName);
    }
    private static List<BlockPos> points(JsonObject args, String key, int min) {
        JsonArray input = args.getAsJsonArray(key);
        if (input == null || input.size() < min || input.size() > 256) throw new IllegalArgumentException(key + " count must be " + min + "..256");
        var result = new ArrayList<BlockPos>();
        for (var value : input) { var object = new JsonObject(); object.add("point",value); result.add(pos(object,"point")); }
        return result;
    }
    private static void checkBounds(BlockPos min, BlockPos max) {
        var box = BuildApi.Bounds.of(min,max); box.checkSize(WorldEdits.MAX_BLOCKS);
        var world = Minecraft.getInstance().level;
        if (world.isOutsideBuildHeight(box.min()) || world.isOutsideBuildHeight(box.max()) || Math.abs((long)box.min().getX()) > 29999984
                || Math.abs((long)box.max().getX()) > 29999984 || Math.abs((long)box.min().getZ()) > 29999984 || Math.abs((long)box.max().getZ()) > 29999984)
            throw new IllegalArgumentException("Native operation extends outside world bounds");
        for (int x=box.min().getX()>>4; x<=box.max().getX()>>4; x++) for (int z=box.min().getZ()>>4; z<=box.max().getZ()>>4; z++)
            if (!world.hasChunk(x,z)) throw new IllegalArgumentException("Native operation requires loaded chunks");
    }
    private static void checkPoints(List<BlockPos> points, int margin) {
        int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
        for (var point:points) { minX=Math.min(minX,point.getX()); minY=Math.min(minY,point.getY()); minZ=Math.min(minZ,point.getZ());
            maxX=Math.max(maxX,point.getX()); maxY=Math.max(maxY,point.getY()); maxZ=Math.max(maxZ,point.getZ()); }
        if(margin<0||margin>128)throw new IllegalArgumentException("Native extent must be 0..128");
        checkBounds(new BlockPos(minX-margin,minY-margin,minZ-margin),new BlockPos(maxX+margin,maxY+margin,maxZ+margin));
    }
    private static int size(JsonObject args, String key, int fallback, int min, int max) {
        int value=integer(args,key,fallback); if(value<min||value>max)throw new IllegalArgumentException(key+" must be "+min+".."+max); return value;
    }
    private static void array(Object tool, String field, Number value) throws Exception {
        Object target=ApiIntrospection.read(tool,field);
        if(target instanceof int[] ints)ints[0]=value.intValue(); else if(target instanceof float[] floats)floats[0]=value.floatValue();
        else throw new IllegalArgumentException("Unknown native parameter: "+field);
    }
    private static JsonObject result(Tool tool) {
        var info=new JsonObject();info.addProperty("native",true);info.addProperty("tool",tool.keybindId());
        info.addProperty("class",tool.getClass().getName());info.addProperty("submitted",true);
        info.addProperty("historyPosition",Dispatcher.getHistoryPosition(false));return info;
    }

    public static JsonObject stroke(JsonObject args) throws Exception {
        Tool source=resolve(args,null);
        if(!supportsStroke(source))throw new IllegalArgumentException("This tool has no stroke adapter; use native_shape/native_path/native_mesh/stamp_clipboard or its native API");
        var controls=points(args,"points",1);require(source,args);Tool tool=cloneTool(source,args);
        try {
        var brush=(BrushWidget)ApiIntrospection.read(tool,"brushWidget"); BrushShape shape=brush.getBrushShape();
        var box=shape.boundingBox(); int margin=0;
        for(var p:List.of(box.pos1(),box.pos2())) margin=Math.max(margin,Math.max(Math.abs(p.getX()),Math.max(Math.abs(p.getY()),Math.abs(p.getZ()))));
        checkPoints(controls,margin);
        var samples=new LongArrayList();samples.add(controls.getFirst().asLong());
        if(!string(args,"sampling","continuous").equals("continuous") && !string(args,"sampling","continuous").equals("stamps"))throw new IllegalArgumentException("sampling must be continuous or stamps");
        for(int i=1;i<controls.size();i++) {
            if(string(args,"sampling","continuous").equals("stamps"))samples.add(controls.get(i).asLong());
            else Rasterization3D.ddaSkipFrom(controls.get(i-1),controls.get(i),(x,y,z)->{
                if(samples.size()>=MAX_SAMPLES)throw new IllegalArgumentException("Stroke exceeds "+MAX_SAMPLES+" path samples"); samples.add(BlockPos.asLong(x,y,z));
            });
        }
        Direction face=Direction.valueOf(string(args,"face","up").toUpperCase(Locale.ROOT));
        int history=Dispatcher.getHistoryPosition(false);
        try(var input=NativeToolInput.begin(controls.getFirst(),face,bool(args,"shift",false))) {
            if(tool.getClass().getSimpleName().equals("GradientPainterTool")) {
                var start=args.has("gradient_from")?pos(args,"gradient_from"):controls.getFirst();
                var end=args.has("gradient_to")?pos(args,"gradient_to"):controls.getLast();
                if(start.equals(end))throw new IllegalArgumentException("Gradient needs distinct gradient_from/gradient_to");
                ApiIntrospection.write(tool,"pos1",Vec3.atCenterOf(start));ApiIntrospection.write(tool,"pos2",Vec3.atCenterOf(end));
                ApiIntrospection.write(tool,"direction",Vec3.atCenterOf(end).subtract(Vec3.atCenterOf(start)).normalize());array(tool,"lockedGradient",2);
            }
            if(tool.getClass().getSimpleName().equals("ScriptBrushTool") && Boolean.TRUE.equals(ApiIntrospection.read(tool,"executingOnce")))
                throw new IllegalArgumentException("$once$ scripts are point actions; use regular native Script Brush for strokes");
            tool.callAction(UserAction.RIGHT_MOUSE,null);
            var provider=(AsyncToolPathProvider)ApiIntrospection.read(tool,"pathProvider");
            if(provider==null)throw new IllegalStateException("Native tool could not start; check brush settings, script and materials");
            // No mouse/camera sampling. Stop the empty native worker, then supply path samples to its real pather.
            provider.close();var pather=(AsyncToolPather)ApiIntrospection.read(provider,"pather");
            pather.acceptInitial(samples.getLong(0));pather.update();
            if(string(args,"sampling","continuous").equals("stamps")) {
                for(int i=1;i<samples.size();i++){pather.acceptInitial(samples.getLong(i));pather.update();}
            } else {
                long[] sampled=samples.toLongArray();
                for(int i=1;i<sampled.length;i+=32){pather.accept(Arrays.copyOfRange(sampled,i,Math.min(i+32,sampled.length)));pather.update();}
            }
            if(tool.getClass().getSimpleName().equals("ScriptBrushTool") && ApiIntrospection.read(tool,"luaExecutionError")!=null)
                throw new IllegalArgumentException("Native Lua brush failed: "+ApiIntrospection.read(tool,"luaExecutionError"));
            // Unlock the temporary gradient before release: its locked-mode tail renders guides after committing.
            if(tool.getClass().getSimpleName().equals("GradientPainterTool"))array(tool,"lockedGradient",0);
            // The release branch does not render; it runs the native final filters and native undo submission.
            tool.render(null);
            var response=result(tool);response.addProperty("pathSamples",samples.size());response.addProperty("controlPoints",controls.size());
            response.addProperty("historyPositionBefore",history);return response;
        }
        } finally {
            tool.reset();
            if(tool.getClass().getSimpleName().equals("ScriptBrushTool"))
                ((imgui.moulberry92.extension.texteditor.TextEditor)ApiIntrospection.read(tool,"luaEditor")).destroy();
        }
    }

    public static JsonObject shape(JsonObject args) throws Exception {
        Tool source=resolve(new JsonObject(),"shape");require(source,args);
        var tool=new ShapeTool();var center=pos(args,"center");
        String type=string(args,"shape","sphere"); var solids=List.of("sphere","cuboid","octahedron","cylinder","supersphere","supercylinder","capsule","tube","cone","pyramid","torus","dodecahedron","icosahedron");
        var planes=List.of("disk","plane","polygon","superellipse","spiral");
        if(!solids.contains(type)&&!planes.contains(type))throw new IllegalArgumentException("Unknown native shape");
        var dimensions=args.getAsJsonArray("size");if(dimensions==null||dimensions.size()!=3)throw new IllegalArgumentException("size requires three diameters");
        float[] sizes=new float[3];float max=0;
        for(int i=0;i<3;i++){sizes[i]=dimensions.get(i).getAsFloat();if(!Float.isFinite(sizes[i])||sizes[i]<=0||sizes[i]>128)throw new IllegalArgumentException("size must be 0..128");max=Math.max(max,sizes[i]);}
        checkPoints(List.of(center),(int)Math.ceil(Math.sqrt(sizes[0]*sizes[0]+sizes[1]*sizes[1]+sizes[2]*sizes[2])/2)+2);
        array(tool,"shapeCategory",solids.contains(type)?0:1);array(tool,solids.contains(type)?"shapeSolid":"shapePlane",solids.contains(type)?solids.indexOf(type):planes.indexOf(type));
        ApiIntrospection.write(tool,"separateXYZ",true);array(tool,"sizeX",sizes[0]);array(tool,"sizeY",sizes[1]);array(tool,"sizeZ",sizes[2]);
        ApiIntrospection.write(tool,"hollow",bool(args,"hollow",false));ApiIntrospection.write(tool,"keepExisting",bool(args,"keep_existing",false));
        ApiIntrospection.write(tool,"gizmo",new Gizmo(center));
        if(args.has("rotation")){var r=args.getAsJsonArray("rotation");if(r.size()!=3)throw new IllegalArgumentException("rotation requires x/y/z degrees");
            float[] degrees=new float[3];for(int i=0;i<3;i++){degrees[i]=r.get(i).getAsFloat();if(!Float.isFinite(degrees[i]))throw new IllegalArgumentException("Invalid rotation");}
            array(tool,"rotationX",degrees[0]);array(tool,"rotationY",degrees[1]);array(tool,"rotationZ",degrees[2]);
            ((Quaternionf)ApiIntrospection.read(tool,"rotationQuaternion")).identity().rotateYXZ((float)Math.toRadians(degrees[1]),(float)Math.toRadians(degrees[0]),(float)Math.toRadians(degrees[2]));}
        if(args.has("parameters"))for(var entry:args.getAsJsonObject("parameters").entrySet()) {
            if(!Set.of("thickness","ringH","ringV","exponent","capHeight","loops","coneRounding","polygonSides","cutoffAmount").contains(entry.getKey()))throw new IllegalArgumentException("Unknown shape parameter");
            float v=entry.getValue().getAsFloat();if(!Float.isFinite(v)||v<0||v>128)throw new IllegalArgumentException("Shape parameters must be 0..128");array(tool,entry.getKey(),v);
        }
        try {tool.pasteShape(bool(args,"select",false));return result(tool);}finally{tool.reset();}
    }
    public static JsonObject path(JsonObject args) throws Exception {
        Tool source=resolve(new JsonObject(),"path");require(source,args);var tool=(PathTool)cloneTool(source,args);
        var controls=points(args,"points",2);int radius=size(args,"radius",2,0,32),endRadius=size(args,"end_radius",radius,0,32),depth=size(args,"depth",0,0,32);
        var curves=List.of("bresenham","dda","catenary","catmull_rom","bezier");String curve=string(args,"curve","catmull_rom");if(!curves.contains(curve))throw new IllegalArgumentException("Unknown curve");
        var shapes=List.of("round","road","clipboard","spike");String shape=string(args,"profile","round");if(!shapes.contains(shape))throw new IllegalArgumentException("Unknown profile");
        if(shape.equals("clipboard")&&Clipboard.INSTANCE.isEmpty())throw new IllegalStateException("Copy a region first");
        int margin=Math.max(radius,Math.max(endRadius,depth))+2;
        if(shape.equals("clipboard")) {var clip=Clipboard.INSTANCE.getClipboard().blockRegion();margin=Math.max(margin,Math.max(clip.max().getX()-clip.min().getX(),Math.max(clip.max().getY()-clip.min().getY(),clip.max().getZ()-clip.min().getZ()))+2);}
        checkPoints(controls,margin);
        tool.restore(controls,List.of(),curves.indexOf(curve),bool(args,"looped",false),bool(args,"stairs_and_slabs",false),shapes.indexOf(shape),radius,endRadius,depth,
                bool(args,"inverted",false),size(args,"slack",20,0,200),bool(args,"keep_existing",false),false);
        try {invoke(tool,"pastePath");return result(tool);}finally{tool.reset();}
    }
    public static JsonObject mesh(JsonObject args) throws Exception {
        Tool source=resolve(new JsonObject(),"modelling");require(source,args);var tool=new ModellingTool();
        var input=args.getAsJsonArray("rows");if(input==null||input.isEmpty()||input.size()>64)throw new IllegalArgumentException("rows count must be 1..64");
        var rows=new ArrayList<List<BlockPos>>();var all=new ArrayList<BlockPos>();
        for(var row:input){var object=new JsonObject();object.add("row",row);var points=points(object,"row",2);rows.add(points);all.addAll(points);}
        var modes=List.of("flat_surface","catmull_rom_surface","bezier_surface","convex_hull","smart_surface","triangle_strip","triangle_fan");
        String mode=string(args,"mode","flat_surface");if(!modes.contains(mode))throw new IllegalArgumentException("Unknown modelling mode");
        int thickness=size(args,"thickness",1,1,16);checkPoints(all,thickness+2);
        if(modes.indexOf(mode)<=2 && rows.size()<2)throw new IllegalArgumentException("Surface modelling needs at least two rows");
        tool.restore(rows,modes.indexOf(mode),thickness,bool(args,"keep_existing",false),false);
        try{invoke(tool,"pasteShape");return result(tool);}finally{tool.reset();}
    }
    public static JsonObject clipboardBrush(JsonObject args) throws Exception {
        String mode=string(args,"mode","stamp");if(Clipboard.INSTANCE.isEmpty())throw new IllegalStateException("Select and copy a region first");
        Tool target=resolve(new JsonObject(),mode.equals("stamp")?"stamp":"painter");require(target,new JsonObject());
        if(mode.equals("stamp")){if(bool(args,"replace",true)){var tag=new CompoundTag();target.writeSettings(tag);tag.remove("Blueprints");target.loadSettings(tag);}invoke(target,"addBlueprint",Clipboard.INSTANCE.getClipboard());}
        else if(mode.equals("tiled")||mode.equals("stamped")){var settings=new CompoundTag();target.writeSettings(settings);settings.putInt("Mode",mode.equals("tiled")?1:2);target.loadSettings(settings);}
        else throw new IllegalArgumentException("mode must be stamp, tiled or stamped");
        ToolManager.setCurrentToolIndex(ToolManager.getTools().indexOf(target));ToolManager.setToolSelected(true);
        var info=result(target);info.addProperty("mode",mode);info.addProperty("clipboardBlocks",Clipboard.INSTANCE.getClipboard().blockRegion().count());return info;
    }
    @SuppressWarnings("unchecked") public static JsonObject stamp(JsonObject args) throws Exception {
        Tool source=resolve(new JsonObject(),"stamp");require(source,args);var tool=(StampTool)cloneTool(source,args);
        var controls=points(args,"points",1);Direction face=Direction.valueOf(string(args,"face","up").toUpperCase(Locale.ROOT));
        if(bool(args,"use_clipboard",true)){if(Clipboard.INSTANCE.isEmpty())throw new IllegalStateException("Copy a region first");
            var settings=new CompoundTag();tool.writeSettings(settings);settings.remove("Blueprints");tool.loadSettings(settings);invoke(tool,"addBlueprint",Clipboard.INSTANCE.getClipboard());}
        var blueprints=(List<com.moulberry.axiom.clipboard.ClipboardObject>)ApiIntrospection.read(tool,"blueprints");if(blueprints.isEmpty())throw new IllegalStateException("Stamp has no blueprints");
        int margin=2;for(var clip:blueprints){var min=clip.blockRegion().min();var max=clip.blockRegion().max();margin=Math.max(margin,Math.max(max.getX()-min.getX(),Math.max(max.getY()-min.getY(),max.getZ()-min.getZ()))+2);}
        checkPoints(controls,margin);var placements=(List<Object>)ApiIntrospection.read(tool,"brushPlacements");
        var mask=com.moulberry.axiom.mask.MaskManager.getSourceMask();var context=new com.moulberry.axiom.mask.MaskContext(Minecraft.getInstance().level);
        try {for(var point:controls){context.setPosition(point.getX(),point.getY(),point.getZ());
            if(((int[])ApiIntrospection.read(tool,"maskMode"))[0]!=1&&!mask.test(context))continue;
            var base=point.relative(face);Object placement=invoke(tool,"createSinglePlacement",base.getX(),base.getY(),base.getZ(),face);if(placement!=null)placements.add(placement);}
            int count=placements.size();invoke(tool,"doPlacement");var response=result(tool);response.addProperty("placements",count);return response;
        }finally{tool.reset();}
    }
}
