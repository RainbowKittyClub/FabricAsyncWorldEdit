'use strict'

const path = require('node:path')
const { GateError, writeJson, json } = require('./rig')
const s4 = require('./s4')

async function contract (rig, send, name, native) {
  await s4.contract(rig, send, name)
  const command = async text => {
    const answer = await send(text)
    try { return JSON.parse(answer) } catch { throw new GateError(`${text}: ${answer}`) }
  }
  const status = () => command('fawepublish status')
  const results = []
  await send('gamerule random_tick_speed 0')
  try {
    for (const scenario of ['dense', 'sparse', 'nbt', 'nbt-retry', 'nbt-aba', 'fluid', 'random', 'light', 'remove', 'furnace', 'be-remove', 'poi', 'biome', 'unknown', 'entity', 'heavy-limit', 'race', 'aba', 'retry', 'cancel', 'mid-failure', 'finalization-failure', 'send-held', 'send-failure']) {
      await command(`fawepublish fixture ${scenario}`)
      await rig.wait('S5 independent fixture', async () => (await status()).fixture, 60000)
      await command(`fawepublish start ${name}`)
      if (scenario !== 'entity') await rig.wait('S5 production prepublication barrier', async () => (await status()).prepared === 1)
      if (['race', 'aba', 'retry'].includes(scenario)) await command('fawepublish mutate')
      if (scenario === 'nbt-retry' || scenario === 'nbt-aba') {
        const { cx } = await status()
        await send(`item replace block ${cx * 16} 80 1024 container.0 with minecraft:diamond 3`)
        if (scenario === 'nbt-aba') await send(`item replace block ${cx * 16} 80 1024 container.0 with minecraft:air`)
      }
      if (scenario === 'cancel') await command('fawepublish cancel')
      await command('fawepublish release')
      if (scenario === 'send-held') {
        await rig.wait('Required watcher publication barrier', async () => (await status()).published === 1)
        const held = await status()
        if (held.complete || held.tickets === 0) throw new GateError('Completion/tickets escaped required publication')
        await command('fawepublish release-send')
      }
      await rig.wait(`S5 ${scenario} completion`, async () => (await status()).complete, 60000)
      const result = await command('fawepublish verify')
      if (['retry', 'nbt-retry', 'nbt-aba'].includes(scenario) && result.attempts !== 2) throw new GateError('Conflict did not reprepare exactly once')
      if (scenario.endsWith('failure') && !result.failure) throw new GateError('Injected failure was not propagated')
      results.push(result)
      writeJson(path.join(rig.root, 's5-publication-observations.json'), results)
    }
    await command('fawepublish verify-all')
    await send('forceload add 1040 1040 1071 1055')
    await send('fill 1040 96 1040 1071 111 1055 minecraft:stone')
    const before = await command('fawepublish commands')
    await native.chatCommand('/pos1 1040,96,1040', /First position set/)
    await native.chatCommand('/pos2 1071,111,1055', /Second position set/)
    await native.chatCommand('fawe-coordinated set gold_block', /Coordinated edit complete: 8192 changes/)
    await command('fawepublish command-world set')
    await send('fill 1040 96 1040 1040 111 1040 minecraft:diamond_block')
    await native.chatCommand('fawe-coordinated replace gold_block glass', /Coordinated edit complete: 8176 changes/)
    await command('fawepublish command-world replace')
    const after = await command('fawepublish commands')
    if (after.complete !== before.complete + 2 || after.prepared !== before.prepared + 4
        || after.recorded !== before.recorded + 4 || after.publication.sections < before.publication.sections + 4
        || after.preparation.snapshots < before.preparation.snapshots + 4) {
      throw new GateError(`Command bypassed production stages: ${JSON.stringify({ before, after })}`)
    }
    await send('forceload add 1104 1040 1135 1055')
    await send('fill 1104 96 1040 1135 111 1055 minecraft:stone')
    await native.chatCommand('/pos1 1104,96,1040', /First position set/)
    await native.chatCommand('/pos2 1135,111,1055', /Second position set/)
    await command('fawepublish hold-commands')
    await native.chatCommand('fawe-coordinated set gold_block', /Coordinated edit admitted/)
    await rig.wait('First overlap snapshot', async () => (await command('fawepublish commands')).prepared === after.prepared + 1)
    await native.chatCommand('fawe-coordinated set diamond_block', /Coordinated edit admitted/)
    await rig.wait('Second overlap snapshot', async () => (await command('fawepublish commands')).prepared === after.prepared + 2)
    await command('fawepublish release-commands')
    await rig.wait('Overlapping production commands', async () => (await command('fawepublish commands')).complete === after.complete + 2, 60000)
    await command('fawepublish overlap-world')
    const overlap = await command('fawepublish commands')
    if (overlap.publication.repreparations <= after.publication.repreparations) throw new GateError('Overlap did not exercise stale snapshots')
    await native.chatCommand('/pos1 1105,112,1041', /First position set/)
    await native.chatCommand('/pos2 1105,112,1041', /Second position set/)
    await native.edit('/setbiome desert', async () => (await send('fawepublish wna-biome')) === '{"quartCells":3}')
    rig.results.push({ gate: 's5-wna-biome-quart-coordinates', status: 'passed', cases: 1 })
    writeJson(path.join(rig.root, 's5-command-observations.json'), { before, after, overlap, cells: 8192, changed: [8192, 8176] })
    rig.results.push({ gate: 's5-owner-publication-api', status: 'passed', cases: results.length,
      scope: 'Declared vanilla block/BE/biome support; entity and unknown lifecycle refusals verified.' })
    rig.results.push({ gate: 's5-production-native-commands', status: 'passed', cases: 4 })
  } finally {
    await command('fawepublish cleanup')
  }
}
async function beforeStop (rig, send, name) {
  await s4.beforeStop(rig, send, name)
  const status = async () => JSON.parse(await send('fawepublish status'))
  await send('fawepublish fixture stop-published')
  await rig.wait('Published-stop fixture', async () => (await status()).fixture, 60000)
  await send(`fawepublish start ${name}`)
  await rig.wait('Published-stop preparation', async () => (await status()).prepared === 1)
  await send('fawepublish release')
  await rig.wait('Published-stop watcher barrier', async () => (await status()).published === 1)
  const held = await status()
  if (held.complete || held.tickets === 0) throw new GateError('Published-stop did not retain required completion')
}

async function restart (rig, send) {
  await s4.restart(rig, send)
  const shutdown = json(path.join(rig.root, 'lifecycle-first-s5-stop.json'))
  if (shutdown.applied !== 4096 || shutdown.history !== 4096 || shutdown.closed !== 1
      || !shutdown.drainFailure.includes('Shutdown interrupted finalization')) throw new GateError('Published-stop history/drain mismatch')
  await send(`forceload add ${shutdown.cx * 16} 1024`)
  if (await send(`fawepublish verify-saved ${shutdown.cx} stop-published`) !== '{"savedWorld":true}') throw new GateError('Published-stop save lost metadata')
  rig.results.push({ gate: 's5-published-stop-history-and-restart', status: 'passed', shutdown })
  const results = []
  for (const { cx, kind } of json(path.join(rig.root, 's5-publication-observations.json'))) {
    await send(`forceload add ${cx * 16} 1024`)
    const response = await send(`fawepublish verify-saved ${cx} ${kind}`)
    if (response !== '{"savedWorld":true}') throw new GateError(`Reloaded ${kind}: ${response}`)
    results.push({ cx, kind, savedWorld: true })
  }
  await send('forceload add 1040 1040 1071 1055')
  if (await send('fawepublish command-world replace') !== '{"cells":8192}') throw new GateError('Command fixture failed after restart')
  await send('forceload add 1104 1040 1135 1055')
  if (await send('fawepublish overlap-world') !== '{"cells":8192}') throw new GateError('Overlap fixture failed after restart')
  if (await send('fawepublish wna-biome') !== '{"quartCells":3}') throw new GateError('WNA quart biome failed after restart')
  writeJson(path.join(rig.root, 's5-restart-observations.json'), results)
  rig.results.push({ gate: 's5-restart-metadata', status: 'passed', cases: results.length + 3 })
}
module.exports = { ...s4, contract, beforeStop, restart }
