'use strict'

const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { DatabaseSync } = require('node:sqlite')
const { Rig, GateError, writeJson } = require('./rig')

// Exercise actual child-runner exit codes, not just assertions caught in the success process.
async function controls (rig, repo) {
  for (const [fault, diagnostic] of [
    ['wrong-block', /Expected minecraft:diamond_block .*Test failed/],
    ['failed-command', /Command failed: Unknown/],
    ['dead-client', /native-client exited unexpectedly/]
  ]) {
    const child = rig.spawn(`negative-${fault}`, process.execPath,
      [path.join(repo, 'test/harness/fawe.js'), 'run', '--suite', 'smoke', '--profile', 'reference', '--fault', fault])
    await rig.wait(`${fault} exit`, () => child.child.exitCode !== null || child.child.signalCode !== null, 240000)
    const output = fs.readFileSync(child.log, 'utf8')
    if (child.child.exitCode !== 1 || !diagnostic.test(output)) {
      throw new GateError(`Negative ${fault} failed for the wrong reason; inspect ${child.log}`)
    }
    const result = JSON.parse(output.split('\n').find(line => line.startsWith('{"suite"')))
    const processes = JSON.parse(fs.readFileSync(path.join(result.fixtureRoot, 'processes.json'), 'utf8'))
    if (processes.some(entry => !entry.stopped)) throw new GateError(`Negative ${fault} leaked owned processes`)
    rig.results.push({ gate: fault, status: 'passed', observedExit: 1, fixtureRoot: result.fixtureRoot })
  }
  const missing = structuredClone(rig.manifest)
  missing.availableDependencies['worldedit-reference'].path = path.join(rig.root, 'missing.jar')
  const missingFile = path.join(rig.root, 'missing-manifest.json')
  writeJson(missingFile, missing)
  const child = rig.spawn('negative-missing-jar', process.execPath,
    [path.join(repo, 'test/harness/fawe.js'), 'preflight', '--manifest', missingFile])
  await rig.wait('missing jar preflight exit', () => child.child.exitCode !== null, 30000)
  if (child.child.exitCode !== 2 || !/Missing pinned input:.*missing.jar/.test(fs.readFileSync(child.log, 'utf8'))) {
    throw new GateError('Missing-jar preflight did not return capability exit 2')
  }
  rig.results.push({ gate: 'missing-jar', status: 'passed', observedExit: 2 })
  await isolation(rig, repo)
}

// Two live leases/processes/databases must remain separate, including rejected cross-rig cleanup.
async function isolation (parent, repo) {
  const manifestFile = path.join(repo, 'test/manifest.json')
  const a = new Rig('/mnt/WORKSPACE/scratchpad/fawe', parent.manifest, manifestFile, parent.admission)
  const b = new Rig('/mnt/WORKSPACE/scratchpad/fawe', parent.manifest, manifestFile, parent.admission)
  const databases = []
  try {
    const [pa, pb] = await Promise.all([a.port(), b.port()])
    assert.notEqual(pa.port, pb.port)
    assert.notEqual(a.root, b.root)
    for (const rig of [a, b]) {
      fs.mkdirSync(path.join(rig.root, 'world'))
      fs.writeFileSync(path.join(rig.root, 'world/fixture'), rig.token)
      const db = new DatabaseSync(path.join(rig.root, 'fixture.sqlite'))
      databases.push(db)
      db.exec('CREATE TABLE owner (token TEXT NOT NULL)')
      db.prepare('INSERT INTO owner VALUES (?)').run(rig.token)
    }
    const ca = a.spawn('ownership-child', process.execPath, ['-e', 'setInterval(() => {}, 1000)'])
    const cb = b.spawn('ownership-child', process.execPath, ['-e', 'setInterval(() => {}, 1000)'])
    assert.notEqual(ca.pid, cb.pid)
    await assert.rejects(a.stop(cb), { code: 3 })
    b.alive(cb)
    await a.stop(ca)
    b.alive(cb)
    assert.equal(fs.readFileSync(path.join(b.root, 'world/fixture'), 'utf8'), b.token)
    assert.equal(databases[1].prepare('SELECT token FROM owner').get().token, b.token)
    parent.results.push({ gate: 'two-rig-isolation', status: 'passed', roots: [a.root, b.root],
      ports: [pa.port, pb.port], pids: [ca.pid, cb.pid], crossCleanupExit: 3 })
  } finally {
    for (const db of databases) db.close()
    try { await a.close() } finally { await b.close() }
  }
}

module.exports = { controls }
