'use strict'

const path = require('node:path')
const fs = require('node:fs')
const { spawn } = require('node:child_process')
const { SingleLaneLease, RESOURCE_POLICY, admitResources, memory, assertResources, startTime, writeJson } = require('./rig')

// Build sequentially under the same admission policy as native runs; never stop shared daemons.
async function main () {
  const repo = path.resolve(__dirname, '../..')
  fs.mkdirSync(path.join(repo, 'build'), { recursive: true })
  const recordFile = path.join(repo, `build/resources-build-${process.pid}-${Date.now()}.json`)
  const record = { resourcePolicy: RESOURCE_POLICY, admissionSamples: [] }
  let lease, timer, failure
  try {
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: record.admissionSamples })
    record.initial = admission.initial
    writeJson(recordFile, record)
    const child = spawn(path.join(repo, 'gradlew'), ['-p', repo, '-PfabricOnly=true', '--no-daemon', '--no-parallel',
      ':worldedit-fabric:shadowJar', ':worldedit-fabric:testmodJar'], { cwd: repo, stdio: 'inherit' })
    record.pid = child.pid
    record.startTime = startTime(child.pid)
    timer = setInterval(() => {
      try { assertResources(memory(), record.initial) } catch (error) {
        failure ||= error
        if (startTime(child.pid) === record.startTime) child.kill('SIGTERM')
      }
    }, 100)
    child.on('error', error => { failure ||= error })
    await new Promise(resolve => child.on('close', (code, signal) => {
      Object.assign(record, { code, signal })
      resolve()
    }))
    record.final = memory()
    assertResources(record.final, record.initial)
  } catch (error) { failure ||= error } finally {
    clearInterval(timer)
    lease?.close()
    record.failure = failure?.message
    writeJson(recordFile, record)
    console.log(JSON.stringify({ resourceRecord: recordFile, ...record }))
  }
  if (failure) throw failure
  process.exitCode = record.code || (record.signal ? 1 : 0)
}
main().catch(error => { console.error(error.stack); process.exitCode = Number.isInteger(error.code) ? error.code : 1 })
