"""Pin only the current task candidate and its non-shipped test consumer."""
import hashlib
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[2]
manifest = json.loads((root / 'test/manifest.json').read_text())
for key, suffix in [('candidate', 'dist'), ('lifecycle-probe', 'testmod')]:
    jars = list((root / 'worldedit-fabric/build/libs').glob(f'*-{suffix}.jar'))
    if len(jars) != 1:
        raise RuntimeError(f'Expected one {suffix} jar, found {jars}')
    jar = jars[0]
    with zipfile.ZipFile(jar) as archive:
        metadata = json.loads(archive.read('fabric.mod.json'))
        if key == 'candidate':
            if any('/testharness/' in name for name in archive.namelist()):
                raise RuntimeError('Production jar contains the test consumer')
            if metadata['depends']['minecraft'] != '26.3':
                raise RuntimeError('Candidate is not version-gated to 26.3')
        nested = [{'path': name, 'sha256': hashlib.sha256(archive.read(name)).hexdigest()}
                  for name in archive.namelist() if name.endswith('.jar')]
    manifest['availableDependencies'][key] = {
        'path': str(jar), 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'nested': nested}
manifest['profiles']['base'] = ['candidate', 'fabric-api', 'lifecycle-probe']
for profile in ('base', 'polymer'):
    if profile in manifest['unavailableProfiles']:
        manifest['unavailableProfiles'].remove(profile)
(root / 'test/candidate-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({key: manifest['availableDependencies'][key]
                  for key in ['candidate', 'lifecycle-probe']}, indent=2))
