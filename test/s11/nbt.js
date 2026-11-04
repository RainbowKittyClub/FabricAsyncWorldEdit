'use strict'

// A minimal NBT writer for hand-built fixtures. Values are tagged: [type, value]; compound values
// are arrays of [name, [type, value]] so field order is kept as written.

const TYPES = { byte: 1, short: 2, int: 3, string: 8, list: 9, compound: 10, byteArray: 7, intArray: 11 }

function string (text) {
  const bytes = Buffer.from(text, 'utf8')
  const length = Buffer.alloc(2)
  length.writeUInt16BE(bytes.length)
  return Buffer.concat([length, bytes])
}

function payload ([type, value]) {
  switch (type) {
    case 'byte': return Buffer.from([value & 0xff])
    case 'short': { const out = Buffer.alloc(2); out.writeInt16BE(value); return out }
    case 'int': { const out = Buffer.alloc(4); out.writeInt32BE(value); return out }
    case 'string': return string(value)
    case 'byteArray': { const out = Buffer.alloc(4); out.writeInt32BE(value.length); return Buffer.concat([out, Buffer.from(value)]) }
    case 'intArray': {
      const out = Buffer.alloc(4 + 4 * value.length)
      out.writeInt32BE(value.length)
      value.forEach((entry, index) => out.writeInt32BE(entry, 4 + 4 * index))
      return out
    }
    case 'list': {
      const [elementType, elements] = value
      const header = Buffer.alloc(5)
      header.writeUInt8(elements.length ? TYPES[elementType] : 0)
      header.writeInt32BE(elements.length, 1)
      return Buffer.concat([header, ...elements.map(element => payload([elementType, element]))])
    }
    case 'compound': return Buffer.concat([...value.map(([name, tag]) => named(name, tag)), Buffer.from([0])])
  }
  throw new Error(`Unknown NBT type ${type}`)
}

const named = (name, tag) => Buffer.concat([Buffer.from([TYPES[tag[0]]]), string(name), payload(tag)])

/** An uncompressed root compound with the given name. */
const root = (name, fields) => named(name, ['compound', fields])

module.exports = { root }
