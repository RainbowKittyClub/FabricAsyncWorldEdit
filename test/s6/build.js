'use strict'

const path = require('node:path')
const fs = require('node:fs')
const { spawn } = require('node:child_process')
const { SingleLaneLease, RESOURCE_POLICY, ResourceGuard, admitResources, admissionSample, startTime, writeJson } = require('../harness/rig')

async function main () {
  const repo = path.resolve(__dirname, '../..')
  const evidence = path.join(repo, 'handoff/s6')
  fs.mkdirSync(evidence, { recursive: true })
  const recordFile = path.join(evidence, `build-${Date.now()}.json`)
  const record = { resourcePolicy: RESOURCE_POLICY, admissionSamples: [], launched: false }
  const guard = new ResourceGuard()
  let lease, timer, failure
  try {
    lease = new SingleLaneLease()
    const admission = await admitResources({ samples: record.admissionSamples })
    record.initial = admission.initial
    guard.check(record.initial)
    writeJson(recordFile, record)
    const args = ['-p', repo, '-PfabricOnly=true', '--no-daemon', '--no-parallel',
      '-I', path.join(__dirname, 'opac.init.gradle'), ':worldedit-fabric:shadowJar', ':worldedit-fabric:s6probeJar',
      ':worldedit-fabric:testmodJar']
    record.command = [path.join(repo, 'gradlew'), ...args]
    const child = spawn(record.command[0], args, { cwd: repo, stdio: 'inherit' })
    record.launched = true
    record.pid = child.pid
    record.startTime = startTime(child.pid)
    timer = setInterval(() => {
      try { guard.check(admissionSample()) } catch (error) {
        failure ||= error
        if (startTime(child.pid) === record.startTime) child.kill('SIGTERM')
      }
    }, RESOURCE_POLICY.runtimeSampleIntervalMs)
    child.on('error', error => { failure ||= error })
    await new Promise(resolve => child.on('close', (code, signal) => {
      Object.assign(record, { code, signal })
      resolve()
    }))
  } catch (error) { failure ||= error } finally {
    clearInterval(timer)
    try {
      record.final = admissionSample()
      if (record.initial) guard.check(record.final)
    } catch (error) { failure ||= error }
    record.resources = guard.report()
    lease?.close()
    record.failure = failure?.message
    record.leaseReleased = !lease || lease.closed
    writeJson(recordFile, record)
    console.log(JSON.stringify({ resourceRecord: recordFile, ...record }))
  }
  if (failure) throw failure
  process.exitCode = record.code || (record.signal ? 1 : 0)
}
main().catch(error => { console.error(error.stack); process.exitCode = Number.isInteger(error.code) ? error.code : 1 })
