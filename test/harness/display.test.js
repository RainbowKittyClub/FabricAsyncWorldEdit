'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { DisplayLease } = require('./display')
const { Rig, memory } = require('./rig')

function fixture (action) {
  const root = fs.mkdtempSync(path.join(process.env.TMPDIR, 'fawe-display-test-'))
  fs.mkdirSync(path.join(root, '.X11-unix'))
  const options = { tempRoot: root, leaseRoot: path.join(root, 'leases'), display: ':0', unix: () => '' }
  try { action(root, options) } finally { fs.rmSync(root, { recursive: true }) }
}

test('refuse primary, secondary, dangling and stale PID entries without removing them', () => fixture((root, options) => {
  for (const name of ['.X11-unix/X200', '.X11-unix/X201_', '.X202-lock']) {
    fs.writeFileSync(path.join(root, name), 'stale')
  }
  fs.symlinkSync(path.join(root, 'missing'), path.join(root, '.X11-unix/X203'))
  const before = fs.readdirSync(path.join(root, '.X11-unix')).sort()
  assert.throws(() => new DisplayLease({ ...options, numbers: [200, 201, 202, 203] }), { code: 3 })
  assert.deepEqual(fs.readdirSync(path.join(root, '.X11-unix')).sort(), before)
  assert.equal(fs.readFileSync(path.join(root, '.X202-lock'), 'utf8'), 'stale')
}))

test('refuse abstract listeners with missing filesystem sockets', () => fixture((root, options) => {
  for (const suffix of ['', '_']) {
    const unix = () => `000: 2 0 10000 1 1 123 @${root}/.X11-unix/X200${suffix}\n`
    assert.throws(() => new DisplayLease({ ...options, unix, numbers: [200] }), { code: 3 })
  }
}))

test('exclude desktop high display and low displays even without sockets', () => fixture((root, options) => {
  assert.throws(() => new DisplayLease({ ...options, display: ':200.0', numbers: [0, 1, 200, 1000] }), { code: 3 })
}))

test('lease collisions refuse, startup rechecks, cleanup preserves desktop identities', () => fixture((root, options) => {
  fs.writeFileSync(path.join(root, '.X11-unix/X0_'), 'desktop')
  fs.symlinkSync(path.join(root, 'absent'), path.join(root, '.X11-unix/X0'))
  const a = new DisplayLease({ ...options, numbers: [200] })
  assert.throws(() => new DisplayLease({ ...options, numbers: [200] }), { code: 3 })
  fs.writeFileSync(path.join(root, '.X200-lock'), 'foreign')
  assert.throws(() => a.beforeSpawn(), { code: 3 })
  assert.throws(() => a.close(), { code: 3 })
  assert.equal(fs.readFileSync(path.join(root, '.X200-lock'), 'utf8'), 'foreign')
  // Only the fixture removes its own mock collision; production never unlinks X11 entries.
  fs.unlinkSync(path.join(root, '.X200-lock'))
  a.attach({ stopped: false })
  assert.throws(() => a.close(), { code: 3 })
  a.entry.stopped = true
  a.close()
  assert.deepEqual(a.after, a.before)
  const b = new DisplayLease({ ...options, numbers: [200] })
  b.close()
}))

test('nested displays verify in reverse child shutdown order', async () => {
  const root = fs.mkdtempSync(path.join(process.env.TMPDIR, 'fawe-display-order-'))
  fs.mkdirSync(path.join(root, '.X11-unix'))
  const options = { tempRoot: root, leaseRoot: path.join(root, 'leases'), display: ':0', unix: () => '' }
  try {
    fs.writeFileSync(path.join(root, '.X11-unix/X0_'), 'desktop')
    const first = new DisplayLease({ ...options, numbers: [200] })
    fs.writeFileSync(first.paths(200)[0], 'first child socket')
    const second = new DisplayLease({ ...options, numbers: [201] })
    fs.writeFileSync(second.paths(201)[0], 'second child socket')
    first.attach({ stopped: false })
    second.attach({ stopped: false })
    const rig = { root, children: [first.entry, second.entry], displays: [first, second],
      ports: [], tempDirs: [], initialMemory: memory(),
      stop: async entry => {
        const display = entry === first.entry ? first : second
        // Simulate each owned Xvfb removing only its own mock socket at exit.
        fs.unlinkSync(display.paths(display.number)[0])
        entry.stopped = true
      } }
    await Rig.prototype.close.call(rig)
    assert.equal(first.closed, true)
    assert.equal(second.closed, true)
    assert.equal(fs.readFileSync(path.join(root, '.X11-unix/X0_'), 'utf8'), 'desktop')
    assert.deepEqual(first.before, first.after)
    assert.deepEqual(second.before, second.after)
  } finally { fs.rmSync(root, { recursive: true }) }
})

test('changed desktop entry fails cleanup without repairing it', () => fixture((root, options) => {
  const desktop = path.join(root, '.X11-unix/X0_')
  fs.writeFileSync(desktop, 'desktop')
  const lease = new DisplayLease({ ...options, numbers: [200] })
  fs.unlinkSync(desktop)
  assert.throws(() => lease.close(), { code: 3 })
  assert.equal(fs.existsSync(desktop), false)
  assert.equal(fs.existsSync(lease.lease.file), true)
}))
