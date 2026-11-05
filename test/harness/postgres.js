'use strict'

const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { tool } = require('./rig')

// A disposable Postgres for one lane: its own container with a random name, credentials and loopback
// port, data on tmpfs and no volume, so nothing of a deployed database is reachable or left behind.
const IMAGE = 'postgres:18-alpine'
// The superuser belongs to the harness (readers, outages); Ledger connects as its own role, which
// owns the database and nothing else, so the harness can cut Ledger off without losing access.
const USER = 'ledger'
const APP_USER = 'ledger_app'
const DATABASE = 'ledger'

const docker = (args, options = {}) => spawnSync('docker', args, { encoding: 'utf8', timeout: 60000, ...options })

async function startPostgres (rig) {
  const name = `fawe-pg-${rig.token.replaceAll('-', '').slice(0, 12)}`
  const password = crypto.randomBytes(18).toString('hex')
  const started = docker(['run', '-d', '--rm', '--name', name, '--label', `claude.scratch=fawe-${rig.manifest.runLabel}-${rig.token}`,
    '-e', `POSTGRES_USER=${USER}`, '-e', `POSTGRES_PASSWORD=${password}`, '-e', `POSTGRES_DB=${DATABASE}`,
    '--tmpfs', '/var/lib/postgresql:rw,size=2g', '-p', '127.0.0.1::5432', IMAGE,
    // Statement statistics, dumped at teardown: calls against rows shows whether inserts are batched.
    '-c', 'shared_preload_libraries=pg_stat_statements'])
  if (started.status !== 0) throw new Error(`Postgres container did not start: ${started.stderr}`)
  const postgres = { name, user: USER, database: DATABASE, password, port: 0, appUser: APP_USER, appPassword: crypto.randomBytes(18).toString('hex') }
  try {
    const mapped = docker(['port', name, '5432/tcp']).stdout.trim().split('\n')[0]
    postgres.port = Number(mapped.slice(mapped.lastIndexOf(':') + 1))
    if (!postgres.port) throw new Error(`No host port for ${name}: ${mapped}`)
    // Over TCP: during initialization the image answers on its socket only.
    await rig.wait('Postgres ready', async () =>
      docker(['exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', USER, '-d', DATABASE]).status === 0, 60000)
    const created = psql(postgres, 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements')
    if (created.status !== 0) throw new Error(`pg_stat_statements unavailable: ${created.stderr}`)
    const role = psql(postgres, `CREATE ROLE ${APP_USER} LOGIN PASSWORD '${postgres.appPassword}'; ` +
      `ALTER DATABASE ${DATABASE} OWNER TO ${APP_USER}`)
    if (role.status !== 0) throw new Error(`Ledger role not created: ${role.stderr}`)
  } catch (error) {
    stopPostgres(postgres)
    throw error
  }
  return postgres
}

// One statement through psql in the container.
const psql = (postgres, sql, ...flags) => docker(['exec', postgres.name, 'psql', '-X', '-q', '-v', 'ON_ERROR_STOP=1',
  '-U', postgres.user, '-d', postgres.database, ...flags, '-c', sql])

// The statements that cost the most database time, with calls against rows.
const STATEMENTS = `SELECT calls, rows, round(mean_exec_time::numeric, 2) AS mean_ms, round(total_exec_time::numeric) AS total_ms,
  left(regexp_replace(query, '\\s+', ' ', 'g'), 110) AS query FROM pg_stat_statements
  WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) AND query NOT LIKE '%pg_stat_statements%'
  ORDER BY total_exec_time DESC LIMIT 15`

// Removes exactly this lane's container, keeping its log and statement statistics beside the lane's logs.
function stopPostgres (postgres, logFile) {
  if (!postgres) return
  if (logFile) {
    const logs = docker(['logs', postgres.name])
    fs.writeFileSync(logFile, `${logs.stdout}${logs.stderr}`)
    const statements = psql(postgres, STATEMENTS)
    fs.writeFileSync(logFile.replace(/\.log$/, '-statements.txt'), statements.stdout || statements.stderr)
  }
  docker(['rm', '-f', postgres.name])
}

// Ledger's config with the extension section appended: the file the suite wrote, else Ledger's defaults.
function writeExtensionConfig (serverDir, manifest, postgres) {
  const file = path.join(serverDir, 'config/ledger.toml')
  fs.mkdirSync(path.dirname(file), { recursive: true })
  const existing = fs.existsSync(file) ? fs.readFileSync(file, 'utf8')
    : `${tool('unzip', ['-p', manifest.availableDependencies['ledger-rkc'].path, 'ledger.toml']).trimEnd()}\n`
  fs.writeFileSync(file, `${existing.trimEnd()}\n\n[database_extensions]\ndatabase = "POSTGRESQL"\n` +
    `url = "127.0.0.1:${postgres.port}/${postgres.database}"\nusername = "${postgres.appUser}"\n` +
    `password = "${postgres.appPassword}"\nproperties = []\nmaxPoolSize = 10\nconnectionTimeout = 60000\n`)
}

// A database outage Ledger can see: its role may not log in and its open sessions are ended, so a
// commit fails and a new connection is refused, while the data stays. `on` and `off` toggle it.
function outage (postgres, on) {
  const result = psql(postgres, on
    ? `ALTER ROLE ${APP_USER} NOLOGIN; SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '${APP_USER}'`
    : `ALTER ROLE ${APP_USER} LOGIN`)
  if (result.status !== 0) throw new Error(`Postgres outage ${on ? 'start' : 'end'} failed: ${result.stderr}`)
}

module.exports = { startPostgres, stopPostgres, writeExtensionConfig, outage, docker }
