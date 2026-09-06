import assert from 'node:assert/strict'
import test from 'node:test'
import { AgentSession, AGENT_EVENT_LIMIT } from '../src/features/agent/services/agentSession.ts'
import { AGENT_EVENT_TYPES } from '../src/features/agent/types/agent.types.ts'

const now = () => '2026-09-05T12:00:00.000Z'

test('registra apertura, selección y referencias sin guardar el grafo', () => {
  const session = new AgentSession(7, 11, now)
  session.record('NODE_SELECTED', { nodeId: 'producto' }, now)
  const events = session.snapshot()
  assert.deepEqual(events.map(event => event.type), ['PROJECT_OPENED', 'DIAGRAM_OPENED', 'NODE_SELECTED'])
  assert.equal(events[2].nodeId, 'producto')
  assert.equal('nodes' in session, false)
  assert.equal('edges' in session, false)
})

test('mantiene solamente los 25 eventos más recientes', () => {
  const session = new AgentSession(7, null, now)
  for (let index = 0; index < 40; index += 1) {
    session.record('NODE_UPDATED', { nodeId: `node-${index}` }, now)
  }
  assert.equal(session.snapshot().length, AGENT_EVENT_LIMIT)
  assert.equal(session.snapshot().at(-1)?.nodeId, 'node-39')
})

test('no define eventos por pixel y rechaza tipos desconocidos en runtime', () => {
  assert.equal(AGENT_EVENT_TYPES.includes('NODE_MOVED'), false)
  const session = new AgentSession(7, null, now)
  const before = session.snapshot().length
  assert.equal(session.record('MOUSE_MOVED', {}, now), false)
  assert.equal(session.snapshot().length, before)
})

test('snapshot no permite mutar el buffer interno', () => {
  const session = new AgentSession(7, null, now)
  session.snapshot().push({ type: 'SAVE_FAILED', nodeId: null, edgeId: null, timestamp: now() })
  assert.equal(session.snapshot().length, 2)
})
