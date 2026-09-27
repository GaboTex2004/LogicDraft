import {
  VOICE_CHUNK_MS,
  VoiceActivityDetector,
  VoiceTranscriptSession,
  voiceCaptureErrorMessage,
} from './voiceTranscriptSession.ts'

export type VoiceCaptureStatus = 'idle' | 'listening' | 'processing'

export type VoiceCaptureCallbacks = {
  onStatus: (status: VoiceCaptureStatus) => void
  onTranscript: (text: string) => void
  onError: (message: string) => void
  onAutoSubmit: (text: string) => void
}

export type VoiceCaptureEnvironment = {
  getUserMedia: () => Promise<MediaStream>
  createRecorder: (stream: MediaStream, options?: MediaRecorderOptions) => MediaRecorder
  supportsMimeType: (mimeType: string) => boolean
  createAudioContext: () => AudioContext
  requestFrame: (callback: FrameRequestCallback) => number
  cancelFrame: (handle: number) => void
  now: () => number
  schedule: (callback: () => void, delayMs: number) => number
  cancelSchedule: (handle: number) => void
}

const MIME_TYPES = [
  'audio/webm;codecs=opus',
  'audio/webm',
  'audio/ogg;codecs=opus',
  'audio/mp4',
]

function extensionFor(mimeType: string): string {
  if (mimeType.includes('ogg')) return 'ogg'
  if (mimeType.includes('mp4')) return 'mp4'
  return 'webm'
}

function audioLevel(analyser: AnalyserNode, samples: Uint8Array<ArrayBuffer>): number {
  analyser.getByteTimeDomainData(samples)
  let squareSum = 0
  for (const sample of samples) {
    const normalized = (sample - 128) / 128
    squareSum += normalized * normalized
  }
  return Math.sqrt(squareSum / samples.length)
}

export function browserVoiceEnvironment(): VoiceCaptureEnvironment | null {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return null
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') return null
  const AudioContextConstructor = window.AudioContext
  if (!AudioContextConstructor) return null
  return {
    getUserMedia: () => navigator.mediaDevices.getUserMedia({ audio: true }),
    createRecorder: (stream, options) => new MediaRecorder(stream, options),
    supportsMimeType: mimeType => MediaRecorder.isTypeSupported(mimeType),
    createAudioContext: () => new AudioContextConstructor(),
    requestFrame: callback => window.requestAnimationFrame(callback),
    cancelFrame: handle => window.cancelAnimationFrame(handle),
    now: () => performance.now(),
    schedule: (callback, delayMs) => window.setTimeout(callback, delayMs),
    cancelSchedule: handle => window.clearTimeout(handle),
  }
}

export class MediaRecorderVoiceController {
  private readonly environment: VoiceCaptureEnvironment
  private readonly transcribe: (audio: Blob, filename: string) => Promise<string>
  private readonly callbacks: VoiceCaptureCallbacks
  private readonly transcript = new VoiceTranscriptSession()
  private readonly activity = new VoiceActivityDetector()
  private stream: MediaStream | null = null
  private recorder: MediaRecorder | null = null
  private audioContext: AudioContext | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private analyser: AnalyserNode | null = null
  private animationFrame: number | null = null
  private rotationTimer: number | null = null
  private segmentParts: Blob[] = []
  private pending = new Set<Promise<void>>()
  private nextChunkIndex = 0
  private ending: 'manual' | 'natural' | 'error' | null = null
  private finalized = false
  private failedChunk = false
  private chunkErrorMessage = ''
  private disposed = false
  private mimeType = ''

  constructor(
    environment: VoiceCaptureEnvironment,
    transcribe: (audio: Blob, filename: string) => Promise<string>,
    callbacks: VoiceCaptureCallbacks,
  ) {
    this.environment = environment
    this.transcribe = transcribe
    this.callbacks = callbacks
  }

  async start(): Promise<void> {
    if (this.stream || this.recorder) return
    this.transcript.reset()
    this.activity.reset()
    this.nextChunkIndex = 0
    this.ending = null
    this.finalized = false
    this.failedChunk = false
    this.chunkErrorMessage = ''
    this.disposed = false
    this.callbacks.onError('')
    this.callbacks.onTranscript('')
    try {
      this.stream = await this.environment.getUserMedia()
      if (this.disposed) {
        this.stopTracks()
        return
      }
      this.mimeType = MIME_TYPES.find(type => this.environment.supportsMimeType(type)) ?? ''
      this.setupAudioAnalysis()
      this.startSegment()
      this.callbacks.onStatus('listening')
    } catch (error: unknown) {
      this.cleanupCapture()
      this.callbacks.onStatus('idle')
      this.callbacks.onError(voiceCaptureErrorMessage(error))
    }
  }

  stopManually(): void {
    if (!this.stream || this.ending) return
    this.transcript.stopManually()
    this.requestStop('manual')
  }

  dispose(): void {
    this.disposed = true
    this.transcript.stopManually()
    this.ending = 'manual'
    if (this.recorder?.state === 'recording') this.recorder.stop()
    this.cleanupCapture()
  }

  private setupAudioAnalysis(): void {
    if (!this.stream) return
    this.audioContext = this.environment.createAudioContext()
    this.source = this.audioContext.createMediaStreamSource(this.stream)
    this.analyser = this.audioContext.createAnalyser()
    this.analyser.fftSize = 2048
    this.source.connect(this.analyser)
    const samples = new Uint8Array(this.analyser.fftSize)
    const inspect = () => {
      if (!this.analyser || this.ending) return
      if (this.activity.observe(audioLevel(this.analyser, samples), this.environment.now())) {
        this.requestStop('natural')
        return
      }
      this.animationFrame = this.environment.requestFrame(inspect)
    }
    this.animationFrame = this.environment.requestFrame(inspect)
  }

  private startSegment(): void {
    if (!this.stream || this.ending) return
    this.segmentParts = []
    const options = this.mimeType ? { mimeType: this.mimeType } : undefined
    const recorder = this.environment.createRecorder(this.stream, options)
    this.recorder = recorder
    recorder.addEventListener('dataavailable', event => {
      if (event.data.size > 0) this.segmentParts.push(event.data)
    })
    recorder.addEventListener('error', () => {
      this.callbacks.onError('Ocurrió un error durante la grabación de voz.')
      this.requestStop('error')
    })
    recorder.addEventListener('stop', () => {
      const type = recorder.mimeType || this.mimeType || 'audio/webm'
      const blob = new Blob(this.segmentParts, { type })
      if (blob.size > 0) this.queueTranscription(blob, extensionFor(type))
      this.recorder = null
      if (this.ending) {
        void this.finalize()
      } else {
        this.startSegment()
      }
    })
    recorder.start()
    this.rotationTimer = this.environment.schedule(() => {
      if (!this.ending && recorder.state === 'recording') recorder.stop()
    }, VOICE_CHUNK_MS)
  }

  private queueTranscription(blob: Blob, extension: string): void {
    const index = this.nextChunkIndex
    this.nextChunkIndex += 1
    const request: Promise<void> = this.transcribe(blob, `voice-${index}.${extension}`)
      .then(text => {
        const merged = this.transcript.addChunk(index, text)
        if (!this.disposed) this.callbacks.onTranscript(merged)
      })
      .catch((error: unknown) => {
        this.failedChunk = true
        this.chunkErrorMessage = error instanceof Error ? error.message : 'No se pudo transcribir el audio.'
        this.transcript.addChunk(index, '')
      })
      .finally(() => this.pending.delete(request))
    this.pending.add(request)
  }

  private requestStop(mode: 'manual' | 'natural' | 'error'): void {
    if (this.ending) return
    this.ending = mode
    if (this.rotationTimer !== null) this.environment.cancelSchedule(this.rotationTimer)
    this.rotationTimer = null
    if (mode !== 'error') this.callbacks.onStatus('processing')
    if (this.recorder?.state === 'recording') {
      this.recorder.stop()
    } else {
      void this.finalize()
    }
  }

  private async finalize(): Promise<void> {
    if (this.finalized) return
    this.finalized = true
    this.cleanupCapture()
    while (this.pending.size > 0) {
      await Promise.allSettled([...this.pending])
    }
    if (this.disposed) return

    const text = this.transcript.text()
    this.callbacks.onTranscript(text)
    if (this.failedChunk) {
      this.callbacks.onError(`${this.chunkErrorMessage} Revisa el texto antes de enviarlo.`)
    } else if (this.ending === 'natural') {
      const submitted = this.transcript.takeAutoSubmit()
      if (submitted) this.callbacks.onAutoSubmit(submitted)
      else this.callbacks.onError('No se detectó voz para transcribir.')
    }
    this.callbacks.onStatus('idle')
  }

  private cleanupCapture(): void {
    if (this.rotationTimer !== null) this.environment.cancelSchedule(this.rotationTimer)
    this.rotationTimer = null
    if (this.animationFrame !== null) this.environment.cancelFrame(this.animationFrame)
    this.animationFrame = null
    this.source?.disconnect()
    this.source = null
    this.analyser = null
    if (this.audioContext) void this.audioContext.close().catch(() => undefined)
    this.audioContext = null
    this.stopTracks()
  }

  private stopTracks(): void {
    this.stream?.getTracks().forEach(track => track.stop())
    this.stream = null
  }
}
