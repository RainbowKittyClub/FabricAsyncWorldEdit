'use strict'

// Comparator preflight only. Boot the pinned upstream plugin, edit as a real vanilla client,
// verify server cells, undo, save/restart, and verify again. This is not a performance result.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { Rig, SingleLaneLease, admitResources, verify, hash, writeJson, GateError, RESOURCE_POLICY } = require('../harness/rig')
const { Rcon } = require('../harness/rcon')
const { startClient, offlineUuid } = require('../harness/native')
const repo = path.resolve(__dirname, '../..')
const manifestFile = path.join(__dirname, 'manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestFile))
const evidence = '/mnt/WORKSPACE/scratchpad/fawe-s14-evidence'

async function main () {
  fs.mkdirSync(evidence, { recursive: true })
  const recordFile = path.join(evidence, `paper-preflight-${Date.now()}.json`)
  const report = { status: 'pending', purpose: 'comparator-preflight-not-performance', checks: [], resourcePolicy: RESOURCE_POLICY }
  let rig, lease, rcon, server, failure
  const check = (name, pass, detail) => {
    report.checks.push({ name, passed: Boolean(pass), detail })
    if (!pass) throw new GateError(`Paper preflight ${name}: ${JSON.stringify(detail)}`)
  }
  try {
    for (const pin of Object.values(manifest.comparators)) verify(pin)
    verify(manifest.minecraft.serverBundle)
    const eula = fs.readFileSync(verify(manifest.eula), 'utf8')
    if (!/^eula=true\s*$/m.test(eula)) throw new GateError('Existing authorized EULA missing')
    report.sourceHashes = Object.fromEntries(['test/s14/paper-preflight.js', 'test/s14/pin.py',
      'test/harness/rig.js', 'test/harness/native.js', 'test/harness/rcon.js'].map(file => [file, hash(path.join(repo, file))]))
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: [] })
    rig = new Rig('/mnt/WORKSPACE/scratchpad/fawe/s14-paper-preflight', manifest, manifestFile, admission)
    report.root = rig.root
    const dir = path.join(rig.root, 'server')
    fs.mkdirSync(path.join(dir, 'plugins'), { recursive: true })
    fs.mkdirSync(path.join(dir, 'cache'), { recursive: true })
    fs.copyFileSync(verify(manifest.comparators.paper), path.join(dir, 'paper.jar'))
    fs.copyFileSync(verify(manifest.comparators['paper-fawe']), path.join(dir, 'plugins/FastAsyncWorldEdit.jar'))
    // Paperclip's download-context checksum matches the already pinned Mojang bundle exactly.
    check('mojang-bootstrap-is-pinned', manifest.minecraft.serverBundle.sha256 ===
      'd052f14d7a173734fba553711e5b570162e2f2a313267ee31a21b975a679be64')
    fs.copyFileSync(verify(manifest.minecraft.serverBundle), path.join(dir, 'cache/mojang_26.3.jar'))
    fs.writeFileSync(path.join(dir, 'eula.txt'), eula)
    const game = await rig.port()
    const remote = await rig.port()
    const password = crypto.randomBytes(24).toString('hex')
    const name = 'fwe' + rig.token.replaceAll('-', '').slice(0, 12)
    const properties = { 'server-ip': '127.0.0.1', 'server-port': game.port, 'online-mode': false,
      'enable-rcon': true, 'rcon.port': remote.port, 'rcon.password': password, 'enable-status': false,
      'pause-when-empty-seconds': 0, 'spawn-protection': 0, difficulty: 'peaceful', gamemode: 'creative',
      'allow-flight': true, 'view-distance': 3, 'simulation-distance': 3, 'max-players': 4,
      'level-seed': manifest.seed, 'level-type': 'minecraft:flat', 'generate-structures': false,
      'generator-settings': JSON.stringify({ layers: [{ block: 'minecraft:bedrock', height: 1 }],
        biome: 'minecraft:plains', features: false, lakes: false }), 'enforce-secure-profile': false,
      'sync-chunk-writes': true }
    fs.writeFileSync(path.join(dir, 'server.properties'), Object.entries(properties).map(([key, value]) => `${key}=${value}`).join('\n') + '\n')
    writeJson(path.join(dir, 'ops.json'), [{ uuid: offlineUuid(name), name, level: 4, bypassesPlayerLimit: false }])
    // No BStats traffic is needed for a private comparator.
    fs.mkdirSync(path.join(dir, 'plugins/bStats'), { recursive: true })
    fs.writeFileSync(path.join(dir, 'plugins/bStats/config.yml'), 'enabled: false\n')
    await new Promise(resolve => game.reservation.close(resolve))
    await new Promise(resolve => remote.reservation.close(resolve))
    const launch = async suffix => {
      server = rig.spawn(`paper-${suffix}`, 'java', ['-Xmx3G', '--enable-native-access=ALL-UNNAMED',
        `-Dclaude.scratch=fawe-${rig.manifest.runLabel}-${rig.token}`, '-jar', 'paper.jar', '--nogui'], { cwd: dir })
      await rig.wait('Paper boot', () => /Done \(/.test(fs.readFileSync(server.log, 'utf8')), 240000, [server])
      await rig.wait('Paper RCON', async () => {
        try { rcon = await Rcon.connect({ port: remote.port, password }); return true } catch (error) {
          if (error.code !== 'ECONNREFUSED') throw error
          return false
        }
      }, 30000, [server])
      // Paper's bare /version answers asynchronously; RCON initially gets only "Checking version".
      // Verify the exact startup build banner and a separate live RCON query instead.
      const version = fs.readFileSync(server.log, 'utf8').split('\n').find(line => line.includes('This server is running Paper version'))
      check(`paper-version-${suffix}`, /Paper version 26\.3-244-main@35a2d15/.test(version || ''), version)
      check(`paper-rcon-${suffix}`, /players online|of a max of/.test(await rcon.send('list')))
      const plugin = await rcon.send('version FastAsyncWorldEdit')
      check(`upstream-fawe-${suffix}`, /2\.16\.2/.test(plugin), plugin)
    }
    const cells = async block => {
      for (let x = 1024; x <= 1025; x++) for (let z = 1024; z <= 1025; z++) {
        if (!/Test passed/.test(await rcon.send(`execute if block ${x} 80 ${z} minecraft:${block}`))) return false
      }
      return true
    }
    await launch('first')
    await rcon.send('forceload add 1024 1024 1039 1039')
    await rcon.send('fill 1024 80 1024 1025 80 1025 minecraft:air strict')
    const client = await startClient(rig, repo, { name, port: game.port, pack: false, label: 'paper' })
    await rcon.send(`tp ${name} 1024.5 82 1024.5`)
    const chat = async (command, answer) => {
      const offset = fs.statSync(client.entry.log).size
      await client.send({ action: 'command', command })
      await rig.wait(`Paper ${command}`, () => answer.test(fs.readFileSync(client.entry.log, 'utf8').slice(offset)), 60000, [client.entry, server])
    }
    await chat('/pos1 1024,80,1024', /First position set|Position already set/)
    await chat('/pos2 1025,80,1025', /Second position set|Position already set/)
    await chat('/set stone', /Operation completed|blocks have been changed|blocks affected/)
    await rig.wait('Paper set cells', () => cells('stone'), 30000, [server])
    check('paper-set', await cells('stone'))
    await chat('/undo', /Undid/)
    await rig.wait('Paper undo cells', () => cells('air'), 30000, [server])
    check('paper-undo', await cells('air'))
    await chat('/redo', /Redid/)
    await rig.wait('Paper redo cells', () => cells('stone'), 30000, [server])
    check('paper-redo', await cells('stone'))
    await rig.stop(client.entry)
    await rcon.send('save-all flush')
    await rcon.send('stop')
    rcon.close(); rcon = null
    await rig.wait('Paper saved exit', () => server.child.exitCode !== null, 60000)
    check('paper-clean-stop', server.child.exitCode === 0, server.child.exitCode)
    await launch('restart')
    check('paper-restart-data', await cells('stone'))
    await rcon.send('stop')
    rcon.close(); rcon = null
    await rig.wait('Paper restart saved exit', () => server.child.exitCode !== null, 60000)
    check('paper-restart-clean-stop', server.child.exitCode === 0, server.child.exitCode)
  } catch (error) { failure = error } finally {
    rcon?.close()
    if (rig) {
      try { await rig.close() } catch (error) { failure ||= error }
      for (const entry of rig.children) {
        if (/\/ERROR\]| ERROR\]:|Failed to handle packet|Could not load.*FastAsyncWorldEdit|Error occurred while enabling/.test(fs.readFileSync(entry.log, 'utf8'))) {
          failure ||= new GateError(`Runtime error in ${entry.log}`)
        }
      }
      report.resources = rig.resourceReport()
      report.processes = rig.children.map(({ child, ...entry }) => entry)
    }
    if (!rig || rig.children.every(entry => entry.stopped)) lease?.close()
    report.leaseReleased = !lease || lease.closed
    report.error = failure?.stack
    report.status = failure ? 'failed' : 'passed-comparator-preflight'
    writeJson(recordFile, report)
    console.log(JSON.stringify({ recordFile, status: report.status, error: report.error }))
  }
  if (failure) throw failure
}

main().catch(error => { console.error(error.stack); process.exitCode = 1 })
