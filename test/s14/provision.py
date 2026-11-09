"""Pin the public Paper comparator without touching any server or client installation."""
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path('/mnt/WORKSPACE/scratchpad/fawe-s14-evidence/artifacts/paper-26.3-244')
AGENT = 'minecraft-dev/1.0 (local FAWE S14 validation)'


def fetch(url):
    with urlopen(Request(url, headers={'User-Agent': AGENT}), timeout=120) as response:
        return response.read()


def retain(path, data):
    if path.exists():
        if path.read_bytes() != data:
            raise RuntimeError(f'Refusing to replace {path}')
    else:
        path.write_bytes(data)


ROOT.mkdir(parents=True, exist_ok=True)
paper_api = 'https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/244'
fawe_api = 'https://api.modrinth.com/v2/version/JOyEqKdC'
paper = json.loads(fetch(paper_api))
assert paper['id'] == 244 and paper['channel'] == 'STABLE'
paper_file = paper['downloads']['server:default']
assert paper_file['checksums']['sha256'] == '9601f9a26a2a52a0f2c807cf1abf691f96ee4fb287878313b601cebc2c51eabf'
fawe = json.loads(fetch(fawe_api))
assert fawe['project_id'] == 'z4HZZnLr' and fawe['version_number'] == '2.16.2'
assert '26.3' in fawe['game_versions'] and fawe['loaders'] == ['paper']
fawe_file = next(file for file in fawe['files'] if file['primary'])
assert fawe_file['hashes']['sha512'] == (
    '319f6c961b01206dc7d9cf03e8105d87d4bb7698fe82c4405afd346689b948541c05355dca62bbd9cc45ff3de6ae9b481082c9fc5f4e02e2b1fbc33a9e56e404')
artifacts = []
for name, url, algorithm, expected, size in (
    (paper_file['name'], paper_file['url'], 'sha256', paper_file['checksums']['sha256'], paper_file['size']),
    (fawe_file['filename'], fawe_file['url'], 'sha512', fawe_file['hashes']['sha512'], fawe_file['size']),
):
    target = ROOT / name
    data = target.read_bytes() if target.exists() else fetch(url)
    assert len(data) == size and hashlib.new(algorithm, data).hexdigest() == expected, name
    retain(target, data)
    artifacts.append({'path': str(target), 'url': url, 'size': size, 'sha256': hashlib.sha256(data).hexdigest(),
                      'upstreamChecksum': {algorithm: expected}})
retain(ROOT / 'paper-build.json', (json.dumps(paper, indent=2) + '\n').encode())
retain(ROOT / 'fawe-release.json', (json.dumps(fawe, indent=2) + '\n').encode())
record = {'minecraft': '26.3', 'paperBuild': 244, 'faweVersion': '2.16.2', 'metadataUrls': [paper_api, fawe_api],
          'status': 'downloaded-and-hash-verified; boot and performance not yet proved', 'artifacts': artifacts}
retain(ROOT / 'pins.json', (json.dumps(record, indent=2) + '\n').encode())
print(json.dumps(record, indent=2))
