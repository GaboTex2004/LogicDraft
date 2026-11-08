import test from 'node:test'
import assert from 'node:assert/strict'
import { createTutorNarrator } from '../src/features/tutor/services/tutorNarrator.ts'

function fixture() {
  const players = [], messages = []
  const narrator = createTutorNarrator(() => {
    const player = { src: '', currentTime: 0, pauses: 0, plays: 0, onended: null, onerror: null,
      async play() { this.plays++ }, pause() { this.pauses++ } }
    players.push(player); return player
  }, message => messages.push(message))
  return { narrator, players, messages }
}
test('un solo audio, sin repetir por renderizados y repetición explícita', async () => {
  const { narrator, players } = fixture()
  await narrator.play('step1', '/one.wav'); await narrator.play('step1', '/one.wav')
  assert.equal(players.length, 1)
  await narrator.play('step1', '/one.wav', true)
  assert.equal(players.length, 2); assert.equal(players[0].src, '')
  await narrator.play('step2', '/two.wav')
  assert.equal(players[1].src, ''); assert.equal(players[2].src, '/two.wav')
})
test('silenciar/cancelar retira listeners y detiene el reproductor', async () => {
  const { narrator, players } = fixture()
  await narrator.play('step', '/one.wav')
  narrator.stop(); narrator.stop()
  assert.equal(players[0].src, ''); assert.equal(players[0].onended, null); assert.equal(players[0].onerror, null)
})
test('final del audio sólo actualiza estado de reproducción', async () => {
  const { narrator, players, messages } = fixture()
  await narrator.play('step', '/one.wav'); players[0].onended()
  assert.deepEqual(messages, ['']); assert.equal(players.length, 1)
})
test('rechazo tardío de play no muestra error en el siguiente paso', async () => {
  let rejectPlay
  const messages = []
  const narrator = createTutorNarrator(() => ({ src: '', currentTime: 0, onended: null, onerror: null, pause() {}, play: () => new Promise((_, reject) => { rejectPlay = reject }) }), message => messages.push(message))
  const playing = narrator.play('step', '/one.wav')
  narrator.stop(); rejectPlay(new Error('late')); await playing
  assert.deepEqual(messages, [])
})
