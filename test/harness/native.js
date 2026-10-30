'use strict'

const fs = require('node:fs')
const { DisplayLease } = require('./display')
const path = require('node:path')
const crypto = require('node:crypto')
const { GateError, verify, writeJson, tool, hash } = require('./rig')

function classpath (manifest, side) {
  return [verify(manifest.minecraft[side]), ...manifest.minecraft.libraries.map(verify),
    ...manifest.loader.libraries.map(verify)].join(path.delimiter)
}

function buildDriver (repo, manifest) {
  const output = path.join(repo, 'build/fawe-native-probe')
  fs.mkdirSync(output, { recursive: true })
  tool('javac', ['--release', '25', '-proc:none', '-cp', classpath(manifest, 'client'), '-d', output,
    path.join(repo, 'worldedit-fabric/src/testmod/java/com/fastasyncworldedit/testharness/NativeDriverMixin.java')])
  const jar = path.join(repo, 'build/fawe-native-probe.jar')
  tool('jar', ['--create', '--file', jar, '-C', output, '.', '-C', path.join(repo, 'test/native'), '.'])
  return jar
}

async function display (rig) {
  if (!process.env.TMPDIR) throw new GateError('TMPDIR required for private X11 authentication', 2)
  const temp = fs.mkdtempSync(path.join(process.env.TMPDIR, 'fawe-native-'))
  fs.chmodSync(temp, 0o700)
  rig.tempDirs.push(temp)
  const auth = path.join(temp, 'Xauthority')
  fs.writeFileSync(auth, '', { mode: 0o600 })
  const cookie = crypto.randomBytes(16).toString('hex')
  const lease = new DisplayLease()
  rig.displays.push(lease)
  const number = lease.number
  writeJson(path.join(rig.root, 'x11-before.json'), { number, entries: lease.before })
  tool('xauth', ['-f', auth, 'add', `:${number}`, '.', cookie])
  lease.beforeSpawn()
  const entry = rig.spawn('xvfb', 'Xvfb', [`:${number}`, '-screen', '0', '854x480x24',
    '-nolisten', 'tcp', '-auth', auth, '-noreset'])
  lease.attach(entry)
  await rig.wait('owned private display', () => lease.ready(), 15000, [entry])
  const env = { ...process.env, DISPLAY: `:${number}`, XAUTHORITY: auth, XDG_RUNTIME_DIR: temp,
    SDL_VIDEO_DRIVER: 'x11', LIBGL_ALWAYS_SOFTWARE: '1', ALSOFT_DRIVERS: 'null' }
  delete env.WAYLAND_DISPLAY
  delete env.DBUS_SESSION_BUS_ADDRESS
  delete env.JAVA_TOOL_OPTIONS
  delete env._JAVA_OPTIONS
  env.VK_LOADER_LAYERS_DISABLE = '~implicit~'
  return { entry, env, temp }
}

function offlineUuid (name) {
  const bytes = crypto.createHash('md5').update(`OfflinePlayer:${name}`).digest()
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

async function startClient (rig, repo, options = {}) {
  const driver = buildDriver(repo, rig.manifest)
  writeJson(path.join(rig.root, 'native-driver.json'), { jarSha256: hash(driver),
    sourceSha256: hash(path.join(repo, 'worldedit-fabric/src/testmod/java/com/fastasyncworldedit/testharness/NativeDriverMixin.java')) })
  const screen = await display(rig)
  const dir = path.join(rig.root, 'client')
  fs.mkdirSync(path.join(dir, 'mods'), { recursive: true })
  fs.mkdirSync(path.join(dir, 'frames'))
  fs.writeFileSync(path.join(dir, 'options.txt'), [
    'version:5023', 'onboardAccessibility:false', 'startedCleanly:true', 'pauseOnLostFocus:false',
    'enableVsync:false', 'maxFps:30', 'renderDistance:3', 'simulationDistance:5', 'fullscreen:false',
    'narrator:0', 'lang:en_us', ''
  ].join('\n'))
  fs.copyFileSync(driver, path.join(dir, 'mods/fawe-native-probe.jar'))
  const request = path.join(dir, 'request.json')
  const events = path.join(dir, 'events.jsonl')
  const args = ['-Xmx3G', `-Dclaude.scratch=fawe-${rig.manifest.runLabel}-${rig.token}`,
    '--enable-native-access=ALL-UNNAMED', '--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED',
    '-XX:StackShadowPages=32', `-Dfawe.test.request=${request}`, `-Dfawe.test.events=${events}`,
    `-Dfawe.test.frames=${path.join(dir, 'frames')}`,
    '-cp', classpath(rig.manifest, 'client'), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
    '--username', options.name || 'fawecontrol', '--uuid', offlineUuid(options.name || 'fawecontrol'),
    '--accessToken', '0', '--offlineDeveloperMode', '--graphicsBackend', 'OPENGL',
    '--version', '26.3', '--versionType', 'release', '--gameDir', dir,
    '--assetsDir', rig.manifest.minecraft.assetsRoot, '--assetIndex', rig.manifest.minecraft.assetIndexName,
    '--width', '854', '--height', '480']
  if (options.port) args.push('--quickPlayMultiplayer', `127.0.0.1:${options.port}`)
  const entry = rig.spawn('native-client', 'java', args, { cwd: dir, env: screen.env })
  const readEvents = () => {
    if (!fs.existsSync(events)) return []
    const text = fs.readFileSync(events, 'utf8')
    return text.slice(0, text.lastIndexOf('\n') + 1).split('\n').filter(Boolean).map(line => JSON.parse(line))
  }
  const send = async action => {
    const id = crypto.randomUUID()
    writeJson(request + '.new', { id, ...action })
    fs.renameSync(request + '.new', request)
    return rig.wait(`native ${action.action} ${id}`, () => readEvents().find(item => item.id === id), 60000, [entry, screen.entry])
  }
  await rig.wait('native ready scene', () => readEvents().at(-1)?.state === (options.port ? 'world' : 'title'), 180000, [entry, screen.entry])
  return { entry, screen, send, dir, events }
}

async function renderControl (rig, repo) {
  const client = await startClient(rig, repo)
  const frame = await client.send({ action: 'capture' })
  if (frame.width !== 854 || frame.height !== 480 || frame.differentPixels < 1000) {
    throw new GateError(`Invalid rendered control frame: ${JSON.stringify(frame)}`)
  }
  frame.sha256 = hash(frame.image)
  writeJson(path.join(rig.root, 'native-control.json'), frame)
  rig.results.push({ gate: 'native-control', status: 'passed', ...frame })
}

module.exports = { classpath, buildDriver, startClient, renderControl, offlineUuid }
