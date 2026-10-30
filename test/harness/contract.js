'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { GateError, json, writeJson } = require('./rig')

// RCON supplies fixtures and race mutations; the non-shipped consumer submits the actual API edit.
async function contract (rig, send, name) {
  const expected = async (block) => {
    const result = await send(`execute if block 1044 80 1032 ${block}`)
    if (result !== 'Test passed') throw new GateError(`Independent contract world check: ${result}`)
  }
  const clock = async () => {
    const response = await send('time query gametime')
    const match = response.match(/^The game time is (\d+) tick\(s\)$/)
    if (!match) throw new GateError(`Unreadable heartbeat: ${response}`)
    return Number(match[1])
  }
  const status = async () => JSON.parse(await send('fawecontract status'))
  const results = []
  for (const scenario of ['basic', 'held', 'deny', 'cancel', 'conflict', 'aba', 'nbt',
    'shutdown-prepare', 'shutdown-discard-failure', 'shutdown-audit', 'reconcile-failure']) {
    const fixture = scenario === 'nbt'
      ? 'minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}'
      : 'minecraft:stone'
    await send(`setblock 1044 80 1032 ${fixture}`)
    await expected(scenario === 'nbt' ? 'minecraft:chest[facing=north]' : 'minecraft:stone')
    const start = await send(`fawecontract start ${name} ${scenario}`)
    if (!start.startsWith('{')) throw new GateError(`Contract admission failed: ${start}`)
    const initial = JSON.parse(start)
    if (initial.complete || initial.published !== 0) throw new GateError('Submission completed before preparation barrier')
    const tick = await clock()
    await rig.wait(`${scenario} heartbeat with outstanding preparation`, async () => await clock() >= tick + 3, 10000)
    if ((await status()).published !== 0) throw new GateError('Publication ignored preparation barrier')
    if (scenario === 'conflict') await send('setblock 1045 80 1032 minecraft:diamond_block')
    if (scenario === 'aba') {
      await send('setblock 1044 80 1032 minecraft:dirt')
      await send('setblock 1044 80 1032 minecraft:stone')
    }
    if (scenario === 'shutdown-prepare' || scenario === 'shutdown-discard-failure') {
      await rig.wait('preparation entered before shutdown', async () => (await status()).prepared === 1)
      await send('fawecontract stop')
      if (!(await status()).complete) throw new GateError('Shutdown left preparation completion unresolved')
      if ((await status()).drain !== 'pending') throw new GateError('Shutdown forgot late preparation cleanup')
    }
    await send('fawecontract release prepare')
    if (scenario === 'shutdown-audit') {
      await rig.wait('recording entered before shutdown', async () => (await status()).recorded === 1)
      if ((await status()).complete) throw new GateError('Audit barrier did not hold completion')
      await send('fawecontract stop')
      if (!(await status()).complete) throw new GateError('Shutdown left audit completion unresolved')
      if ((await status()).drain !== 'pending') throw new GateError('Shutdown forgot outstanding recorder')
      await send('fawecontract release audit')
    }
    if (scenario === 'held') {
      await rig.wait('publication without history completion', async () => (await status()).published === 1)
      if ((await status()).complete) throw new GateError('Completion escaped history barrier')
      await expected('minecraft:gold_block')
      await send('fawecontract release history')
      await rig.wait('audit admission', async () => (await status()).recorded === 1)
      if ((await status()).complete) throw new GateError('Completion escaped audit acknowledgement barrier')
      await send('fawecontract release audit')
    }
    const evidence = path.join(rig.root, `lifecycle-first-${scenario}.json`)
    await rig.wait(`${scenario} actual receipt`, () => fs.existsSync(evidence), 30000)
    const result = json(evidence)
    const applied = ['basic', 'held', 'nbt', 'shutdown-audit', 'reconcile-failure'].includes(scenario) ? 1 : 0
    const preStop = scenario === 'shutdown-prepare' || scenario === 'shutdown-discard-failure'
    const exceptional = preStop || scenario === 'shutdown-audit' || scenario === 'reconcile-failure'
    if (result.applied !== applied || result.exceptional !== exceptional ||
        result.closed !== (preStop ? 0 : 1) || result.recorded !== (preStop ? 0 : 1) || result.pending !== 0 || result.bytes !== 0) {
      throw new GateError(`Incorrect ${scenario} receipt: ${JSON.stringify(result)}`)
    }
    const outcome = { deny: 'PARTIAL', cancel: 'CANCELLED_BEFORE_WRITE', conflict: 'CONFLICT', aba: 'CONFLICT' }[scenario]
    if (outcome && result.outcomes[0] !== outcome) throw new GateError(`Wrong ${scenario} outcome`)
    await expected(scenario === 'nbt' ? 'minecraft:chest[facing=north]' : applied ? 'minecraft:gold_block' : 'minecraft:stone')
    if (scenario === 'conflict') {
      if (await send('execute if block 1045 80 1032 minecraft:diamond_block') !== 'Test passed') {
        throw new GateError('Concurrent untouched cell was overwritten')
      }
    }
    if (scenario === 'nbt') {
      const inventory = await send('execute if data block 1044 80 1032 Items[{Slot:0b,id:"minecraft:diamond",count:3}]')
      const nbt = await send('data get block 1044 80 1032')
      if (inventory !== 'Test passed. Count: 1' || !nbt.includes('contract-new')) throw new GateError(`NBT-only write lost data: ${nbt}`)
      result.independentNbt = nbt
    }
    if (scenario.startsWith('shutdown-')) {
      await rig.wait('exactly-once release after shutdown', async () => (await status()).closed === 1)
      const drain = scenario === 'shutdown-discard-failure' ? 'failed' : 'complete'
      await rig.wait('detached shutdown drain', async () => (await status()).drain === drain)
      const stoppedTick = await clock()
      await rig.wait('heartbeat after late completion', async () => await clock() >= stoppedTick + 3, 10000)
      const settled = await status()
      if (settled.closed !== 1 || settled.pending !== 0 || settled.bytes !== 0 || !settled.complete || settled.published !== 1) {
        throw new GateError(`Late completion changed terminated operation: ${JSON.stringify(settled)}`)
      }
      result.afterLateCompletion = settled
    }
    results.push(result)
  }
  writeJson(path.join(rig.root, 'contract-observations.json'), results)
  rig.results.push({ gate: 'coordinator-contract-subset', status: 'passed', cases: results.length,
    caveat: 'Coordinator-only coverage; production queue/API/history acceptance belongs to the separate s3 suite.' })
}

async function restart (rig, send) {
  for (const [x, block] of [[1044, 'minecraft:gold_block'], [1045, 'minecraft:diamond_block']]) {
    if (await send(`execute if block ${x} 80 1032 ${block}`) !== 'Test passed') {
      throw new GateError(`Coordinator committed cell did not survive restart at ${x}`)
    }
  }
  rig.results.push({ gate: 'coordinator-subset-save-restart', status: 'passed' })
}

module.exports = { contract, restart }
