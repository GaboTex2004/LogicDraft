export const VOICE_SILENCE_MS = 1500
export const VOICE_CHUNK_MS = 2000
export const VOICE_LEVEL_THRESHOLD = 0.025

function comparableWord(value: string): string {
  return value.toLocaleLowerCase('es').replace(/^[^\p{L}\p{N}]+|[^\p{L}\p{N}]+$/gu, '')
}

export function mergeVoiceTranscripts(current: string, incoming: string): string {
  const left = current.trim().replace(/\s+/g, ' ')
  const right = incoming.trim().replace(/\s+/g, ' ')
  if (!left) return right
  if (!right) return left

  const leftWords = left.split(' ')
  const rightWords = right.split(' ')
  const maximumOverlap = Math.min(leftWords.length, rightWords.length, 24)
  for (let size = maximumOverlap; size > 0; size -= 1) {
    const suffix = leftWords.slice(-size).map(comparableWord)
    const prefix = rightWords.slice(0, size).map(comparableWord)
    if (size === 1 && /^\d+$/.test(suffix[0] ?? '')) continue
    if (suffix.every((word, index) => word !== '' && word === prefix[index])) {
      return [...leftWords, ...rightWords.slice(size)].join(' ')
    }
  }
  return `${left} ${right}`
}

export class VoiceTranscriptSession {
  private chunks = new Map<number, string>()
  private manuallyStopped = false
  private submitted = false

  reset(): void {
    this.chunks.clear()
    this.manuallyStopped = false
    this.submitted = false
  }

  addChunk(index: number, transcript: string): string {
    if (!Number.isSafeInteger(index) || index < 0) throw new Error('Índice de audio inválido.')
    this.chunks.set(index, transcript.trim())
    return this.text()
  }

  text(): string {
    let result = ''
    for (let index = 0; this.chunks.has(index); index += 1) {
      result = mergeVoiceTranscripts(result, this.chunks.get(index) ?? '')
    }
    return result
  }

  stopManually(): void {
    this.manuallyStopped = true
  }

  takeAutoSubmit(): string | null {
    const text = this.text()
    if (this.manuallyStopped || this.submitted || !text) return null
    this.submitted = true
    return text
  }
}

export class VoiceActivityDetector {
  private speechDetected = false
  private lastSpeechAt = 0
  private readonly silenceMs: number
  private readonly threshold: number

  constructor(silenceMs = VOICE_SILENCE_MS, threshold = VOICE_LEVEL_THRESHOLD) {
    this.silenceMs = silenceMs
    this.threshold = threshold
  }

  reset(): void {
    this.speechDetected = false
    this.lastSpeechAt = 0
  }

  observe(level: number, now: number): boolean {
    if (level >= this.threshold) {
      this.speechDetected = true
      this.lastSpeechAt = now
      return false
    }
    return this.speechDetected && now - this.lastSpeechAt >= this.silenceMs
  }

  hasSpeech(): boolean {
    return this.speechDetected
  }
}

export function voiceCaptureErrorMessage(error: unknown): string {
  if (error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'SecurityError')) {
    return 'No se concedió permiso para usar el micrófono.'
  }
  if (error instanceof DOMException && error.name === 'NotFoundError') {
    return 'No se encontró un micrófono disponible.'
  }
  return 'No se pudo iniciar la grabación de voz.'
}
