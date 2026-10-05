"""Exercise the built add-on over real HTTP in an isolated creative test world."""
from pathlib import Path
import base64
import hashlib
import json
import shutil
import time
import urllib.request
import production_launcher as launcher
import native_integration

ROOT = launcher.ROOT
ENDPOINT = 'http://127.0.0.1:18766/mcp'
session = None
sequence = 0
records = []


def rpc(method, params=None):
    global sequence, session
    sequence += 1
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    if session:
        headers['Mcp-Session-Id'] = session
    payload = {'jsonrpc': '2.0', 'id': sequence, 'method': method, 'params': params or {}}
    request = urllib.request.Request(ENDPOINT, data=json.dumps(payload).encode(), headers=headers)
    with urllib.request.urlopen(request, timeout=35) as response:
        session = response.headers.get('Mcp-Session-Id', session)
        result = json.load(response)
    if 'error' in result:
        raise RuntimeError(result['error'])
    return result['result']


def tool(name, arguments=None, fail=False):
    result = rpc('tools/call', {'name': 'axiom_' + name, 'arguments': arguments or {}})
    if fail:
        assert result.get('isError'), (name, result)
    elif result.get('isError'):
        raise RuntimeError(f'{name}: {result}')
    data = result.get('structuredContent', {})
    records.append({'tool': name, 'arguments': arguments or {}, 'errorExpected': fail,
                    'isError': result.get('isError', False), 'result': data})
    print(f'PASS {name}', flush=True)
    return result if name == 'screenshot' else data


def blocks(a, b=None):
    return tool('get_blocks', {'min': a, 'max': b or a, 'include_air': True})['blocks']


def script(code, **args):
    return tool('execute_code', {'code': code, **args})


def main():
    process, jar, mods = launcher.launch()
    success = False
    try:
        deadline = time.monotonic() + 100
        initialized = False
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError('Game exited before MCP/world readiness')
            try:
                if not initialized:
                    rpc('initialize', {'protocolVersion': '2025-11-25', 'clientInfo': {'name': 'Axiom integration', 'version': '1'}})
                    initialized = True
                status = rpc('tools/call', {'name': 'axiom_get_editor_state', 'arguments': {}})
                state = status.get('structuredContent', {})
                if state.get('worldLoaded') and state.get('handshakeProcessed') and state.get('allowedOnServer'):
                    break
            except (OSError, RuntimeError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError('World/MCP readiness timed out')
        catalog = rpc('tools/list')['tools']
        assert len(catalog) >= 35
        assert len({t['name'] for t in catalog}) == len(catalog)
        (ROOT / 'reports/tool-catalog.json').write_text(json.dumps(catalog, indent=2), encoding='utf-8')
        tool('get_server_status')
        tool('open_editor', {'settings': True})
        script('com.moulberry.axiom.editor.tutorial.TutorialManager.skip(); return true')
        time.sleep(2)
        state = tool('get_editor_state')
        assert state['editorActive'] and state['mcpSettingsRenderedFrames'] > 0
        assert len(tool('list_tools')['tools']) >= 32
        details = tool('get_tool', {'index': 6})
        tool('update_tool', {'index': 6, 'patch': details['settings']})
        tool('select_tool', {'index': 6})
        assert tool('list_api_classes', {'filter': 'Dispatcher'})['count'] > 0
        assert tool('api_lookup', {'class': 'com.moulberry.axiom.world_modification.Dispatcher', 'filter': 'push'})['methods']
        assert tool('api_lookup', {'expression': 'tools[6]', 'filter': 'Settings'})['methods']
        tool('list_blocks', {'filter': 'oak_stairs'})
        tool('set_active_block', {'block': 'minecraft:oak_stairs[facing=east]'})
        tool('set_selection', {'min': [0,-59,0], 'max': [3,-57,3]})
        assert tool('get_selection')['count'] == 48
        edit = tool('fill', {'block': 'minecraft:stone', 'description': 'integration fill'})
        assert edit['changed'] == 48
        assert all(b['block'] == 'minecraft:stone' for b in blocks([0,-59,0],[3,-57,3]))
        tool('undo_redo', {'action': 'undo'})
        assert all(b['block'] == 'minecraft:air' for b in blocks([0,-59,0],[3,-57,3]))
        tool('undo_redo', {'action': 'redo'})
        assert all(b['block'] == 'minecraft:stone' for b in blocks([0,-59,0],[3,-57,3]))
        tool('get_history')
        tool('batch', {'operations': [
            {'tool': 'fill', 'arguments': {'min':[4,-59,0], 'max':[6,-57,2], 'block':'minecraft:bricks', 'hollow':True}},
            {'tool': 'build_shape', 'arguments': {'min':[8,-59,0], 'max':[12,-55,4], 'block':'minecraft:stone_bricks','shape':'ellipsoid'}},
        ]})
        before = blocks([15,-59,0])
        tool('batch', {'operations': [
            {'tool':'set_blocks','arguments':{'blocks':[{'pos':[15,-59,0],'block':'minecraft:stone'}]}},
            {'tool':'set_blocks','arguments':{'blocks':[{'pos':[15,-59,1],'block':'invalid:not_a_block'}]}}
        ]}, fail=True)
        assert blocks([15,-59,0]) == before
        for shape in ['cylinder', 'line', 'pyramid', 'box']:
            tool('build_shape', {'min':[16,-59,6], 'max':[20,-55,10], 'shape':shape, 'block':'minecraft:quartz_block'})
        result = script("println 'building'; mcp.fill(-4,-59,0,-2,-57,2,'minecraft:oak_planks'); return [nativeTools: tools.size(), value: args.value + 1]", args={'value':41})
        assert result['result']['value'] == 42 and result['edit']['changed'] == 27
        script("retained = 17; return retained")
        assert script("return retained * 2")['result'] == 34
        assert all(b['block'] == 'minecraft:oak_planks' for b in blocks([-4,-59,0],[-2,-57,2]))
        tool('execute_code', {'code': "mcp.setBlock(15,-59,0,'minecraft:gold_block'); throw new RuntimeException('intentional failure')"}, fail=True)
        assert blocks([15,-59,0]) == before
        tool('set_blocks', {'blocks':[{'pos':[14,-59,0], 'block':'minecraft:chest', 'nbt':'{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}'}]})
        assert 'minecraft:diamond' in blocks([14,-59,0])[0]['nbt']
        tool('set_blocks', {'blocks':[{'pos':[14,-59,0], 'block':'minecraft:stone'}]})
        tool('set_blocks', {'blocks':[{'pos':[14,-59,0], 'block':'minecraft:stone'}]})
        tool('undo_redo', {'action':'undo'})
        assert 'minecraft:diamond' in blocks([14,-59,0])[0]['nbt']
        tool('set_selection', {'min':[0,-59,0], 'max':[3,-57,3]})
        copy = tool('copy_selection')
        assert copy['count'] == 48
        tool('transform_clipboard', {'action':'rotate','axis':'y','quarter_turns':1})
        tool('transform_clipboard', {'action':'flip','axis':'x'})
        tool('paste_clipboard', {'pos':[0,-58,12]})
        tool('export_structure', {'path':'mcp-test/clipboard.nbt'})
        assert (launcher.GAME_DIR / 'mcp-test/clipboard.nbt').is_file()
        tool('export_schematic', {'path':'mcp-test/clipboard.schem','name':'integration copy'})
        assert tool('import_schematic', {'path':'mcp-test/clipboard.schem'})['count'] == 48
        tool('get_clipboard')
        tool('editor_windows', {'window':'TOOL_MASKS','open':True})
        assert tool('update_mask', {'expression':'block = minecraft:stone','enabled':True})['expression']
        tool('get_mask')
        tool('update_mask', {'expression':'','enabled':False})
        tool('set_blocks', {'blocks':[{'pos':[15.5,-59,0], 'block':'minecraft:stone'}]}, fail=True)
        tool('fill', {'min':[0,-59,0], 'max':[1000,0,1000], 'block':'minecraft:stone'}, fail=True)
        tool('move_selection', {'offset':[1,0,0]})
        tool('native_operation', {'operation':'expand','amount':1})
        tool('native_operation', {'operation':'shrink','amount':1})
        tool('native_operation', {'operation':'update'})
        tool('editor_action', {'action':'ESCAPE'})
        tool('clear_selection')
        native_integration.run(tool, blocks, script)
        pavilion = script((ROOT/'examples/native-pavilion.groovy').read_text(encoding='utf-8'), args={'origin': [-12,-35,76]})
        assert pavilion['result']['operations'] == 9
        tool('flush')
        assert sum(b['block'] != 'minecraft:air' for b in blocks([-14,-36,64],[14,-16,94])) > 800
        tool('set_view', {'position': [30,-10,120], 'look_at': [0,-26,82]})
        capture = tool('screenshot', {'region':'scene','max_size':1280,'wait_frames':30})
        (ROOT/'reports/native-pavilion-scene.png').write_bytes(base64.b64decode(next(c for c in capture['content'] if c['type']=='image')['data']))
        tool('set_selection', {'min':[-14,-36,64], 'max':[14,-16,94]})
        tool('copy_selection', {'include_air':True})
        tool('export_schematic', {'path':str(ROOT/'reports/native-pavilion.schem'),'name':'Native Axiom pavilion'})
        tool('clear_selection')
        demo=script((ROOT/'examples/courtyard-villa.groovy').read_text(encoding='utf-8'),description='MCP courtyard villa')
        assert demo['edit']['changed'] > 2000
        assert blocks([32,-59,16])[0]['block'] == 'minecraft:smooth_stone'
        tool('set_selection', {'min':[30,-59,13], 'max':[54,-46,32]})
        tool('copy_selection', {'include_air':True})
        tool('export_schematic', {'path':str(ROOT/'reports/courtyard-villa.schem'), 'name':'MCP courtyard villa'})
        tool('clear_selection')
        tool('set_view', {'position':[65,-37,49], 'look_at':[42,-53,23]})
        tool('flush')
        script("api.write(dev.axiommcp.AxiomMcp.get().server,'lastError',''); client.gui.getChat().clearMessages(false); return true")
        for region in ['editor','scene','ui','screen']:
            capture=tool('screenshot',{'region':region,'max_size':1280,'wait_frames':20})
            image=next(c for c in capture['content'] if c['type']=='image')
            data=base64.b64decode(image['data']); assert data.startswith(b'\x89PNG\r\n\x1a\n')
            (ROOT / f'reports/integration-{region}.png').write_bytes(data)
        tool('clear_selection')
        tool('close_editor')
        tool('open_editor')
        script('Thread.startVirtualThread({ Thread.sleep(1500); client.execute({ client.stop() } as Runnable) } as Runnable); return "integration complete"')
        process.wait(timeout=25)
        assert process.returncode == 0
        success = True
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=20)
        latest=launcher.GAME_DIR / 'logs/latest.log'
        if latest.exists():
            shutil.copy2(latest,ROOT/'reports/integration-latest.log')
        result={'success':success,'exitCode':process.returncode,'jar':str(jar),
                'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),
                'mods':[p.name for p in mods], 'calls':records}
        (ROOT/'reports/integration-result.json').write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
    print('AXIOM_MCP_INTEGRATION_SUCCESS',flush=True)


if __name__ == '__main__':
    main()
