'use strict'

const { GateError } = require('./rig')
const jvmArgs = ['-Dfawe.experimental.coordinated=true']
async function contract (rig, send, name, native) {
  await send('forceload add 1104 1040')
  await native.chatCommand('/pos1 1105,112,1041', /First position set/)
  await native.chatCommand('/pos2 1105,112,1041', /Second position set/)
  await native.edit('/setbiome desert', async () => (await send('fawepublish wna-biome')) === '{"quartCells":3}')
  rig.results.push({ gate: 's5-wna-biome-focused', status: 'passed' })
}
async function restart (rig, send) {
  await send('forceload add 1104 1040')
  if (await send('fawepublish wna-biome') !== '{"quartCells":3}') throw new GateError('WNA quart biome failed after restart')
}
module.exports = { jvmArgs, contract, restart }
