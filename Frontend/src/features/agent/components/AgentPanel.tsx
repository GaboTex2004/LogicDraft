import { useState, type FormEvent } from 'react'

interface Message { role: 'user' | 'agent'; text: string }

export function AgentPanel({ onAsk, disabledReason }: {
  onAsk: (message: string) => Promise<string>
  disabledReason?: string
}) {
  const [open, setOpen] = useState(false)
  const [message, setMessage] = useState('')
  const [messages, setMessages] = useState<Message[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function submit(event: FormEvent) {
    event.preventDefault()
    const question = message.trim()
    if (!question || loading || disabledReason) return
    setMessage('')
    setError('')
    setMessages(current => [...current, { role: 'user', text: question } satisfies Message].slice(-10))
    setLoading(true)
    try {
      const answer = await onAsk(question)
      setMessages(current => [...current, { role: 'agent', text: answer } satisfies Message].slice(-10))
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : 'No se pudo consultar al agente.')
    } finally {
      setLoading(false)
    }
  }

  return <section className={`agent-panel ${open ? 'is-open' : ''}`}>
    <button className="agent-toggle" type="button" onClick={() => setOpen(value => !value)}
      aria-expanded={open} aria-controls="agent-conversation">
      Agente consultivo
    </button>
    {open && <div id="agent-conversation" className="agent-conversation">
      <header>
        <strong>Contexto del proyecto</strong>
        <small>No modifica el diagrama</small>
      </header>
      <div className="agent-messages" aria-live="polite">
        {!messages.length && <p>Pregunta por la entidad o relación seleccionada.</p>}
        {messages.map((item, index) => <p key={index} className={`is-${item.role}`}>
          <b>{item.role === 'user' ? 'Tú' : 'Agente'}:</b> {item.text}
        </p>)}
      </div>
      <form onSubmit={submit}>
        <textarea maxLength={4000} rows={3} value={message} disabled={loading}
          onChange={event => setMessage(event.target.value)}
          placeholder="¿Qué opinas de este modelo?" aria-label="Pregunta al agente" />
        <button type="submit" disabled={loading || !message.trim() || !!disabledReason}>
          {loading ? 'Consultando…' : 'Preguntar'}
        </button>
      </form>
      <small className={error ? 'agent-error' : ''}>{error || disabledReason || 'Solo consulta el diagrama guardado.'}</small>
    </div>}
  </section>
}
