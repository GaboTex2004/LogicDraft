import { useEffect, useRef, useState, type CSSProperties } from 'react'
import { AppIcon } from '../../../shared/components/AppIcon'
import { tutorialCatalog } from '../content/catalog.ts'
import { TutorControlLocator } from './TutorControlLocator'
import type { TargetAddress } from '../services/tutorialTargetRegistry.ts'
import { TutorLessonControls } from './TutorLessonControls'

interface Props { style: CSSProperties; onClose: () => void; onReset: () => void; onMove: () => void; onLocate: (address: TargetAddress) => void }

export function TutorPanel({ style, onClose, onReset, onMove, onLocate }: Props) {
  const headingRef = useRef<HTMLHeadingElement>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const tutorial = tutorialCatalog.tutorials.find(item => item.id === selected)
  useEffect(() => { headingRef.current?.focus() }, [])

  return <section id="logicdraft-tutor-panel" className="tutor-panel" style={style}
    role="dialog" aria-modal="false" aria-labelledby="logicdraft-tutor-title"
    onKeyDown={event => { event.stopPropagation(); if (event.key === 'Escape') { event.preventDefault(); onClose() } }}>
    <header className="tutor-panel-header">
      <span className="tutor-panel-avatar"><AppIcon name="robot" /></span>
      <div><span className="tutor-eyebrow">APRENDE A USAR</span><h2 id="logicdraft-tutor-title" ref={headingRef} tabIndex={-1}>LogicDraft</h2></div>
      <button type="button" className="tutor-close" onClick={onClose} aria-label="Cerrar panel del instructor">×</button>
    </header>
    <div className="tutor-panel-content">
      <p className="tutor-introduction">Tu guía para crear diagramas, paso a paso.</p>
      {tutorial && <button type="button" className="tutor-back" onClick={() => setSelected(null)}>Mi primer diagrama · recorrido completo</button>}
      <p className="tutor-availability" role="note">Sigue las instrucciones y realiza las acciones en la plataforma. Puedes activar la voz Puck durante la guía.</p>
      <TutorLessonControls key={selected ?? 'journey'} tutorial={tutorial} />
      {tutorial ? <div className="tutor-preview">
        <button className="tutor-back" type="button" onClick={() => setSelected(null)}>← Todas las guías</button>
        <h3>{tutorial.title}</h3>
        <p>{tutorial.examples[0]}</p>
        <dl><div><dt>Pasos de la guía</dt><dd>{tutorial.steps.length}</dd></div>
          <div><dt>Dónde se realiza</dt><dd>{tutorial.entryScreens.map(screen => ({ dashboard: 'Dashboard', projects: 'Proyectos', editor: 'Editor' })[screen]).join(', ')}</dd></div>
          <div><dt>Acceso necesario</dt><dd>{tutorial.roles.includes('VIEWER') ? 'Acceso al proyecto o workspace' : 'Permiso de edición'}</dd></div></dl>
        <p className="tutor-preview-note">Podrás seguir esta guía en la plataforma cuando se habilite el recorrido.</p>
        <TutorControlLocator targets={tutorial.steps.flatMap(step => [step.target, ...(step.completion.kind === 'control-ready' ? [step.completion.target] : [])])} onLocate={onLocate} />
      </div> : <nav aria-label="Catálogo de guías del instructor" className="tutor-guide-list">
        {tutorialCatalog.tutorials.map((item, index) => <button key={item.id} type="button" onClick={() => setSelected(item.id)}>
          <span className="tutor-guide-number">{String(index + 1).padStart(2, '0')}</span>
          <span>{item.title}</span><AppIcon name="arrow" />
        </button>)}
      </nav>}
    </div>
    <footer className="tutor-panel-footer">
      <button type="button" onClick={onMove}>Mover con teclado</button>
      <button type="button" onClick={onReset}>Restablecer posición</button>
    </footer>
  </section>
}
