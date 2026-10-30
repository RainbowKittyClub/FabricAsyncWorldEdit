'use strict'

const fs = require('node:fs')
const path = require('node:path')
const { GateError, SingleLaneLease, startTime } = require('./rig')

function identity (file) {
  try {
    const stat = fs.lstatSync(file)
    return { dev: stat.dev, ino: stat.ino, mode: stat.mode, ctimeMs: stat.ctimeMs,
      link: stat.isSymbolicLink() ? fs.readlinkSync(file) : null }
  } catch (error) {
    if (error.code === 'ENOENT') return null
    throw error
  }
}

// X11 paths are fixed by the protocol, not the runner's temporary-file directory.
class DisplayLease {
  constructor ({ tempRoot = '/tmp', leaseRoot = '/mnt/WORKSPACE/scratchpad/fawe/.display-leases',
    display = process.env.DISPLAY, unix = () => fs.readFileSync('/proc/net/unix', 'utf8'),
    numbers = Array.from({ length: 800 }, (_, i) => 200 + i) } = {}) {
    this.tempRoot = tempRoot
    this.unix = unix
    this.desktop = Number(/:(\d+)(?:\.\d+)?$/.exec(display || '')?.[1])
    this.before = this.snapshot()
    fs.mkdirSync(leaseRoot, { recursive: true })
    for (const number of numbers) {
      if (!Number.isInteger(number) || number < 200 || number > 999 || number === this.desktop || this.occupied(number)) continue
      let lease
      try { lease = new SingleLaneLease(path.join(leaseRoot, `${number}.json`)) } catch (error) {
        if (error.code === 3) continue
        throw error
      }
      if (this.occupied(number)) { lease.close(); continue }
      this.number = number
      this.lease = lease
      return
    }
    throw new GateError('No unoccupied private high X11 display available', 3)
  }

  paths (number) {
    return [path.join(this.tempRoot, '.X11-unix', `X${number}`),
      path.join(this.tempRoot, '.X11-unix', `X${number}_`), path.join(this.tempRoot, `.X${number}-lock`)]
  }

  listeners (number) {
    const socket = path.join(this.tempRoot, '.X11-unix', `X${number}`)
    return this.unix().split('\n').map(line => line.trim().split(/\s+/))
      .filter(fields => [socket, socket + '_', '@' + socket, '@' + socket + '_'].includes(fields[7]))
  }

  occupied (number) {
    return this.paths(number).some(file => identity(file)) || this.listeners(number).length !== 0
  }

  snapshot () {
    const entries = []
    for (const [dir, pattern] of [[this.tempRoot, /^\.X\d+-lock$/],
      [path.join(this.tempRoot, '.X11-unix'), /^X\d+_?$/]]) {
      let names
      try { names = fs.readdirSync(dir) } catch (error) {
        if (error.code === 'ENOENT') continue
        throw error
      }
      for (const name of names.filter(name => pattern.test(name))) {
        const file = path.join(dir, name)
        entries.push([file, identity(file)])
      }
    }
    return Object.fromEntries(entries.sort(([a], [b]) => a.localeCompare(b)))
  }

  beforeSpawn () {
    if (this.occupied(this.number)) throw new GateError('X11 display occupied after leasing; refusing launch', 3)
  }

  attach (entry) { this.entry = entry }

  ready () {
    const entry = this.entry
    if (!entry?.pid || startTime(entry.pid) !== entry.startTime) throw new GateError('Xvfb child identity changed', 3)
    const [socket, , lock] = this.paths(this.number)
    if (!identity(lock)) return false
    if (Number(fs.readFileSync(lock, 'utf8').trim()) !== entry.pid) {
      throw new GateError('X11 lock belongs to another process', 3)
    }
    if (!identity(socket)) return false
    const owned = new Set(fs.readdirSync(`/proc/${entry.pid}/fd`).flatMap(fd => {
      try { return [fs.readlinkSync(`/proc/${entry.pid}/fd/${fd}`)] } catch (error) {
        if (error.code === 'ENOENT') return []
        throw error
      }
    }))
    const listeners = this.listeners(this.number)
    return listeners.length > 0 && listeners.every(fields => owned.has(`socket:[${fields[6]}]`))
  }

  close () {
    if (this.closed) return
    if (this.entry && (!this.entry.stopped ||
        (this.entry.pid && startTime(this.entry.pid) === this.entry.startTime))) {
      throw new GateError('Refused display release before exact child shutdown', 3)
    }
    const after = this.snapshot()
    // Exclude only our display; preserve every pre-existing entry, including dangling links.
    for (const file of this.paths(this.number)) delete after[file]
    if (JSON.stringify(after) !== JSON.stringify(this.before)) {
      throw new GateError('Desktop X11 entries changed; preserving display lease for inspection', 3)
    }
    if (this.occupied(this.number)) throw new GateError('Owned X11 entries remain; never unlink them automatically', 3)
    this.lease.close()
    this.closed = true
    this.after = after
  }
}

module.exports = { DisplayLease, identity }
