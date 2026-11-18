'use strict'

// First S14 bottleneck experiment, not the complete performance gate: current WNA compatibility
// commands vs explicit coordinated commands, on the same pinned vanilla server and warm chunks.
// Keep failures and the local JFR rather than changing targets or benchmarking a palette swap.
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { execFile } = require('node:child_process')
const { promisify } = require('node:util')
const { Rig, SingleLaneLease, admitResources, verify, hash, writeJson, GateError, RESOURCE_POLICY, tool } = require('../harness/rig')
const { smoke } = require('../harness/smoke')
const repo = path.resolve(__dirname, '../..')
const manifestFile = path.resolve(process.env.FAWE_S14_MANIFEST || path.join(__dirname, 'manifest.json'))
const manifest = JSON.parse(fs.readFileSync(manifestFile))
const evidence = '/mnt/WORKSPACE/scratchpad/fawe-s14-evidence'
const BOX = [8192, 80, 8192, 8319, 95, 8319]
const VOLUME = 128 * 16 * 128
const REFERENCE_OFFSET = 256
const SAMPLES = 5
const wait = ms => new Promise(resolve => setTimeout(resolve, ms))
const runFile = promisify(execFile)
const median = values => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)]
const percentile = (values, p) => [...values].sort((a, b) => a - b)[Math.max(0, Math.ceil(values.length * p) - 1)]
const visibleBox = { name: 'visible', x1: 8255, x2: 8256, y1: 80, y2: 80, z1: 8255, z2: 8256, padding: 0 }

function rss (entry) {
  const text = fs.readFileSync(`/proc/${entry.pid}/status`, 'utf8')
  return Object.fromEntries(['VmRSS', 'VmHWM'].map(key => [key, Number(new RegExp(`^${key}:\\s+(\\d+)`, 'm').exec(text)?.[1]) * 1024]))
}

function storageReserve () {
  const space = fs.statfsSync(repo)
  const availableBytes = space.bavail * space.bsize
  if (availableBytes < 2 * 1024 ** 3) throw new GateError(`Root filesystem reserve exhausted: ${availableBytes} bytes`)
  return availableBytes
}

async function main () {
  fs.mkdirSync(evidence, { recursive: true })
  const recordFile = path.join(evidence, `fabric-fill-diagnostic-${Date.now()}.json`)
  const report = { accepted: false, purpose: 'warm-fill-bottleneck-diagnostic-only', checks: [], measurements: [],
    resourcePolicy: RESOURCE_POLICY, targets: manifest.targets, fixture: { box: BOX, cells: VOLUME, chunks: 64,
      history: 'disk-on', neighborCallbacks: false, sampleCountPerRoute: SAMPLES, warmupsPerRoute: 1,
      inputPolicy: 'Every sample starts as air; paired routes write the same target block, with order alternating',
      baseline: 'current native WNA compatibility route, not the old single-cell-per-tick implementation',
      scope: 'vanilla warm fill only; no Paper, cold, masks, NBT, full stack or scaling acceptance' },
    hardware: { platform: os.platform(), release: os.release(), cpuModel: os.cpus()[0].model,
      logicalCpus: os.cpus().length, activeProcessorCount: 4, heapMaxBytes: 3 * 1024 ** 3 } }
  let rig, lease, failure, monitorFailure, monitor, monitoring = false
  const heartbeatRows = []
  const check = (name, condition, detail) => report.checks.push({ name, passed: Boolean(condition), detail })
  try {
    report.rootAvailableBytes = storageReserve()
    report.timeOriginMs = performance.timeOrigin
    report.heartbeatPolicy = 'One shared serialized RCON connection; total latency includes local queue wait, serviceMs excludes it'
    if (manifest.measurementPolicy.minimumSamplesAfterWarmup !== SAMPLES) throw new GateError('Sample policy drift')
    const profile = 'throughput-base'
    for (const key of manifest.profiles[profile]) verify(manifest.availableDependencies[key])
    report.sourceHashes = Object.fromEntries(['test/s14/fabric-fill-diagnostic.js', 'test/harness/smoke.js',
      'test/harness/native.js', 'test/harness/rig.js', 'test/harness/rcon.js'].map(file => [file, hash(path.join(repo, file))]))
    lease = new SingleLaneLease()
    rig = new Rig('/mnt/WORKSPACE/scratchpad/fawe/s14-fabric-fill', manifest, manifestFile,
      await admitResources({ samples: [] }))
    report.root = rig.root
    await smoke(rig, repo, '/home/kgadberry/projects/minecraft-dev', profile, undefined, {
      jvmArgs: ['-Dfawe.experimental.coordinated=true', '-XX:ActiveProcessorCount=4'],
      async contract (rig, sendRaw, name, { client }) {
        const server = rig.children.find(entry => entry.name === 'server-first')
        // Vanilla's RCON console output buffer is shared across connections. Serialize fixture and
        // heartbeat commands on the existing connection, not merely each socket independently.
        let requests = Promise.resolve()
        const send = (command, observed = false) => {
          const queuedAt = performance.now()
          const result = requests.then(async () => {
            const started = performance.now()
            const answer = await sendRaw(command)
            return observed ? { answer, queueMs: started - queuedAt, serviceMs: performance.now() - started } : answer
          })
          requests = result.catch(() => {})
          return result
        }
        const chat = async (command, answer) => {
          const offset = fs.statSync(client.entry.log).size
          await client.send({ action: 'command', command })
          await rig.wait(`diagnostic ${command}`, () => answer.test(fs.readFileSync(client.entry.log, 'utf8').slice(offset)),
            180000, [client.entry, server])
        }
        // Coordinate commands promise no neighbor callbacks. Match the compatibility route's
        // exposed side effects while keeping actual history on; vanilla publication still repairs
        // height/light and sends chunks in both adapters. No //fast or history bypass is used.
        for (const effect of ['neighbors', 'update', 'validation', 'entity_ai', 'events', 'lighting', 'heightmaps']) {
          await chat(`/perf ${effect} off`, /set to|is already/)
        }
        await chat('/perf history on', /set to|is already/)
        await send('gamerule random_tick_speed 0')
        await send('gamerule spawn_mobs false')
        await send('forceload add 8192 8192 8319 8319')
        await send('forceload add 8192 8448 8319 8575')
        for (let x = BOX[0]; x <= BOX[3]; x += 16) for (let z = BOX[2]; z <= BOX[5]; z += 16) {
          await send(`fill ${x} 80 ${z} ${x + 15} 95 ${z + 15} minecraft:air strict`)
        }
        await send(`tp ${name} 8256 100 8256 0 45`)
        await chat('/pos1 8192,80,8192', /First position set|Position already set/)
        await chat('/pos2 8319,95,8319', /Second position set|Position already set/)
        report.profile = { file: path.join(rig.root, 'server-fill.jfr'), checkpoints: [],
          start: tool('jcmd', [String(server.pid), 'JFR.start', 'name=faweS14', 'settings=profile',
             `filename=${path.join(rig.root, 'server-fill.jfr')}`, 'dumponexit=true']) }
        // A pressure abort may kill the JVM while its exit dump is being written. Keep complete,
        // uniquely named dumps outside timed pairs; never defer or weaken resource-triggered stop.
        const checkpoint = async label => {
          const file = path.join(rig.root, `server-fill-${label}.jfr`)
          const { stdout } = await runFile('jcmd', [String(server.pid), 'JFR.dump',
            'name=faweS14', `filename=${file}`], { timeout: 10000 })
          rig.assertResources()
          const item = { label, file, sha256: hash(file), bytes: fs.statSync(file).size, output: stdout }
          if (item.bytes === 0) throw new GateError(`Empty JFR checkpoint: ${file}`)
          report.profile.checkpoints.push(item)
          writeJson(path.join(rig.root, 'profile-checkpoints.json'), report.profile.checkpoints)
        }
        let window = 'idle'
        monitoring = true
        monitor = (async () => {
          while (monitoring) {
            const scheduled = performance.now()
            const label = window
            const { answer, queueMs, serviceMs } = await send('time query gametime', true)
            const finished = performance.now()
            if (!/^The game time is \d+ tick\(s\)$/.test(answer)) throw new GateError(`Unexpected heartbeat: ${answer}`)
            heartbeatRows.push({ label, startedMs: scheduled, startedEpochMs: performance.timeOrigin + scheduled,
              latencyMs: finished - scheduled, queueMs, serviceMs, ...rss(server) })
            await wait(Math.max(0, 50 - (performance.now() - scheduled)))
          }
        })()
        // Prevent an unobserved rejection while a client command is awaited.
        monitor.catch(error => { monitorFailure ||= error })
        await wait(5000)
        window = 'between-samples'
        let currentBlock = 'air'
        const sample = async (route, round, warmup) => {
          if (monitorFailure) throw monitorFailure
          storageReserve()
          const block = round >= 0 && round % 2 ? 'glass' : 'stone'
          // Identical initial blocks and target blocks in each pair; changing route order must not
          // change the opacity transition or leave a route measuring no-ops. Setup is untimed.
          for (let x = BOX[0]; x <= BOX[3]; x += 16) for (let z = BOX[2]; z <= BOX[5]; z += 16) {
            await send(`fill ${x} 80 ${z} ${x + 15} 95 ${z + 15} minecraft:air strict`)
            await send(`fill ${x} 80 ${z + REFERENCE_OFFSET} ${x + 15} 95 ${z + 15 + REFERENCE_OFFSET} minecraft:${block} strict`)
          }
          const initial = JSON.parse(await send(`fawes11 dump ${BOX.join(' ')}`)).runs
          if (initial.length !== 1 || initial[0][0] !== 'minecraft:air' || initial[0][1] !== VOLUME) {
            throw new GateError(`Diagnostic initial blocks differ: ${JSON.stringify(initial)}`)
          }
          const beforeHistory = JSON.parse(await send(`fawes10 history ${name}`)).entries.at(-1)?.operation
          const beforeTiming = JSON.parse(await send('fawes11 timing'))
          const beforeStats = JSON.parse(await send('fawes11 statistics'))
          const beforePreparation = JSON.parse(await send('faweprep status')).stats
          const beforeTick = JSON.parse(await send('fawes11 timeline 2147483647')).tick
          const command = route === 'wna' ? `/set ${block}` : `fawe-coordinated set ${block}`
          const reply = route === 'wna' ? /Operation completed/ : /Coordinated edit complete:/
          const label = `${warmup ? 'warmup' : 'sample'}:${round}:${route}`
          const started = performance.now()
          window = label
          await chat(command, reply)
          if (monitorFailure) throw monitorFailure
          const replyMs = performance.now() - started
          let history
          await rig.wait('diagnostic history entry', async () => {
            history = JSON.parse(await send(`fawes10 history ${name}`)).entries.at(-1)
            return history && history.operation !== beforeHistory && history.size === VOLUME && history.type === 'DiskStorageHistory'
          }, 30000, [server, client.entry])
          const historyMs = performance.now() - started
          let afterPreparation, afterStats
          await rig.wait('diagnostic fully drained', async () => {
            afterPreparation = JSON.parse(await send('faweprep status')).stats
            afterStats = JSON.parse(await send('fawes11 statistics'))
            return ['queued', 'active', 'bytes', 'tickets'].every(key => afterPreparation[key] === 0) &&
              afterPreparation.acquired === afterPreparation.released && afterStats.pending === 0 && afterStats.maintenance === 0
          }, 30000, [server, client.entry])
          const drainedMs = performance.now() - started
          await rig.wait('diagnostic visible cells', async () => {
            const area = (await client.send({ action: 'region', boxes: [visibleBox] })).boxes?.visible
            return area?.loaded && area.cells.length === 4 && area.cells.every(cell => cell.state === `minecraft:${block}`)
          }, 30000, [server, client.entry])
          const visibleMs = performance.now() - started
          window = 'between-samples'
          const afterTiming = JSON.parse(await send('fawes11 timing'))
          const timeline = JSON.parse(await send(`fawes11 timeline ${beforeTick}`)).publications
          let exact = true
          for (let x = BOX[0]; x <= BOX[3]; x += 16) for (let z = BOX[2]; z <= BOX[5]; z += 16) {
            const answer = await send(`execute if blocks ${x} 80 ${z} ${x + 15} 95 ${z + 15} ${x} 80 ${z + REFERENCE_OFFSET} all`)
            if (!/^Test passed/.test(answer)) exact = false
          }
          const beats = heartbeatRows.filter(row => row.label === label)
          const delta = (before, after) => Object.fromEntries(Object.keys(after).filter(key => typeof after[key] === 'number' &&
            !key.startsWith('max') && !key.startsWith('peak'))
            .map(key => [key, after[key] - (before[key] || 0)]))
          const ticks = timeline.map(unit => unit.tick)
          const entry = { route, round, warmup, block, cells: VOLUME, replyMs, historyMs, drainedMs, visibleMs,
            startedEpochMs: performance.timeOrigin + started, finishedEpochMs: performance.timeOrigin + started + visibleMs,
            blocksPerSecond: VOLUME * 1000 / Math.max(drainedMs, visibleMs), history,
            heartbeatCount: beats.length, heartbeatP99Ms: percentile(beats.map(row => row.latencyMs), 0.99),
            maxRssBytes: Math.max(...beats.map(row => row.VmRSS)), exact,
            publication: delta(beforeTiming, afterTiming), backend: delta(beforeStats, afterStats),
            preparation: delta(beforePreparation, afterPreparation), drainedPreparation: afterPreparation,
            publicationUnits: { count: timeline.length, firstTick: Math.min(...ticks), lastTick: Math.max(...ticks),
              spanTicks: Math.max(...ticks) - Math.min(...ticks) + 1,
              maxPublishMs: Math.max(...timeline.map(unit => unit.nanos)) / 1e6 },
            globalHighWater: Object.fromEntries(Object.entries(afterTiming).filter(([key]) => key.startsWith('max'))) }
          report.measurements.push(entry)
          writeJson(path.join(rig.root, 'fill-samples.json'), report.measurements)
          check(`${label}:all-cells-match-vanilla`, exact)
          check(`${label}:heartbeat-observed`, beats.length > 0)
          check(`${label}:all-work-drained`, afterPreparation.acquired === afterPreparation.released &&
            afterStats.pending === 0 && afterStats.maintenance === 0)
          if (!exact) throw new GateError(`Diagnostic fill differs from vanilla: ${label}`)
          currentBlock = block
        }
        await sample('wna', -1, true)
        await sample('fast', -1, true)
        await checkpoint('warmups')
        for (let round = 0; round < SAMPLES; round++) {
          for (const route of round % 2 ? ['fast', 'wna'] : ['wna', 'fast']) await sample(route, round, false)
          await checkpoint(`pair-${round}`)
        }
        monitoring = false
        await monitor
        writeJson(path.join(rig.root, 'heartbeat.json'), heartbeatRows)
        report.profile.stop = tool('jcmd', [String(server.pid), 'JFR.stop', 'name=faweS14'])
        const measured = report.measurements.filter(row => !row.warmup)
        const wna = measured.filter(row => row.route === 'wna')
        const fast = measured.filter(row => row.route === 'fast')
        report.summary = { wnaMedianBlocksPerSecond: median(wna.map(row => row.blocksPerSecond)),
          fastMedianBlocksPerSecond: median(fast.map(row => row.blocksPerSecond)),
          idleHeartbeatP99Ms: percentile(heartbeatRows.filter(row => row.label === 'idle').map(row => row.latencyMs), 0.99) }
        report.summary.speedup = report.summary.fastMedianBlocksPerSecond / report.summary.wnaMedianBlocksPerSecond
        check('five-measured-samples-per-route', wna.length === SAMPLES && fast.length === SAMPLES)
        check('idle-heartbeat-healthy', report.summary.idleHeartbeatP99Ms < manifest.targets.heartbeatP99MsExclusive, report.summary)
        check('warm-simple-fill-speedup-target', report.summary.speedup >= manifest.targets.warmSimpleFillSpeedupOverWna, report.summary)
        check('measured-heartbeat-target', measured.every(row => row.heartbeatCount > 0 &&
          row.heartbeatP99Ms < manifest.targets.heartbeatP99MsExclusive), measured.map(row => ({ route: row.route, p99: row.heartbeatP99Ms })))
        report.finalBlock = currentBlock
      },
      async restart (rig, send) {
        await send('forceload add 8192 8192 8319 8319')
        await send('forceload add 8192 8448 8319 8575')
        let exact = true
        for (let x = BOX[0]; x <= BOX[3]; x += 16) for (let z = BOX[2]; z <= BOX[5]; z += 16) {
          if (!/^Test passed/.test(await send(`execute if blocks ${x} 80 ${z} ${x + 15} 95 ${z + 15} ${x} 80 ${z + REFERENCE_OFFSET} all`))) exact = false
        }
        check('restart-all-cells-match-reference', exact)
      }
    })
    if (report.checks.some(row => !row.passed)) throw new GateError('Warm-fill diagnostic has unmet targets; inspect measurements and local JFR')
  } catch (error) { failure = rig?.resourceFailure || error } finally {
    monitoring = false
    try { await monitor } catch (error) { monitorFailure ||= error }
    if (rig) {
      writeJson(path.join(rig.root, 'heartbeat.json'), heartbeatRows)
      try { await rig.close() } catch (error) { failure ||= error }
      report.resources = rig.resourceReport()
      report.processes = rig.children.map(({ child, ...entry }) => entry)
      for (const entry of rig.children) if (/\/ERROR\]|Mixin apply failed|InvalidInjectionException|Failed to handle packet/.test(fs.readFileSync(entry.log, 'utf8'))) {
        failure ||= new GateError(`Runtime error in ${entry.log}`)
      }
    }
    if (!rig || rig.children.every(entry => entry.stopped)) lease?.close()
    report.leaseReleased = !lease || lease.closed
    if (monitorFailure) report.monitorError = monitorFailure.stack
    failure ||= monitorFailure
    failure = rig?.resourceFailure || failure
    report.error = failure?.stack
    report.status = failure ? 'failed-diagnostic' : 'passed-warm-diagnostic-only'
    writeJson(recordFile, report)
    console.log(JSON.stringify({ recordFile, status: report.status, summary: report.summary, error: report.error }))
  }
  if (failure) throw failure
}

main().catch(error => { console.error(error.stack); process.exitCode = 1 })
