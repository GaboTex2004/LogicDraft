import test from 'node:test'
import assert from 'node:assert/strict'
import {
  mergeVoiceTranscripts,
  VoiceActivityDetector,
  VoiceTranscriptSession,
  VOICE_CHUNK_MS,
  VOICE_SILENCE_MS,
} from '../src/features/diagram/services/voiceTranscriptSession.ts'
import { MediaRecorderVoiceController } from '../src/features/diagram/services/mediaRecorderVoiceController.ts'

test('chunk transcripts stay ordered when requests finish out of order', () => {
  const voice = new VoiceTranscriptSession()
  assert.equal(voice.addChunk(1, 'a Materia'), '')
  assert.equal(voice.addChunk(0, 'Agrega codigo a Alumno y'), 'Agrega codigo a Alumno y a Materia')
  assert.equal(voice.addChunk(2, 'con tipo STRING'), 'Agrega codigo a Alumno y a Materia con tipo STRING')
})

test('overlap is merged without deleting legitimate repeated digits', () => {
  assert.equal(mergeVoiceTranscripts('crea un teléfono con', 'teléfono con 7 7 8 8'),
    'crea un teléfono con 7 7 8 8')
  assert.equal(mergeVoiceTranscripts('número 700', '700 001'), 'número 700 700 001')
})

test('manual stop preserves transcript and prevents automatic submission', () => {
  const voice = new VoiceTranscriptSession()
  voice.addChunk(0, 'texto conservado')
  voice.stopManually()
  assert.equal(voice.text(), 'texto conservado')
  assert.equal(voice.takeAutoSubmit(), null)
  voice.reset()
  voice.addChunk(0, 'segunda grabación')
  assert.equal(voice.takeAutoSubmit(), 'segunda grabación')
  assert.equal(voice.takeAutoSubmit(), null)
})

test('silence only finishes after actual speech and the configured margin', () => {
  assert.equal(VOICE_SILENCE_MS, 1500)
  assert.equal(VOICE_CHUNK_MS, 2000)
  const detector = new VoiceActivityDetector()
  assert.equal(detector.observe(0, 5000), false)
  assert.equal(detector.observe(0.04, 6000), false)
  assert.equal(detector.observe(0, 7499), false)
  assert.equal(detector.observe(0, 7500), true)
})

class MockRecorder {
  static instances = []
  state = 'inactive'
  mimeType
  listeners = new Map()

  constructor(_stream, options) {
    this.mimeType = options?.mimeType ?? 'audio/webm'
    MockRecorder.instances.push(this)
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) ?? []
    listeners.push(listener)
    this.listeners.set(type, listeners)
  }

  emit(type, event = {}) {
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }

  start() { this.state = 'recording' }

  stop() {
    this.state = 'inactive'
    this.emit('dataavailable', { data: new Blob(['recorded-audio'], { type: this.mimeType }) })
    this.emit('stop')
  }
}

function mockCapture() {
  let now = 0
  let loud = false
  let frame
  let scheduled
  let stoppedTracks = 0
  const analyser = {
    fftSize: 32,
    getByteTimeDomainData(samples) { samples.fill(loud ? 140 : 128) },
  }
  const context = {
    createMediaStreamSource() { return { connect() {}, disconnect() {} } },
    createAnalyser() { return analyser },
    async close() {},
  }
  const stream = { getTracks: () => [{ stop: () => { stoppedTracks += 1 } }] }
  const environment = {
    getUserMedia: async () => stream,
    createRecorder: (value, options) => new MockRecorder(value, options),
    supportsMimeType: type => type === 'audio/webm;codecs=opus',
    createAudioContext: () => context,
    requestFrame: callback => { frame = callback; return 1 },
    cancelFrame: () => { frame = undefined },
    now: () => now,
    schedule: callback => { scheduled = callback; return 2 },
    cancelSchedule: () => { scheduled = undefined },
  }
  return {
    environment,
    setNow: value => { now = value },
    setLoud: value => { loud = value },
    runFrame: () => frame?.(now),
    runScheduled: () => scheduled?.(),
    stoppedTracks: () => stoppedTracks,
  }
}

const settle = () => new Promise(resolve => setTimeout(resolve, 0))

test('MediaRecorder/getUserMedia/AudioContext flow flushes audio and manual stop never submits', async () => {
  MockRecorder.instances = []
  const capture = mockCapture()
  const statuses = []
  const transcripts = []
  const submitted = []
  const requests = []
  const controller = new MediaRecorderVoiceController(capture.environment,
    async (blob, filename) => {
      requests.push({ size: blob.size, filename })
      return 'Crea una entidad Cliente'
    }, {
      onStatus: status => statuses.push(status),
      onTranscript: text => transcripts.push(text),
      onError: () => {},
      onAutoSubmit: text => submitted.push(text),
    })

  await controller.start()
  controller.stopManually()
  await settle()

  assert.equal(MockRecorder.instances.length, 1)
  assert.deepEqual(requests, [{ size: 14, filename: 'voice-0.webm' }])
  assert.equal(transcripts.at(-1), 'Crea una entidad Cliente')
  assert.deepEqual(submitted, [])
  assert.deepEqual(statuses, ['listening', 'processing', 'idle'])
  assert.equal(capture.stoppedTracks(), 1)
})

test('periodic complete chunks update the editable transcript progressively', async () => {
  MockRecorder.instances = []
  const capture = mockCapture()
  const transcripts = []
  const controller = new MediaRecorderVoiceController(capture.environment,
    async (_blob, filename) => filename === 'voice-0.webm' ? 'Crea una entidad' : 'entidad Cliente', {
      onStatus: () => {}, onTranscript: text => transcripts.push(text), onError: () => {}, onAutoSubmit: () => {},
    })

  await controller.start()
  capture.runScheduled()
  await settle()
  assert.equal(transcripts.at(-1), 'Crea una entidad')
  assert.equal(MockRecorder.instances.length, 2)
  controller.stopManually()
  await settle()
  assert.equal(transcripts.at(-1), 'Crea una entidad Cliente')
})

test('detected speech followed by silence flushes the final chunk and auto-submits once', async () => {
  MockRecorder.instances = []
  const capture = mockCapture()
  const submitted = []
  const controller = new MediaRecorderVoiceController(capture.environment,
    async () => 'Agrega nombre a Cliente', {
      onStatus: () => {}, onTranscript: () => {}, onError: () => {},
      onAutoSubmit: text => submitted.push(text),
    })

  await controller.start()
  capture.setLoud(true)
  capture.setNow(100)
  capture.runFrame()
  capture.setLoud(false)
  capture.setNow(1600)
  capture.runFrame()
  await settle()

  assert.deepEqual(submitted, ['Agrega nombre a Cliente'])
})

test('natural finish waits for the final in-flight transcription before submitting', async () => {
  const capture = mockCapture()
  const submitted = []
  let resolveTranscription
  const transcription = new Promise(resolve => { resolveTranscription = resolve })
  const controller = new MediaRecorderVoiceController(capture.environment,
    () => transcription, {
      onStatus: () => {}, onTranscript: () => {}, onError: () => {},
      onAutoSubmit: text => submitted.push(text),
    })
  await controller.start()
  capture.setLoud(true)
  capture.setNow(10)
  capture.runFrame()
  capture.setLoud(false)
  capture.setNow(1510)
  capture.runFrame()
  await settle()
  assert.deepEqual(submitted, [])
  resolveTranscription('Crea Cliente')
  await settle()
  assert.deepEqual(submitted, ['Crea Cliente'])
})

test('natural finish with an empty transcript does not invoke Gemini', async () => {
  const capture = mockCapture()
  const submitted = []
  const errors = []
  const controller = new MediaRecorderVoiceController(capture.environment,
    async () => '', {
      onStatus: () => {}, onTranscript: () => {}, onError: value => { if (value) errors.push(value) },
      onAutoSubmit: text => submitted.push(text),
    })
  await controller.start()
  capture.setLoud(true)
  capture.setNow(20)
  capture.runFrame()
  capture.setLoud(false)
  capture.setNow(1520)
  capture.runFrame()
  await settle()
  assert.deepEqual(submitted, [])
  assert.match(errors.at(-1), /No se detectó voz/)
})

test('empty speech and transcription failure are controlled and do not auto-submit', async () => {
  const capture = mockCapture()
  const errors = []
  const submitted = []
  const controller = new MediaRecorderVoiceController(capture.environment,
    async () => { throw new Error('mock endpoint failure') }, {
      onStatus: () => {}, onTranscript: () => {}, onError: value => { if (value) errors.push(value) },
      onAutoSubmit: text => submitted.push(text),
    })
  await controller.start()
  controller.stopManually()
  await settle()
  assert.deepEqual(submitted, [])
  assert.match(errors.at(-1), /Revisa el texto/)
})
