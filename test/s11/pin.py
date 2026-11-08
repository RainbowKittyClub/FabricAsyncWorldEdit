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
# The pinned asset index lives in a root of its own: Fabric Loom swaps the one in its cache for Mojang's
# live index whenever a mod's datagen client runs, which invalidates the pin. The index is the cached
# one's exact bytes (restored by content address, sha1 below) and the objects are hardlinks of Loom's.
assets_root = Path.home() / '.cache/fawe/assets-pinned'
index = (assets_root / 'indexes/26.3-34.json').resolve(strict=True)
if hashlib.sha1(index.read_bytes()).hexdigest() != manifest['minecraft']['cachedAssetIndexSha1']:
    raise RuntimeError('The private asset index is not the one the earlier stages pinned')
manifest['minecraft']['assetsRoot'] = str(assets_root)
manifest['minecraft']['assetIndex'] = {'path': str(index), 'sha256': hashlib.sha256(index.read_bytes()).hexdigest(),
                                       'bytes': index.stat().st_size}
manifest['minecraft']['assetIndexPolicy'] = ('Pinned index restored by content address into a private root with hardlinks of '
                                             'the Gradle cache objects; Loom refreshing its own cached index does not touch it.')
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
# The polymer suite: the polymer set beside S7's fixture overlays, from the Polymer worktree's pins.
s7 = json.loads(Path('/home/kgadberry/projects/minecraft-dev/rkc/forks/polymer/.claude/worktrees/'
                     'fawe-fast-editing-20261029-s7/test/bulk/s7-manifest.json').read_text())
fixture = pin(Path(s7['artifacts']['fixture']['path']))
if fixture['sha256'] != s7['artifacts']['fixture']['sha256']:
    raise RuntimeError('S7 fixture jar differs from its pin')
deps['polymer-fixture'] = fixture
manifest['profiles']['polymer-content'] = [*manifest['profiles']['polymer'], 'polymer-fixture']
# The accepted Polymer is the published fork build: S7's bulk-edit API plus the vanilla-id fix.
deps['polymer-accepted'] = pin(Path('/mnt/WORKSPACE/scratchpad/fawe-s11-evidence/artifacts/polymer-0.18.6-rkc.3/'
                                    'polymer-bundled-0.18.6-rkc.3+26.3.jar'))
# Diagonal walls with its fawe:lifecycle declaration (feat/fawe-lifecycle), built from the mod's 26.3 node.
deps['diagonal-walls'] = pin(Path('/mnt/WORKSPACE/scratchpad/fawe-s11-evidence/artifacts/diagonal-walls-fawe-lifecycle/'
                                  'polymerdiagonalwalls-0.1.1+26.3.jar'))
# Player portals with its fawe:lifecycle declaration (feat/fawe-lifecycle): the staged 0.2.0 jar plus
# that one field, built from the mod's 26.3 node.
deps['player-portals'] = pin(Path('/mnt/WORKSPACE/scratchpad/fawe-s11-evidence/artifacts/player-portals-fawe-lifecycle/'
                                  'playerportals-0.2.0+26.3.jar'))
# Sensible storage with its fawe:lifecycle declaration (feat/fawe-lifecycle): the staged 0.6.1 jar plus
# the container and bookshelf declarations, built after the 26.3 node's datagen.
deps['sensible-storage'] = pin(Path('/mnt/WORKSPACE/scratchpad/fawe-s11-evidence/artifacts/sensible-storage-fawe-containers/'
                                    'sensiblestorage-0.6.1+26.3.jar'))
deps['polymer-patch-bundle'] = pin(Path('/mnt/WORKSPACE/scratchpad/fawe-s11-evidence/artifacts/ppb-fawe-lifecycle/'
                                      'polymer-patch-bundle-1.1.7+26.3.jar'))
# Increment 6: the deployment stack. Every suite runs against OPAC, Ledger with UniversalLedger, the
# accepted Polymer, Lithium and C2ME, and the content mods staged for the server; 'full' adds
# ScalableLux, 'vanilla-light' leaves lighting to vanilla. Only the suite that needs one gets its
# fixture: s6-probe for claims, and the polymer fixture, whose overlays sit on vanilla chests and logs.
stack = ['candidate', 'fabric-api-polymer', 'lifecycle-probe', 'polymer-accepted', *audit, 'test-permissions',
         'lithium', 'c2me', 'diagonal-walls', 'sensible-storage', 'player-portals', 'terrestria', 'polymer-patch-bundle']
# Throughput with only Ledger and OPAC added to the base set: tells their cost from the rest of the stack.
manifest['profiles']['throughput-audit'] = ['candidate', 'fabric-api', 'lifecycle-probe', *audit]
manifest['profiles']['throughput-opac'] = ['candidate', 'fabric-api', 'lifecycle-probe', 'opac', 'forge-config']
fixtures = {'claims': ['s6-probe'], 'polymer': ['polymer-fixture']}
for suite in ('functional', 'operations', 'content', 'throughput', 'claims', 'ledger', 'polymer', 'adapters'):
    for base, extra in (('full', ['scalablelux']), ('vanilla-light', [])):
        manifest['profiles'][f'{suite}-{base}'] = [*stack, *extra, *fixtures.get(suite, [])]
# backend-db: the full stack with Ledger's Postgres extension (the jar the previous network ran,
# 1.2.2, taken from the server-data history), against a disposable Postgres 18 container.
deps['ledger-databases'] = pin(Path('/mnt/WORKSPACE/scratchpad/fawe-s11-evidence/artifacts/ledger-databases-1.2.2/'
                                    'ledger-databases-1.2.2.jar'))
for suite in ('functional', 'operations', 'content', 'throughput', 'claims', 'ledger', 'polymer', 'adapters'):
    manifest['profiles'][f'{suite}-backend-db'] = [*manifest['profiles'][f'{suite}-full'], 'ledger-databases']
# These profiles now run; only Paper, the throughput reference, is still to provision.
manifest['unavailableProfiles'] = [name for name in manifest['unavailableProfiles']
                                   if name not in ('claims', 'full', 'vanilla-light', 'backend-db')]
manifest['unresolved'] = [('Paper 26.3 is not provisioned' if line.startswith('Paper 26.3') else line)
                          for line in manifest['unresolved']]
# The S8 audit and S10 replay lanes run from this manifest too (`s8/run.js backend-db`).
for lane in ('audit', 'replay'):
    manifest['profiles'][f'{lane}-backend-db'] = [*manifest['profiles'][lane], 'ledger-databases']
(root / 'test/s11/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({key: deps[key]['sha256'][:16] for key in ('candidate', 'lifecycle-probe')}))
