#!/usr/bin/env python3
"""Resolve explicitly selected inputs once; subsequent runs verify this lock, never refresh it."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--workspace', type=Path, required=True)
parser.add_argument('--gradle-cache', type=Path, required=True)
parser.add_argument('--staged-jars', type=Path, required=True)
parser.add_argument('--loader-profile', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def nested(data):
    result = []
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        for name in sorted(jar.namelist()):
            if name.endswith('.jar'):
                content = jar.read(name)
                result.append({'entry': name, 'sha256': digest(content), 'nested': nested(content)})
    return result


def artifact(path, sha1=None):
    path = path.resolve(strict=True)
    data = path.read_bytes()
    if sha1 and hashlib.sha1(data).hexdigest() != sha1:
        raise ValueError(f'Upstream SHA1 mismatch: {path}')
    result = {'path': str(path), 'sha256': digest(data), 'bytes': len(data)}
    if path.suffix == '.jar':
        result['nested'] = nested(data)
    return result


def library(name, sha1=None):
    group, module, version, *classifier = name.split(':')
    file = f'{module}-{version}' + (f'-{classifier[0]}' if classifier else '') + '.jar'
    matches = list((args.gradle_cache / 'modules-2/files-2.1' / group / module / version).glob('*/' + file))
    if len(matches) != 1:
        raise FileNotFoundError(f'Expected exactly one cached {name}, got {matches}')
    return artifact(matches[0], sha1)


mc = args.gradle_cache / 'fabric-loom/26.3'
info = json.loads((mc / 'mojang_minecraft_info.json').read_text())
client_libraries = []
for item in info['libraries']:
    allowed = not item.get('rules')
    for rule in item.get('rules', []):
        os = rule.get('os', {})
        if os.get('name', 'linux') == 'linux' and os.get('arch', 'amd64') in ('amd64', 'x86_64'):
            allowed = rule['action'] == 'allow'
    if allowed:
        client_libraries.append(library(item['name'], item['downloads']['artifact']['sha1']))
loader = json.loads(args.loader_profile.read_text())
loader_libraries = [library(item['name'], item.get('sha1')) for item in loader['libraries']]
asset_index = args.gradle_cache / 'fabric-loom/assets/indexes/26.3-34.json'
assets = json.loads(asset_index.read_text())
missing = []
for obj in assets['objects'].values():
    file = args.gradle_cache / 'fabric-loom/assets/objects' / obj['hash'][:2] / obj['hash']
    if (not file.is_file() or file.stat().st_size != obj['size']
            or hashlib.sha1(file.read_bytes()).hexdigest() != obj['hash']):
        missing.append(str(file))
if missing:
    raise FileNotFoundError(f'{len(missing)} absent/incomplete cached assets: {missing[:3]}')

jars = {
    'worldedit-reference': 'worldedit-mod-7.4.6.jar',
    'fabric-api': 'fabric-api-0.165.0+26.3.jar',
    'opac': 'open-parties-and-claims-fabric-26.3-0.33.0.jar',
    'ledger': 'ledger-1.3.25.jar',
    'universal-ledger': 'universalledger-1.2.1.jar',
    'kotlin': 'fabric-language-kotlin-1.14.2+kotlin.2.4.21.jar',
    'polymer-staged': 'polymer-bundled-0.18.5-rkc.4+26.3.jar',
    'lithium': 'lithium-fabric-0.26.2+mc26.3.jar',
    'c2me': 'c2me-fabric-mc26.3-0.4.2-alpha.0.106.jar',
    'scalablelux': 'ScalableLux-fabric-mc26.3-0.3.0-alpha.0.6-all.jar',
    'diagonal-walls': 'polymerdiagonalwalls-0.1.1+26.3.jar',
    'sensible-storage': 'sensiblestorage-0.6.1+26.3.jar',
    'player-portals': 'playerportals-0.2.0+26.3.jar',
    'terrestria': 'terrestria-8.1.0-rkc.2+26.3.jar',
    'polymer-patch-bundle': 'polymer-patch-bundle-1.1.7+26.3.jar',
}
manifest = {
    'schema': 1,
    'runLabel': 'fawe-fast-editing-20261029-autonomous',
    'seed': 260329,
    'sources': {
        'fabric': 'e302d1742398f6f5f4afdee2d6e40418d1325ef0',
        'faweCore': 'b70bf05ff10144ee6b2ca32c8b46889b1f9bac45',
        'enginehubTag': 'ad2f4e7a927875b7343f18b1e233f7cb6903ae80',
        'enginehub': '00664b08c5d281ef47924f999320ec17a43b19e1',
        'polymer': 'c0ed648957e5787f23492a6aaf9d6ad545ec200f',
        'opac': '524944a8518fd543cd586c850eab945bab57170f',
        'ledger': 'dd947aaec3df4f308aae05707855ea796d9dc581',
    },
    'plan': artifact(args.workspace / 'rkc/forks/docs/plan/fawe-fabric-fast-editing.md'),
    'minecraft': {
        'version': '26.3',
        'metadata': artifact(mc / 'mojang_minecraft_info.json'),
        'client': artifact(mc / 'minecraft-client.jar', info['downloads']['client']['sha1']),
        'serverBundle': artifact(mc / 'minecraft-server.jar', info['downloads']['server']['sha1']),
        'server': artifact(mc / 'minecraft-extracted_server.jar'),
        'assetsRoot': str(args.gradle_cache / 'fabric-loom/assets'),
        'assetIndex': artifact(asset_index),
        'metadataAssetIndexSha1': info['assetIndex']['sha1'],
        'cachedAssetIndexSha1': hashlib.sha1(asset_index.read_bytes()).hexdigest(),
        'assetIndexPolicy': 'Pin the existing authorized cached index; live translation index differs.',
        'assetIndexName': '26.3-34',
        'assetCount': len(assets['objects']),
        'libraries': client_libraries,
    },
    'loader': {'version': '0.19.5', 'profile': artifact(args.loader_profile), 'libraries': loader_libraries},
    'eula': artifact(args.workspace / 'rkc/mods/chopt/versions/26.3/run/eula.txt'),
    'helpers': {name: artifact(args.workspace / 'scripts/loadtest/lib' / f'{name}.js')
                for name in ('rcon', 'devserver', 'flyer')},
    'availableDependencies': {key: artifact(args.staged_jars / name) for key, name in jars.items()},
    'profiles': {'reference': ['worldedit-reference', 'fabric-api']},
    'unavailableProfiles': ['base', 'claims', 'audit', 'polymer', 'full', 'vanilla-light', 'backend-db', 'paper'],
    'unresolved': [
        'Bridge-specific permissions/audit consumers and profile suites belong to S6-S14',
        'Staged Polymer 0.18.5-rkc.4 does not equal the 0.18.6 research reference',
        'The packaged candidate and non-shipped probe are pinned by pin-candidate.py',
        'Paper 26.3 and isolated database-extension profile not provisioned',
    ],
    'targets': {'wnaSpeedup': 5, 'paperRatio': 0.70, 'twoWorkerPreparationSpeedup': 1.3,
                'p99HeartbeatMs': 100, 'samples': 5, 'soakMinutes': 30},
}
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open('x') as out:
    json.dump(manifest, out, indent=2)
    out.write('\n')
print(args.output)
