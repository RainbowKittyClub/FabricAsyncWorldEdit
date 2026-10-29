'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const net = require('node:net')
const { once } = require('node:events')
const { setTimeout: delay } = require('node:timers/promises')
const { Rcon, encode } = require('./rcon')

async function fixture (t, command) {
  let peer
  const peers = new Set()
  const server = net.createServer(socket => {
    peer = socket
    peers.add(socket)
    socket.on('error', () => {})
    socket.on('close', () => peers.delete(socket))
    let buffer = Buffer.alloc(0)
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk])
      while (buffer.length >= 4 && buffer.length >= buffer.readInt32LE(0) + 4) {
        const length = buffer.readInt32LE(0)
        const id = buffer.readInt32LE(4)
        const type = buffer.readInt32LE(8)
        const body = buffer.toString('utf8', 12, length + 2)
        buffer = buffer.subarray(length + 4)
        if (type === 3) socket.write(encode(id, 2, ''))
        else command(socket, id, body)
      }
    })
  })
  t.after(async () => {
    for (const socket of peers) socket.destroy()
    await new Promise(resolve => server.close(resolve))
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  const client = await Rcon.connect({ port: server.address().port, password: 'test-only', requestTimeoutMs: 100 })
  t.after(() => client.close())
  return { client, peer: () => peer }
}

test('idle longer than the request deadline remains usable', { timeout: 2000 }, async t => {
  const { client } = await fixture(t, (socket, id, body) => socket.write(encode(id, 0, body)))
  await delay(150)
  assert.equal(await client.send('list'), 'list')
})

test('idle remote disconnect rejects the next send and queued requests', { timeout: 2000 }, async t => {
  const { client, peer } = await fixture(t, () => {})
  const closed = once(client.socket, 'close')
  peer().end()
  await closed
  await assert.rejects(client.send('list'), /ended|closed/)
  await assert.rejects(client.send('stop'), /ended|closed/)
})

test('mid-request disconnect rejects without waiting for a response', { timeout: 2000 }, async t => {
  const { client } = await fixture(t, socket => socket.end())
  await assert.rejects(client.send('list'), /ended|closed/)
})

test('unanswered request expires and invalidates the connection', { timeout: 2000 }, async t => {
  const { client } = await fixture(t, () => {})
  await assert.rejects(client.send('list'), /deadline/)
  assert.equal(client.socket.destroyed, true)
  await assert.rejects(client.send('stop'), /deadline/)
})

test('write callback errors reject the active request', { timeout: 2000 }, async t => {
  const { client } = await fixture(t, () => {})
  client.socket.write = (packet, callback) => { callback(new Error('injected write failure')); return false }
  await assert.rejects(client.send('list'), /injected write failure/)
})

test('destroyed sockets reject even before the close event', { timeout: 2000 }, async t => {
  const { client } = await fixture(t, () => {})
  client.socket.destroy()
  await assert.rejects(client.send('list'), /not writable|closed/)
})
