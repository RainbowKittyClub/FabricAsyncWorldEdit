"""Pin the S8 audit lane: the candidate manifest plus the built FAWE jars and the Ledger fork jar."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

root = Path(__file__).resolve().parents[2]
LEDGER = Path(sys.argv[1]) if len(sys.argv) > 1 else root.parents[3] / 'Ledger/build/libs/ledger-1.3.25-rkc.1+local.jar'


def pin(path):
    path = path.resolve(strict=True)
    data = path.read_bytes()
    with zipfile.ZipFile(path) as archive:
        nested = [{'path': name, 'sha256': hashlib.sha256(archive.read(name)).hexdigest()}
                  for name in archive.namelist() if name.endswith('.jar')]
    return {'path': str(path), 'sha256': hashlib.sha256(data).hexdigest(), 'nested': nested}


manifest = json.loads((root / 'test/candidate-manifest.json').read_text())
manifest['runLabel'] = 'fawe-fast-editing-20261029-s8'
deps = manifest['availableDependencies']
for key, suffix in [('candidate', 'dist'), ('lifecycle-probe', 'testmod')]:
    jars = list((root / 'worldedit-fabric/build/libs').glob(f'*-{suffix}.jar'))
    if len(jars) != 1:
        raise RuntimeError(f'Expected one {suffix} jar: {jars}')
    deps[key] = pin(jars[0])
with zipfile.ZipFile(LEDGER) as archive:
    version = json.loads(archive.read('fabric.mod.json'))['version']
if '-rkc.' not in version:
    raise RuntimeError(f'{LEDGER} is not the RKC Ledger fork ({version})')
deps['ledger-rkc'] = pin(LEDGER)
# OPAC decides denied edits; its config library is pinned by the S6 lane, rehashed here.
s6 = json.loads((root / 'test/s6/manifest.json').read_text())
deps['forge-config'] = pin(Path(s6['availableDependencies']['forge-config']['path']))
manifest['profiles']['audit'] = ['candidate', 'fabric-api', 'lifecycle-probe', 'ledger-rkc', 'kotlin', 'universal-ledger',
                                 'opac', 'forge-config']
manifest['unavailableProfiles'] = [name for name in manifest['unavailableProfiles'] if name != 'audit']
(root / 'test/s8/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({key: deps[key]['sha256'][:16] for key in ('candidate', 'lifecycle-probe', 'ledger-rkc')}))
