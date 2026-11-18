"""Provision byte-identical S14 runtime inputs outside live data and disposable user caches."""
from concurrent.futures import ThreadPoolExecutor
import copy
import hashlib
import io
import json
from pathlib import Path
import sys
from urllib.parse import urlparse
from urllib.request import urlopen
from zipfile import ZipFile

source = (Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name('manifest.json')).resolve(strict=True)
original = source.read_bytes()
manifest = json.loads(original)
root = Path('/mnt/WORKSPACE/scratchpad/fawe-s14-evidence/artifacts') / (
    'runtime-' + hashlib.sha256(original).hexdigest()[:16])
root.mkdir(parents=True, exist_ok=True)
modrinth = Path('/home/kgadberry/.local/share/ModrinthApp/meta')
relocations = []


def digest(data, algorithm='sha256'):
    return hashlib.new(algorithm, data).hexdigest()


def retain(path, data):
    assert path.resolve().is_relative_to(root.resolve()), path
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        assert path.read_bytes() == data, f'Refusing to overwrite changed input: {path}'
    else:
        with path.open('xb') as output:
            output.write(data)


def download(url):
    assert urlparse(url).scheme == 'https'
    assert urlparse(url).hostname in {
        'piston-data.mojang.com', 'piston-meta.mojang.com', 'libraries.minecraft.net',
        'resources.download.minecraft.net', 'maven.fabricmc.net'}
    with urlopen(url, timeout=60) as response:
        assert urlparse(response.url).hostname == urlparse(url).hostname
        return response.read()


def restore(item, candidates=(), url=None, data=None):
    target = root / 'jars' / item['sha256'] / Path(item['path']).name
    for candidate in (target, *candidates):
        if candidate.is_file():
            found = candidate.read_bytes()
            if digest(found) == item['sha256']:
                data = found
                break
    if data is None:
        assert url, f'No verified local source or pinned URL: {item["path"]}'
        data = download(url)
    assert digest(data) == item['sha256'], f'Pinned SHA-256 mismatch: {item["path"]}'
    retain(target, data)
    relocations.append({'from': item['path'], 'to': str(target), 'sha256': item['sha256']})
    item['path'] = str(target)
    return data


def read_pinned(item):
    path = Path(item['path'])
    assert not str(path).startswith('/mnt/WORKSPACE/minecraft-server-data/')
    data = path.read_bytes()
    assert digest(data) == item['sha256'], path
    return json.loads(data)


mc = manifest['minecraft']
metadata = read_pinned(mc['metadata'])
loader = read_pinned(manifest['loader']['profile'])
for side in ('client', 'serverBundle'):
    kind = 'server' if side == 'serverBundle' else side
    restore(mc[side], [modrinth / 'versions/26.3/26.3.jar'] if side == 'client' else [],
            metadata['downloads'][kind]['url'])
with ZipFile(mc['serverBundle']['path']) as bundle:
    restore(mc['server'], data=bundle.read('META-INF/versions/26.3/server-26.3.jar'))

urls = {}
for library in metadata['libraries']:
    for artifact in library.get('downloads', {}).values():
        if isinstance(artifact, dict) and 'url' in artifact:
            urls[Path(artifact['path']).name] = artifact['url']
for library in loader['libraries']:
    group, name, version = library['name'].split(':')
    filename = f'{name}-{version}.jar'
    urls[filename] = library['url'] + f'{group.replace(".", "/")}/{name}/{version}/{filename}'
for item in mc['libraries'] + manifest['loader']['libraries']:
    basename = Path(item['path']).name
    candidates = list((modrinth / 'libraries').glob(f'**/{basename}'))
    restore(item, candidates, urls.get(basename))

api = manifest['availableDependencies']['fabric-api']
prior = Path('/mnt/WORKSPACE/scratchpad/fawe/s14-fabric-fill/fawe-fast-editing-20261029-s14/'
             's1-s2/c7d33fd8-7bf8-4775-b0ed-bdb7698326de/server/mods') / Path(api['path']).name
restore(api, [prior])
for key in manifest['profiles']['throughput-base']:
    read_path = Path(manifest['availableDependencies'][key]['path'])
    assert not str(read_path).startswith('/mnt/WORKSPACE/minecraft-server-data/')
    assert digest(read_path.read_bytes()) == manifest['availableDependencies'][key]['sha256']

index_base, _, index_name = metadata['assetIndex']['url'].rsplit('/', 2)
index_url = f'{index_base}/{mc["cachedAssetIndexSha1"]}/{index_name}'
index = restore(mc['assetIndex'], [modrinth / 'assets/indexes/34.json'], index_url)
assert digest(index, 'sha1') == mc['cachedAssetIndexSha1']
assets = root / 'assets'
retain(assets / 'indexes' / f'{mc["assetIndexName"]}.json', index)
objects = json.loads(index)['objects']
assert len(objects) == mc['assetCount']


def asset(item):
    name = item['hash']
    relative = Path('objects') / name[:2] / name
    target = assets / relative
    local = modrinth / 'assets' / relative
    if target.is_file():
        data = target.read_bytes()
    elif local.is_file():
        data = local.read_bytes()
    else:
        data = download(f'https://resources.download.minecraft.net/{name[:2]}/{name}')
    assert digest(data, 'sha1') == name and len(data) == item['size'], name
    retain(target, data)


unique = {item['hash']: item for item in objects.values()}
with ThreadPoolExecutor(max_workers=4) as pool:
    list(pool.map(asset, unique.values()))
old_assets = mc['assetsRoot']
mc['assetsRoot'] = str(assets)

# Only storage locations may differ from the frozen manifest. All hashes, versions, targets,
# ordering, sample counts, policies, and unused profiles remain unchanged.
restored = copy.deepcopy(manifest)
for entry in relocations:
    def undo(value):
        if isinstance(value, dict):
            if value.get('path') == entry['to']:
                value['path'] = entry['from']
            for child in value.values():
                undo(child)
        elif isinstance(value, list):
            for child in value:
                undo(child)
    undo(restored)
restored['minecraft']['assetsRoot'] = old_assets
assert restored == json.loads(original), 'Non-location manifest drift'
retain(root / 'source-manifest.json', original)
retain(root / 'relocations.json', (json.dumps(relocations, indent=2) + '\n').encode())
retain(root / 'manifest.json', (json.dumps(manifest, indent=2) + '\n').encode())
print(json.dumps({'manifest': str(root / 'manifest.json'), 'relocatedFiles': len(relocations),
                  'uniqueAssets': len(unique), 'sourceSha256': digest(original)}))
