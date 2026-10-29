'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { spawn } = require('node:child_process')
const { once } = require('node:events')
const { safeRoot, verify, hash, Rig } = require('./rig')

// These are harness unit tests, not substitutes for installed-server negative controls.
test('reject production, archive and arbitrary roots with safety exit 3', () => {
  for (const root of ['/mnt/WORKSPACE/minecraft-server-data/felis-novum',
    '/mnt/WORKSPACE/minecraft-server-data/backups', '/home/kgadberry/projects/minecraft-dev']) {
    assert.throws(() => safeRoot(root), { code: 3 })
  }
  assert.equal(safeRoot('/mnt/WORKSPACE/scratchpad/fawe'), '/mnt/WORKSPACE/scratchpad/fawe')
})

test('missing pinned jar is unavailable, never a pass', () => {
  assert.throws(() => verify({ path: path.join(process.env.TMPDIR, 'fawe-absent-jar-260329.jar'), sha256: '0' }), { code: 2 })
})

test('changed input hash is unavailable', () => {
  const dir = fs.mkdtempSync(path.join(process.env.TMPDIR, 'fawe-pin-test-'))
  try {
    const file = path.join(dir, 'input')
    fs.writeFileSync(file, 'original')
    const input = { path: file, sha256: hash(file) }
    assert.equal(verify(input), file)
    fs.writeFileSync(file, 'changed')
    assert.throws(() => verify(input), { code: 2 })
  } finally { fs.rmSync(dir, { recursive: true }) }
})

test('real exited child fails the liveness assertion', async () => {
  const child = spawn(process.execPath, ['-e', 'process.exit(7)'])
  await once(child, 'exit')
  assert.throws(() => Rig.prototype.alive({ child, name: 'dead-test-child', log: 'test-only' }), { code: 1 })
})

test('unsatisfied barrier has a bounded failure', async () => {
  const context = { assertResources () {}, alive () {} }
  await assert.rejects(Rig.prototype.wait.call(context, 'never', () => false, 20), { code: 1 })
})
