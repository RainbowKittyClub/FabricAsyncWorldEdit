'use strict'

const { DatabaseSync } = require('node:sqlite')
const { spawnSync } = require('node:child_process')

// Read-only access to Ledger's database as the lanes see it: the world's SQLite file, or a disposable
// Postgres. Both answer `query(sql, ...params)` with plain row objects; the SQL must run on both, so
// aliases are quoted. Booleans come back as 0 and 1, as SQLite stores them.

function sqliteReader (file) {
  return {
    kind: 'sqlite',
    // A WAL reader can still meet a brief lock while Ledger checkpoints; wait it out.
    query (sql, ...params) {
      const db = new DatabaseSync(file, { readOnly: true, timeout: 10000 })
      try { return db.prepare(sql).all(...params) } finally { db.close() }
    }
  }
}

function postgresReader (postgres) {
  return {
    kind: 'postgres',
    query (sql, ...params) {
      let next = 0
      const text = sql.replace(/\?/g, () => {
        const value = params[next++]
        if (typeof value === 'string') return `'${value.replaceAll("'", "''")}'`
        if (!Number.isFinite(value)) throw new Error(`The Postgres reader takes numbers and strings only: ${value}`)
        return String(value)
      })
      const result = spawnSync('docker', ['exec', postgres.name, 'psql', '-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
        '-U', postgres.user, '-d', postgres.database, '-c', `SELECT COALESCE(json_agg(t), '[]'::json) FROM (${text}) t`],
      { encoding: 'utf8', timeout: 60000, maxBuffer: 256 * 1024 * 1024 })
      if (result.status !== 0) throw new Error(`Postgres query failed: ${result.stderr.trim()}\n${text}`)
      return JSON.parse(result.stdout.trim()).map(row => Object.fromEntries(
        Object.entries(row).map(([key, value]) => [key, typeof value === 'boolean' ? Number(value) : value])))
    }
  }
}

module.exports = { sqliteReader, postgresReader }
