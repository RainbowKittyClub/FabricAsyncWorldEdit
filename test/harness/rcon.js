'use strict'

const net = require('node:net')

// Task-local adaptation of the workspace scripts/loadtest/lib/rcon.js protocol client.
// Idle connections have no timer; deadlines belong to connects and individual requests.
class Rcon {
  static async connect ({ host = '127.0.0.1', port, password, connectTimeoutMs = 10000,
    requestTimeoutMs = 10000 }) {
    const socket = net.createConnection({ host, port })
    const client = new Rcon(socket, requestTimeoutMs)
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => finish(new Error('RCON connect deadline exceeded')), connectTimeoutMs)
        const connected = () => finish()
        const failed = error => finish(error)
        const closed = () => finish(new Error('RCON connection closed during connect'))
        function finish (error) {
          clearTimeout(timer)
          socket.off('connect', connected).off('error', failed).off('close', closed)
          if (error) reject(error)
          else resolve()
        }
        socket.once('connect', connected).once('error', failed).once('close', closed)
      })
      const reply = await client.request(3, password)
      if (reply.id === -1) throw new Error('RCON authentication refused')
      return client
    } catch (error) {
      client.fail(error)
      throw error
    }
  }

  constructor (socket, requestTimeoutMs = 10000) {
    this.socket = socket
    this.requestTimeoutMs = requestTimeoutMs
    this.nextId = 1
    this.buffer = Buffer.alloc(0)
    this.pending = null
    this.failure = null
    this.queue = Promise.resolve()
    socket.on('data', chunk => this.receive(chunk))
    socket.on('error', error => this.fail(error))
    socket.on('end', () => this.fail(new Error('RCON connection ended')))
    socket.on('close', () => this.fail(new Error('RCON connection closed')))
  }

  send (command, timeoutMs = this.requestTimeoutMs) {
    const run = () => this.request(2, command, timeoutMs).then(reply => reply.body)
    const result = this.queue.then(run)
    this.queue = result.catch(() => {})
    return result
  }

  close () { this.fail(new Error('RCON client closed')) }

  request (type, body, timeoutMs = this.requestTimeoutMs) {
    if (this.failure || this.socket.destroyed || !this.socket.writable) {
      return Promise.reject(this.failure || new Error('RCON socket is not writable'))
    }
    if (this.pending) return Promise.reject(new Error('Concurrent RCON request refused'))
    const id = this.nextId++
    const packet = encode(id, type, body)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.fail(new Error('RCON request deadline exceeded')), timeoutMs)
      this.pending = { id, type, parts: [], timer, resolve, reject }
      try {
        this.socket.write(packet, error => { if (error) this.fail(error) })
      } catch (error) { this.fail(error) }
    })
  }

  receive (chunk) {
    this.buffer = Buffer.concat([this.buffer, chunk])
    while (!this.failure && this.buffer.length >= 4) {
      const length = this.buffer.readInt32LE(0)
      if (length < 10 || length > 4106) return this.fail(new Error('Invalid RCON frame length'))
      if (this.buffer.length < length + 4) return
      const id = this.buffer.readInt32LE(4)
      const type = this.buffer.readInt32LE(8)
      const body = this.buffer.toString('utf8', 12, length + 2)
      this.buffer = this.buffer.subarray(length + 4)
      const pending = this.pending
      if (!pending) continue
      if (pending.type === 3) {
        if (type === 2 && (id === pending.id || id === -1)) this.complete({ id, body })
      } else if (id === pending.id && type === 0) {
        pending.parts.push(body)
        if (length - 10 < 4096) this.complete({ id, body: pending.parts.join('') })
      }
    }
  }

  complete (reply) {
    const pending = this.pending
    this.pending = null
    clearTimeout(pending.timer)
    pending.resolve(reply)
  }

  fail (error) {
    this.failure ||= error
    const pending = this.pending
    this.pending = null
    if (pending) {
      clearTimeout(pending.timer)
      pending.reject(this.failure)
    }
    this.socket.destroy()
  }
}

function encode (id, type, body) {
  const payload = Buffer.from(body, 'utf8')
  const packet = Buffer.alloc(14 + payload.length)
  packet.writeInt32LE(10 + payload.length, 0)
  packet.writeInt32LE(id, 4)
  packet.writeInt32LE(type, 8)
  payload.copy(packet, 12)
  return packet
}

module.exports = { Rcon, encode }
