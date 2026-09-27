import { useEffect, useRef, useState, type FormEvent } from 'react'
import { transcribeDiagramAudio } from '../api/diagramAudioApi.ts'
import {
  browserVoiceEnvironment,
  MediaRecorderVoiceController,
  type VoiceCaptureStatus,
} from '../services/mediaRecorderVoiceController.ts'

type AiPromptBarProps = {
  projectId: number
  onImageSelected?: (image: File, prompt: string) => Promise<void>
  onSubmit: (prompt: string) => Promise<void>
  loading: boolean
  message: string
  error: string
  disabledReason?: string
}

export function AiPromptBar({ projectId, onImageSelected, onSubmit, loading, message, error,
  disabledReason }: AiPromptBarProps) {
  const [prompt, setPrompt] = useState('')
  const [voiceStatus, setVoiceStatus] = useState<VoiceCaptureStatus>('idle')
  const [audioError, setAudioError] = useState('')
  const imageInputRef = useRef<HTMLInputElement | null>(null)
  const voiceControllerRef = useRef<MediaRecorderVoiceController | null>(null)
  const mountedRef = useRef(true)
  const processingRef = useRef(false)
  const voiceEnvironment = browserVoiceEnvironment()
  const recording = voiceStatus === 'listening'
  const voiceProcessing = voiceStatus === 'processing'

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      voiceControllerRef.current?.dispose()
      voiceControllerRef.current = null
    }
  }, [])

  async function submitText(text: string) {
    const value = text.trim()
    if (!value || processingRef.current || loading || disabledReason) return
    processingRef.current = true
    try {
      await onSubmit(value)
    } finally {
      processingRef.current = false
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (recording || voiceProcessing) return
    void submitText(prompt)
  }

  async function startListening() {
    if (!voiceEnvironment || loading || processingRef.current || disabledReason || voiceControllerRef.current) return
    const controller = new MediaRecorderVoiceController(
      voiceEnvironment,
      (audio, filename) => transcribeDiagramAudio(projectId, audio, filename),
      {
        onStatus: status => {
          if (!mountedRef.current) return
          setVoiceStatus(status)
          if (status === 'idle') voiceControllerRef.current = null
        },
        onTranscript: text => { if (mountedRef.current) setPrompt(text) },
        onError: value => { if (mountedRef.current) setAudioError(value) },
        onAutoSubmit: text => { if (mountedRef.current) void submitText(text) },
      },
    )
    voiceControllerRef.current = controller
    setAudioError('')
    await controller.start()
  }

  function handleMicrophone() {
    if (!recording) {
      void startListening()
      return
    }
    voiceControllerRef.current?.stopManually()
  }

  function handleImageSelected(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setAudioError('')
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) {
      setAudioError('Selecciona una imagen PNG, JPG o WEBP.')
      return
    }
    if (file.size > 5 * 1024 * 1024) {
      setAudioError('La imagen supera el límite de 5 MB.')
      return
    }
    if (onImageSelected) void onImageSelected(file, prompt.trim())
  }

  const statusText = recording
    ? 'Escuchando...'
    : voiceProcessing || loading
      ? 'Procesando...'
      : audioError || error || disabledReason
        || (voiceEnvironment ? message : 'Escribe tu instrucción; voz no disponible en este navegador.')

  return <div className="ai-prompt-wrapper">
    <form className="ai-prompt-bar" onSubmit={handleSubmit}>
      <strong>LogicDraft</strong>
      <input maxLength={10000} disabled={loading} value={prompt}
        onChange={event => setPrompt(event.target.value)}
        placeholder="Describe el cambio que quieres hacer en el diagrama..." aria-label="Instrucción para IA" />
      <input ref={imageInputRef} type="file" accept="image/png,image/jpeg,image/webp" style={{ display: 'none' }}
        onChange={handleImageSelected} aria-label="Seleccionar imagen del diagrama" />
      <button type="button" title="Adjuntar imagen" aria-label="Adjuntar imagen"
        disabled={!onImageSelected || loading || recording || voiceProcessing || !!disabledReason}
        onClick={() => imageInputRef.current?.click()}>▧</button>
      <button type="button" onClick={handleMicrophone}
        disabled={!voiceEnvironment || voiceProcessing || (!recording && (loading || !!disabledReason))}
        title={!voiceEnvironment ? 'Grabación de voz no disponible' : recording ? 'Detener sin enviar' : 'Usar micrófono'}
        aria-label={recording ? 'Detener grabación' : 'Usar micrófono'} aria-pressed={recording}>
        {recording ? '■' : '🎤'}
      </button>
      <button className="ai-send" type="submit"
        disabled={loading || recording || voiceProcessing || !prompt.trim() || !!disabledReason}
        title={disabledReason || 'Enviar'} aria-label="Enviar instrucción">{loading ? '…' : '➤'}</button>
    </form>
    <span className="ai-coming-soon" role={audioError || error ? 'alert' : 'status'}>
      {statusText}
    </span>
  </div>
}
