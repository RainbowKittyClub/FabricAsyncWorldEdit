"""Freeze S14 comparators and existing targets before any measurements."""
import hashlib
import json
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
source = repo / 'test/s11/manifest.json'
manifest = json.loads(source.read_text())
paper_pins = Path('/mnt/WORKSPACE/scratchpad/fawe-s14-evidence/artifacts/paper-26.3-244/pins.json')
paper = json.loads(paper_pins.read_text())
assert paper['minecraft'] == manifest['minecraft']['version'] == '26.3'
for artifact in paper['artifacts']:
    assert hashlib.sha256(Path(artifact['path']).read_bytes()).hexdigest() == artifact['sha256']
manifest['runLabel'] = 'fawe-fast-editing-20261029-s14'
manifest['s11ManifestSha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
manifest['comparators'] = {'paper': paper['artifacts'][0], 'paper-fawe': paper['artifacts'][1]}
manifest['targets'] = {'warmSimpleFillSpeedupOverWna': 5, 'matchedPaperThroughputRatio': 0.70,
                       'oneToTwoWorkerPreparationSpeedup': 1.3, 'heartbeatP99MsExclusive': 100}
manifest['measurementPolicy'] = {'minimumSamplesAfterWarmup': 5, 'workers': [1, 2, 4],
                                 'alternateBaselineOrder': True, 'chunkStates': ['warm', 'cold'],
                                 'seeds': [260329, 260330], 'sequentialRunsOnly': True,
                                 'fullStackReportedSeparately': True}
(repo / 'test/s14/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({'paperBuild': paper['paperBuild'], 'upstreamFawe': paper['faweVersion'],
                  'targets': manifest['targets']}))
