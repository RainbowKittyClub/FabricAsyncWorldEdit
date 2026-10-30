'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { GateError, json, writeJson } = require('./rig')

const jvmArgs = ['-Dfawe.experimental.coordinated=true']
async function contract (rig, send, name) {
  await require('./contract').contract(rig, send, name)
  const status = async () => JSON.parse(await send('fawequeue status'))
  const expect = async (x, z, block) => {
    const answer = await send(`execute if block ${x} 80 ${z} minecraft:${block}`)
    if (answer !== 'Test passed') throw new GateError(`Queue world oracle ${x},${z} ${block}: ${answer}`)
  }
  const clock = async () => {
    const response = await send('time query gametime')
    const match = response.match(/^The game time is (\d+) tick\(s\)$/)
    if (!match) throw new GateError(`Unreadable heartbeat: ${response}`)
    return Number(match[1])
  }
  const cases = ['basic', 'replacement', 'veto', 'deny', 'conflict', 'noop', 'nbt', 'biome', 'held-reconcile', 'held-history',
    'held-audit', 'full-queue', 'recursive', 'recursive-failure', 'immediate-failure', 'processor-failure',
    'saturated', 'finalizer-failure', 'post-failure', 'history-failure']
  const results = []
  for (const scenario of cases) {
    const x = scenario === 'history-failure' ? 1012 : 1044
    const fixture = scenario === 'nbt' ? 'chest[facing=north]{CustomName:"queue-before",Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}'
      : scenario === 'noop' ? 'gold_block' : 'stone'
    await send(`setblock ${x} 80 1032 minecraft:${fixture}`)
    if (scenario === 'biome') await send(`fillbiome ${x} 80 1032 ${x} 80 1032 minecraft:plains`)
    await send('setblock 1028 80 1032 minecraft:stone')
    await send('setblock 1044 80 1048 minecraft:stone')
    await send('setblock 1045 80 1032 minecraft:stone')
    await expect(x, 1032, scenario === 'nbt' ? 'chest[facing=north]' : scenario === 'noop' ? 'gold_block' : 'stone')
    const answer = await send(`fawequeue start ${name} ${scenario}`)
    if (!answer.startsWith('{')) throw new GateError(`Production API admission failed: ${answer}`)
    if (scenario !== 'processor-failure') {
      await rig.wait(`${scenario} real queue preparation`, async () => (await status()).prepared > 0)
      if ((await status()).complete) throw new GateError('Queue completed before admitted preparation')
      const started = await clock()
      await rig.wait(`${scenario} heartbeat under outstanding queue work`, async () => await clock() >= started + 3, 10000)
      if (scenario === 'saturated') {
        await rig.wait('every real FAWE blocking worker occupied', async () => {
          const state = await status()
          return state.saturated > 0 && state.saturated === state.poolSize && !state.poolDone
        })
      }
      await send('fawequeue main')
      if (!(await status()).mainRejected) throw new GateError('Main-thread flush failed to reject safely')
      if (scenario === 'conflict') await send('setblock 1045 80 1032 minecraft:diamond_block')
      await send('fawequeue release prepare')
    }
    if (scenario.startsWith('held-')) {
      const barrier = scenario.slice(5)
      const counter = { reconcile: 'reconciled', history: 'historyEntered', audit: 'recorded' }[barrier]
      await rig.wait(`${scenario} consumer entered`, async () => (await status())[counter] === 1)
      if ((await status()).complete) throw new GateError(`Completion escaped ${barrier}`)
      await expect(x, 1032, 'gold_block')
      const tick = await clock()
      await rig.wait(`${scenario} withheld consumer heartbeat`, async () => await clock() >= tick + 3, 10000)
      await send(`fawequeue release ${barrier}`)
    }
    if (scenario.startsWith('recursive')) {
      await rig.wait('actual write while recursive child remains pending', async () => (await status()).closed === 1)
      if ((await status()).complete) throw new GateError('Completed root dropped recursive future')
      await send('fawequeue release recursive')
    }
    const evidence = path.join(rig.root, `lifecycle-first-queue-${scenario}.json`)
    await rig.wait(`${scenario} actual queue completion`, () => fs.existsSync(evidence), 30000)
    const result = json(evidence)
    const noWrite = ['veto', 'deny', 'conflict', 'processor-failure'].includes(scenario)
    const historyCount = noWrite || scenario === 'history-failure' || scenario === 'noop' ? 0 : scenario === 'full-queue' ? 3 : 1
    const exceptional = ['recursive-failure', 'immediate-failure', 'processor-failure', 'finalizer-failure', 'post-failure', 'history-failure'].includes(scenario)
    const appliedCount = noWrite || scenario === 'noop' ? 0 : scenario === 'full-queue' ? 3 : 1
    if (result.applied !== appliedCount || result.exceptional !== exceptional || result.history.length !== historyCount || result.completions !== 1) {
      throw new GateError(`Incorrect ${scenario} completion/history: ${JSON.stringify(result)}`)
    }
    for (const block of result.history) {
      if (scenario === 'nbt') {
        if (block.before !== block.after || !block.beforeNbt.includes('queue-before') || !block.afterNbt.includes('queue-after') ||
            !block.beforeNbt.includes('minecraft:diamond') || !block.afterNbt.includes('minecraft:diamond')) {
          throw new GateError('NBT-only history lost an actual side')
        }
      } else if (scenario === 'biome') {
        if (block.before !== 'minecraft:plains' || block.after !== 'minecraft:desert') throw new GateError('Biome history mismatch')
      } else if (block.before !== 'minecraft:stone' || block.after !== `minecraft:${scenario === 'replacement' ? 'dirt' : 'gold_block'}`) {
        throw new GateError(`History is not actual-before/after: ${JSON.stringify(block)}`)
      }
    }
    await expect(x, 1032, scenario === 'nbt' ? 'chest[facing=north]' : scenario === 'biome' || noWrite ? 'stone' : scenario === 'replacement' ? 'dirt' : 'gold_block')
    if (scenario === 'biome') {
      // execute-if-biome samples the noise-jittered biome, not this stored quart cell.
      const biome = JSON.parse(await send('fawequeue biome'))
      if (biome.target !== 'minecraft:desert' || biome.neighbor !== 'minecraft:plains') throw new GateError('Independent quart biome oracle failed')
      result.independentBiomes = biome
    }
    if (scenario === 'nbt' && !(await send(`data get block ${x} 80 1032`)).includes('queue-after')) throw new GateError('Independent NBT oracle failed')
    if (scenario === 'full-queue') {
      await expect(1028, 1032, 'gold_block')
      await expect(1044, 1048, 'gold_block')
      if (result.processed !== 3 || result.closed !== 3) throw new GateError('Full queue bypassed chunk execution or leaked reservations')
    }
    if (scenario === 'saturated') {
      await send('fawequeue release pool')
      await rig.wait('owned saturation tasks drain', async () => (await status()).poolDone)
    }
    if (scenario !== 'processor-failure' && result.closed !== (scenario === 'full-queue' ? 3 : 1)) {
      throw new GateError('Reservation release not exactly once')
    }
    results.push(result)
  }
  writeJson(path.join(rig.root, 's3-queue-observations.json'), results)
  rig.results.push({ gate: 's3-production-queue-api', status: 'passed', cases: results.length })
}

async function beforeStop (rig, send, name) {
  await send('setblock 1012 80 1032 minecraft:stone')
  const answer = await send(`fawequeue start ${name} server-stop`)
  if (!answer.startsWith('{')) throw new GateError(`Shutdown admission failed: ${answer}`)
  await rig.wait('real server shutdown with queued preparation', async () => JSON.parse(await send('fawequeue status')).prepared === 1)
}

async function restart (rig, send) {
  const shutdown = json(path.join(rig.root, 'lifecycle-first-queue-shutdown.json'))
  if (!shutdown.exceptional || shutdown.completions !== 1 || shutdown.history.length !== 0) {
    throw new GateError(`Real shutdown left inaccurate completion: ${JSON.stringify(shutdown)}`)
  }
  for (const [x, block] of [[1012, 'stone'], [1044, 'gold_block']]) {
    if (await send(`execute if block ${x} 80 1032 minecraft:${block}`) !== 'Test passed') {
      throw new GateError('Queue save/restart or cancelled shutdown write failed')
    }
  }
  rig.results.push({ gate: 's3-real-server-stop-and-restart', status: 'passed', shutdown })
}

module.exports = { jvmArgs, contract, beforeStop, restart }
