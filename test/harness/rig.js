'use strict'

const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const net = require('node:net')
const { spawn, spawnSync } = require('node:child_process')

class GateError extends Error {
  constructor (message, code = 1) { super(message); this.code = code }
}
const hash = file => crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex')
const json = file => JSON.parse(fs.readFileSync(file, 'utf8'))
const writeJson = (file, value) => fs.writeFileSync(file, JSON.stringify(value, null, 2) + '\n')

// Resolve existing ancestors before creation so symlinks cannot redirect a scratch root.
function safeRoot (root) {
  const resolved = path.resolve(root)
  let ancestor = resolved
  const suffix = []
  while (!fs.existsSync(ancestor)) { suffix.unshift(path.basename(ancestor)); ancestor = path.dirname(ancestor) }
  const actual = path.join(fs.realpathSync(ancestor), ...suffix)
  const allowed = '/mnt/WORKSPACE/scratchpad/fawe'
  if (actual !== resolved || !(actual === allowed || actual.startsWith(allowed + '/'))) {
    throw new GateError(`Refused non-FAWE or symlinked scratch root: ${root}`, 3)
  }
  return actual
}

function verify (artifact) {
  if (!fs.existsSync(artifact.path)) throw new GateError(`Missing pinned input: ${artifact.path}`, 2)
  if (hash(artifact.path) !== artifact.sha256) throw new GateError(`Pinned input changed: ${artifact.path}`, 2)
  return artifact.path
}

function memory () {
  const entries = Object.fromEntries([...fs.readFileSync('/proc/meminfo', 'utf8').matchAll(/^(\w+):\s+(\d+)/gm)]
    .map(match => [match[1], Number(match[2]) * 1024]))
  return { available: entries.MemAvailable, swapUsed: entries.SwapTotal - entries.SwapFree }
}

// Admit idle swap once per run; never move that run's baseline after admission.
const RESOURCE_POLICY = Object.freeze({ admission: 'bounded-idle-samples',
  sampleCount: 4, sampleIntervalMs: 250, admissionDeadlineMs: 3000,
  minAvailableBytes: 8 * 1024 ** 3, maxSwapGrowthBytes: 0, maxConcurrentLanes: 1 })

function assertResources (current, initial = current) {
  if (current.available < RESOURCE_POLICY.minAvailableBytes || current.swapUsed > initial.swapUsed) {
    throw new GateError(`Resource admission refused: ${JSON.stringify({ current, initial, policy: RESOURCE_POLICY })}`, 2)
  }
}

function admissionSample () {
  const vm = Object.fromEntries([...fs.readFileSync('/proc/vmstat', 'utf8')
    .matchAll(/^(pswpin|pswpout) (\d+)$/gm)].map(match => [match[1], Number(match[2])]))
  const pressure = Object.fromEntries(fs.readFileSync('/proc/pressure/memory', 'utf8').trim().split('\n')
    .map(line => { const [kind, ...fields] = line.split(' ')
      return [kind, Object.fromEntries(fields.map(field => { const [key, value] = field.split('='); return [key, Number(value)] }))] }))
  return { ...memory(), ...vm, pressure }
}

async function admitResources ({ sample = admissionSample, now = () => performance.now(),
  wait = ms => new Promise(resolve => setTimeout(resolve, ms)), samples = [] } = {}) {
  const started = now()
  for (let i = 0; i < RESOURCE_POLICY.sampleCount; i++) {
    if (i) await wait(RESOURCE_POLICY.sampleIntervalMs)
    if (now() - started >= RESOURCE_POLICY.admissionDeadlineMs) {
      throw new GateError('Resource admission sampling deadline exceeded', 2)
    }
    const current = sample()
    samples.push({ elapsedMs: now() - started, ...current })
    if (now() - started >= RESOURCE_POLICY.admissionDeadlineMs) {
      throw new GateError('Resource admission sampling deadline exceeded', 2)
    }
    assertResources(current)
    const first = samples[0]
    const counters = [current.pswpin, current.pswpout, current.pressure?.some?.total, current.pressure?.full?.total]
    if (counters.some(value => !Number.isFinite(value)) ||
        ['some', 'full'].some(kind => current.pressure?.[kind]?.avg10 !== 0 ||
          current.pressure[kind].total !== first.pressure[kind].total) ||
        current.pswpin !== first.pswpin || current.pswpout !== first.pswpout || current.swapUsed !== first.swapUsed) {
      throw new GateError(`Resource admission requires idle swap and pressure: ${JSON.stringify(samples)}`, 2)
    }
  }
  return Object.freeze({ initial: Object.freeze({ available: samples.at(-1).available,
    swapUsed: samples.at(-1).swapUsed }), samples, policy: RESOURCE_POLICY })
}

// Shared across worktrees and run roots. Stale leases fail closed; never reclaim another run.
class SingleLaneLease {
  constructor (file = '/mnt/WORKSPACE/scratchpad/fawe/.single-lane.json') {
    this.file = file
    this.owner = JSON.stringify({ token: crypto.randomUUID(), pid: process.pid, startTime: startTime(process.pid) })
    try { fs.writeFileSync(file, this.owner, { flag: 'wx', mode: 0o600 }) } catch (error) {
      if (error.code === 'EEXIST') throw new GateError(`Single lane already leased: ${file}`, 3)
      throw error
    }
    this.identity = fs.lstatSync(file)
  }

  close () {
    if (this.closed) return
    const identity = fs.lstatSync(this.file)
    if (identity.dev !== this.identity.dev || identity.ino !== this.identity.ino ||
        fs.readFileSync(this.file, 'utf8') !== this.owner) {
      throw new GateError('Refused cleanup of replaced lane lease', 3)
    }
    fs.unlinkSync(this.file)
    this.closed = true
  }
}

function startTime (pid) {
  try { return fs.readFileSync(`/proc/${pid}/stat`, 'utf8').split(') ').at(-1).split(' ')[19] } catch { return null }
}

class Rig {
  constructor (root, manifest, manifestFile, admission) {
    safeRoot(root)
    this.token = crypto.randomUUID()
    this.root = path.join(root, manifest.runLabel, 's1-s2', this.token)
    fs.mkdirSync(this.root, { recursive: true })
    this.manifest = manifest
    this.admission = admission
    this.initialMemory = admission.initial
    this.children = []
    this.tempDirs = []
    this.displays = []
    this.ports = []
    this.results = []
    this.leaseRoot = path.join(root, '.ports')
    fs.mkdirSync(this.leaseRoot, { recursive: true })
    fs.writeFileSync(path.join(this.root, 'owner.json'), JSON.stringify({
      token: this.token, runner: process.pid, startTime: startTime(process.pid), manifest: hash(manifestFile)
    }), { flag: 'wx' })
    writeJson(path.join(this.root, 'manifest.json'), { ...manifest, resourcePolicy: RESOURCE_POLICY,
      admission, initialResources: this.initialMemory, sourceManifestSha256: hash(manifestFile) })
    writeJson(path.join(this.root, 'resources-start.json'), this.initialMemory)
    this.assertResources()
  }

  assertResources () {
    assertResources(memory(), this.initialMemory)
  }

  spawn (name, command, args, options = {}) {
    this.assertResources()
    const log = path.join(this.root, `${name}.log`)
    const fd = fs.openSync(log, 'wx')
    const child = spawn(command, args, { cwd: this.root, ...options,
      stdio: ['ignore', fd, fd, ...(options.extraPipe ? ['pipe'] : [])] })
    fs.closeSync(fd)
    child.launchError = null
    child.on('error', error => { child.launchError = error })
    const entry = { name, pid: child.pid, startTime: startTime(child.pid), child, log, stopped: false }
    this.children.push(entry)
    this.recordProcesses()
    return entry
  }

  recordProcesses () {
    writeJson(path.join(this.root, 'processes.json'), this.children.map(({ child, ...entry }) => entry))
  }

  alive (entry) {
    if (entry.child.launchError) throw new GateError(`${entry.name}: ${entry.child.launchError}`, 2)
    if (entry.child.exitCode !== null || entry.child.signalCode !== null) {
      throw new GateError(`${entry.name} exited unexpectedly; inspect ${entry.log}`)
    }
  }

  async wait (label, predicate, timeoutMs = 120000, entries = []) {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      this.assertResources()
      for (const entry of entries) this.alive(entry)
      const value = await predicate()
      if (value) return value
      await new Promise(resolve => setTimeout(resolve, 100))
    }
    throw new GateError(`Timeout waiting for ${label}`)
  }

  async port () {
    for (let attempt = 0; attempt < 20; attempt++) {
      const reservation = net.createServer()
      await new Promise((resolve, reject) => {
        reservation.once('error', reject)
        reservation.listen(0, '127.0.0.1', resolve)
      })
      const port = reservation.address().port
      const file = path.join(this.leaseRoot, String(port))
      try { fs.writeFileSync(file, this.token, { flag: 'wx' }) } catch (error) {
        reservation.close()
        if (error.code !== 'EEXIST') throw error
        continue
      }
      const lease = { port, file, reservation }
      this.ports.push(lease)
      return lease
    }
    throw new GateError('No isolated port lease available', 2)
  }

  async stop (entry) {
    const owner = json(path.join(safeRoot(this.root), 'owner.json'))
    if (!this.children.includes(entry) || owner.token !== this.token || owner.runner !== process.pid ||
        owner.startTime !== startTime(process.pid)) {
      throw new GateError('Refused process cleanup without matching rig ownership', 3)
    }
    if (entry.stopped) return
    if (entry.child.exitCode === null && entry.child.signalCode === null && entry.pid) {
      if (startTime(entry.pid) !== entry.startTime) throw new GateError(`PID identity changed: ${entry.pid}`, 3)
      entry.child.kill('SIGTERM')
      const end = Date.now() + 15000
      while (entry.child.exitCode === null && entry.child.signalCode === null && Date.now() < end) {
        await new Promise(resolve => setTimeout(resolve, 100))
      }
      if (entry.child.exitCode === null && entry.child.signalCode === null) {
        if (startTime(entry.pid) !== entry.startTime) throw new GateError(`PID identity changed: ${entry.pid}`, 3)
        entry.child.kill('SIGKILL')
        const killedDeadline = Date.now() + 5000
        while (entry.child.exitCode === null && entry.child.signalCode === null && Date.now() < killedDeadline) {
          await new Promise(resolve => setTimeout(resolve, 100))
        }
        if (entry.child.exitCode === null && entry.child.signalCode === null) {
          throw new GateError(`Owned process did not exit: ${entry.pid}`, 3)
        }
      }
    }
    entry.stopped = true
    this.recordProcesses()
  }

  async close () {
    const failures = []
    const releaseDisplay = display => {
      try {
        display.close()
        writeJson(path.join(this.root, `x11-after-${display.number}.json`), display.after)
      } catch (error) { failures.push(error.message) }
    }
    for (const entry of this.children.toReversed()) {
      try { await this.stop(entry) } catch (error) { failures.push(error.message) }
      // Later displays captured earlier ones; verify them before shutting those earlier children down.
      for (const display of this.displays.filter(display => display.entry === entry)) releaseDisplay(display)
    }
    for (const display of this.displays.filter(display => !display.entry)) releaseDisplay(display)
    for (const lease of this.ports) {
      lease.reservation.close()
      if (fs.existsSync(lease.file) && fs.readFileSync(lease.file, 'utf8') === this.token) fs.unlinkSync(lease.file)
    }
    if (!failures.length) {
      for (const dir of this.tempDirs) fs.rmSync(dir, { recursive: true })
    }
    const finalResources = memory()
    writeJson(path.join(this.root, 'resources-end.json'), finalResources)
    try { assertResources(finalResources, this.initialMemory) } catch (error) { failures.push(error.message) }
    if (failures.length) throw new GateError(`Cleanup failed: ${failures.join('; ')}`, 3)
  }
}

function tool (command, args, options = {}) {
  const result = spawnSync(command, args, { encoding: 'utf8', timeout: 120000, ...options })
  if (result.error) throw new GateError(`${command}: ${result.error.message}`, 2)
  if (result.status !== 0) throw new GateError(`${command} exited ${result.status}: ${result.stdout}\n${result.stderr}`)
  return result.stdout
}

module.exports = { GateError, hash, json, writeJson, safeRoot, verify, memory, Rig, tool,
  RESOURCE_POLICY, admitResources, assertResources, SingleLaneLease, startTime }
