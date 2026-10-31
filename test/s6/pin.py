"""Pin this lane's artifacts without changing the shared candidate manifest."""
import hashlib
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[2]
manifest = json.loads((root / 'test/s6/manifest.json').read_text())
for key, suffix in [('candidate', 'dist'), ('s6-probe', 's6probe'), ('lifecycle-probe', 'testmod')]:
    jars = list((root / 'worldedit-fabric/build/libs').glob(f'*-{suffix}.jar'))
    if len(jars) != 1:
        raise RuntimeError(f'Expected one {suffix} jar: {jars}')
    jar = jars[0]
    with zipfile.ZipFile(jar) as archive:
        metadata = json.loads(archive.read('fabric.mod.json'))
        if key == 'candidate' and any('/s6/' in name for name in archive.namelist()):
            raise RuntimeError('Production jar contains probe')
        nested = [{'path': name, 'sha256': hashlib.sha256(archive.read(name)).hexdigest()}
                  for name in archive.namelist() if name.endswith('.jar')]
    manifest['availableDependencies'][key] = {'path': str(jar),
        'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'nested': nested}
(root / 'test/s6/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
