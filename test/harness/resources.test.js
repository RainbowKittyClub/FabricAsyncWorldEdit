'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { RESOURCE_POLICY, admitResources, assertResources, SingleLaneLease } = require('./rig')

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
test('stable nonzero idle swap above obsolete ceiling is admitted and frozen', async () => {
  const admitted = await admitResources(sampler())
  assert.equal(admitted.initial.swapUsed, 1200128)
  assert.equal(admitted.samples.length, 4)
  assert.ok(Object.isFrozen(admitted.initial))
  assert.equal(RESOURCE_POLICY.maxInitialSwapBytes, undefined)
  const zero = await admitResources(sampler(value => { value.swapUsed = 0 }))
  assert.equal(zero.initial.swapUsed, 0)
})
test('active swapping or memory pressure refuses admission', async () => {
  for (const mutate of [
    (value, i) => { value.pswpin += i },
    (value, i) => { value.pswpout += i },
    (value, i) => { value.swapUsed += i },
    value => { value.pressure.some.avg10 = 0.01 },
    value => { value.pressure.full.avg10 = 0.01 },
    (value, i) => { value.pressure.some.total += i },
    (value, i) => { value.pressure.full.total += i },
    value => { delete value.pswpin }
  ]) await assert.rejects(admitResources(sampler(mutate)), { code: 2 })
})
test('finite admission deadline refuses delayed samples', async () => {
  await assert.rejects(admitResources(sampler(() => {}, RESOURCE_POLICY.admissionDeadlineMs)), { code: 2 })
})
test('retain eight GiB available-memory reserve', async () => {
  await assert.rejects(admitResources(sampler(value => { value.available = 8 * 1024 ** 3 - 1 })), { code: 2 })
})
test('abort any growth above actual frozen admission baseline', () => {
  assert.throws(() => assertResources({ ...baseline, swapUsed: baseline.swapUsed + 1 }, baseline), { code: 2 })
  assert.throws(() => assertResources({ ...baseline, swapUsed: 1 }, { ...baseline, swapUsed: 0 }), { code: 2 })
  assert.doesNotThrow(() => assertResources({ ...baseline, swapUsed: 0 }, baseline))
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
