'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { RESOURCE_POLICY, assertResources, SingleLaneLease } = require('./rig')

const baseline = { available: 16 * 1024 ** 3, swapUsed: 1064960 }
test('admit bounded existing swap and zero swap', () => {
  assert.doesNotThrow(() => assertResources(baseline))
  assert.doesNotThrow(() => assertResources({ ...baseline, swapUsed: 0 }))
  assert.equal(RESOURCE_POLICY.maxInitialSwapBytes, 1064960)
})
test('refuse initial swap above fixed allowance', () => {
  assert.throws(() => assertResources({ ...baseline, swapUsed: 1064961 }), { code: 2 })
})
test('retain eight GiB available-memory reserve', () => {
  assert.throws(() => assertResources({ ...baseline, available: 8 * 1024 ** 3 - 1 }), { code: 2 })
})
test('abort any growth above actual admission sample, not allowance', () => {
  assert.throws(() => assertResources({ ...baseline, swapUsed: 1064961 }, baseline), { code: 2 })
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
