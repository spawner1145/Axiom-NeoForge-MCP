"""Run only after joining a world: clears and replaces the chosen building volume."""
from pathlib import Path
import argparse
import base64
import json
import urllib.request

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--endpoint',default='http://127.0.0.1:8766/mcp')
    parser.add_argument('--origin',nargs=3,type=int,default=[32,-59,16])
    parser.add_argument('--output',type=Path,default=Path('villa-result'))
    options=parser.parse_args();session=None;sequence=0
    def rpc(method,params):
        nonlocal session,sequence
        sequence+=1;headers={'Content-Type':'application/json'}
        if session:headers['Mcp-Session-Id']=session
        payload={'jsonrpc':'2.0','id':sequence,'method':method,'params':params}
        request=urllib.request.Request(options.endpoint,data=json.dumps(payload).encode(),headers=headers)
        with urllib.request.urlopen(request,timeout=35) as response:
            session=response.headers.get('Mcp-Session-Id',session);result=json.load(response)
        if 'error' in result:raise RuntimeError(result['error'])
        return result['result']
    def tool(name,arguments=None):
        result=rpc('tools/call',{'name':'axiom_'+name,'arguments':arguments or {}})
        if result.get('isError'):raise RuntimeError(result)
        return result
    rpc('initialize',{'protocolVersion':'2025-11-25','clientInfo':{'name':'Axiom villa example','version':'1'}})
    try:
        state=tool('get_editor_state')['structuredContent']
        if not state['worldLoaded']:raise RuntimeError('Join a world before running this example')
        tool('open_editor')
        script=(Path(__file__).parent/'courtyard-villa.groovy').read_text(encoding='utf-8')
        result=tool('execute_code',{'code':script,'args':{'origin':options.origin},'description':'MCP courtyard villa'})
        tool('flush');tool('clear_selection')
        x,y,z=options.origin
        tool('set_view',{'position':[x+33,y+22,z+33],'look_at':[x+10,y+6,z+7]})
        options.output.mkdir(parents=True,exist_ok=True)
        for region in ['editor','scene']:
            screenshot=tool('screenshot',{'region':region,'max_size':1600})
            image=next(value for value in screenshot['content'] if value['type']=='image')
            (options.output/f'villa-{region}.png').write_bytes(base64.b64decode(image['data']))
        tool('set_selection',{'min':[x-2,y,z-3],'max':[x+22,y+13,z+16]})
        tool('copy_selection',{'include_air':True})
        tool('export_schematic',{'path':str((options.output/'courtyard-villa.schem').resolve()),'name':'Courtyard villa'})
        tool('clear_selection')
        (options.output/'build-result.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        print('Saved screenshots and schematic to',options.output.resolve())
    finally:
        if session:
            request=urllib.request.Request(options.endpoint,method='DELETE',headers={'Mcp-Session-Id':session})
            with urllib.request.urlopen(request,timeout=5):pass

if __name__=='__main__':main()
