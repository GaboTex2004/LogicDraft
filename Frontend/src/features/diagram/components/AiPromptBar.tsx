import { useState, type FormEvent } from 'react'

export function AiPromptBar({ onSubmit, loading, message, error, disabledReason }: {
  onSubmit: (prompt: string) => Promise<void>
  loading: boolean
  message: string
  error: string
  disabledReason?: string
}) {
  const [prompt, setPrompt] = useState('')

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!loading && !disabledReason && prompt.trim()) void onSubmit(prompt.trim())
  }

  return (
    <div className="ai-prompt-wrapper">
      <form className="ai-prompt-bar" onSubmit={handleSubmit}>
        <strong>LogicDraft</strong>
        <input maxLength={10000} disabled={loading} value={prompt} onChange={(event) => setPrompt(event.target.value)} placeholder="Describe el cambio que quieres hacer en el diagrama..." aria-label="Instrucción para IA" />
        <button type="button" disabled title="Imagen próximamente" aria-label="Adjuntar imagen">▧</button>
        <button type="button" disabled title="Voz próximamente" aria-label="Usar micrófono">◉</button>
        <button className="ai-send" type="submit" disabled={loading || !prompt.trim() || !!disabledReason} title={disabledReason || 'Enviar'} aria-label="Enviar instrucción">{loading ? '…' : '➤'}</button>
      </form>
      <span className="ai-coming-soon" role={error ? 'alert' : 'status'}>
        {loading ? 'Interpretando…' : error || disabledReason || message}
      </span>
    </div>
  )
}
