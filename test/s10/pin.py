"""Pin the S10 lanes: S8's audit manifest with the built FAWE jars and, if given, a Ledger fork jar."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parents[2]


def pin(path):
    path = path.resolve(strict=True)
    with zipfile.ZipFile(path) as archive:
        nested = [{'path': name, 'sha256': hashlib.sha256(archive.read(name)).hexdigest()}
                  for name in archive.namelist() if name.endswith('.jar')]
    return {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'nested': nested}


manifest = json.loads((root / 'test/s8/manifest.json').read_text())
manifest['runLabel'] = 'fawe-fast-editing-20261029-s10'
deps = manifest['availableDependencies']
for key, suffix in [('candidate', 'dist'), ('lifecycle-probe', 'testmod')]:
    jars = list((root / 'worldedit-fabric/build/libs').glob(f'*-{suffix}.jar'))
    if len(jars) != 1:
        raise RuntimeError(f'Expected one {suffix} jar: {jars}')
    deps[key] = pin(jars[0])
if len(sys.argv) > 1:
    ledger = Path(sys.argv[1])
    with zipfile.ZipFile(ledger) as archive:
        version = json.loads(archive.read('fabric.mod.json'))['version']
    if '-rkc.' not in version:
        raise RuntimeError(f'{ledger} is not the RKC Ledger fork ({version})')
    deps['ledger-rkc'] = pin(ledger)
manifest['profiles']['history-base'] = ['candidate', 'fabric-api', 'lifecycle-probe']
manifest['profiles']['history-audit'] = manifest['profiles']['audit']
(root / 'test/s10/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({key: deps[key]['sha256'][:16] for key in ('candidate', 'lifecycle-probe', 'ledger-rkc')}))
