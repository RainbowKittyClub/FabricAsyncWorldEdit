"""Pin the S11 lanes: S10's manifest with the built FAWE jars."""
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


manifest = json.loads((root / 'test/s10/manifest.json').read_text())
manifest['runLabel'] = 'fawe-fast-editing-20261029-s11'
deps = manifest['availableDependencies']
for key, suffix in [('candidate', 'dist'), ('lifecycle-probe', 'testmod')]:
    jars = list((root / 'worldedit-fabric/build/libs').glob(f'*-{suffix}.jar'))
    if len(jars) != 1:
        raise RuntimeError(f'Expected one {suffix} jar: {jars}')
    deps[key] = pin(jars[0])
for suite in ('functional', 'operations', 'content', 'throughput'):
    manifest['profiles'][f'{suite}-base'] = ['candidate', 'fabric-api', 'lifecycle-probe']
# The claims suite: S6's OPAC set, pinned by test/s6/pin.py, beside this lane's probe.
s6 = json.loads((root / 'test/s6/manifest.json').read_text())
claims = ['opac', 'forge-config', 's6-probe', 'test-permissions']
for key in claims:
    deps[key] = s6['availableDependencies'][key]
manifest['profiles']['claims-opac'] = ['candidate', 'fabric-api', 'lifecycle-probe', *claims]
# The ledger suite: S8's audit set, pinned by test/s8/pin.py.
s8 = json.loads((root / 'test/s8/manifest.json').read_text())
audit = ['ledger-rkc', 'kotlin', 'universal-ledger', 'opac', 'forge-config']
for key in audit:
    deps[key] = s8['availableDependencies'][key]
manifest['profiles']['ledger-audit'] = ['candidate', 'fabric-api', 'lifecycle-probe', *audit]
(root / 'test/s11/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({key: deps[key]['sha256'][:16] for key in ('candidate', 'lifecycle-probe')}))
