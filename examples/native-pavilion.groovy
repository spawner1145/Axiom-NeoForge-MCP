// Native Axiom workflow: primitives, sparse strokes, curved paths and a surface mesh.
// Run in a prepared empty volume; no per-block placement list or helper fill is used.
def origin = args.origin ?: [0, 80, 0]
int x = origin[0] as int, y = origin[1] as int, z = origin[2] as int
def results = []
results << mcp.nativeShape([shape:'cuboid', center:[x+12,y,z+8], size:[25,1,17], block:'minecraft:stone_bricks'])
for (int dx : [2, 22]) for (int dz : [2, 14]) {
    results << mcp.stroke([tool:'FreehandDraw', points:[[x+dx,y+1,z+dz],[x+dx,y+9,z+dz]],
        block:'minecraft:quartz_pillar', settings:[BrushRadius:1, BrushShape:'Sphere']])
}
// Four control points describe each architectural arch.
for (int dz : [2, 14]) {
    results << mcp.nativePath([points:[[x+2,y+8,z+dz],[x+7,y+12,z+dz],[x+17,y+12,z+dz],[x+22,y+8,z+dz]],
        curve:'bezier', profile:'round', radius:1, block:'minecraft:quartz_block'])
}
// A 3x3 control net is rasterized by the native surface modeller.
results << mcp.nativeMesh([rows:[
    [[x,y+9,z],[x+12,y+14,z],[x+24,y+9,z]],
    [[x,y+10,z+8],[x+12,y+15,z+8],[x+24,y+10,z+8]],
    [[x,y+9,z+16],[x+12,y+14,z+16],[x+24,y+9,z+16]]],
    mode:'bezier_surface', thickness:2, block:'minecraft:dark_prismarine'])
results << mcp.nativePath([points:[[x+12,y,z-10],[x+7,y,z-5],[x+12,y,z]],
    curve:'catmull_rom', profile:'road', radius:2, depth:1, block:'minecraft:smooth_stone'])
return [workflow:'native Axiom creation', operations:results.size(), results:results]
