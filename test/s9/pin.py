"""Pin the S9 lanes: the built FAWE jars, S1's optimization jars and a stub unverified light engine."""
import hashlib
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[2]


def pin(path):
    path = path.resolve(strict=True)
    with zipfile.ZipFile(path) as archive:
        nested = [{'path': name, 'sha256': hashlib.sha256(archive.read(name)).hexdigest()}
                  for name in archive.namelist() if name.endswith('.jar')]
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'nested': nested}


# A metadata-only mod whose id is a light engine with no verified barrier; fixed timestamps keep its hash.
stub = root / 'worldedit-fabric/build/s9/phosphor-stub.jar'
stub.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(stub, 'w') as archive:
    info = zipfile.ZipInfo('fabric.mod.json', date_time=(2026, 11, 3, 0, 0, 0))
    archive.writestr(info, json.dumps({'schemaVersion': 1, 'id': 'phosphor', 'version': '0.0.0-s9-stub',
                                       'name': 'S9 unverified light engine stub', 'environment': '*'}))

manifest = json.loads((root / 'test/candidate-manifest.json').read_text())
manifest['runLabel'] = 'fawe-fast-editing-20261029-s9'
deps = manifest['availableDependencies']
for key, suffix in [('candidate', 'dist'), ('lifecycle-probe', 'testmod')]:
    jars = list((root / 'worldedit-fabric/build/libs').glob(f'*-{suffix}.jar'))
    if len(jars) != 1:
        raise RuntimeError(f'Expected one {suffix} jar: {jars}')
    deps[key] = pin(jars[0])
deps['phosphor-stub'] = pin(stub)
base = ['candidate', 'fabric-api', 'lifecycle-probe']
manifest['profiles'].update({
    'light-vanilla': base,
    'vanilla-light-opt': base + ['lithium', 'c2me'],
    'full-opt': base + ['lithium', 'c2me', 'scalablelux'],
    'unverified': base + ['phosphor-stub'],
})
(root / 'test/s9/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({key: deps[key]['sha256'][:16] for key in ('candidate', 'lifecycle-probe', 'phosphor-stub')}))
