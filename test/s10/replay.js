'use strict'

// S10 replay lane: real `/ledger rollback` and `/ledger restore` commands against the RKC Ledger fork
// with FAWE's replay guard and OPAC, checked by reading Ledger's SQLite file directly (node:sqlite).
// Each leg names the world cells, the original rows' flags and the replay's own audit row.

const fs = require('node:fs')
const path = require('node:path')
const { DatabaseSync } = require('node:sqlite')
const { Rig, SingleLaneLease, admitResources, RESOURCE_POLICY, writeJson, verify, hash, tool, GateError } = require('../harness/rig')
const { smoke } = require('../harness/smoke')
const repo = path.resolve(__dirname, '../..')
const workspace = '/home/kgadberry/projects/minecraft-dev'
const manifestFile = path.join(__dirname, 'manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestFile))
const evidence = '/mnt/WORKSPACE/scratchpad/fawe-s10-evidence'
const profile = 'replay'

const ROWS = `SELECT a.id, ai.action_identifier AS action, a.x, a.y, a.z, a.rolled_back AS rolledBack,
  s.name AS source, a.extra_data AS extra
  FROM actions a JOIN ActionIdentifiers ai ON ai.id = a.action_id JOIN sources s ON s.id = a.source`

function query (dbFile, sql, ...params) {
  const db = new DatabaseSync(dbFile, { readOnly: true, timeout: 10000 })
  try { return db.prepare(sql).all(...params) } finally { db.close() }
}

async function main () {
  fs.mkdirSync(evidence, { recursive: true })
  const recordFile = path.join(evidence, `s10-${profile}-${Date.now()}.json`)
  const report = { accepted: false, suite: 's10-replay', profile, resourcePolicy: RESOURCE_POLICY, samples: [], checks: [] }
  let rig, lease, problem
  try {
    for (const side of ['client', 'server', 'metadata', 'assetIndex']) verify(manifest.minecraft[side])
    for (const input of [...manifest.minecraft.libraries, ...manifest.loader.libraries]) verify(input)
    for (const [name, input] of Object.entries(manifest.helpers)) {
      if (hash(path.join(workspace, 'scripts/loadtest/lib', `${name}.js`)) !== input.sha256) throw new GateError('Helper drift', 2)
    }
    for (const key of manifest.profiles[profile]) verify(manifest.availableDependencies[key])
    if (!/^eula=true\s*$/m.test(fs.readFileSync(verify(manifest.eula), 'utf8'))) throw new GateError('Authorized EULA missing', 2)
    for (const binary of ['java', 'javac', 'jar', 'unzip', 'Xvfb', 'xauth', 'vulkaninfo']) tool('sh', ['-c', 'command -v "$1"', 'sh', binary])
    if (!process.env.TMPDIR || !process.env.VK_DRIVER_FILES || !fs.existsSync(process.env.VK_DRIVER_FILES)) throw new GateError('Use pinned Nix shell', 2)
    report.sourceHashes = Object.fromEntries(['test/s10/replay.js', 'test/s10/pin.py', 'test/harness/smoke.js']
      .map(name => [name, hash(path.join(repo, name))]))
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: report.samples })
    rig = new Rig('/mnt/WORKSPACE/scratchpad/fawe/fawe-fast-editing-20261029-s10-replay', manifest, manifestFile, admission)
    report.admission = admission
    report.root = rig.root
    console.log(JSON.stringify({ root: rig.root }))
    const dbFile = path.join(rig.root, 'server/world/ledger.sqlite')
    // S10_SOFT=1 records every check and fails at the end, for red runs that map all differences.
    const check = (name, condition, detail) => {
      report.checks.push({ name, passed: Boolean(condition), detail })
      if (!condition && !process.env.S10_SOFT) throw new GateError(`${name}: ${JSON.stringify(detail)}`)
    }
    const rowsAt = (action, x1, x2, z, y = 80) => query(dbFile,
      `${ROWS} WHERE ai.action_identifier = ? AND a.x BETWEEN ? AND ? AND a.y = ? AND a.z = ? ORDER BY a.id`, action, x1, x2, y, z)
    // Ledger also logs the lane's own fill and setblock commands, as source "command".
    const playerRowsAt = (...args) => rowsAt(...args).filter(row => row.source === 'player')
    const replays = () => query(dbFile, `${ROWS} WHERE ai.action_identifier = 'ledger-replay' ORDER BY a.id`)
      .map(row => ({ ...row, extra: JSON.parse(row.extra) }))
    const settle = () => new Promise(resolve => setTimeout(resolve, 3000))
    let flagged
    await smoke(rig, repo, workspace, profile, undefined, {
      jvmArgs: ['-Dfawe.experimental.coordinated=true'],
      async contract (rig, send, name, { client, chatCommand: sendChatCommand }) {
        let lastCommandTick
        const clock = async () => {
          const match = (await send('time query gametime')).match(/^The game time is (\d+) tick\(s\)$/)
          if (!match) throw new GateError('Unexpected tick clock')
          return Number(match[1])
        }
        const chatCommand = async (command, response) => {
          if (lastCommandTick !== undefined) {
            await rig.wait('S10 command pacing', async () => await clock() >= lastCommandTick + 21, 10000, [client.entry])
          }
          lastCommandTick = await clock()
          await sendChatCommand(command, response)
        }
        const audit = async words => JSON.parse(await send(`faweaudit ${words}`))
        const select = async (x1, y1, z1, x2, y2, z2) => {
          await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
          await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
        }
        const blocks = async (x1, x2, z, block, y = 80) => {
          let count = 0
          for (let x = x1; x <= x2; x++) if ((await send(`execute if block ${x} ${y} ${z} minecraft:${block}`)) === 'Test passed') count++
          return count
        }
        // A replay command's reply arrives only after the flags and the audit row are committed.
        const DONE = /only applied actions were marked|No results for that search/
        const replay = async (command, x, z) => {
          await send(`tp ${name} ${x} 80 ${z}`)
          const before = replays().length
          await chatCommand(command, DONE)
          const after = replays()
          return after.length > before ? after.at(-1) : null
        }
        const counts = (row, expected) => Object.entries(expected).every(([key, value]) => row.extra.counts[key] === value)

        await send('forceload add 4384 4384 4479 4415')
        await send('forceload add 3200 3200 3215 3215')
        await rig.wait('Ledger accepting producer batches', async () => (await audit('ready')).ready, 60000, [client.entry])
        await send('fill 4396 79 4396 4480 79 4404 minecraft:stone')
        await send('fill 4396 80 4396 4480 84 4404 minecraft:air')
        await settle()

        // A. FAWE rows: rollback writes the before side and only then flags the rows; a second rollback
        // finds nothing; restore writes the after side and clears the flags; neither adds mutation rows.
        await select(4400, 80, 4400, 4403, 80, 4400)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit complete: 4 changes/)
        const originals = rowsAt('fawe-block', 4400, 4403, 4400)
        check('fawe-rows', originals.length === 4 && originals.every(row => row.rolledBack === 0), originals)
        const faweRowCount = () => query(dbFile, `${ROWS} WHERE ai.action_identifier LIKE 'fawe-%'`).length
        const faweBefore = faweRowCount()
        const rolled = await replay('ledger rollback action:fawe-block range:5 after:1h', 4401.5, 4400.5)
        check('fawe-rollback-world', await blocks(4400, 4403, 4400, 'air') === 4, {})
        check('fawe-rollback-flags', rowsAt('fawe-block', 4400, 4403, 4400).every(row => row.rolledBack === 1), rowsAt('fawe-block', 4400, 4403, 4400))
        check('fawe-rollback-audit', rolled && rolled.extra.mode === 'ROLLBACK' && rolled.extra.selected === 4 &&
          counts(rolled, { APPLIED: 4, CONFLICT: 0, DENIED: 0, UNSUPPORTED: 0, FAILED: 0 }) &&
          JSON.stringify(rolled.extra.actions.APPLIED.sort()) === JSON.stringify(originals.map(row => row.id).sort()), rolled)
        check('fawe-rollback-no-second', await replay('ledger rollback action:fawe-block range:5 after:1h', 4401.5, 4400.5) === null &&
          await blocks(4400, 4403, 4400, 'air') === 4, {})
        const restored = await replay('ledger restore action:fawe-block range:5 after:1h', 4401.5, 4400.5)
        check('fawe-restore', await blocks(4400, 4403, 4400, 'stone') === 4 && restored && restored.extra.mode === 'RESTORE' &&
          counts(restored, { APPLIED: 4 }) && rowsAt('fawe-block', 4400, 4403, 4400).every(row => row.rolledBack === 0), restored)
        check('replay-adds-no-mutation-rows', faweRowCount() === faweBefore, { before: faweBefore, after: faweRowCount() })
        // The console has no player for claims to decide for, so a protected replay denies it everything.
        const consoleBefore = replays().length
        await send('execute positioned 4401 80 4400 run ledger rollback action:fawe-block range:5 after:1h')
        await rig.wait('console replay recorded', async () => replays().length > consoleBefore, 15000, [client.entry])
        const fromConsole = replays().at(-1)
        check('console-denied', fromConsole.source === 'console' && counts(fromConsole, { APPLIED: 0, DENIED: 4 }) &&
          await blocks(4400, 4403, 4400, 'stone') === 4 &&
          rowsAt('fawe-block', 4400, 4403, 4400).every(row => row.rolledBack === 0), fromConsole)

        // B. A later change wins: the changed cell is a conflict and keeps its row unflagged.
        await select(4420, 80, 4400, 4421, 80, 4400)
        await chatCommand('fawe-coordinated set glass', /Coordinated edit complete: 2 changes/)
        await send('setblock 4421 80 4400 minecraft:dirt')
        const conflicted = await replay('ledger rollback action:fawe-block range:4 after:1h', 4420.5, 4400.5)
        const conflictRows = rowsAt('fawe-block', 4420, 4421, 4400)
        check('conflict-left-alone', conflicted && counts(conflicted, { APPLIED: 1, CONFLICT: 1 }) &&
          await blocks(4420, 4420, 4400, 'air') === 1 && await blocks(4421, 4421, 4400, 'dirt') === 1 &&
          conflictRows.find(row => row.x === 4420).rolledBack === 1 && conflictRows.find(row => row.x === 4421).rolledBack === 0,
        { conflicted, conflictRows })

        // C. Ordinary Ledger rows go through the same guard: block rows replay, item rows have no
        // known footprint and are refused.
        await send('fill 4440 80 4400 4441 80 4400 minecraft:stone')
        for (const x of [4440, 4441]) await audit(`ordinary place ${x} 80 4400 minecraft:stone ${name}`)
        await audit(`ordinary insert 4442 80 4400 minecraft:diamond ${name}`)
        await settle()
        check('ordinary-rows', playerRowsAt('block-place', 4440, 4441, 4400).length === 2 && playerRowsAt('item-insert', 4442, 4442, 4400).length === 1, {})
        const ordinary = await replay(`ledger rollback action:block-place range:4 after:1h source:${name}`, 4441.5, 4400.5)
        check('ordinary-rollback', ordinary && counts(ordinary, { APPLIED: 2 }) && await blocks(4440, 4441, 4400, 'air') === 2 &&
          playerRowsAt('block-place', 4440, 4441, 4400).every(row => row.rolledBack === 1), ordinary)
        const items = await replay(`ledger rollback action:item-insert range:4 after:1h source:${name}`, 4441.5, 4400.5)
        check('item-unsupported', items && counts(items, { APPLIED: 0, UNSUPPORTED: 1 }) &&
          playerRowsAt('item-insert', 4442, 4442, 4400)[0].rolledBack === 0, items)

        // D. An ordinary row in a chunk the operator may not edit: OPAC denies it, whoever made the row.
        await audit('claim')
        await send('setblock 3205 80 3205 minecraft:stone')
        await audit(`ordinary place 3205 80 3205 minecraft:stone ${name}`)
        await settle()
        const claimed = await replay(`ledger rollback action:block-place range:3 after:1h source:${name}`, 3205.5, 3205.5)
        check('claim-denied', claimed && counts(claimed, { APPLIED: 0, DENIED: 1 }) &&
          (await send('execute if block 3205 80 3205 minecraft:stone')) === 'Test passed' &&
          playerRowsAt('block-place', 3205, 3205, 3205)[0].rolledBack === 0, claimed)

        // E. Permission revoked part-way: what was applied is flagged, the rest is denied and unflagged.
        await select(4460, 80, 4400, 4475, 80, 4400)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit complete: 16 changes/)
        await send(`tp ${name} 4467.5 80 4400.5`)
        const revokeBefore = replays().length
        await client.send({ action: 'command', command: 'ledger rollback action:fawe-block range:9 after:1h' })
        await rig.wait('revoke leg part-applied', async () => await blocks(4460, 4475, 4400, 'air') > 0, 20000, [client.entry])
        await send(`deop ${name}`)
        await rig.wait('revoke leg recorded', async () => replays().length > revokeBefore, 30000, [client.entry])
        const revoked = replays().at(-1)
        const air = await blocks(4460, 4475, 4400, 'air')
        const revokeRows = rowsAt('fawe-block', 4460, 4475, 4400)
        flagged = revokeRows.filter(row => row.rolledBack === 1).length
        check('revoked-partial', air > 0 && air < 16 && flagged === air && counts(revoked, { APPLIED: air, DENIED: 16 - air }),
          { air, flagged, revoked })
        await send(`op ${name}`)
      },
      // Flags and world survive the restart together.
      async restart (rig, send) {
        await send('forceload add 4384 4384 4479 4415')
        let air = 0
        for (let x = 4460; x <= 4475; x++) if ((await send(`execute if block ${x} 80 4400 minecraft:air`)) === 'Test passed') air++
        const rows = rowsAt('fawe-block', 4460, 4475, 4400).filter(row => row.rolledBack === 1).length
        check('flags-and-world-after-restart', air === flagged && rows === flagged, { air, rows, flagged })
        const failed = report.checks.filter(entry => !entry.passed).map(entry => entry.name)
        if (failed.length) throw new GateError(`S10 replay checks failed: ${failed.join(', ')}`)
      }
    })
  } catch (error) { problem = error } finally {
    if (rig) {
      try { await rig.close() } catch (error) { problem ||= error }
      report.results = rig.results
      report.resources = rig.resourceReport()
      for (const entry of rig.children) {
        if (/\/ERROR\]|Mixin apply failed|InvalidInjectionException|Failed to handle packet|Failed to load options/.test(fs.readFileSync(entry.log, 'utf8'))) {
          problem ||= new GateError(`Runtime error in ${entry.log}`)
        }
      }
      report.processes = rig.children.map(({ child, ...entry }) => entry)
    }
    if (!rig || rig.children.every(entry => entry.stopped)) lease?.close()
    report.leaseReleased = !lease || lease.closed
    report.error = problem?.stack
    report.status = problem ? 'failed' : 'passed-subset'
    writeJson(recordFile, report)
    console.log(JSON.stringify({ recordFile, status: report.status, error: report.error }))
  }
  if (problem) throw problem
}

main().catch(error => { console.error(error.stack); process.exitCode = Number.isInteger(error.code) ? error.code : 1 })
