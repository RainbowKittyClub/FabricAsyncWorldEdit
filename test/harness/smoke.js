'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { GateError, writeJson, verify, hash } = require('./rig')
const { classpath, startClient, offlineUuid } = require('./native')

async function smoke (rig, repo, workspace, profile = 'reference', fault, extension) {
  const { Rcon } = require('./rcon')
  const { readDevServer } = require(path.join(workspace, 'scripts/loadtest/lib/devserver.js'))
  const serverDir = path.join(rig.root, 'server')
  fs.mkdirSync(path.join(serverDir, 'mods'), { recursive: true })
  for (const key of rig.manifest.profiles[profile]) {
    const input = rig.manifest.availableDependencies[key]
    fs.copyFileSync(verify(input), path.join(serverDir, 'mods', path.basename(input.path)))
  }
  if (extension?.configureServer) await extension.configureServer(serverDir)
  const eula = fs.readFileSync(verify(rig.manifest.eula), 'utf8')
  if (!/^eula=true\s*$/m.test(eula)) throw new GateError('Existing authorized EULA configuration missing', 2)
  fs.writeFileSync(path.join(serverDir, 'eula.txt'), eula)
  const game = await rig.port()
  const rconPort = await rig.port()
  const name = 'fwe' + rig.token.replaceAll('-', '').slice(0, 12)
  const password = crypto.randomBytes(24).toString('hex')
  const properties = {
    'server-ip': '127.0.0.1', 'server-port': game.port, 'online-mode': false,
    'enable-rcon': true, 'rcon.port': rconPort.port, 'rcon.password': password,
    'enable-query': false, 'enable-jmx-monitoring': false, 'enable-status': false,
    'pause-when-empty-seconds': 0, 'spawn-protection': 0, difficulty: 'peaceful',
    gamemode: 'creative', 'allow-flight': true, 'view-distance': 3, 'simulation-distance': 3,
    'max-players': 4, 'level-seed': rig.manifest.seed, 'level-type': 'minecraft:flat',
    'generator-settings': JSON.stringify({ layers: [{ block: 'minecraft:bedrock', height: 1 }],
      biome: 'minecraft:plains', features: false, lakes: false }), 'generate-structures': false,
    'enforce-secure-profile': false, 'white-list': false, 'sync-chunk-writes': true
  }
  const propsFile = path.join(serverDir, 'server.properties')
  fs.writeFileSync(propsFile, Object.entries(properties).map(([k, v]) => `${k}=${v}`).join('\n') + '\n')
  writeJson(path.join(serverDir, 'ops.json'), [{ uuid: offlineUuid(name), name, level: 4, bypassesPlayerLimit: false }])
  const config = readDevServer(repo, undefined, { runDir: serverDir })
  const records = []
  let server, rcon, client
  let playerCommands = 0
  const send = async command => {
    const answer = await rcon.send(command)
    records.push({ command, answer })
    return answer
  }
  const launch = async suffix => {
    server = rig.spawn(`server-${suffix}`, 'java', ['-Xmx3G', ...(extension?.jvmArgs || []),
      `-Dfawe.test.lifecycle=${path.join(rig.root, `lifecycle-${suffix}`)}`,
      `-Dclaude.scratch=fawe-${rig.manifest.runLabel}-${rig.token}`, '--enable-native-access=ALL-UNNAMED',
      '-cp', classpath(rig.manifest, 'server'), 'net.fabricmc.loader.impl.launch.knot.KnotServer', 'nogui'],
    { cwd: serverDir })
    await rig.wait('reference server boot', () => fs.readFileSync(server.log, 'utf8').includes('Done ('), 180000, [server])
    await rig.wait('server RCON ready', async () => {
      try {
        rcon = await Rcon.connect({ host: '127.0.0.1', port: config.rconPort, password: config.rconPassword })
        return true
      } catch (error) {
        if (error.code !== 'ECONNREFUSED') throw error
        records.push({ startup: suffix, rconNotReady: error.code })
        return false
      }
    }, 30000, [server])
    if (!/players online/.test(await send('list'))) throw new GateError('Reference server query failed')
    if (profile === 'base') await rig.wait(`lifecycle ${suffix}`, () =>
      fs.existsSync(path.join(rig.root, `lifecycle-${suffix}-running.json`)), 30000, [server])
  }
  const expected = async (x, y, z, block) => {
    const answer = await send(`execute if block ${x} ${y} ${z} ${block}`)
    if (answer !== 'Test passed') throw new GateError(`Expected ${block} at ${x},${y},${z}: ${answer}`)
  }
  const edit = async (command, condition) => {
    const started = performance.now()
    await client.send({ action: 'command', command })
    playerCommands++
    if (condition) await rig.wait(command, async () => {
      if (/\/ERROR\]|Failed to handle packet/.test(fs.readFileSync(server.log, 'utf8'))) {
        throw new GateError(`Server error during ${command}; inspect ${server.log}`)
      }
      return condition()
    }, 30000, [client.entry, server])
    records.push({ playerCommand: command, elapsedMs: performance.now() - started })
  }
  const chatCommand = async (command, response) => {
    const offset = fs.statSync(client.entry.log).size
    await edit(command, () => response.test(fs.readFileSync(client.entry.log, 'utf8').slice(offset)))
  }
  const completedEdit = async (command, response, condition) => {
    await chatCommand(command, response)
    await rig.wait(`${command} authoritative result`, condition, 30000, [client.entry, server])
  }
  const inventory = (x, item, count) => async () =>
    (await send(`execute if data block ${x} 80 1024 Items[{Slot:0b,id:"minecraft:${item}",count:${count}}]`)) === 'Test passed. Count: 1'
  const expectInventory = async (x, item, count) => {
    if (!await inventory(x, item, count)()) throw new GateError(`Inventory mismatch at ${x}: ${item} x${count}`)
  }
  const changed = (x, block) => async () => (await send(`execute if block ${x} 80 1024 ${block}`)) === 'Test passed'
  try {
    await Promise.all([new Promise(resolve => game.reservation.close(resolve)),
      new Promise(resolve => rconPort.reservation.close(resolve))])
    await launch('first')
    await send('difficulty peaceful')
    await send('weather clear')
    await send('time set day')
    await send('gamerule advance_weather false')
    await send('forceload add 1008 1008 1055 1055')
    await send('fill 1020 79 1020 1040 79 1040 minecraft:stone')
    client = await startClient(rig, repo, { name, port: game.port, ...extension?.clientOptions })
    if (extension?.login) await extension.login(rig, send, client)
    if (fault === 'dead-client') {
      await rig.stop(client.entry)
      await client.send({ action: 'capture' })
      throw new GateError('Dead client escaped detection')
    }
    if (fault === 'failed-command') {
      const answer = await send('fawe_test_nonexistent_command')
      if (!answer.includes('Test passed')) throw new GateError(`Command failed: ${answer}`)
      throw new GateError('Invalid command escaped detection')
    }
    await expected(1024, 79, 1024, fault === 'wrong-block' ? 'minecraft:diamond_block' : 'minecraft:stone')
    await send(`tp ${name} 1024 81 1024 0 45`)
    await edit('/pos1 1024,80,1024')
    await edit('/pos2 1025,80,1025')
    await completedEdit('/set stone', /Operation completed|blocks? (?:have been changed|changed)/, changed(1024, 'minecraft:stone'))
    for (const x of [1024, 1025]) for (const z of [1024, 1025]) await expected(x, 80, z, 'minecraft:stone')
    await completedEdit('/replace stone gold_block', /blocks? (?:have been replaced|replaced)/, changed(1024, 'minecraft:gold_block'))
    await completedEdit('undo', /Undid/, changed(1024, 'minecraft:stone'))
    await completedEdit('redo', /Redid/, changed(1024, 'minecraft:gold_block'))
    await send('setblock 1030 80 1024 minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}')
    await send('setblock 1032 80 1024 minecraft:chest[facing=south]{Items:[{Slot:0b,id:"minecraft:emerald",count:2}]}')
    await expectInventory(1030, 'diamond', 3)
    await expectInventory(1032, 'emerald', 2)
    await send(`tp ${name} 1030.5 80 1024.5 0 45`)
    await chatCommand('/pos1 1030,80,1024', /First position set/)
    await chatCommand('/pos2 1030,80,1024', /Second position set/)
    await chatCommand('/copy', /copied|1 blocks affected/i)
    await send(`tp ${name} 1032.5 80 1024.5 0 45`)
    await completedEdit('/paste', /clipboard has been pasted/, inventory(1032, 'diamond', 3))
    await expected(1032, 80, 1024, 'minecraft:chest[facing=north]')
    await completedEdit('undo', /Undid/, inventory(1032, 'emerald', 2))
    await expected(1032, 80, 1024, 'minecraft:chest[facing=south]')
    await completedEdit('redo', /Redid/, inventory(1032, 'diamond', 3))
    if (!(await client.send({ action: 'command-tree', command: '/set' })).visible) {
      throw new GateError('Operator cannot see //set')
    }
    await send('scoreboard objectives add fawe_probe trigger')
    await send(`scoreboard players enable ${name} fawe_probe`)
    // Vanilla counts operator commands too, but checks accumulated spam only after deop.
    const clock = async () => {
      const answer = await send('time query gametime')
      const match = answer.match(/^The game time is (\d+) tick\(s\)$/)
      if (!match) throw new GateError(`Unexpected tick clock: ${answer}`)
      return Number(match[1])
    }
    const drainUntil = await clock() + playerCommands * 20
    await rig.wait('command spam debt drained', async () => await clock() >= drainUntil, 45000, [server, client.entry])
    await send(`deop ${name}`)
    await rig.wait('non-op command tree', async () =>
      !(await client.send({ action: 'command-tree', command: '/set' })).visible, 10000, [client.entry, server])
    await edit('/set diamond_block')
    await edit('trigger fawe_probe set 1', async () =>
      (await send(`scoreboard players get ${name} fawe_probe`)).includes('has 1 [fawe_probe]'))
    await expected(1030, 80, 1024, 'minecraft:chest[facing=north]')
    await expectInventory(1030, 'diamond', 3)
    await send(`op ${name}`)
    if (extension) await extension.contract(rig, send, name, { client, edit, chatCommand, expected })
    const frame = await client.send({ action: 'capture' })
    if (frame.state !== 'world' || frame.differentPixels < 1000) throw new GateError('Empty native world frame')
    frame.sha256 = hash(frame.image)
    writeJson(path.join(rig.root, 'native-control.json'), frame)
    if (!(await send('save-all flush')).includes('Saved the game')) throw new GateError('Save failed')
    if (extension?.beforeStop) await extension.beforeStop(rig, send, name)
    await rig.stop(client.entry)
    await send('stop')
    await rig.wait('saved server exit', () => server.child.exitCode !== null, 30000)
    if (server.child.exitCode !== 0) throw new GateError('Reference server did not stop cleanly')
    if (profile === 'base' && !fs.existsSync(path.join(rig.root, 'lifecycle-first-stopped.json'))) {
      throw new GateError('Scheduler shutdown rejection was not observed')
    }
    rcon.close()
    await launch('restart')
    await send('forceload add 1008 1008 1055 1055')
    if (extension) await extension.restart(rig, send)
    for (const x of [1024, 1025]) for (const z of [1024, 1025]) await expected(x, 80, z, 'minecraft:gold_block')
    await expectInventory(1030, 'diamond', 3)
    await expectInventory(1032, 'diamond', 3)
    await expected(1032, 80, 1024, 'minecraft:chest[facing=north]')
    const cells = []
    for (const x of [1024, 1025]) for (const z of [1024, 1025]) {
      cells.push({ position: [x, 80, z], block: 'minecraft:gold_block' })
    }
    for (const x of [1030, 1032]) {
      await expected(x, 80, 1024, 'minecraft:chest[facing=north,type=single,waterlogged=false]')
      cells.push({ position: [x, 80, 1024], block: 'minecraft:chest',
        properties: { facing: 'north', type: 'single', waterlogged: false },
        verifiedInventoryEntry: { slot: 0, id: 'minecraft:diamond', count: 3 },
        savedNbt: await send(`data get block ${x} 80 1024`) })
    }
    writeJson(path.join(rig.root, 'fixture-snapshot.json'), { seed: rig.manifest.seed,
      oracle: 'Explicit expected states and inventory entries verified by vanilla commands after restart', cells })
    writeJson(path.join(rig.root, 'timings.json'), records.filter(record => record.playerCommand))
    rig.results.push({ gate: `${profile}-set-replace-copy-paste-nbt-undo-redo-permissions-save-restart`, status: 'passed', frame })
  } finally {
    if (rcon) {
      try {
        await rcon.send('tick unfreeze', 2000)
        await rcon.send('forceload remove all', 2000)
        await rcon.send('stop', 2000)
      } catch (error) { records.push({ cleanupError: error.message }) }
      rcon.close()
    }
    writeJson(path.join(rig.root, 'rcon-observations.json'), records)
    if (server) await rig.stop(server)
    fs.writeFileSync(propsFile, fs.readFileSync(propsFile, 'utf8').replace(/^rcon.password=.*$/m, 'rcon.password=REDACTED'))
  }
}
module.exports = { smoke }
