// A complete building is staged by mcp and committed as one Axiom undo step.
// origin defaults to [32,-59,16]; supply args.origin to build somewhere else.
def o = (args.origin ?: [32, -59, 16]).collect { (it as Number).intValue() }
def put = { int x, int y, int z, String block -> mcp.setBlock(o[0]+x, o[1]+y, o[2]+z, block) }
def fill = { int ax, int ay, int az, int bx, int by, int bz, String block ->
    mcp.fill(o[0]+ax,o[1]+ay,o[2]+az,o[0]+bx,o[1]+by,o[2]+bz,block)
}

// Clear the working volume, then lay a raised terrace and a pale stone foundation.
fill(-2,0,-3,22,19,16,'minecraft:air')
fill(-2,0,-3,22,0,16,'minecraft:smooth_stone')
fill(0,1,0,20,1,14,'minecraft:polished_andesite')
fill(1,2,1,19,2,13,'minecraft:oak_planks')

// Two stories around a small, open courtyard.
for (int y=3;y<=11;y++) {
    fill(0,y,0,20,y,0,'minecraft:white_terracotta')
    fill(0,y,14,20,y,14,'minecraft:white_terracotta')
    fill(0,y,1,0,y,13,'minecraft:white_terracotta')
    fill(20,y,1,20,y,13,'minecraft:white_terracotta')
    fill(6,y,4,14,y,4,'minecraft:white_terracotta')
    fill(6,y,10,14,y,10,'minecraft:white_terracotta')
    fill(6,y,5,6,y,9,'minecraft:white_terracotta')
    fill(14,y,5,14,y,9,'minecraft:white_terracotta')
}
// The center remains a courtyard; the upper floor forms a ring around it.
fill(1,7,1,19,7,13,'minecraft:dark_oak_planks')
fill(7,7,5,13,7,9,'minecraft:air')
fill(7,2,5,13,2,9,'minecraft:moss_block')
fill(9,2,6,11,2,8,'minecraft:water')

// Tall front glazing, corner pilasters and repeated window modules.
for (int x : [0,5,10,15,20]) {
    fill(x,2,-1,x,11,-1,'minecraft:quartz_pillar[axis=y]')
    put(x,12,-1,'minecraft:chiseled_quartz_block')
}
for (int x : [2,7,12,17]) {
    fill(x,3,0,x+1,5,0,'minecraft:light_blue_stained_glass')
    fill(x,8,0,x+1,10,0,'minecraft:light_blue_stained_glass')
    fill(x,3,14,x+1,5,14,'minecraft:light_blue_stained_glass')
    fill(x,8,14,x+1,10,14,'minecraft:light_blue_stained_glass')
}
for (int z : [2,6,10]) {
    fill(0,3,z,0,5,z+1,'minecraft:light_blue_stained_glass')
    fill(20,3,z,20,5,z+1,'minecraft:light_blue_stained_glass')
    fill(0,8,z,0,10,z+1,'minecraft:light_blue_stained_glass')
    fill(20,8,z,20,10,z+1,'minecraft:light_blue_stained_glass')
}
fill(9,3,0,11,5,0,'minecraft:air')
fill(9,3,4,11,5,4,'minecraft:air')
fill(9,3,-2,11,3,-1,'minecraft:smooth_stone_slab[type=bottom]')

// Entry balcony, warm trim bands and four courtyard lights.
fill(0,6,-1,20,6,-1,'minecraft:dark_oak_planks')
fill(-1,6,0,-1,6,14,'minecraft:dark_oak_planks')
fill(21,6,0,21,6,14,'minecraft:dark_oak_planks')
fill(0,6,15,20,6,15,'minecraft:dark_oak_planks')
for (int x : [7,13]) for (int z : [5,9]) put(x,3,z,'minecraft:lantern')

// Roof ring with stepped eaves, keeping the courtyard open to the sky.
for (int y=12;y<=13;y++) {
    fill(-1,y,-1,21,y,15,'minecraft:dark_oak_planks')
    fill(7,y,5,13,y,9,'minecraft:air')
}
for (int x=-1;x<=21;x++) {
    put(x,12,-2,'minecraft:dark_oak_stairs[facing=south,half=bottom]')
    put(x,12,16,'minecraft:dark_oak_stairs[facing=north,half=bottom]')
}
for (int z=-1;z<=15;z++) {
    put(-2,12,z,'minecraft:dark_oak_stairs[facing=east,half=bottom]')
    put(22,12,z,'minecraft:dark_oak_stairs[facing=west,half=bottom]')
}
// Interior ascent: place a normal staircase and an opening in the upper floor.
for (int i=0;i<5;i++) {
    fill(2,3+i,2+i,3,3+i,2+i,'minecraft:oak_stairs[facing=south]')
}
fill(2,7,5,3,7,6,'minecraft:air')

println "Courtyard villa staged at ${o}"
return [origin:o, dimensions:[25,14,20], courtyard:true, history:'one helper undo step']
