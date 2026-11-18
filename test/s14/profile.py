"""Summarize local JFR CPU samples, optionally attributing them to recorded measurement windows."""
from collections import Counter, defaultdict
from datetime import datetime
import json
from pathlib import Path
import subprocess
import sys

recording = Path(sys.argv[1]).resolve(strict=True)
assert recording.is_relative_to(Path('/mnt/WORKSPACE/scratchpad/fawe'))
windows = []
if len(sys.argv) > 2:
    report = json.loads(Path(sys.argv[2]).read_text())
    for sample in report['measurements']:
        if 'startedEpochMs' in sample and 'finishedEpochMs' in sample:
            label = f"{'warmup' if sample['warmup'] else 'sample'}:{sample['round']}:{sample['route']}"
            windows.append((sample['startedEpochMs'], sample['finishedEpochMs'], label))
printed = subprocess.run(['jfr', 'print', '--json', '--events', 'jdk.ExecutionSample', str(recording)],
                         capture_output=True)
if printed.returncode:
    sys.exit(printed.stderr.decode(errors='replace').strip() or f'jfr exited {printed.returncode}')
events = json.loads(printed.stdout)['recording']['events']
counts = Counter()
leaves = defaultdict(Counter)
buckets = defaultdict(Counter)
categories = {
    'snapshot': ('FabricGetBlocks', 'FabricStateMapping'),
    'preparation': ('FabricPreparation', 'FabricBufferEdits', 'CharSetBlocks'),
    'publication': ('FabricWorldAccess', 'FabricCommitCoordinator', 'FabricPublication'),
    'history': ('HistoryProvenance', 'FaweStreamChangeSet', 'DiskStorageHistory', 'HistoryExtent'),
    'lighting': ('LightEngine', 'LightingProvider', 'LightQueue'),
    'native-world-write': ('FabricWorldNativeAccess', 'LevelChunk.setBlockState'),
}
for event in events:
    values = event['values']
    thread = (values.get('sampledThread') or {}).get('javaName', '<unknown>')
    frames = (values.get('stackTrace') or {}).get('frames', [])
    methods = []
    for frame in frames:
        method = frame['method']
        methods.append(method['type']['name'].replace('/', '.') + '.' + method['name'])
    label = 'outside-measurement'
    if windows:
        time = datetime.fromisoformat(values['startTime'].replace('Z', '+00:00')).timestamp() * 1000
        label = next((name for start, end, name in windows if start <= time <= end), label)
    key = (label, thread)
    counts[key] += 1
    if methods:
        leaves[key][methods[0]] += 1
    # Inclusive stack categories overlap: these are samples, not additive wall times.
    for category, anchors in categories.items():
        if any(anchor in method for anchor in anchors for method in methods):
            buckets[key][category] += 1
assert sum(counts.values()) > 0, 'No execution samples: profiling evidence unavailable'
summary = {'recording': str(recording), 'executionSamples': sum(counts.values()),
           'note': 'Inclusive CPU sample counts, not wall time; categories may overlap.',
           'windows': [{'window': key[0], 'thread': key[1], 'samples': count,
                        'inclusiveBuckets': dict(buckets[key]), 'topLeaves': leaves[key].most_common(15)}
                       for key, count in counts.most_common()]}
target = recording.with_suffix('.cpu-summary.json')
data = json.dumps(summary, indent=2) + '\n'
if target.exists():
    assert target.read_text() == data, f'Refusing to replace {target}'
else:
    target.write_text(data)
print(json.dumps({'summary': str(target), 'executionSamples': summary['executionSamples'],
                  'windows': len(summary['windows'])}))
