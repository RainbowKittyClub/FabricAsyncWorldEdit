'use strict'

const path = require('node:path')
const fs = require('node:fs')
const { spawn } = require('node:child_process')
const { SingleLaneLease, RESOURCE_POLICY, memory, assertResources, startTime, writeJson } = require('./rig')

// Build sequentially under the same admission policy as native runs; never stop shared daemons.
const repo = path.resolve(__dirname, '../..')
const initial = memory()
assertResources(initial)
const lease = new SingleLaneLease()
fs.mkdirSync(path.join(repo, 'build'), { recursive: true })
const record = { initial, resourcePolicy: RESOURCE_POLICY }
const child = spawn(path.join(repo, 'gradlew'), ['-p', repo, '-PfabricOnly=true', '--no-daemon', '--no-parallel',
  ':worldedit-fabric:shadowJar', ':worldedit-fabric:testmodJar'], { cwd: repo, stdio: 'inherit' })
record.pid = child.pid
record.startTime = startTime(child.pid)
let failure
const timer = setInterval(() => {
  try { assertResources(memory(), initial) } catch (error) {
    failure ||= error
    if (startTime(child.pid) === record.startTime) child.kill('SIGTERM')
  }
}, 100)
child.on('error', error => { failure ||= error })
child.on('close', (code, signal) => {
  clearInterval(timer)
  record.final = memory()
  try { assertResources(record.final, initial) } catch (error) { failure ||= error }
  Object.assign(record, { code, signal, failure: failure?.message })
  writeJson(path.join(repo, `build/resources-build-${child.pid || Date.now()}.json`), record)
  lease.close()
  if (failure) console.error(failure)
  process.exitCode = failure?.code || (failure ? 1 : code || (signal ? 1 : 0))
})
