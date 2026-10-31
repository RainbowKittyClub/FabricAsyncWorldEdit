'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { RESOURCE_POLICY, ResourceGuard, Rig, admitResources, SingleLaneLease } = require('./rig')

const baseline = { available: 16 * 1024 ** 3, swapUsed: 1200128, pswpin: 10, pswpout: 20,
  pressure: { some: { avg10: 0, total: 123 }, full: { avg10: 0, total: 42 } } }
function sampler (mutate = () => {}, delay = RESOURCE_POLICY.sampleIntervalMs) {
  let time = 0, count = 0
  return { now: () => time, wait: async () => { time += delay }, sample: () => {
    const value = structuredClone(baseline)
    mutate(value, count++)
    return value
  } }
}
function runtime () {
  let time = 0
  const guard = new ResourceGuard({ now: () => time })
  return { guard, check: (at, mutate = () => {}) => {
    time = at
    const value = structuredClone(baseline)
    mutate(value)
    return guard.check(value)
  } }
}
const full = value => { value.pressure.full.avg10 = 1 }
const some = value => { value.pressure.some.avg10 = 5 }

test('policy pins pressure duration, reserve and one lane; swap has no ceiling', () => {
  assert.equal(RESOURCE_POLICY.version, 'pressure-aware-v2')
  assert.equal(RESOURCE_POLICY.minAvailableBytes, 8 * 1024 ** 3)
  assert.equal(RESOURCE_POLICY.fullAvg10Percent, 1)
  assert.equal(RESOURCE_POLICY.someAvg10Percent, 5)
  assert.equal(RESOURCE_POLICY.sustainedPressureMs, 3000)
  assert.equal(RESOURCE_POLICY.maxConcurrentLanes, 1)
  assert.equal(RESOURCE_POLICY.maxSwapGrowthBytes, undefined)
})
test('healthy swap growth, swap-in/out and cumulative PSI increases are telemetry only', async () => {
  const change = (value, i) => {
    value.swapUsed += i * 1024 ** 3
    value.pswpin += i * 1000
    value.pswpout += i * 1000
    value.pressure.some.avg10 = 4.99
    value.pressure.full.avg10 = 0.99
    value.pressure.some.total += i * 1000
    value.pressure.full.total += i * 1000
  }
  const admitted = await admitResources(sampler(change))
  assert.equal(admitted.samples.length, 4)
  assert.equal(admitted.initial.swapUsed, baseline.swapUsed + 3 * 1024 ** 3)
  assert.ok(Object.isFrozen(admitted.initial))
  assert.equal(admitted.initial.pressure.full.avg10, 0.99)
  const { check, guard } = runtime()
  check(0)
  check(4000, value => change(value, 10))
  assert.equal(guard.report().final.pswpin, baseline.pswpin + 10000)
})
test('reserve failure refuses admission after bounded waiting and stops runtime immediately', async () => {
  const low = value => { value.available = 8 * 1024 ** 3 - 1 }
  await assert.rejects(admitResources(sampler(low)), /admission.*deadline/i)
  const { check } = runtime()
  assert.throws(() => check(0, low), /reserve/i)
  assert.doesNotThrow(() => runtime().check(0, value => { value.available = 8 * 1024 ** 3 }))
})
test('admission waits for a recovering reserve without requiring zero PSI', async () => {
  const admitted = await admitResources(sampler((value, i) => {
    if (i < 5) value.available = 1
    value.pressure.some.avg10 = 2
  }))
  assert.equal(admitted.samples.length, 6)
})
test('brief full or some PSI spikes reset when pressure subsides', async () => {
  for (const high of [full, some]) {
    const admitted = await admitResources(sampler((value, i) => { if (i < 5) high(value) }))
    assert.equal(admitted.samples.length, 6)
    const { check } = runtime()
    check(0, high)
    check(2999, high)
    check(3000)
    check(3001, high)
    assert.doesNotThrow(() => check(6000, high))
  }
})
test('full or some PSI at the threshold for 3000 monotonic ms stops admission and runtime', async () => {
  for (const high of [full, some]) {
    await assert.rejects(admitResources(sampler(high)), /sustained memory pressure/i)
    const { check, guard } = runtime()
    check(0, high)
    check(2999, high)
    assert.throws(() => check(3000, high), /sustained memory pressure/i)
    assert.equal(guard.report().failure.pressureDurationMs, 3000)
  }
})
test('continuous either-threshold pressure does not reset when its kind changes', () => {
  const { check } = runtime()
  check(0, full)
  check(2000, some)
  assert.throws(() => check(3000, full), /sustained memory pressure/i)
})
test('wall-clock jumps do not change pressure duration', () => {
  const original = Date.now
  try {
    const { check } = runtime()
    Date.now = () => 0
    check(0, full)
    Date.now = () => 1e15
    check(2999, full)
    Date.now = () => -1e15
    assert.throws(() => check(3000, full), /sustained memory pressure/i)
  } finally { Date.now = original }
})
test('missing or invalid required metrics fail closed at admission and runtime', async () => {
  for (const mutate of [
    value => { delete value.available },
    value => { value.available = NaN },
    value => { value.swapUsed = -1 },
    value => { delete value.pswpin },
    value => { value.pswpout = Infinity },
    value => { delete value.pressure },
    value => { delete value.pressure.full.avg10 },
    value => { value.pressure.some.avg10 = NaN },
    value => { value.pressure.some.avg10 = 101 },
    value => { delete value.pressure.full.total }
  ]) {
    await assert.rejects(admitResources(sampler(mutate)), /missing or invalid/i)
    assert.throws(() => runtime().check(0, mutate), /missing or invalid/i)
  }
  await assert.rejects(admitResources({ sample: () => { throw new Error('metrics unreadable') } }), /metrics unreadable/)
})
test('finite admission deadline refuses delayed samples', async () => {
  await assert.rejects(admitResources(sampler(() => {}, RESOURCE_POLICY.admissionDeadlineMs)), /deadline/i)
})
test('native runtime monitor stops owned workloads on sustained pressure, leaving display cleanup ordered', async () => {
  const { check } = runtime()
  let time = 0
  const server = {}, client = {}, display = {}
  const stopped = []
  const rig = { children: [server, display, client], displays: [{ entry: display }],
    assertResources: () => check(time, full), stop: async entry => { stopped.push(entry) } }
  Rig.prototype.monitorResources.call(rig)
  assert.deepEqual(stopped, [])
  time = 2999
  Rig.prototype.monitorResources.call(rig)
  assert.deepEqual(stopped, [])
  time = 3000
  Rig.prototype.monitorResources.call(rig)
  await rig.resourceStops
  assert.deepEqual(stopped, [client, server])
  assert.match(rig.resourceFailure.message, /sustained memory pressure/i)
})
test('second process refuses leased lane; only owner can release', () => {
  const dir = fs.mkdtempSync(path.join(process.env.TMPDIR, 'fawe-lane-test-'))
  const file = path.join(dir, 'lane.json')
  try {
    const lease = new SingleLaneLease(file)
    const child = spawnSync(process.execPath, ['-e', `
      const { SingleLaneLease } = require(${JSON.stringify(require.resolve('./rig'))});
      try { new SingleLaneLease(process.argv[1]); process.exitCode = 99 }
      catch (error) { process.exitCode = error.code === 3 ? 0 : 98 }
    `, file])
    assert.equal(child.status, 0)
    assert.equal(fs.readFileSync(file, 'utf8'), lease.owner)
    lease.close()
    lease.close()
    const next = new SingleLaneLease(file)
    fs.writeFileSync(file, 'not-this-owner')
    assert.throws(() => next.close(), { code: 3 })
    assert.equal(fs.readFileSync(file, 'utf8'), 'not-this-owner')
  } finally { fs.rmSync(dir, { recursive: true }) }
})
