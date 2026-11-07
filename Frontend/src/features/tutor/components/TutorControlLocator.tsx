import { useContext, useEffect, useState } from 'react'
import { TutorTargetContext, type TutorControl } from '../context/tutorTargetContext'
import { tutorialTargets, type TutorialTargetId } from '../content/targets.ts'
import type { TargetAddress } from '../services/tutorialTargetRegistry.ts'

export function TutorControlLocator({ targets, onLocate }: { targets: readonly string[]; onLocate: (address: TargetAddress) => void }) {
  const runtime = useContext(TutorTargetContext)
  const [choices, setChoices] = useState<{ address: TargetAddress; control: TutorControl }[]>([])
  useEffect(() => {
    const refresh = () => {
      if (!runtime) return
      const found = runtime.registry.addresses().filter(address => address.sessionId === runtime.sessionId && targets.includes(address.target))
        .flatMap(address => { const result = runtime.registry.resolve(address); return result.status === 'ready' ? [{ address, control: result.control }] : [] })
      setChoices(found)
    }
    refresh()
    const timer = window.setInterval(refresh, 300)
    return () => window.clearInterval(timer)
  }, [runtime, targets])
  return <section className="tutor-control-locator" aria-label="Ubicar controles">
    <h4>Ubicar controles</h4>
    <p>Señala un control visible de esta pantalla. No inicia ni completa el tutorial. Escape o un clic retira el resaltado.</p>
    {!choices.length && <p role="status">No hay controles disponibles aquí. Abre la pantalla o el panel indicado y comprueba tu permiso de edición.</p>}
    {choices.map(({ address, control }) => <button type="button" key={JSON.stringify(address)} onClick={() => onLocate(address)}>
      Señalar: {control.label || tutorialTargets[address.target as TutorialTargetId].control}
    </button>)}
  </section>
}
