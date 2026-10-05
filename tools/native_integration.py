"""Behavior checks for actual native tool strokes, masks and clipboard/curve/surface workflows."""
import time


def run(tool, blocks, script):
    def settle():
        for _ in range(80):
            if tool('flush')['queueEmpty']:
                # Client updates/packets must also be visible to native masks on subsequent strokes.
                time.sleep(0.12)
                return
            time.sleep(0.1)
        raise AssertionError('Native dispatcher did not settle')

    def stroke(name, points, **options):
        result = tool('tool_stroke', {'tool': name, 'points': points, **options})
        assert result['native']
        settle()
        return result

    tool('clear_selection')
    tool('update_mask', {'expression': '', 'enabled': False, 'use_lua': False})
    catalog = tool('list_tools')['tools']
    assert sum(t['automation']['stroke'] for t in catalog) == 14
    keys = {t['class'].split('.')[-1]: t['keybind'] for t in catalog}

    # Sparse diagonal path; adjacent samples drive a volumetric native brush, one native undo entry.
    points = [[70, -48, 0], [77, -46, 6]]
    before = blocks([68, -50, -2], [79, -44, 8])
    result = stroke(keys['FreehandDraw'], points, block='minecraft:stone', settings={'BrushRadius': 2})
    assert result['pathSamples'] > len(points)
    painted = blocks([68, -50, -2], [79, -44, 8])
    assert sum(b['block'] == 'minecraft:stone' for b in painted) > 50
    tool('undo_redo', {'action': 'undo'})
    settle()
    assert blocks([68, -50, -2], [79, -44, 8]) == before
    tool('undo_redo', {'action': 'redo'})
    settle()
    assert blocks([68, -50, -2], [79, -44, 8]) == painted
    draw_index = next(t['index'] for t in catalog if t['class'].endswith('.FreehandDraw'))
    assert tool('get_tool', {'index': draw_index})['settings'] == next(t['settings'] for t in catalog if t['index'] == draw_index)

    # Tool destination masks apply inside native brush execution, not just in configuration.
    tool('update_mask', {'expression': 'block = minecraft:stone', 'mode': 2, 'enabled': True})
    stroke(keys['FreehandDraw'], points, block='minecraft:gold_block', settings={'BrushRadius': 3})
    masked = blocks([67, -51, -3], [80, -43, 9])
    old = {tuple(b['pos']): b['block'] for b in painted}
    gold = [b for b in masked if b['block'] == 'minecraft:gold_block']
    assert len(gold) > 50
    assert all(old.get(tuple(b['pos'])) == 'minecraft:stone' for b in gold)
    assert any(b['block'] == 'minecraft:air' for b in masked)
    tool('update_mask', {'expression': '', 'enabled': False})

    # Paint/noise operate on existing solid blocks, with native seeded noise and thresholds.
    tool('native_shape', {'shape': 'cuboid', 'center': [86, -48, 0], 'size': [9, 7, 9], 'block': 'minecraft:stone'})
    settle()
    original = blocks([81, -52, -5], [91, -44, 5])
    stroke('NoisePainterTool', [[84, -48, 0], [88, -48, 0]], settings={
        'BrushRadius': 4, 'MaskSurface': 0, 'NoiseSeed': '7319', 'Octaves': 3,
        'NoiseScaleX': 3, 'NoiseScaleY': 3, 'NoiseScaleZ': 3,
        'BlockThresholds': [{'Block': 'minecraft:granite', 'Percentage': 50.0},
                            {'Block': 'minecraft:andesite', 'Percentage': 50.0}]})
    noisy = blocks([81, -52, -5], [91, -44, 5])
    assert {'minecraft:granite', 'minecraft:andesite'} <= {b['block'] for b in noisy}
    tool('undo_redo', {'action': 'undo'})
    settle()
    assert blocks([81, -52, -5], [91, -44, 5]) == original
    stroke('NoisePainterTool', [[84, -48, 0], [88, -48, 0]], settings={
        'BrushRadius': 4, 'MaskSurface': 0, 'NoiseSeed': '7319', 'Octaves': 3,
        'NoiseScaleX': 3, 'NoiseScaleY': 3, 'NoiseScaleZ': 3,
        'BlockThresholds': [{'Block': 'minecraft:granite', 'Percentage': 50.0},
                            {'Block': 'minecraft:andesite', 'Percentage': 50.0}]})
    assert blocks([81, -52, -5], [91, -44, 5]) == noisy
    stroke('PainterTool', [[84, -48, 0], [88, -48, 0]], block='minecraft:bricks', settings={'BrushRadius': 4, 'MaskSurface': 0, 'Mode': 0})
    assert any(b['block'] == 'minecraft:bricks' for b in blocks([81, -52, -5], [91, -44, 5]))

    # Selection itself can be painted as a native volumetric stroke.
    stroke('FreehandSelect', [[70, -40, 15], [75, -40, 15]], settings={'BrushRadius': 2, 'Mode': 0})
    assert tool('get_selection')['count'] > 50
    tool('clear_selection')

    # Lua script brush compiles and uses the same native runtime without opening its options panel.
    stroke('ScriptBrushTool', [[70, -38, 15], [76, -38, 15]], settings={'BrushRadius': 2, 'Script': 'return blocks.emerald_block'})
    assert any(b['block'] == 'minecraft:emerald_block' for b in blocks([68, -40, 13], [78, -36, 17]))
    failed_before = blocks([68, -40, 13], [78, -36, 17])
    tool('tool_stroke', {'tool': 'ScriptBrushTool', 'points': [[70, -38, 15]], 'settings': {'BrushRadius': 2, 'Script': 'error("brush failure")'}}, fail=True)
    assert blocks([68, -40, 13], [78, -36, 17]) == failed_before

    # Original Shape/Path/Modelling generators receive whole primitives/control vertices.
    shape_before = blocks([94, -51, -4], [106, -41, 4])
    tool('native_shape', {'shape': 'cylinder', 'center': [100, -46, 0], 'size': [7, 7, 7], 'block': 'minecraft:quartz_block', 'rotation': [0, 0, 45]})
    settle()
    assert any(b['block'] == 'minecraft:quartz_block' for b in blocks([94, -51, -4], [106, -41, 4]))
    tool('undo_redo', {'action': 'undo'})
    settle()
    assert blocks([94, -51, -4], [106, -41, 4]) == shape_before
    tool('native_path', {'points': [[82, -40, 16], [86, -36, 20], [90, -40, 24]], 'curve': 'catmull_rom', 'radius': 2, 'block': 'minecraft:stone_bricks'})
    settle()
    assert any(b['block'] == 'minecraft:stone_bricks' for b in blocks([80, -42, 14], [92, -34, 26]))
    # Flat road profile stays in the path plane/depth; it must not map to Axiom's Spike profile.
    tool('native_path', {'points': [[82, -30, 16], [90, -30, 16]], 'curve':'dda', 'profile':'road', 'radius':2, 'depth':1, 'block':'minecraft:polished_andesite'})
    settle()
    road = [b for b in blocks([79,-34,12],[93,-26,20]) if b['block'] == 'minecraft:polished_andesite']
    assert len(road) > 20
    assert all(-31 <= b['pos'][1] <= -30 for b in road)
    mesh_before = blocks([94, -44, 14], [108, -34, 26])
    tool('native_mesh', {'rows': [[[96, -40, 16], [104, -38, 16]], [[96, -38, 24], [104, -40, 24]]], 'mode': 'flat_surface', 'thickness': 2, 'block': 'minecraft:prismarine'})
    settle()
    assert sum(b['block'] == 'minecraft:prismarine' for b in blocks([94, -44, 14], [108, -34, 26])) > 30
    tool('undo_redo', {'action': 'undo'})
    settle()
    assert blocks([94, -44, 14], [108, -34, 26]) == mesh_before

    # Copy a native primitive and turn the actual region into Painter/Stamp material.
    tool('native_shape', {'shape': 'cuboid', 'center': [70, -46, 30], 'size': [3, 3, 3], 'block': 'minecraft:lapis_block', 'select': True})
    settle()
    tool('copy_selection', {'include_air': False})
    assert tool('get_clipboard')['count'] > 0
    tool('clear_selection')
    for mode in ['tiled', 'stamped']:
        tool('clipboard_to_brush', {'mode': mode})
        if mode == 'tiled':
            tool('native_shape', {'shape': 'cuboid', 'center': [80, -46, 30], 'size': [7, 5, 7], 'block': 'minecraft:stone'})
            settle()
            stroke('PainterTool', [[80, -46, 30]], settings={'BrushRadius': 3, 'MaskSurface': 0})
            assert any(b['block'] == 'minecraft:lapis_block' for b in blocks([76, -49, 26], [84, -43, 34]))
        else:
            tool('native_shape', {'shape': 'cuboid', 'center': [90, -46, 30], 'size': [7, 5, 7], 'block': 'minecraft:stone'})
            settle()
            stroke('PainterTool', [[90, -46, 30]], sampling='stamps', settings={'MaskSurface': 0})
            assert any(b['block'] == 'minecraft:lapis_block' for b in blocks([85, -51, 25], [95, -41, 35]))
    tool('clipboard_to_brush', {'mode': 'stamp'})
    stamp_before = blocks([94, -51, 25], [110, -41, 35])
    stamped = tool('stamp_clipboard', {'points': [[98, -48, 30], [106, -48, 30]], 'settings': {'RandomYaw': 0, 'RandomXZFlip': 0}})
    assert stamped['placements'] == 2
    settle()
    assert sum(b['block'] == 'minecraft:lapis_block' for b in blocks([94, -51, 25], [110, -41, 35])) >= 27
    tool('undo_redo', {'action': 'undo'})
    settle()
    assert blocks([94, -51, 25], [110, -41, 35]) == stamp_before

    # Every advertised native stroke adapter executes its actual initialization/pather/release.
    tool('native_shape', {'shape': 'sphere', 'center': [54, -46, 58], 'size': [9, 9, 9], 'block': 'minecraft:stone'})
    settle()
    for native in ['GradientPainterTool', 'RockTool', 'WeldTool', 'MeltTool', 'SmoothTool', 'DistortTool', 'BlendTool', 'ShatterTool', 'BiomePainterTool']:
        args = {'settings': {'BrushRadius': 3}, 'block': 'minecraft:andesite'}
        if native == 'GradientPainterTool':
            args['gradient_from'] = [52, -46, 58]
            args['gradient_to'] = [56, -46, 58]
            args['settings']['MaskSurface'] = 0
        if native == 'BiomePainterTool':
            args['settings']['FillVertically'] = 0
        checkpoint = blocks([46, -54, 50], [62, -38, 66])
        response = stroke(native, [[52, -46, 58], [56, -46, 58]], **args)
        if response['historyPosition'] > response['historyPositionBefore']:
            tool('undo_redo', {'action': 'undo'})
            settle()
            assert blocks([46, -54, 50], [62, -38, 66]) == checkpoint

    # Groovy helpers use whole native strokes too; bad paths/tools cannot mutate the test world.
    script("return mcp.stroke([tool:'FreehandDraw', points:[[72,-32,0],[78,-32,0]], block:'minecraft:copper_block', settings:[BrushRadius:2]])")
    settle()
    assert any(b['block'] == 'minecraft:copper_block' for b in blocks([70, -34, -2], [80, -30, 2]))
    tool('tool_stroke', {'tool': 'ShapeTool', 'points': [[70, -38, 15]]}, fail=True)
    tool('tool_stroke', {'tool': 'FreehandDraw', 'points': [[70.5, -38, 15]]}, fail=True)
    tool('tool_stroke', {'tool': 'FreehandDraw', 'points': [[70, -38, 15], [10000, -38, 15]]}, fail=True)
    tool('select_tool', {'index': next(t['index'] for t in catalog if t['class'].endswith('.PainterTool'))})
    tool('clear_selection')
    print('NATIVE_CREATION_BEHAVIOR_SUCCESS', flush=True)
