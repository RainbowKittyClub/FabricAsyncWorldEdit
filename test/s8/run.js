'use strict'

// S8 audit lane: coordinated edits against the RKC Ledger fork, checked by reading Ledger's own
// SQLite file directly (node:sqlite), never through Ledger's code.

const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')
const { DatabaseSync } = require('node:sqlite')
const { Rig, SingleLaneLease, admitResources, RESOURCE_POLICY, writeJson, verify, hash, tool, GateError } = require('../harness/rig')
const { smoke } = require('../harness/smoke')
const repo = path.resolve(__dirname, '../..')
const workspace = '/home/kgadberry/projects/minecraft-dev'
const manifestFile = path.join(__dirname, 'manifest.json')
const manifest = JSON.parse(fs.readFileSync(manifestFile))
const evidence = '/mnt/WORKSPACE/scratchpad/fawe-s8-evidence'
const profile = 'audit'
// Small enough that four 2048-cell edits fill the producer queue while Ledger is stalled.
const LEDGER_LIMITS = 'producerMaxBatchActions = 4096\nproducerMaxPendingActions = 8192\nproducerMaxPendingBatches = 64\n'

const ROWS = `SELECT a.id, ai.action_identifier AS action, a.x, a.y, a.z, w.identifier AS world,
  o.identifier AS object, oo.identifier AS oldObject, a.block_state AS state, a.old_block_state AS oldState,
  s.name AS source, p.player_name AS player, a.extra_data AS extra
  FROM actions a JOIN ActionIdentifiers ai ON ai.id = a.action_id JOIN worlds w ON w.id = a.world_id
  JOIN ObjectIdentifiers o ON o.id = a.object_id JOIN ObjectIdentifiers oo ON oo.id = a.old_object_id
  JOIN sources s ON s.id = a.source LEFT JOIN players p ON p.id = a.player_id`

function uuidOf (value) {
  if (typeof value === 'string') return value.toLowerCase()
  const hex = Buffer.from(value).toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

// Asynchronous faweaudit results are polled until the probe stops reporting them pending.
async function pollResult (rig, audit, key, liveness = []) {
  let value
  await rig.wait(`faweaudit ${key}`, async () => !(value = await audit(`result ${key}`)).pending, 30000, liveness)
  return value
}

// A WAL reader can still meet a brief lock while Ledger opens or checkpoints; wait it out.
function query (dbFile, sql, ...params) {
  const db = new DatabaseSync(dbFile, { readOnly: true, timeout: 10000 })
  try { return db.prepare(sql).all(...params) } finally { db.close() }
}

async function main () {
  fs.mkdirSync(evidence, { recursive: true })
  const recordFile = path.join(evidence, `s8-${Date.now()}.json`)
  const report = { accepted: false, suite: 's8-audit', profile, resourcePolicy: RESOURCE_POLICY, samples: [], checks: [] }
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
    report.sourceHashes = Object.fromEntries(['test/s8/run.js', 'test/s8/pin.py'].map(name => [name, hash(path.join(repo, name))]))
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: report.samples })
    rig = new Rig('/mnt/WORKSPACE/scratchpad/fawe/fawe-fast-editing-20261029-s8', manifest, manifestFile, admission)
    report.admission = admission
    report.root = rig.root
    console.log(JSON.stringify({ root: rig.root }))
    const dbFile = path.join(rig.root, 'server/world/ledger.sqlite')
    const check = (name, condition, detail) => {
      report.checks.push({ name, passed: Boolean(condition), detail })
      if (!condition) throw new GateError(`${name}: ${JSON.stringify(detail)}`)
    }
    // The bridge's outbox: one intent file per unpublished or unacknowledged unit, its receipt beside it.
    const outboxDir = path.join(rig.root, 'server/world/fawe/ledger-outbox')
    const quarantineDir = path.join(outboxDir, 'quarantine')
    const unitsIn = dir => fs.existsSync(dir)
      ? fs.readdirSync(dir).filter(name => name.endsWith('.intent.json')).map(name => name.slice(0, -'.intent.json'.length))
      : []
    const unitFile = (dir, id, kind) => path.join(dir, `${id}.${kind}.json`)
    const readUnit = id => ({
      id,
      intent: fs.readFileSync(unitFile(outboxDir, id, 'intent'), 'utf8'),
      receipt: fs.existsSync(unitFile(outboxDir, id, 'receipt')) ? fs.readFileSync(unitFile(outboxDir, id, 'receipt'), 'utf8') : null
    })
    const opRows = operation => query(dbFile, `${ROWS} WHERE ai.action_identifier = 'fawe-block'`)
      .filter(row => JSON.parse(row.extra).operation === operation)
    // Units the contract leaves journaled for the restart checks.
    const held = {}
    await smoke(rig, repo, workspace, profile, undefined, {
      jvmArgs: ['-Dfawe.experimental.coordinated=true'],
      async configureServer (serverDir) {
        // Ledger copies its default config on first start; seed that copy with the lane's limits.
        const defaults = tool('unzip', ['-p', manifest.availableDependencies['ledger-rkc'].path, 'ledger.toml'])
        if (!defaults.includes('[database]\n')) throw new GateError('Ledger default config changed shape', 2)
        fs.mkdirSync(path.join(serverDir, 'config'), { recursive: true })
        fs.writeFileSync(path.join(serverDir, 'config/ledger.toml'), defaults.replace('[database]\n', `[database]\n${LEDGER_LIMITS}`))
      },
      async contract (rig, send, name, { client, chatCommand: sendChatCommand, expected }) {
        let lastCommandTick
        const clock = async () => {
          const match = (await send('time query gametime')).match(/^The game time is (\d+) tick\(s\)$/)
          if (!match) throw new GateError('Unexpected tick clock')
          return Number(match[1])
        }
        const chatCommand = async (command, response) => {
          if (lastCommandTick !== undefined) {
            await rig.wait('S8 command pacing', async () => await clock() >= lastCommandTick + 21, 10000, [client.entry])
          }
          lastCommandTick = await clock()
          await sendChatCommand(command, response)
        }
        const audit = async words => JSON.parse(await send(`faweaudit ${words}`))
        const result = key => pollResult(rig, audit, key, [client.entry])
        const select = async (x1, y1, z1, x2, y2, z2) => {
          await chatCommand(`/pos1 ${x1},${y1},${z1}`, /First position set|Position already set/)
          await chatCommand(`/pos2 ${x2},${y2},${z2}`, /Second position set|Position already set/)
        }
        const faweRows = () => query(dbFile, `${ROWS} WHERE ai.action_identifier LIKE 'fawe-%' ORDER BY a.id`)
        const receipts = () => query(dbFile, 'SELECT producer, operation_id AS operation, sequence, payload_hash AS hash, batch_id AS batch, action_id AS action FROM producer_receipts ORDER BY id')
          .map(row => ({ ...row, operation: uuidOf(row.operation) }))
        const extraOf = row => JSON.parse(row.extra)

        await send('forceload add 3184 3184 3423 3231')
        await rig.wait('Ledger accepting producer batches', async () => (await audit('ready')).ready, 60000, [client.entry])
        await send('fill 3199 79 3199 3210 79 3201 minecraft:stone')
        await send('fill 3200 80 3200 3209 80 3200 minecraft:air')
        const before = { fawe: faweRows().length, receipts: receipts().length }

        // 1. Exact applied rows: actor, source, states, both sides, keys and one batch receipt.
        await select(3200, 80, 3200, 3203, 80, 3200)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit complete: 4 changes/)
        const set = faweRows().slice(before.fawe)
        const keys = receipts().slice(before.receipts)
        const extras = set.map(extraOf)
        check('set-rows', set.length === 4 && set.every(row => row.action === 'fawe-block' && row.source === 'fawe' &&
          row.player === name && row.world === 'minecraft:overworld' && row.object === 'minecraft:stone' &&
          row.oldObject === 'minecraft:air' && row.y === 80 && row.z === 3200), set)
        check('set-positions', JSON.stringify(set.map(row => row.x).sort()) === '[3200,3201,3202,3203]', set.map(row => row.x))
        check('set-extra', extras.every(extra => extra.format === 'fawe-ledger-v1' && extra.kind === 'BLOCK' &&
          extra.before.id === 'minecraft:air' && extra.after.id === 'minecraft:stone' && extra.before.nbt === null &&
          extra.after.nbt === null && extra.operation === extras[0].operation), extras)
        check('set-keys', keys.length === 4 && keys.every(key => key.producer === 'fawe' && key.operation === extras[0].operation) &&
          new Set(keys.map(key => key.batch)).size === 1 &&
          JSON.stringify(keys.map(key => key.sequence).sort()) === JSON.stringify(extras.map(extra => extra.sequence).sort()) &&
          JSON.stringify(keys.map(key => key.action).sort()) === JSON.stringify(set.map(row => row.id).sort()), keys)

        // 2. NBT-bearing change: the chest's items survive in the before side; stone over stone adds nothing.
        await send('setblock 3200 80 3200 minecraft:chest[facing=north]{Items:[{Slot:0b,id:"minecraft:diamond",count:5}]}')
        const preChest = faweRows().length
        await select(3200, 80, 3200, 3203, 80, 3200)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit complete: 1 changes/)
        const chest = faweRows().slice(preChest)
        const chestExtra = chest.length === 1 ? extraOf(chest[0]) : {}
        check('nbt-before-side', chest.length === 1 && chest[0].oldObject === 'minecraft:chest' && chest[0].object === 'minecraft:stone' &&
          /minecraft:diamond/.test(chestExtra.before?.nbt || '') && chestExtra.after?.nbt === null &&
          chestExtra.before.id.startsWith('minecraft:chest['), chest)
        await select(3203, 80, 3200, 3203, 80, 3200)
        await chatCommand('fawe-coordinated set chest', /Coordinated edit complete: 1 changes/)
        const placed = faweRows().slice(preChest + 1)
        const placedExtra = placed.length === 1 ? extraOf(placed[0]) : {}
        // A fresh chest is in its default state, which Ledger's convention stores as null properties.
        check('nbt-after-side', placed.length === 1 && placed[0].object === 'minecraft:chest' &&
          placedExtra.before?.id === 'minecraft:stone' && placedExtra.after?.id.startsWith('minecraft:chest['), placed)

        // 3. No-op: an edit that changes nothing writes no rows and no keys.
        const preNoop = { fawe: faweRows().length, receipts: receipts().length }
        await select(3200, 80, 3200, 3202, 80, 3200)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit complete: 0 changes/)
        check('noop-no-rows', faweRows().length === preNoop.fawe && receipts().length === preNoop.receipts,
          { rows: faweRows().length, receipts: receipts().length, before: preNoop })

        // 4. Identical retries: the bridge's last batch redelivered, and Ledger's own retry/conflict contract.
        const captured = await audit('captured')
        const preRetry = { fawe: faweRows().length, receipts: receipts().length }
        await audit('redeliver')
        const redelivered = await result('redeliver')
        check('redeliver-no-duplicates', captured.captured && redelivered.accepted?.length === 0 &&
          JSON.stringify(redelivered.duplicates) === JSON.stringify(captured.sequences) &&
          faweRows().length === preRetry.fawe && receipts().length === preRetry.receipts, { captured, redelivered })
        const started = await audit('contract')
        const contract = await result('contract')
        const testRows = query(dbFile, `${ROWS} WHERE s.name = 'fawe-test'`)
        check('ledger-contract', JSON.stringify(contract.result?.first?.accepted) === '[0,1]' &&
          contract.result?.retry?.accepted?.length === 0 && JSON.stringify(contract.result?.retry?.duplicates) === '[0,1]' &&
          contract.result?.conflict === 'CONFLICT' && contract.receipts === 2 && testRows.length === 2 &&
          testRows.every(row => row.object === 'minecraft:stone'), { started, contract, testRows })

        // 5. Ordinary Ledger logging still works beside the bridge.
        const ordinary = () => query(dbFile, `${ROWS} WHERE a.x = 3209 AND a.y = 80 AND a.z = 3200 AND ai.action_identifier NOT LIKE 'fawe-%'`).length
        const preOrdinary = ordinary()
        await chatCommand('setblock 3209 80 3200 minecraft:gold_block', /Changed the block/)
        await rig.wait('ordinary Ledger row', () => ordinary() > preOrdinary, 30000, [client.entry])
        check('ordinary-logging', ordinary() > preOrdinary, { before: preOrdinary, after: ordinary() })

        // 6. Oversized payloads: refused before any write, world and rows unchanged. Plain text trips
        // FAWE's snapshot cap; U+2028 fits it but escapes past Ledger's extra-data limit.
        const preBig = { fawe: faweRows().length, receipts: receipts().length }
        for (const [x, args, refusal, label] of [
          [3206, '70000', /Snapshot block entity exceeds 64 KiB/, 'oversize-snapshot-refused'],
          [3207, '15000 linesep', /extra data is \d+ characters, over 65535/, 'oversize-ledger-refused']]) {
          await audit(`bigchest ${x} 80 3200 ${args}`)
          await select(x, 80, 3200, x, 80, 3200)
          await chatCommand('fawe-coordinated set stone', new RegExp('Coordinated edit REFUSED for chunk 200,200; no changes were written: .*' + refusal.source))
          await expected(x, 80, 3200, 'minecraft:chest[facing=north,type=single,waterlogged=false]')
          check(label, faweRows().length === preBig.fawe && receipts().length === preBig.receipts, preBig)
          // The snapshot covers every block entity in the chunk, so a left-over chest refuses the next edit.
          await send(`setblock ${x} 80 3200 minecraft:air`)
        }

        // 7. Backpressure while Ledger is stalled (save-off holds its database writes).
        // 7a. Ledger's queue full: two private 4096-action batches fill it; a FAWE edit is then refused
        // before writing, and the queued batches commit once writes resume.
        const cell = index => 3264 + 16 * index
        for (let index = 0; index < 10; index++) await send(`fill ${cell(index)} 80 3200 ${cell(index) + 3} 80 3200 minecraft:air`)
        await send('save-off')
        await audit('fill 2 4096')
        await rig.wait('Ledger queue full', async () => {
          const state = await audit('ready')
          return !state.ready && /queue is full/.test(state.reason)
        }, 30000, [client.entry])
        const preFull = faweRows().length
        await select(cell(9), 80, 3200, cell(9) + 3, 80, 3200)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit REFUSED for chunk \d+,200; no changes were written: .*queue is full/)
        await expected(cell(9), 80, 3200, 'minecraft:air')
        check('ledger-queue-full-refused', faweRows().length === preFull, { rows: faweRows().length, preFull })
        await send('save-on')
        const filled = await result('fill')
        await rig.wait('Ledger queue drained', async () => (await audit('ready')).ready, 60000, [client.entry])
        check('ledger-queue-drained', filled.length === 2 && filled.every(ack => ack.accepted?.length === 4096), filled.map(ack => ack.accepted?.length ?? ack))

        // 7b. FAWE's admission full: the first edit publishes and waits for its record, so the preparation
        // worker holds; seven more are admitted and wait unpublished; the ninth is refused. All eight
        // complete with their rows once writes resume.
        const preStall = faweRows().length
        await send('save-off')
        for (let index = 0; index < 8; index++) {
          await select(cell(index), 80, 3200, cell(index) + 3, 80, 3200)
          await chatCommand('fawe-coordinated set stone', /Coordinated edit admitted/)
        }
        await select(cell(8), 80, 3200, cell(8) + 3, 80, 3200)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit failed after 0 recorded changes in chunk \d+,200: Operation admission is closed, full/)
        for (let index = 1; index < 9; index++) await expected(cell(index), 80, 3200, 'minecraft:air')
        check('fawe-admission-refused', faweRows().length === preStall, { rows: faweRows().length, preStall })
        await send('save-on')
        await rig.wait('stalled edits committed', () => faweRows().length === preStall + 8 * 4, 120000, [client.entry])
        for (let index = 0; index < 8; index++) await expected(cell(index), 80, 3200, 'minecraft:stone')
        await expected(cell(8), 80, 3200, 'minecraft:air')
        check('fawe-admission-drained', faweRows().length === preStall + 8 * 4 &&
          query(dbFile, `${ROWS} WHERE a.x BETWEEN ? AND ? AND ai.action_identifier = 'fawe-block'`, cell(8), cell(8) + 3).length === 0,
          { rows: faweRows().length - preStall })

        // 8. Acknowledged units leave nothing behind in the outbox.
        check('outbox-cleared', unitsIn(outboxDir).length === 0, unitsIn(outboxDir))

        // 9. Full disk: the intent cannot be journaled, so the edit is refused before any write; once the
        // disk takes writes again the same edit goes through.
        await send('fill 3344 80 3204 3347 80 3204 minecraft:air')
        const preDisk = faweRows().length
        await audit('diskfull on')
        await select(3344, 80, 3204, 3347, 80, 3204)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit REFUSED for chunk 209,200; no changes were written: Ledger audit outbox could not record the intent: No space left on device/)
        await expected(3344, 80, 3204, 'minecraft:air')
        check('disk-full-refused', faweRows().length === preDisk && unitsIn(outboxDir).length === 0,
          { rows: faweRows().length - preDisk, units: unitsIn(outboxDir) })
        await audit('diskfull off')
        await chatCommand('fawe-coordinated set stone', /Coordinated edit complete: 4 changes/)
        check('disk-full-recovered', faweRows().length === preDisk + 4 && unitsIn(outboxDir).length === 0,
          { rows: faweRows().length - preDisk, units: unitsIn(outboxDir) })

        // 10. Database outage: the edit publishes, its intent and receipt stay journaled and no row is
        // written; once Ledger answers again the rows land and the outbox clears.
        await send('fill 3344 80 3206 3347 80 3206 minecraft:air')
        const preOutage = faweRows().length
        await audit('dbdown on')
        await select(3344, 80, 3206, 3347, 80, 3206)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit admitted/)
        await rig.wait('receipt journaled during the outage', () => unitsIn(outboxDir).some(id => readUnit(id).receipt), 30000, [client.entry])
        held.acknowledged = readUnit(unitsIn(outboxDir)[0])
        const outageIntent = JSON.parse(held.acknowledged.intent)
        const outageReceipt = JSON.parse(held.acknowledged.receipt)
        await expected(3344, 80, 3206, 'minecraft:stone')
        check('db-outage-held', faweRows().length === preOutage && unitsIn(outboxDir).length === 1 &&
          outageReceipt.applied.length === 4 && outageIntent.deltas.length === 4 &&
          outageIntent.deltas.every(delta => delta.before.id === 'minecraft:air' && delta.after.id === 'minecraft:stone'),
          { rows: faweRows().length - preOutage, intent: outageIntent, receipt: outageReceipt })
        await audit('dbdown off')
        await rig.wait('outage rows committed', () => faweRows().length === preOutage + 4 && unitsIn(outboxDir).length === 0, 60000, [client.entry])
        const outageRows = opRows(outageIntent.origin.operation)
        check('db-outage-drained', outageRows.length === 4 && outageRows.every(row => row.player === name), outageRows)

        // 11. Another outage holds unit B when the server stops; beforeStop journals crafted units beside it.
        await send('fill 3344 80 3208 3347 80 3208 minecraft:air')
        await audit('dbdown on')
        await select(3344, 80, 3208, 3347, 80, 3208)
        await chatCommand('fawe-coordinated set stone', /Coordinated edit admitted/)
        await rig.wait('receipt journaled for the held unit', () => unitsIn(outboxDir).some(id => readUnit(id).receipt), 30000, [client.entry])
        held.pending = readUnit(unitsIn(outboxDir)[0])
        check('held-unit-journaled', unitsIn(outboxDir).length === 1 &&
          opRows(JSON.parse(held.pending.intent).origin.operation).length === 0, held.pending.id)
        rig.results.push({ gate: 's8-audit-ingestion', status: 'passed', checks: report.checks.map(entry => entry.name) })
      },
      // Crafted from the held unit's real files: an intent over cells still at before (never published),
      // an intent with no receipt over cells already at after, and a receipt over cells a later edit
      // changed; plus the acknowledged unit's files restored after a later edit at its cells.
      async beforeStop (rig, send) {
        await send('fill 3360 80 3210 3363 80 3210 minecraft:air')
        await send('fill 3364 80 3210 3367 80 3210 minecraft:stone')
        await send('fill 3368 80 3210 3371 80 3210 minecraft:gold_block')
        await send('setblock 3344 80 3206 minecraft:gold_block')
        const craft = (x, withReceipt) => {
          const id = crypto.randomUUID()
          const intent = JSON.parse(held.pending.intent)
          intent.id = id
          intent.origin.operation = crypto.randomUUID()
          intent.chunkX = x >> 4
          for (const delta of intent.deltas) {
            delta.x += x - 3344
            delta.z = 3210
          }
          fs.writeFileSync(unitFile(outboxDir, id, 'intent'), JSON.stringify(intent))
          if (withReceipt) fs.writeFileSync(unitFile(outboxDir, id, 'receipt'), JSON.stringify({ ...JSON.parse(held.pending.receipt), id }))
          return { id, operation: intent.origin.operation }
        }
        held.unpublished = craft(3360, false)
        held.unreceipted = craft(3364, false)
        held.overwritten = craft(3368, true)
        for (const kind of ['intent', 'receipt']) fs.writeFileSync(unitFile(outboxDir, held.acknowledged.id, kind), held.acknowledged[kind])
        report.beforeRestart = {
          rows: query(dbFile, 'SELECT count(*) AS n FROM actions')[0].n,
          receipts: query(dbFile, 'SELECT count(*) AS n FROM producer_receipts')[0].n,
          batches: query(dbFile, 'SELECT count(*) AS n FROM producer_batches')[0].n,
          units: unitsIn(outboxDir)
        }
      },
      // Recovery finishes every journaled unit, acknowledged keys survive, and Ledger records again.
      async restart (rig, send) {
        const audit = async words => JSON.parse(await send(`faweaudit ${words}`))
        await rig.wait('Ledger accepting after restart', async () => (await audit('ready')).ready, 60000)
        await rig.wait('outbox recovered', () => unitsIn(outboxDir).length === 0, 60000)
        const quarantined = unitsIn(quarantineDir)
        const reasons = Object.fromEntries(quarantined.map(id =>
          [id, fs.readFileSync(path.join(quarantineDir, `${id}.reason.txt`), 'utf8').trim()]))
        const pending = JSON.parse(held.pending.intent).origin.operation
        const acknowledged = JSON.parse(held.acknowledged.intent).origin.operation
        const after = {
          receipts: query(dbFile, 'SELECT count(*) AS n FROM producer_receipts')[0].n,
          batches: query(dbFile, 'SELECT count(*) AS n FROM producer_batches')[0].n,
          schema: query(dbFile, "SELECT version FROM ledger_schema_versions WHERE component = 'producer_batches'")
        }
        check('restart-keys-kept', after.receipts === report.beforeRestart.receipts + 4 &&
          after.batches === report.beforeRestart.batches + 1 && after.schema.length === 1 && after.schema[0].version === 1,
          { before: report.beforeRestart, after })
        check('restart-recorded-resubmitted', opRows(pending).length === 4 && !quarantined.includes(held.pending.id),
          { rows: opRows(pending).length, quarantined })
        check('restart-acknowledged-cleaned', opRows(acknowledged).length === 4 && !quarantined.includes(held.acknowledged.id),
          { rows: opRows(acknowledged).length, quarantined })
        check('restart-unpublished-dropped', opRows(held.unpublished.operation).length === 0 &&
          !quarantined.includes(held.unpublished.id) && (await send('execute if block 3360 80 3210 minecraft:air')) === 'Test passed',
          { quarantined })
        check('restart-ambiguous-quarantined', quarantined.length === 2 &&
          [held.unreceipted, held.overwritten].every(unit => quarantined.includes(unit.id) && opRows(unit.operation).length === 0) &&
          /intent without receipt but the world changed/.test(reasons[held.unreceipted.id]) &&
          /receipt recorded but the world differs/.test(reasons[held.overwritten.id]), { quarantined, reasons })
        const started = await audit('contract')
        const contract = await pollResult(rig, audit, 'contract')
        check('restart-ledger-contract', JSON.stringify(contract.result?.first?.accepted) === '[0,1]' &&
          contract.result?.conflict === 'CONFLICT', { started, contract })
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
