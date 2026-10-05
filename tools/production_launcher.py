"""Adapted from Axiom-NeoForge's launcher for packaged MCP integration testing.

Uses only synthetic offline credentials and an isolated game directory.
Never reads launcher accounts or opens the user's existing worlds.
"""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MC_ROOT = Path(r'D:\game\.minecraft')
VERSION = '1.21.1-NeoForge_21.1.252'
VERSION_DIR = MC_ROOT / 'versions' / VERSION
GAME_DIR = ROOT / 'run-production'


def allowed(entry):
    rules = entry.get('rules')
    if not rules:
        return True
    result = False
    for rule in rules:
        os_rule = rule.get('os', {})
        matches = os_rule.get('name', 'windows') == 'windows'
        if 'arch' in os_rule:
            matches &= os_rule['arch'] in ('amd64', 'x86_64')
        if rule.get('features'):
            matches = False
        if matches:
            result = rule['action'] == 'allow'
    return result


def launch():
    metadata = json.loads((VERSION_DIR / (VERSION + '.json')).read_text(encoding='utf-8'))
    GAME_DIR.mkdir(exist_ok=True)
    (GAME_DIR / 'mods').mkdir(exist_ok=True)
    mod_version = re.search(r"version = '([^']+)'", (ROOT / 'build.gradle').read_text(encoding='utf-8')).group(1)
    jars = list((ROOT / 'build/libs').glob(f'axiom-mcp-neoforge-1.21.1-{mod_version}.jar'))
    if len(jars) != 1:
        raise RuntimeError(f'Expected one built Axiom JAR: {jars}')
    combination = sorted((VERSION_DIR / 'mods').glob('*.jar'))
    combination = [p for p in combination if not p.name.lower().startswith('axiom-mcp-')]
    # Keep older test artifacts out of NeoForge discovery when testing a new add-on version.
    for old in (GAME_DIR / 'mods').glob('axiom-mcp-*.jar'):
        if old.name != jars[0].name:
            archive = ROOT / 'reports/old-test-jars'
            archive.mkdir(parents=True, exist_ok=True)
            shutil.move(str(old), str(archive / old.name))
    for source in [jars[0], *combination]:
        shutil.copy2(source, GAME_DIR / 'mods' / source.name)
    (GAME_DIR / 'config').mkdir(exist_ok=True)
    (GAME_DIR / 'config/axiom-mcp.json').write_text('{"host":"127.0.0.1","port":18766}', encoding='utf-8')
    (GAME_DIR / 'config/photon-mcp.json').write_text('{"host":"127.0.0.1","port":18765}', encoding='utf-8')
    classpath = []
    for library in metadata['libraries']:
        artifact = library.get('downloads', {}).get('artifact')
        if artifact and allowed(library):
            path = MC_ROOT / 'libraries' / artifact['path']
            if not path.is_file():
                raise FileNotFoundError(path)
            if str(path) not in classpath:
                classpath.append(str(path))
    classpath.append(str(VERSION_DIR / (VERSION + '.jar')))
    values = {
        'auth_player_name': 'AxiomPortTest', 'version_name': VERSION,
        'game_directory': str(GAME_DIR), 'assets_root': str(MC_ROOT / 'assets'),
        'assets_index_name': metadata['assetIndex']['id'],
        'auth_uuid': '00000000000000000000000000000001', 'auth_access_token': '0',
        'clientid': '0', 'auth_xuid': '0', 'user_type': 'legacy', 'version_type': 'release',
        'natives_directory': str(VERSION_DIR / (VERSION + '-natives')),
        'launcher_name': 'AxiomLocalPortSmoke', 'launcher_version': '1',
        'classpath': ';'.join(classpath), 'library_directory': str(MC_ROOT / 'libraries'),
        'classpath_separator': ';',
    }

    def expand(items):
        output = []
        for item in items:
            if isinstance(item, dict):
                if not allowed(item):
                    continue
                item = item['value']
            for value in item if isinstance(item, list) else [item]:
                value = re.sub(r'\$\{([^}]+)\}', lambda match: values[match[1]], value)
                output.append(value)
        return output

    args = ['-Xmx4G', '-Daxiom.mcp.testing=true', *expand(metadata['arguments']['jvm']),
            metadata['mainClass'], *expand(metadata['arguments']['game']), '--width', '1280', '--height', '800']
    args_file = GAME_DIR / 'smoke-java-args.txt'
    args_file.write_text('\n'.join('"' + arg.replace('\\', '\\\\').replace('"', '\\"') + '"' for arg in args), encoding='utf-8')
    report_dir = ROOT / 'reports'
    report_dir.mkdir(exist_ok=True)
    log = report_dir / 'production-smoke-console.log'
    java = shutil.which('java')
    if java is None:
        raise RuntimeError('Java 21 executable unavailable')
    print('Testing packaged JAR with:', [p.name for p in [jars[0], *combination]], flush=True)
    output = log.open('w', encoding='utf-8')
    process = subprocess.Popen([java, '@' + str(args_file)], cwd=GAME_DIR,
                               stdout=output, stderr=subprocess.STDOUT)
    output.close()
    print(f'Isolated production client PID: {process.pid}', flush=True)
    return process, jars[0], combination
