'use strict'

// Kept separate from the accepted PPB lanes until the bridge artifacts and probe are pinned.
const { canonical, probe } = require('./common')
const Y = 80
const AREA = '7792 7792 7887 7855'
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
const at = b => `${b.x1} ${Y} ${b.z1}`
const box = (x, z) => ({ name: 'position-data', x1: x, x2: x, y1: Y, y2: Y, z1: z, z2: z, padding: 2 })
const views = area => ({ cells: area.cells.map(c => ({ state: canonical(c.state), blockEntity: c.blockEntity })),
  displays: area.displays.filter(d => d.at.split(',').map(Number).every(value => value >= -2 && value <= 3))
    .map(({ at, shows, item, text }) => ({ at, shows, item, text })).sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))), drops: area.drops })
const emptyView = () => ({ cells: [{ state: 'minecraft:air' }], displays: [], drops: [] })
const empty = { data: 'none', clients: { plain: emptyView(), pack: emptyView() } }

module.exports = ({ rig, check }) => {
  let send, name, client, watcher, chat, cases
  const saved = []
  const read = async b => (await probe(send, `positions get ${at(b)}`)).payload
  const setData = async (c, b, present = true) => probe(send,
    `positions ${present ? 'set' : 'absent'} ${at(b)} ${c.owner}${present ? ' ' + c.value : ''}`)
  const select = async b => {
    await chat(`/pos1 ${b.x1},${Y},${b.z1}`, /First position set|Position already set/)
    await chat(`/pos2 ${b.x1},${Y},${b.z1}`, /Second position set|Position already set/)
  }
  const move = async b => {
    await send(`tp ${name} ${b.x1 + 0.5} 82 ${b.z1 + 4.5} 180 20`)
    await send(`tp FaweShelfPack ${b.x1 + 0.5} 82 ${b.z1 + 4.5} 180 20`)
  }
  const snapshot = async b => {
    const clients = {}
    for (const [label, observer] of [['plain', client], ['pack', watcher]]) {
      const area = (await observer.send({ action: 'region', boxes: [b] })).boxes?.[b.name]
      if (!area?.loaded) return null
      clients[label] = views(area)
    }
    return { data: await read(b), clients }
  }
  const stable = async (b, wantedState) => {
    let value, fingerprint, since
    await rig.wait('position-data reference received', async () => {
      value = await snapshot(b)
      if (!value || !Object.values(value.clients).every(v =>
        !['minecraft:air', 'minecraft:void_air', 'minecraft:cave_air'].includes(v.cells[0].state) &&
        (!wantedState || (typeof wantedState === 'function' ? wantedState(v.cells[0].state)
          : v.cells[0].state === canonical(wantedState))))) return false
      const next = JSON.stringify(value)
      if (next !== fingerprint) { fingerprint = next; since = Date.now() }
      return Date.now() - since >= 500
    }, 30000, [client.entry, watcher.entry])
    return value
  }
  const expect = async (c, label, b, wanted) => {
    let seen, okay = false
    try {
      await rig.wait(`position-data ${c.owner} ${label}`, async () => {
        seen = await snapshot(b)
        return (okay = seen !== null && same(seen, wanted))
      }, 25000, [client.entry, watcher.entry])
    } catch (error) { if (!/^Timeout waiting for/.test(error.message)) throw error }
    check(`positions:${c.owner}:${label}`, okay, okay ? undefined : { seen, wanted })
    if (!okay) throw new Error(`Position-data parity failed: ${c.owner} ${label}`)
  }
  const clipboard = async (source, target) => {
    await select(source)
    await send(`tp ${name} ${source.x1 - 0.5} ${Y} ${source.z1 + 0.5}`)
    await chat('/copy', /blocks were copied|blocks affected/)
    await send(`tp ${name} ${target.x1 - 0.5} ${Y} ${target.z1 + 0.5}`)
    await chat('/paste', /clipboard has been pasted/)
    await chat('/clearclipboard', /Clipboard cleared/)
    await move(target)
  }
  return {
    async contract (context) {
      ;({ send, name, client, watcher, chat } = context)
      const owners = await probe(send, 'positions list')
      check('positions:every-provisioned-owner-discovered', owners['chopt:placed_logs'] === 1 &&
        owners['terraform:small_log_leaves'] === 1 && Object.keys(owners).length === 2, owners)
      const catalog = (await probe(send, 'catalog terrestria')).blocks
      const log = [...catalog].sort((a, b) => a.id.localeCompare(b.id)).find(c => c.class.endsWith('.SmallLogBlock'))
      if (!log) throw new Error('No provisioned Terraform small log')
      cases = [
        { owner: 'chopt:placed_logs', state: 'minecraft:oak_log[axis=y]', value: '{present:1b}' },
        { owner: 'terraform:small_log_leaves', state: log.state.replace('has_leaves=false', 'has_leaves=true'), value: '{value:"minecraft:birch_leaves"}' }
      ]
      await send(`forceload add ${AREA}`)
      await send('gamerule random_tick_speed 0')
      await chat('/perf validation off', /set to|is already/)
      for (const [i, c] of cases.entries()) {
        const b = box(7808, 7808 + i * 24)
        const source = box(b.x1 + 4, b.z1)
        const unrelated = box(b.x1 + 8, b.z1)
        const replay = box(b.x1 + 32, b.z1)
        await send(`fill ${b.x1 - 2} 79 ${b.z1 - 2} ${b.x1 + 36} 79 ${b.z1 + 6} minecraft:dirt strict`)
        await send(`fill ${b.x1 - 2} 80 ${b.z1 - 2} ${b.x1 + 36} 83 ${b.z1 + 6} minecraft:air strict`)
        for (const cell of [b, source, unrelated, replay]) {
          await send(`setblock ${at(cell)} ${c.state} strict`)
          await setData(c, cell)
        }
        await move(b)
        const expectedLeaves = c.owner === 'terraform:small_log_leaves'
          ? 'minecraft:birch_leaves[distance=7,persistent=true,waterlogged=false]' : undefined
        const marked = await stable(b, expectedLeaves)
        if (expectedLeaves) check('positions:terraform:reference-shows-declared-leaves',
          Object.values(marked.clients).every(v => v.cells[0].state === canonical(expectedLeaves)), marked)
        await setData(c, b, false)
        const absent = await stable(b, expectedLeaves ? state => state !== canonical(expectedLeaves) : undefined)
        check(`positions:${c.owner}:ordinary-absence-is-distinct`, absent.data !== marked.data && absent.data !== 'none',
          { absent: absent.data, marked: marked.data })
        await setData(c, b)
        await expect(c, 'ordinary-mark-reference-restored', b, marked)
        const turnedState = c.state.replace('axis=y', 'axis=x')
        check(`positions:${c.owner}:ordinary-axis-reference-is-change`, turnedState !== c.state)
        if (turnedState === c.state) throw new Error('Position-data fixture needs an actual axis change')
        await send(`setblock ${at(b)} ${turnedState} strict`)
        const turned = await stable(b, expectedLeaves || turnedState)
        check(`positions:${c.owner}:ordinary-axis-change-keeps-data`, turned.data === marked.data, turned)
        await send(`setblock ${at(b)} ${c.state} strict`)
        await expect(c, 'ordinary-axis-reference-restored', b, marked)
        const untouched = await read(unrelated)
        const sections = (await probe(send, 'statistics')).sections
        await send(`setblock ${at(b)} minecraft:air strict`)
        check(`positions:${c.owner}:ordinary-deletion-cleans-up`, await read(b) === 'none')
        await expect(c, 'ordinary-deletion-clears-views', b, empty)
        await clipboard(source, b)
        await expect(c, 'marked-clipboard-copy', b, marked)
        for (const route of ['native', 'bulk']) {
          await setData(c, b, false)
          const result = await probe(send, `api-copy ${route} ${name} ${at(source)} ${at(b)} ${at(b)}`)
          await rig.wait('position-data API publication', async () =>
            (await probe(send, `api result ${result.id}`)).done, 60000, [client.entry, watcher.entry])
          const outcome = await probe(send, `api result ${result.id}`)
          check(`positions:${c.owner}:api-${route}-finished`, !outcome.error, outcome)
          await expect(c, `api-${route}-copy`, b, marked)
        }
        await setData(c, source, false)
        await clipboard(source, b)
        await expect(c, 'unmarked-copy-matches-ordinary', b, absent)
        check(`positions:${c.owner}:unmarked-copy-clears-mark`, await read(b) === absent.data && absent.data !== marked.data,
          { absent: absent.data, marked: marked.data })
        await chat('/undo', /Undid/)
        await expect(c, 'unmarked-copy-undo-is-exact', b, marked)
        await chat('/redo', /Redid/)
        await expect(c, 'unmarked-copy-redo-is-exact', b, absent)
        await chat('/undo', /Undid/)
        await select(b)
        await chat(`/set ${turnedState}`, /Operation completed/)
        check(`positions:${c.owner}:same-kind-pattern-changes-axis`,
          await send(`execute if block ${at(b)} ${turnedState}`) === 'Test passed')
        await expect(c, 'ordinary-same-kind-pattern-retains-data', b, turned)
        await chat('/undo', /Undid/)
        check(`positions:${c.owner}:same-kind-undo-restores-axis`,
          await send(`execute if block ${at(b)} ${c.state}`) === 'Test passed')
        await expect(c, 'same-kind-undo-is-exact', b, marked)
        await chat('/set air', /Operation completed/)
        check(`positions:${c.owner}:fawe-deletion-cleans-up`, await read(b) === 'none')
        await expect(c, 'deletion-clears-views', b, empty)
        await chat('/undo', /Undid/)
        await expect(c, 'deletion-undo-is-exact', b, marked)
        await chat('/redo', /Redid/)
        check(`positions:${c.owner}:deletion-redo-cleans-up`, await read(b) === 'none')
        await expect(c, 'deletion-redo-clears-views', b, empty)
        await chat('/undo', /Undid/)
        await expect(c, 'restored-for-restart', b, marked)
        check(`positions:${c.owner}:unrelated-mark-unchanged`, await read(unrelated) === untouched)
        check(`positions:${c.owner}:never-direct-install`, (await probe(send, 'statistics')).sections === sections)
        const payload = await read(b)
        for (const [label, invalid] of [
          ['missing-owner', '{}'],
          ['unknown-owner', '{"unknown:position":{version:1}}'],
          ['version', payload.replace(/"version":1/g, '"version":99')],
          ['missing-version', payload.replace(/,"version":1/g, '').replace(/"version":1,?/g, '')],
          ['malformed-value', c.owner === 'chopt:placed_logs' ? payload.replace('"present":1b', '"present":0b')
            : payload.replace('minecraft:birch_leaves', 'minecraft:not_a_block')]
        ]) {
          const result = await probe(send, `positions validate ${at(b)} ${invalid}`)
          check(`positions:${c.owner}:${label}-refused-without-mutation`, result.refused && result.unchanged, result)
        }
        const refusedTarget = box(b.x1 + 48, b.z1)
        await send(`setblock ${at(refusedTarget)} minecraft:stone strict`)
        await probe(send, `positions unknown ${at(source)}`)
        for (const route of ['native', 'bulk']) {
          const result = await probe(send, `api-copy ${route} ${name} ${at(source)} ${at(refusedTarget)} ${at(refusedTarget)}`)
          await rig.wait('unknown attachment API refusal', async () =>
            (await probe(send, `api result ${result.id}`)).done, 60000, [client.entry, watcher.entry])
          const outcome = await probe(send, `api result ${result.id}`)
          check(`positions:${c.owner}:unknown-attachment-${route}-refused`,
            /Attachment lifecycle\/restore codecs are not installed/.test(outcome.error || ''), outcome)
          check(`positions:${c.owner}:unknown-attachment-${route}-writes-nothing`,
            await send(`execute if block ${at(refusedTarget)} minecraft:stone`) === 'Test passed')
        }
        await probe(send, `positions clear-unknown ${at(source)}`)
        check(`positions:${c.owner}:refusal-preserves-existing-marks`, await read(b) === payload && await read(unrelated) === untouched)
        await move(replay)
        const replayReference = await stable(replay, expectedLeaves)
        await select(replay)
        await chat('/set air', /Operation completed/)
        await send(`tp ${name} ${replay.x1 + 0.5} ${Y} ${replay.z1 + 2.5}`)
        await chat('ledger rollback action:fawe-block range:3 after:1h', /only applied actions were marked|No results for that search/)
        await expect(c, 'ledger-rollback-is-exact', replay, replayReference)
        await chat('ledger restore action:fawe-block range:3 after:1h', /only applied actions were marked|No results for that search/)
        check(`positions:${c.owner}:ledger-restore-cleans-up`, await read(replay) === 'none')
        await expect(c, 'ledger-restore-clears-views', replay, empty)
        await chat('ledger rollback action:fawe-block range:3 after:1h', /only applied actions were marked|No results for that search/)
        await expect(c, 'ledger-second-rollback-is-exact', replay, replayReference)
        saved.push({ c, b, marked, replay, replayReference, unrelated, untouched })
      }
      await send('save-all flush')
      await send(`forceload remove ${AREA}`)
      await send(`tp ${name} 1024.5 80 1024.5`)
      await send('tp FaweShelfPack 1024.5 80 1024.5')
      await rig.wait('position-data chunks actually unloaded', async () => {
        const states = []
        for (const b of saved.flatMap(s => [s.b, s.replay, s.unrelated])) states.push(await probe(send, `block-info ${at(b)}`))
        return states.every(state => !state.loaded)
      }, 45000, [client.entry, watcher.entry])
      check('positions:chunks-actually-unloaded', true)
      await send(`forceload add ${AREA}`)
      for (const s of saved) {
        await move(s.b)
        await expect(s.c, 'chunk-reload-is-exact', s.b, s.marked)
        await move(s.replay)
        await expect(s.c, 'replay-chunk-reload-is-exact', s.replay, s.replayReference)
      }
    },
    async restart (context) {
      ;({ send, name, client, watcher } = context)
      await send(`forceload add ${AREA}`)
      for (const s of saved) {
        await move(s.b)
        await expect(s.c, 'restart-is-exact', s.b, s.marked)
        await move(s.replay)
        await expect(s.c, 'replay-restart-is-exact', s.replay, s.replayReference)
        check(`positions:${s.c.owner}:unrelated-mark-survives-restart`, await read(s.unrelated) === s.untouched)
      }
    }
  }
}
