'use strict'

// Focused owner-bridge proof; adapters also runs this fixture in the complete consumer matrix.
const fs = require('node:fs')
const path = require('node:path')
const { startClient } = require('../harness/native')

module.exports = ({ rig, check, paced }) => {
  const fixture = require('./position-data')({ rig, check })
  const repo = path.resolve(__dirname, '../..')
  const watcher = async label => {
    const port = Number(/^server-port=(\d+)$/m.exec(fs.readFileSync(path.join(rig.root, 'server/server.properties'), 'utf8'))[1])
    return startClient(rig, repo, { name: 'FaweShelfPack', port, pack: true, label })
  }
  return {
    clientOptions: { pack: false },
    async configureServer (serverDir) {
      fs.mkdirSync(path.join(serverDir, 'config/polymer'), { recursive: true })
      fs.writeFileSync(path.join(serverDir, 'config/polymer/auto-host.json'), JSON.stringify({ enabled: true, required: false,
        mod_override: false, resource_pack_status_dialog: false, allow_joining_after_generation_failure: false }))
    },
    async contract (rig_, send, name, { client, chatCommand }) {
      const pack = await watcher('positions-pack')
      await fixture.contract({ send, name, client, watcher: pack, chat: paced(send, client, chatCommand) })
      await rig.stop(pack.entry)
    },
    async restart (rig_, send, name, { client }) {
      const pack = await watcher('positions-pack-restart')
      await fixture.restart({ send, name, client, watcher: pack })
      await rig.stop(pack.entry)
    }
  }
}
