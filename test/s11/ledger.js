'use strict'

// S11 ledger suite: every edit route against the RKC Ledger fork, checked by reading Ledger's own
// database directly (the world's SQLite file, or Postgres for backend-db), beside an independent
// expected world, before and after a restart.

const fs = require('node:fs')
const path = require('node:path')
const { probe, differences, delta } = require('./common')
const { tool } = require('../harness/rig')

const NATIVE_STAGES = ['snapshot', 'admitted', 'prepared', 'authorized', 'published', 'reconciled', 'recorded']
const BULK_STAGES = ['ticket', ...NATIVE_STAGES]
const ROUTES = [['native', 'PLAYER'], ['native', 'API'], ['bulk', 'PLAYER'], ['bulk', 'API']]
const AREA = 'forceload add 6800 6800 6895 6863'
const ROWS = `SELECT a.id, ai.action_identifier AS action, a.x, a.y, a.z, a.rolled_back AS "rolledBack",
  o.identifier AS object, oo.identifier AS "oldObject", s.name AS source, p.player_name AS player, a.extra_data AS extra
  FROM actions a JOIN ActionIdentifiers ai ON ai.id = a.action_id JOIN ObjectIdentifiers o ON o.id = a.object_id
  JOIN ObjectIdentifiers oo ON oo.id = a.old_object_id JOIN sources s ON s.id = a.source
  LEFT JOIN players p ON p.id = a.player_id`
// One box per route, 16 cells each, chunk z 425.
const NATIVE = [6804, 80, 6804, 6807, 80, 6807]
const API_NATIVE = [6812, 80, 6804, 6815, 80, 6807]
const COORDINATED = [6820, 80, 6804, 6823, 80, 6807]
const API_BULK = [6828, 80, 6804, 6831, 80, 6807]
const ROLLED = [6836, 80, 6804, 6839, 80, 6804]
const INSPECTED = [6844, 80, 6804]
const STACKED = [6852, 80, 6804, 6853, 80, 6804]
// 16 chunks of 256 cells each.
const WIDE = [6800, 85, 6800, 6863, 85, 6863]
// One chunk whose CHANGED_CELL changes between the unit's snapshot and its publication.
const CHANGED = [6884, 80, 6820, 6887, 80, 6823]
const CHANGED_CELL = [6885, 80, 6821]
// Enclosed water in chunk 429,425; the edit opens the two cells east of it.
const WALLS = [6866, 80, 6802, 6877, 82, 6812]
const OPENED = [6869, 81, 6804, 6870, 81, 6804]
const CHEST = 'minecraft:chest[facing=north,type=single,waterlogged=false]'
const BOOK_DEFAULTS = '[book]\nchatOnly = true\n'
const BOOK_WITH_FAWE = '[book]\nchatOnly = true\nblockActions = ["block-place", "block-break", "block-change", "fawe-block"]\n'

const cellCount = ([x1, y1, z1, x2, y2, z2]) => (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1)
const CASES = [
  { name: 'native', box: NATIVE, expect: () => 'minecraft:glowstone' },
  { name: 'api-native', box: API_NATIVE, expect: () => 'minecraft:birch_log[axis=y]' },
  { name: 'coordinated', box: COORDINATED, expect: () => 'minecraft:oak_planks' },
  { name: 'api-bulk', box: API_BULK, expect: () => 'minecraft:redstone_lamp[lit=false]' },
  { name: 'rolled-restored', box: ROLLED, expect: () => 'minecraft:gold_block' },
  { name: 'inspected', box: [...INSPECTED, ...INSPECTED], expect: () => 'minecraft:diamond_block' },
  { name: 'stacked', box: STACKED, expect: () => CHEST },
  { name: 'wide', box: WIDE, expect: () => 'minecraft:stone' },
  { name: 'changed', box: CHANGED, expect: () => 'minecraft:glowstone' }
]

module.exports = ({ rig, check, paced, ledgerDb }) => {
  const outboxDir = path.join(rig.root, 'server/world/fawe/ledger-outbox')
  const ledgerConfig = path.join(rig.root, 'server/config/ledger.toml')
  const { query } = ledgerDb
  const faweRows = ([x1, y1, z1, x2, y2, z2]) => query(`${ROWS} WHERE ai.action_identifier LIKE 'fawe-%'
    AND a.x BETWEEN ? AND ? AND a.y BETWEEN ? AND ? AND a.z BETWEEN ? AND ? ORDER BY a.id`, x1, x2, y1, y2, z1, z2)
  const allFaweRows = () => query(`${ROWS} WHERE ai.action_identifier LIKE 'fawe-%'`).length
  const pending = () => fs.existsSync(outboxDir) ? fs.readdirSync(outboxDir).filter(name => name.endsWith('.intent.json')) : []
  const unique = rows => new Set(rows.map(row => `${row.x},${row.y},${row.z}`)).size
  // One row per applied cell from this player, as FAWE, from air to the block.
  const exact = (rows, box, name, block) => rows.length === cellCount(box) && unique(rows) === rows.length &&
    rows.every(row => row.action === 'fawe-block' && row.source === 'fawe' && row.player === name &&
      row.oldObject === 'minecraft:air' && row.object === block)
  const summary = rows => ({ rows: rows.length, unique: unique(rows), sample: rows.slice(0, 2) })
  // Drops the [book] section and nothing else: its lines end at the next table header.
  const withoutBook = text => {
    const kept = []
    let inBook = false
    for (const line of text.split('\n')) {
      if (/^\[[^\]]+\]\s*$/.test(line)) inBook = line.trim() === '[book]'
      if (!inBook) kept.push(line)
    }
    return kept.join('\n')
  }
  const verifyAll = async (send, suffix) => {
    for (const entry of CASES) {
      const wrong = await differences(send, entry)
      check(`${entry.name}${suffix}`, wrong.length === 0, wrong)
    }
  }
  let rowsAtStop

  // The ledger book used on the inspected cell: what UniversalLedger lists there, in chat.
  const inspect = async (send, name, client) => {
    await send(`item replace entity ${name} weapon.mainhand with minecraft:written_book[minecraft:custom_data={ledger:1b},` +
      'minecraft:written_book_content={title:"Ledger Book",author:"s11"}]')
    await send(`tp ${name} ${INSPECTED[0] + 0.5} ${INSPECTED[1] + 1} ${INSPECTED[2] + 0.5} 0 90`)
    // The client must have taken the teleport's look before it clicks, or the book meets air.
    const until = (await probe(send, 'timeline 2147483647')).tick + 10
    await rig.wait('teleport settled', async () => (await probe(send, 'timeline 2147483647')).tick >= until, 10000, [client.entry])
    const offset = fs.statSync(client.entry.log).size
    // Block history is a left-click with the book; a right-click lists the area's item actions.
    await client.send({ action: 'attack' })
    let chat = ''
    for (const end = Date.now() + 15000; Date.now() < end && !/\b(placed|No results)\b/.test(chat);) {
      await new Promise(resolve => setTimeout(resolve, 250))
      // Chat lines only, without command feedback ("Replaced 1 slot(s)" would match "placed").
      chat = fs.readFileSync(client.entry.log, 'utf8').slice(offset).split('\n')
        .filter(line => line.includes('[CHAT]') && !line.includes('[Rcon:')).join('\n')
    }
    return chat
  }

  return {
    async configureServer (serverDir) {
      // Ledger reads extension sections from its own file; the book's results go to chat.
      const manifest = JSON.parse(fs.readFileSync(path.join(__dirname, 'manifest.json')))
      const defaults = tool('unzip', ['-p', manifest.availableDependencies['ledger-rkc'].path, 'ledger.toml'])
      fs.mkdirSync(path.join(serverDir, 'config'), { recursive: true })
      fs.writeFileSync(path.join(serverDir, 'config/ledger.toml'), `${defaults.trimEnd()}\n\n${BOOK_DEFAULTS}`)
    },
    async contract (rig, send, name, { client, chatCommand: sendChatCommand }) {
      const chatCommand = paced(send, client, sendChatCommand)
      const select = async (x1, y1, z1, x2, y2, z2) => {
        await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
        await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
      }
      const rowsFor = async (box, count) => {
        await rig.wait(`${count} rows in ${box.join(',')}`, async () => faweRows(box).length >= count, 30000, [client.entry])
          .catch(() => {})
        return faweRows(box)
      }
      await send(AREA)
      await rig.wait('Ledger accepting producer batches', async () => JSON.parse(await send('faweaudit ready')).ready,
        60000, [client.entry])
      await send('fill 6800 79 6800 6895 79 6863 minecraft:stone')
      // Within vanilla's 32768-block fill limit.
      await send('fill 6800 80 6800 6895 83 6863 minecraft:air')
      await send('fill 6800 84 6800 6895 86 6863 minecraft:air')
      await send(`tp ${name} 6802.5 80 6830.5`)
      await probe(send, 'watch 6800 0 6800 6895 255 6863')
      const before = await probe(send, 'coverage')

      // Each route logs one row per applied cell, as FAWE and as the player.
      await select(...NATIVE)
      await chatCommand('/set glowstone', /Operation completed/)
      const native = await rowsFor(NATIVE, 16)
      check('native-rows', exact(native, NATIVE, name, 'minecraft:glowstone'), summary(native))
      // Undo and redo log the cells again, the states reversed and then restored.
      await chatCommand('/undo', /Undid/)
      const undone = (await rowsFor(NATIVE, 32)).slice(16)
      check('undo-rows', undone.length === 16 && undone.every(row => row.oldObject === 'minecraft:glowstone' &&
        row.object === 'minecraft:air' && row.player === name), summary(undone))
      await chatCommand('/redo', /Redid/)
      const redone = (await rowsFor(NATIVE, 48)).slice(32)
      check('redo-rows', redone.length === 16 && redone.every(row => row.oldObject === 'minecraft:air' &&
        row.object === 'minecraft:glowstone'), summary(redone))
      const nativeId = (await probe(send, `api native ${name} minecraft:birch_log ${API_NATIVE.join(' ')}`)).id
      await rig.wait('API native edit', async () => (await probe(send, `api result ${nativeId}`)).done, 60000, [client.entry])
      const apiNative = await rowsFor(API_NATIVE, 16)
      check('api-native-rows', exact(apiNative, API_NATIVE, name, 'minecraft:birch_log'), summary(apiNative))
      await select(...COORDINATED)
      await chatCommand('fawe-coordinated set oak_planks', /Coordinated edit complete: 16 changes/)
      const coordinated = await rowsFor(COORDINATED, 16)
      check('coordinated-rows', exact(coordinated, COORDINATED, name, 'minecraft:oak_planks'), summary(coordinated))
      const bulkId = (await probe(send, `api bulk ${name} minecraft:redstone_lamp ${API_BULK.join(' ')}`)).id
      await rig.wait('API bulk edit', async () => (await probe(send, `api result ${bulkId}`)).done, 60000, [client.entry])
      const apiBulk = await rowsFor(API_BULK, 16)
      check('api-bulk-rows', exact(apiBulk, API_BULK, name, 'minecraft:redstone_lamp'), summary(apiBulk))

      // 16 chunks, one unit each: every cell once.
      await select(...WIDE)
      await chatCommand('/set stone', /Operation completed/)
      const wide = await rowsFor(WIDE, cellCount(WIDE))
      check('wide-rows', exact(wide, WIDE, name, 'minecraft:stone'), { rows: wide.length, unique: unique(wide) })

      // A target cell changed after the snapshot: overwritten and logged with what it replaced, never
      // left behind as a denial.
      await send('fawes10 receipts clear')
      await probe(send, `change-on-admit ${CHANGED_CELL.join(' ')}`)
      await select(...CHANGED)
      await chatCommand('/set glowstone', /Operation completed/)
      const changed = await rowsFor(CHANGED, 16)
      const changedRow = changed.find(row => row.x === CHANGED_CELL[0] && row.z === CHANGED_CELL[2])
      const changedDenied = JSON.parse(await send('fawes10 receipts')).receipts.reduce((sum, receipt) => sum + receipt.denied, 0)
      check('changed-cell-written', changed.length === 16 && unique(changed) === 16 && changedDenied === 0 &&
        changedRow?.oldObject === 'minecraft:stone' && changedRow?.object === 'minecraft:glowstone',
      { ...summary(changed), denied: changedDenied, changedRow })

      // Neighbour updates release water; only the edit's own two cells are FAWE rows.
      await send(`fill ${WALLS.join(' ')} minecraft:stone`)
      await send('setblock 6868 81 6804 minecraft:water')
      await chatCommand('/perf neighbors on', /set to|is already/)
      await select(...OPENED)
      await chatCommand('/set air', /Operation completed/)
      await chatCommand('/perf neighbors off', /set to|is already/)
      const until = (await probe(send, 'timeline 2147483647')).tick + 40
      await rig.wait('water flows', async () => (await probe(send, 'timeline 2147483647')).tick >= until, 20000, [client.entry])
      check('callback-water-flowed', (await send('execute if block 6870 81 6804 minecraft:water')) === 'Test passed', {})
      const walled = await rowsFor(WALLS, 2)
      check('callback-rows-own-cells', walled.length === 2 && walled.every(row => row.y === 81 && row.z === 6804 &&
        row.x >= 6869 && row.x <= 6870 && row.object === 'minecraft:air' && row.oldObject === 'minecraft:stone'), summary(walled))

      // A stacked chest's row carries its items.
      await send(`setblock ${STACKED.slice(0, 3).join(' ')} minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:5}]}`)
      await select(...STACKED.slice(0, 3), ...STACKED.slice(0, 3))
      await chatCommand('/stack 1 east', /blocks changed/)
      const stacked = (await rowsFor([...STACKED.slice(3), ...STACKED.slice(3)], 1))
      check('stack-row-items', stacked.length === 1 && stacked[0].object === 'minecraft:chest' &&
        /minecraft:diamond/.test(stacked[0].extra ?? ''), summary(stacked))
      const items = await send(`data get block ${STACKED.slice(3).join(' ')} Items`)
      check('stack-world-items', /minecraft:diamond/.test(items), { items })

      // Ledger rollback and restore of a //set: the world follows, the rows are flagged and cleared.
      await select(...ROLLED)
      await chatCommand('/set gold_block', /Operation completed/)
      await rowsFor(ROLLED, 4)
      const rowCount = allFaweRows()
      const DONE = /only applied actions were marked|No results for that search/
      await send(`tp ${name} 6837.5 80 6804.5`)
      await chatCommand('ledger rollback action:fawe-block range:3 after:1h', DONE)
      check('rollback-world', (await differences(send, { box: ROLLED, expect: () => 'minecraft:air' })).length === 0 &&
        faweRows(ROLLED).every(row => row.rolledBack === 1), summary(faweRows(ROLLED)))
      await chatCommand('ledger restore action:fawe-block range:3 after:1h', DONE)
      check('restore-world', (await differences(send, CASES[4])).length === 0 &&
        faweRows(ROLLED).every(row => row.rolledBack === 0), summary(faweRows(ROLLED)))
      check('replay-no-rows', allFaweRows() === rowCount, { before: rowCount, after: allFaweRows() })

      // UniversalLedger's book by default lists ordinary block actions only, so not FAWE's row.
      await select(...INSPECTED, ...INSPECTED)
      await chatCommand('/set diamond_block', /Operation completed/)
      await rowsFor([...INSPECTED, ...INSPECTED], 1)
      await send(`faweaudit ordinary place ${INSPECTED.join(' ')} minecraft:diamond_block ${name}`)
      await new Promise(resolve => setTimeout(resolve, 3000))
      const defaultBook = await inspect(send, name, client)
      check('book-default-lists-ordinary', /\bplaced\b/.test(defaultBook), { chat: defaultBook.slice(-600) })
      check('book-default-omits-fawe', /\bplaced\b/.test(defaultBook) && !/\bedited\b/.test(defaultBook),
        { chat: defaultBook.slice(-600) })

      await verifyAll(send, '')
      const coverage = delta(before, await probe(send, 'coverage'))
      check('every-route-every-stage', ROUTES.every(([route, source]) =>
        (route === 'bulk' ? BULK_STAGES : NATIVE_STAGES).every(stage => coverage[`${route}.${source}.${stage}`] > 0)), coverage)
      const writes = await probe(send, 'writes')
      check('no-unguarded-writes', Object.keys(writes.counts).every(key => !/\.unguarded\.|offthread/.test(key)), writes)
      await rig.wait('outbox drained', async () => pending().length === 0, 30000, [client.entry]).catch(() => {})
      check('outbox-empty', pending().length === 0, pending())
      await send('save-all flush')
    },
    async beforeStop () {
      rowsAtStop = allFaweRows()
      // Configured to list FAWE's action too, for the run after the restart.
      fs.writeFileSync(ledgerConfig, `${withoutBook(fs.readFileSync(ledgerConfig, 'utf8')).trimEnd()}\n\n${BOOK_WITH_FAWE}`)
    },
    async restart (rig, send, name, { client }) {
      await send(AREA)
      await verifyAll(send, '-after-restart')
      check('no-rows-after-restart', allFaweRows() === rowsAtStop && pending().length === 0,
        { atStop: rowsAtStop, now: allFaweRows(), pending: pending() })
      const configuredBook = await inspect(send, name, client)
      check('book-configured-lists-fawe', /\bedited\b/.test(configuredBook) && /\bplaced\b/.test(configuredBook),
        { chat: configuredBook.slice(-600) })
      // The comparison fails on a value planted against it, at exactly that cell.
      await send('setblock 6804 80 6804 minecraft:dirt')
      const planted = await differences(send, CASES[0])
      check('detects-known-wrong', planted.length === 1 && planted[0].x === 6804 && planted[0].actual === 'minecraft:dirt', planted)
      await send('setblock 6804 80 6804 minecraft:glowstone')
    }
  }
}
