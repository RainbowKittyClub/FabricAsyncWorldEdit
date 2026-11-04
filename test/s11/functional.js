'use strict'

const fs = require('node:fs')
const { cells, probe, differences, delta } = require('./common')

// S11 functional suite: ordinary WorldEdit commands, the coordinated command and a public-API
// consumer. Each case's expected states are computed here, never read from FAWE, and compared
// with the world before and after a restart. The write oracle and stage counters show the route.

const NATIVE_STAGES = ['snapshot', 'admitted', 'prepared', 'authorized', 'published', 'reconciled', 'recorded']
const BULK_STAGES = ['ticket', ...NATIVE_STAGES]
const ROUTES = [['native', 'PLAYER'], ['native', 'API'], ['bulk', 'PLAYER'], ['bulk', 'API']]
const AREA = 'forceload add 5000 5000 5063 5007'
const NEGATIVE = 'forceload add -5024 -5024 -4992 -4992'
// One cell wide across 64 chunks: one unit each, so losing permission part-way leaves whole units.
const REVOKE = [5000, 80, 3968, 5000, 80, 4991]
const REVOKE_AREA = 'forceload add 5000 3968 5000 4991'
// A row over 5 chunks of 8, 16, 16, 16 and 8 cells: one unit each, all sent in the final flush.
const FINAL = [5000, 86, 5003, 5063, 86, 5003]
const FINAL_PREFIXES = [8, 24, 40, 56]
const applied = receipts => receipts.reduce((sum, receipt) => sum + receipt.applied, 0)

// Each case: a box and the state every cell must hold, or the states it may hold.
const CASES = [
  { name: 'fill', box: [5000, 80, 5000, 5003, 81, 5001], expect: () => 'minecraft:stone' },
  { name: 'mask-replace', box: [5006, 80, 5000, 5009, 80, 5001], expect: (x, y, z) => (x + z) % 2 === 0 ? 'minecraft:stone' : 'minecraft:glass' },
  { name: 'pattern', box: [5012, 80, 5000, 5015, 80, 5001], expect: () => ['minecraft:oak_planks', 'minecraft:glass'] },
  { name: 'sparse', box: [5018, 80, 5000, 5025, 80, 5001], expect: (x, y, z) => ((x - 5018) + (z - 5000) * 8) % 5 === 0 ? 'minecraft:diamond_block' : 'minecraft:air' },
  { name: 'paste', box: [5030, 80, 5004, 5032, 80, 5004], expect: (x) => ['minecraft:furnace[facing=east,lit=false]', 'minecraft:stone', 'minecraft:oak_log[axis=x]'][x - 5030] },
  // //rotate 90 turns the clipboard clockwise seen from above: +x becomes +z, east becomes south.
  { name: 'rotated-paste', box: [5040, 80, 5004, 5040, 80, 5006], expect: (x, y, z) => ['minecraft:furnace[facing=south,lit=false]', 'minecraft:stone', 'minecraft:oak_log[axis=z]'][z - 5004] },
  { name: 'cut-source', box: [5050, 80, 5000, 5051, 80, 5000], expect: () => 'minecraft:air' },
  { name: 'cut-paste', box: [5050, 80, 5004, 5051, 80, 5004], expect: () => 'minecraft:gold_block' },
  { name: 'move', box: [5056, 80, 5000, 5060, 80, 5000], expect: (x) => x >= 5059 ? 'minecraft:cobblestone' : 'minecraft:air' },
  { name: 'stack', box: [5056, 80, 5006, 5061, 80, 5006], expect: () => 'minecraft:jungle_log[axis=y]' },
  { name: 'negative-fill', box: [-5010, 80, -5002, -5006, 81, -5001], expect: () => 'minecraft:glass' },
  { name: 'negative-paste', box: [-5010, 80, -5008, -5006, 81, -5007], expect: () => 'minecraft:glass' },
  { name: 'api-native', box: [5000, 82, 5004, 5003, 82, 5005], expect: () => 'minecraft:birch_log[axis=y]' },
  { name: 'api-bulk', box: [5008, 82, 5004, 5011, 82, 5005], expect: () => 'minecraft:redstone_lamp[lit=false]' },
  { name: 'coordinated', box: [5016, 82, 5004, 5019, 82, 5005], expect: () => 'minecraft:glowstone' },
  // Outside the lifecycle allowlist: skipped and reported, the world left as it was.
  { name: 'unsupported-skip', box: [5022, 82, 5004, 5023, 82, 5004], expect: () => 'minecraft:air' }
]

module.exports = ({ rig, check, paced }) => {
  let revoked
  const receipts = async (send, words) => JSON.parse(await send(`fawes10 receipts${words ? ` ${words}` : ''}`))
  const count = async (send, box, block) => JSON.parse(await send(`fawes9 count ${box.join(' ')} minecraft:${block}`)).count

  const verifyAll = async (send, suffix) => {
    for (const entry of CASES) {
      const wrong = await differences(send, entry)
      check(`${entry.name}${suffix}`, wrong.length === 0, wrong)
    }
  }
  // A route's first edit: only that route and source move, and each of its stages moves.
  const routed = (name, change, route, source, stages) => {
    const prefix = `${route}.${source}.`
    check(`${name}-route`, Object.keys(change).length > 0 && Object.keys(change).every(key => key.startsWith(prefix)) &&
      stages.every(stage => change[prefix + stage] > 0), change)
  }

  return {
    async contract (rig, send, name, { client, chatCommand: sendChatCommand }) {
      const chatCommand = paced(send, client, sendChatCommand)
      const select = async (x1, y1, z1, x2, y2, z2) => {
        await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
      }
      const tp = (x, y, z) => send(`tp ${name} ${x + 0.5} ${y} ${z + 0.5}`)
      await send(AREA)
      await send(NEGATIVE)
      await send(REVOKE_AREA)
      await send('fill 4996 79 4996 5067 79 5011 minecraft:stone')
      await send('fill 4996 80 4996 5067 84 5011 minecraft:air')
      await send('fill -5014 79 -5014 -5002 79 -4998 minecraft:stone')
      await send('fill -5014 80 -5014 -5002 84 -4998 minecraft:air')
      for (let x = 5006; x <= 5009; x++) for (let z = 5000; z <= 5001; z++) {
        await send(`setblock ${x} 80 ${z} minecraft:${(x + z) % 2 === 0 ? 'dirt' : 'glass'}`)
      }
      for (const [x, , z] of cells([5018, 80, 5000, 5025, 80, 5001])) {
        if (((x - 5018) + (z - 5000) * 8) % 5 === 0) await send(`setblock ${x} 80 ${z} minecraft:gold_block`)
      }
      await send('setblock 5030 80 5000 minecraft:furnace[facing=east]')
      await send('setblock 5031 80 5000 minecraft:stone')
      await send('setblock 5032 80 5000 minecraft:oak_log[axis=x]')
      await send('fill 5050 80 5000 5051 80 5000 minecraft:gold_block')
      await send('fill 5056 80 5000 5057 80 5000 minecraft:cobblestone')
      await send('fill 5056 80 5006 5057 80 5006 minecraft:jungle_log')
      await probe(send, 'watch -5100 0 -5100 5100 255 5100')

      // A player's command: every cell is the player's, through every native stage.
      let before = await probe(send, 'coverage')
      await receipts(send, 'clear')
      await select(5000, 80, 5000, 5003, 81, 5001)
      await chatCommand('/set stone', /Operation completed/)
      routed('fill', delta(before, await probe(send, 'coverage')), 'native', 'PLAYER', NATIVE_STAGES)
      const own = (await receipts(send)).receipts
      // One chunk: one unit holding all 16 cells.
      check('fill-attributed-player', own.length === 1 && applied(own) === 16 && own.every(receipt => receipt.source === 'PLAYER'),
        own.slice(0, 3))

      await select(5006, 80, 5000, 5009, 80, 5001)
      await chatCommand('/replace dirt stone', /blocks have been replaced/)
      await select(5012, 80, 5000, 5015, 80, 5001)
      await chatCommand('/set 50%oak_planks,50%glass', /Operation completed/)
      await select(5018, 80, 5000, 5025, 80, 5001)
      await chatCommand('/replace gold_block diamond_block', /blocks have been replaced/)

      await select(5030, 80, 5000, 5032, 80, 5000)
      // The player stands in air beside the source: inside a block the client pushes them out,
      // which moves the copy origin.
      await tp(5029, 80, 5000)
      await chatCommand('/copy', /blocks were copied|blocks affected/)
      await tp(5029, 80, 5004)
      await chatCommand('/paste', /clipboard has been pasted/)
      await chatCommand('/rotate 90', /has been rotated/)
      await tp(5040, 80, 5003)
      await chatCommand('/paste', /clipboard has been pasted/)
      await chatCommand('/clearclipboard', /Clipboard cleared/)

      // FAWE's cut is lazy: the source clears only when the clipboard is pasted.
      await select(5050, 80, 5000, 5051, 80, 5000)
      await tp(5049, 80, 5000)
      await chatCommand('/cut', /will be removed on paste|blocks affected/)
      await tp(5049, 80, 5004)
      await chatCommand('/paste', /clipboard has been pasted/)
      await chatCommand('/clearclipboard', /Clipboard cleared/)

      await select(5056, 80, 5000, 5057, 80, 5000)
      await chatCommand('/move 3 east', /blocks moved/)
      await select(5056, 80, 5006, 5057, 80, 5006)
      await chatCommand('/stack 2 east', /blocks changed/)

      // Negative coordinates, across the x=-5008 chunk border.
      await select(-5010, 80, -5002, -5006, 81, -5001)
      await chatCommand('/set glass', /Operation completed/)
      await tp(-5011, 80, -5002)
      await chatCommand('/copy', /blocks were copied|blocks affected/)
      await tp(-5011, 80, -5008)
      await chatCommand('/paste', /clipboard has been pasted/)
      await chatCommand('/clearclipboard', /Clipboard cleared/)

      // A mod using WorldEdit's session API off the server thread: the player's edit, but as API.
      before = await probe(send, 'coverage')
      await receipts(send, 'clear')
      const api = (await probe(send, `api native ${name} minecraft:birch_log 5000 82 5004 5003 82 5005`)).id
      await rig.wait('API session edit', async () => (await probe(send, `api result ${api}`)).done, 60000, [client.entry])
      const apiResult = await probe(send, `api result ${api}`)
      check('api-native-result', !apiResult.error && apiResult.changed === 8, apiResult)
      routed('api-native', delta(before, await probe(send, 'coverage')), 'native', 'API', NATIVE_STAGES)
      const apiReceipts = (await receipts(send)).receipts
      check('api-native-attributed-api', apiReceipts.length === 1 && applied(apiReceipts) === 8 &&
        apiReceipts.every(receipt => receipt.source === 'API'),
        apiReceipts.slice(0, 3))

      before = await probe(send, 'coverage')
      const bulk = (await probe(send, `api bulk ${name} minecraft:redstone_lamp 5008 82 5004 5011 82 5005`)).id
      await rig.wait('API coordinated edit', async () => (await probe(send, `api result ${bulk}`)).done, 60000, [client.entry])
      const bulkResult = await probe(send, `api result ${bulk}`)
      check('api-bulk-result', !bulkResult.error && bulkResult.changed === 8, bulkResult)
      routed('api-bulk', delta(before, await probe(send, 'coverage')), 'bulk', 'API', BULK_STAGES)

      before = await probe(send, 'coverage')
      await select(5016, 82, 5004, 5019, 82, 5005)
      await chatCommand('fawe-coordinated set glowstone', /Coordinated edit complete: 8 changes/)
      routed('coordinated', delta(before, await probe(send, 'coverage')), 'bulk', 'PLAYER', BULK_STAGES)
      await select(5022, 82, 5004, 5023, 82, 5004)
      await chatCommand('/set bricks', /Skipped 2 unsupported blocks/)

      // Losing permission while the final flush publishes: reported, remembered exactly, undoable.
      const historyBefore = JSON.parse(await send(`fawes10 history ${name}`)).entries.length
      await select(...FINAL)
      const finalOffset = fs.statSync(client.entry.log).size
      await probe(send, `deop-on-publish ${name}`)
      await client.send({ action: 'command', command: '/set gold_block' })
      let finalLast = -1
      await rig.wait('final-flush leg settled', async () => {
        const now = await count(send, FINAL, 'gold_block')
        const settled = now > 0 && now === finalLast
        finalLast = now
        return settled
      }, 30000, [client.entry])
      check('final-flush-partial', FINAL_PREFIXES.includes(finalLast), { written: finalLast })
      let finalChat = ''
      for (const end = Date.now() + 10000; Date.now() < end;) {
        finalChat = fs.readFileSync(client.entry.log, 'utf8').slice(finalOffset)
        if (/Stopped: the player disconnected or no longer has permission/.test(finalChat)) break
        await new Promise(resolve => setTimeout(resolve, 250))
      }
      check('final-flush-reported', /Stopped: the player disconnected or no longer has permission/.test(finalChat), {})
      await send(`op ${name}`)
      let finalHistory
      await rig.wait('final-flush edit remembered', async () => {
        finalHistory = JSON.parse(await send(`fawes10 history ${name}`)).entries
        return finalHistory.length > historyBefore
      }, 10000, [client.entry]).catch(() => {})
      check('final-flush-history-exact', finalHistory.length === historyBefore + 1 &&
        finalHistory.at(-1).size === finalLast, { historyBefore, entries: finalHistory.length, last: finalHistory.at(-1) })
      await chatCommand('/undo', /Undid/)
      check('final-flush-undo-exact', await count(send, FINAL, 'gold_block') === 0, {})

      // The player's command is asked again per cell: losing it part-way stops the rest, and the
      // history holds exactly what was written.
      await select(...REVOKE)
      const chatOffset = fs.statSync(client.entry.log).size
      await client.send({ action: 'command', command: '/set stripped_oak_log' })
      await rig.wait('revoke leg part-written', async () => await count(send, REVOKE, 'stripped_oak_log') > 0, 20000, [client.entry])
      await send(`deop ${name}`)
      let last = -1
      await rig.wait('revoke leg settled', async () => {
        const now = await count(send, REVOKE, 'stripped_oak_log')
        const settled = now === last
        last = now
        return settled
      }, 30000, [client.entry])
      revoked = last
      check('revoked-partial', revoked > 0 && revoked < 1024 && revoked % 16 === 0, { revoked })
      // The refusal can reach the client log just after the count settles; wait up to 10 s.
      let reported = false
      for (const end = Date.now() + 10000; !reported && Date.now() < end;) {
        reported = /Stopped: the player disconnected or no longer has permission/
          .test(fs.readFileSync(client.entry.log, 'utf8').slice(chatOffset))
        if (!reported) await new Promise(resolve => setTimeout(resolve, 250))
      }
      check('revoked-reported', reported, { reported })
      await send(`op ${name}`)
      // FAWE reports the stop before its finally block flushes and remembers the session.
      let entry
      await rig.wait('revoked edit remembered', async () => {
        entry = JSON.parse(await send(`fawes10 history ${name}`)).entries.at(-1)
        return entry && entry.size === revoked
      }, 10000, [client.entry]).catch(() => {})
      check('revoked-history-exact', entry && entry.size === revoked, entry)

      await verifyAll(send, '')
      const coverage = await probe(send, 'coverage')
      check('every-route-every-stage', ROUTES.every(([route, source]) =>
        (route === 'bulk' ? BULK_STAGES : NATIVE_STAGES).every(stage => coverage[`${route}.${source}.${stage}`] > 0)), coverage)
      const writes = await probe(send, 'writes')
      check('no-unguarded-writes', Object.keys(writes.counts).every(key => !/\.unguarded\.|offthread/.test(key)), writes)
      await send('save-all flush')
    },
    async restart (rig, send, name, { client, chatCommand: sendChatCommand }) {
      const chatCommand = paced(send, client, sendChatCommand)
      await send(AREA)
      await send(NEGATIVE)
      await send(REVOKE_AREA)
      await verifyAll(send, '-after-restart')
      check('revoked-after-restart', await count(send, REVOKE, 'stripped_oak_log') === revoked, { revoked })
      await chatCommand('/undo', /Undid/)
      check('revoked-undo-exact', await count(send, REVOKE, 'stripped_oak_log') === 0, {})
      // The comparison fails on a value planted against it, at exactly that cell.
      await send('setblock 5001 80 5000 minecraft:dirt')
      const planted = await differences(send, CASES[0])
      check('detects-known-wrong', planted.length === 1 && planted[0].x === 5001 && planted[0].actual === 'minecraft:dirt', planted)
      await send('setblock 5001 80 5000 minecraft:stone')
    }
  }
}
