'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { GateError, writeJson } = require('./rig')
const s3 = require('./s3')
const { startClient } = require('./native')

const jvmArgs = [...s3.jvmArgs, '-Dfawe.test.s4=true']
async function login (rig, send, client) {
  const server = JSON.parse(await send('faweprep registry'))
  const native = (await client.send({ action: 'palette-registry' })).registry
  if (server.states !== 1024 || native.states !== 1024 || server.sha256 !== native.sha256) {
    throw new GateError(`S4 fixture registry mismatch: ${JSON.stringify({ server, native })}`)
  }
  writeJson(path.join(client.dir, 'palette-login.json'), { server, native, joined: true })
}
async function contract (rig, send, name) {
  await s3.contract(rig, send, name)
  const status = async () => JSON.parse(await send('faweprep status'))
  const command = async text => {
    const answer = await send(text)
    try { return JSON.parse(answer) } catch { throw new GateError(`${text}: ${answer}`) }
  }
  const clock = async () => {
    const answer = await send('time query gametime')
    const match = answer.match(/^The game time is (\d+) tick\(s\)$/)
    if (!match) throw new GateError(`Unreadable heartbeat: ${answer}`)
    return Number(match[1])
  }
  const observations = []
  for (const workers of [1, 2, 4]) {
    for (const scenario of ['local', 'global', 'mixed', 'height', 'cold', 'cancel-queued', 'cancel-load', 'cancel-snapshot', 'cancel-preparation', 'reload', 'entities', 'neighbor-conflict', 'neighbor-aba', 'attachment-snapshot', 'attachment-publication', 'oversized-reads', 'oversized-writes', 'oversized-nbt-text', 'oversized-nbt-array']) {
      await command(`faweprep fixture ${scenario}`)
      await rig.wait('independent fixture setup', async () => {
        const state = await status()
        if (state.fixtureFailure) throw new GateError('Fixture setup failed')
        return state.fixture
      }, 60000)
      const initialTick = await clock()
      await command(`faweprep start ${name} ${scenario} ${workers}`)
      if (scenario.startsWith('neighbor-') || scenario === 'attachment-publication') {
        await rig.wait('neighbor/attachment snapshot barrier', async () => (await status()).workersEntered === 1)
        await command('faweprep mutate')
        await command('faweprep release')
      }
      if (scenario === 'reload') {
        await rig.wait('detached preparation barrier', async () => (await status()).workersEntered >= workers)
        await send('reload')
        await command('faweprep release')
      }
      await rig.wait(`${workers}-worker ${scenario} completion`, async () => (await status()).complete, 60000)
      const finalTick = await clock()
      if (scenario === 'cold' || scenario.startsWith('cancel-')) {
        await command('faweprep observe')
        await rig.wait('post-drain independent world observation', async () => (await status()).fixture, 60000)
      }
      const result = await command('faweprep verify')
      if (result.violations !== 0) throw new GateError(`Worker/oracle violation: ${JSON.stringify(result)}`)
      if (scenario.startsWith('cancel-') && !result.cancelled) throw new GateError('Cancellation phase was not observed')
      if (scenario === 'cold' && finalTick <= initialTick + 1) throw new GateError('Cold-load heartbeat did not advance')
      observations.push({ workers, initialTick, finalTick, ...result })
      writeJson(path.join(rig.root, 's4-preparation-observations.json'), observations)
    }
  }
  const repo = path.resolve(__dirname, '../..')
  const props = fs.readFileSync(path.join(rig.root, 'server/server.properties'), 'utf8')
  const port = Number(props.match(/^server-port=(\d+)$/m)[1])
  const second = await startClient(rig, repo, { name: `${name.slice(0, 12)}b`, port, palette: true, secondary: true })
  try {
    await login(rig, send, second)
    await command('faweprep fixture local')
    await rig.wait('fairness fixture', async () => (await status()).fixture, 60000)
    await command(`faweprep start ${name} saturation 1 ${name.slice(0, 12)}b`)
    await rig.wait('saturated production service barrier', async () => (await status()).workersEntered === 1)
    const saturated = await status()
    if (saturated.stats.queued !== 63 || saturated.stats.active !== 1) throw new GateError('64-unit queue was not saturated')
    const tick = await clock()
    await rig.wait('saturated heartbeat', async () => await clock() >= tick + 3)
    await command('faweprep release')
    await rig.wait('fair queues drain', async () => (await status()).complete, 60000)
    const result = await command('faweprep verify')
    if (result.order.indexOf('7/0') < 0 || result.order.indexOf('7/0') > 2 || result.order.length !== 64) {
      throw new GateError(`Second actor was starved: ${JSON.stringify(result.order)}`)
    }
    observations.push({ workers: 1, ...result })
    writeJson(path.join(rig.root, 's4-preparation-observations.json'), observations)
  } finally {
    await command('faweprep cleanup')
    await rig.stop(second.entry)
  }
  rig.results.push({ gate: 's4-production-preparation', status: 'passed', cases: observations.length })
}
module.exports = { jvmArgs, clientOptions: { palette: true }, login, contract, beforeStop: s3.beforeStop, restart: s3.restart }
