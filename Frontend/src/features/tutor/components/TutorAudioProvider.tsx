import { useCallback, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import type { TutorialRunSnapshot } from '../types/tutorialRuntime.types'
import { TutorAudioContext } from '../context/tutorAudioContext'
import { TutorTargetContext } from '../context/tutorTargetContext'
import { useTutorRuntime } from '../hooks/useTutorRuntime'
import { tutorials } from '../content/catalog.ts'
import { createTutorNarrator } from '../services/tutorNarrator.ts'

interface Manifest { voice: string; files?: string[]; entries: Record<string, { source: string; spoken: string; file: string }> }
export function TutorAudioProvider({ children }: { children: ReactNode }) {
  const store = useTutorRuntime()!
  const target = useContext(TutorTargetContext)!
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot)
  const runJson = JSON.stringify(view.run)
  const run = useMemo(() => JSON.parse(runJson) as TutorialRunSnapshot | null, [runJson])
  const [enabled, setEnabled] = useState(false)
  const [recording, setRecording] = useState(false)
  const [message, setMessage] = useState('')
  const [manifest, setManifest] = useState<Manifest | null>(null)
  const [narrator] = useState(() => createTutorNarrator(() => new Audio(), setMessage))
  const repeatRef = useRef<() => void>(() => {})
  const disable = useCallback(() => { narrator.stop(); setEnabled(false) }, [narrator])
  useEffect(() => {
    let active = true
    void fetch(`${import.meta.env.BASE_URL}tutor-audio/manifest.json`, { cache: 'no-store' }).then(response => {
      if (!response.ok) throw new Error('manifest')
      return response.json()
    }).then(value => { if (active && value.voice === 'Puck' && value.entries) setManifest(value) }).catch(() => { if (active) setMessage('Los audios no están disponibles. La guía continúa en texto.') })
    return () => { active = false; narrator.stop() }
  }, [narrator])
  useEffect(() => {
    const microphoneStart = () => { setRecording(true); disable() }
    const microphoneEnd = () => setRecording(false)
    const closePanel = () => narrator.stop()
    window.addEventListener('logicdraft:microphone-start', microphoneStart)
    window.addEventListener('logicdraft:microphone-end', microphoneEnd)
    window.addEventListener('logicdraft:tutor-audio-stop', closePanel)
    const visibility = () => { if (document.hidden) disable() }
    document.addEventListener('visibilitychange', visibility)
    return () => { window.removeEventListener('logicdraft:microphone-start', microphoneStart); window.removeEventListener('logicdraft:microphone-end', microphoneEnd); window.removeEventListener('logicdraft:tutor-audio-stop', closePanel); document.removeEventListener('visibilitychange', visibility) }
  }, [disable, narrator])
  useEffect(() => {
    narrator.stop()
    const step = tutorials.find(item => item.id === run?.tutorialId)?.steps[run?.stepIndex ?? 0]
    const entry = step && run ? manifest?.entries[`${run.tutorialId}/${step.id}`] : null
    let played = false
    const attempt = (repeat = false) => {
      if (!enabled || recording || !view.active || run?.status !== 'waiting' || !step || !entry || entry.source !== step.text || !/^[a-f0-9]{20}\.wav$/.test(entry.file)) return
      if (manifest?.files && !manifest.files.includes(entry.file)) { setMessage('El audio de este paso está pendiente. Continúa con la instrucción escrita.'); return }
      if (document.querySelector('[role="dialog"][aria-modal="true"], dialog[open]')) { narrator.stop(); played = false; return }
      // Readiness gates the start, not every frame of an instruction. A focus,
      // scroll or temporary disabled state must not discard it permanently.
      if (played && !repeat) return
      const controls = target.registry.addresses().filter(address => address.sessionId === target.sessionId && address.target === step.target
        && address.workspaceId === run.bindings.workspace && address.projectId === (view.scene?.projectId ?? null) && (!step.binding || address.instanceId === run.bindings[step.binding]))
      if (controls.length !== 1 || target.registry.resolve(controls[0]).status !== 'ready') return
      if (!played || repeat) { played = true; setMessage(''); void narrator.play(`${run.ticket.instanceId}:${step.id}:${run.ticket.visit}`, `${import.meta.env.BASE_URL}tutor-audio/${entry.file}`, repeat) }
    }
    repeatRef.current = () => attempt(true)
    const timer = window.setInterval(() => attempt(), 200)
    return () => { window.clearInterval(timer); narrator.stop(); repeatRef.current = () => {} }
  }, [enabled, recording, manifest, view.active, run, view.scene?.projectId, target, narrator])
  return <TutorAudioContext.Provider value={{ enabled, recording, message, toggle: () => { if (recording) return; if (enabled) disable(); else { setMessage(''); setEnabled(true) } }, repeat: () => repeatRef.current() }}>{children}</TutorAudioContext.Provider>
}

export function TutorVoiceControls() {
  const audio = useContext(TutorAudioContext)
  return audio && <div className="tutor-voice-controls" aria-label="Voz del instructor">
    <button type="button" onClick={audio.toggle} disabled={audio.recording}>{audio.recording ? 'Voz detenida mientras grabas' : audio.enabled ? 'Silenciar voz' : 'Activar voz Puck'}</button>
    <button type="button" onClick={audio.repeat} disabled={!audio.enabled}>Repetir instrucción</button>
    {audio.message && <small role="status">{audio.message}</small>}
  </div>
}
