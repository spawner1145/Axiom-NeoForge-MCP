package dev.axiommcp.editor;

import com.google.gson.JsonArray;
import static dev.axiommcp.editor.ToolCatalog.add;

/** Native creation tools are listed first so agents discover the creative workflow first. */
final class NativeToolCatalog {
    private static final String POS="{\"type\":\"array\",\"items\":{\"type\":\"integer\"},\"minItems\":3,\"maxItems\":3}";
    private static final String POINTS="{\"type\":\"array\",\"items\":"+POS+",\"minItems\":1,\"maxItems\":256}";
    private static final String VECTOR="{\"type\":\"array\",\"items\":{\"type\":\"number\"},\"minItems\":3,\"maxItems\":3}";
    private static final String BLOCK="\"block\":{\"type\":\"string\"}";
    private static final String SETTINGS="\"settings\":{\"type\":\"object\"}";
    private static final String FACE="\"face\":{\"enum\":[\"up\",\"down\",\"north\",\"south\",\"east\",\"west\"]}";
    private static final String KEEP="\"keep_existing\":{\"type\":\"boolean\"}";
    static void append(JsonArray tools) {
        add(tools,"tool_stroke","PREFERRED creative workflow: execute an actual Axiom brush along sparse block-center control points. Native brush shape, masks/selection, noise/materials and native release/history logic. Discover automation.stroke and keybind in list_tools. settings patches a temporary tool copy; UI settings remain intact. continuous interpolates the path; stamps makes separate dabs in one stroke. Open editor first; bounded loaded volume up to 262144 blocks. No per-block placement list required.",
                "\"tool\":{\"type\":\"string\"},\"index\":{\"type\":\"integer\",\"minimum\":0},"+SETTINGS+","+BLOCK+",\"points\":"+POINTS+",\"sampling\":{\"enum\":[\"continuous\",\"stamps\"]},"+FACE+",\"shift\":{\"type\":\"boolean\"},\"gradient_from\":"+POS+",\"gradient_to\":"+POS,"points");
        add(tools,"native_shape","PREFERRED shape creation: installed Axiom ShapeTool generates and places shapes using native masks/history. size is x/y/z diameter; center is gizmo position. rotation is x/y/z degrees. Native parameters: thickness, ringH, ringV, exponent, capHeight, loops, coneRounding, polygonSides, cutoffAmount.",
                "\"center\":"+POS+",\"size\":"+VECTOR+",\"shape\":{\"enum\":[\"sphere\",\"cuboid\",\"octahedron\",\"cylinder\",\"supersphere\",\"supercylinder\",\"capsule\",\"tube\",\"cone\",\"pyramid\",\"torus\",\"dodecahedron\",\"icosahedron\",\"disk\",\"plane\",\"polygon\",\"superellipse\",\"spiral\"]},"+BLOCK+",\"rotation\":"+VECTOR+",\"hollow\":{\"type\":\"boolean\"},"+KEEP+",\"select\":{\"type\":\"boolean\"},\"parameters\":{\"type\":\"object\"}","center","size");
        add(tools,"native_path","PREFERRED curves/roads: invoke native Axiom PathTool on control points, including Catmull-Rom, Bezier, catenary and clipboard profiles. Uses native destination mask and editable path undo metadata. radius/end_radius are integer radii; road profile supports depth and stairs/slabs.",
                "\"points\":"+POINTS+","+BLOCK+","+SETTINGS+",\"curve\":{\"enum\":[\"bresenham\",\"dda\",\"catenary\",\"catmull_rom\",\"bezier\"]},\"profile\":{\"enum\":[\"round\",\"road\",\"clipboard\",\"spike\"]},\"radius\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":32},\"end_radius\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":32},\"depth\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":32},\"slack\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":200},\"looped\":{\"type\":\"boolean\"},\"stairs_and_slabs\":{\"type\":\"boolean\"},\"inverted\":{\"type\":\"boolean\"},"+KEEP,"points");
        add(tools,"native_mesh","PREFERRED surface modelling: feed rows of control vertices into Axiom ModellingTool. Native flat/Catmull-Rom/Bezier surfaces, convex hull, smart surface, triangle strip/fan; native masks, thickness and editable modelling undo metadata. Grid surfaces need two or more rows; other modes use one row.",
                "\"rows\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":64,\"items\":"+POINTS+"},\"mode\":{\"enum\":[\"flat_surface\",\"catmull_rom_surface\",\"bezier_surface\",\"convex_hull\",\"smart_surface\",\"triangle_strip\",\"triangle_fan\"]},\"thickness\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16},"+BLOCK+","+KEEP,"rows");
        add(tools,"clipboard_to_brush","Convert current copied region into native Painter tiled/stamped clipboard brush or add it as Stamp blueprint, then activate the tool. Copy selection first. Follow with tool_stroke (Painter) or stamp_clipboard (Stamp).",
                "\"mode\":{\"enum\":[\"stamp\",\"tiled\",\"stamped\"]},\"replace\":{\"type\":\"boolean\"}");
        add(tools,"stamp_clipboard","Native StampTool places copied regions at surface anchors in one history step, using native blueprint transforms, block-entity data, source/destination mask modes and keep-existing settings. points are surface blocks; bases are one block toward face. use_clipboard defaults true; false uses configured blueprints. Random yaw/flips use native settings.",
                "\"points\":"+POINTS+","+SETTINGS+",\"use_clipboard\":{\"type\":\"boolean\"},"+FACE,"points");
    }
}
