import { useState, useSyncExternalStore } from 'react'
import { useTutorRuntime } from '../hooks/useTutorRuntime'
import { tutorials } from '../content/catalog.ts'
import { ATTRIBUTE_TYPES, DIAGRAM_CARDINALITIES } from '../../diagram/types/diagram.types.ts'
import { CARDINALITY_LABELS } from '../../diagram/services/relationshipCardinality.ts'
import type { TutorialDefinition, TutorialBinding, TutorialParameterValues } from '../types/tutorial.types.ts'
import type { RuntimeBindings } from '../types/tutorialRuntime.types.ts'

export function TutorLessonControls({ tutorial, compact = false }: { tutorial?: TutorialDefinition; compact?: boolean }) {
  const runtime = useTutorRuntime()!
  const view = useSyncExternalStore(runtime.subscribe, runtime.getSnapshot, runtime.getSnapshot)
  const [bindings, setBindings] = useState<RuntimeBindings>({})
  const [parameters, setParameters] = useState<TutorialParameterValues>({})
  const run = view.run
  if (view.active || view.finished || run) {
    const definition = tutorials.find(item => item.id === run?.tutorialId)
    const step = definition?.steps[run?.stepIndex ?? 0]
    const text = step?.text.replace(/\{\{([^}]+)\}\}/g, (_, key: keyof TutorialParameterValues) => {
      const value = view.parameters[key] ?? ''
      return key.includes('Cardinality') ? CARDINALITY_LABELS[value as keyof typeof CARDINALITY_LABELS] ?? value : value
    })
    return <section className="tutor-lesson" aria-label="Tutorial en curso">
      {!tutorial && !compact && <><h3>Mi primer diagrama</h3>{!view.journey && <p>Tienes una guía abierta. Termínala o cancélala para iniciar el recorrido completo.</p>}</>}
      <strong>{view.finished ? 'Recorrido completado' : definition?.title ?? 'Preparando la siguiente guía'}</strong>
      {view.journey && <p>Guía {view.lesson} de {view.total}</p>}
      {!view.finished && <><p role="status">{view.message || text}</p><small>{step?.hint}</small>
        {step?.completion.kind === 'input-valid' && run?.status === 'waiting' && <p className="tutor-input-status">Escribe cualquier nombre de 1 a 100 caracteres. Al hacerlo, la guía señalará Crear proyecto.</p>}
        {step?.completion.kind === 'input-matches' && run?.status === 'waiting' && <p className="tutor-input-status">
          {(view.scene?.inputs[step.completion.parameter] ?? '').trim() !== (view.parameters[step.completion.parameter] ?? '').trim()
            ? `El valor debe ser «${view.parameters[step.completion.parameter] ?? ''}». Al coincidir, la guía pasará automáticamente al botón Crear proyecto.`
            : 'Nombre correcto. Esperando la confirmación de la edición local.'}
        </p>}
        {run?.status === 'paused' && <p role="alert">{run.reason === 'review'
          ? 'Volviste al paso anterior. Pulsa Reanudar guía antes de realizar la acción. Anterior no elimina los elementos que ya creaste; si vuelves al paso Agregar, tendrás que agregar un atributo nuevo.'
          : run.reason === 'manual' ? 'Pausaste la guía. Pulsa Reanudar guía para continuar.'
          : `La guía está pausada (${run.reason}). Comprueba el elemento elegido, los permisos y el guardado antes de reanudar.`}</p>}
        {run?.status === 'incomplete' && <p>La guía tiene pasos omitidos. Puedes volver atrás o cancelarla.</p>}
        {!compact && <div><button type="button" onClick={() => runtime.back()}>Anterior</button>
          <button type="button" onClick={() => run?.status === 'waiting' ? runtime.pause() : runtime.resume()}>{run?.status === 'waiting' ? 'Pausar' : 'Reanudar guía'}</button>
          <button type="button" onClick={() => runtime.skip()}>Omitir</button></div>}</>}
      {compact && run?.status === 'paused' && <button type="button" onClick={() => runtime.resume()}>Reanudar guía</button>}
      <button type="button" onClick={() => runtime.cancel()}>{view.finished ? 'Volver al catálogo' : 'Cancelar guía'}</button>
    </section>
  }
  if (compact) return null
  const scene = view.scene
  if (!scene) return <section className="tutor-lesson">{!tutorial && <h3>Mi primer diagrama</h3>}<p role="status">Carga el dashboard para elegir un workspace e iniciar el recorrido.</p></section>
  const ids: RuntimeBindings = { workspace: scene.workspaceId ?? undefined, project: scene.projectId ?? undefined, ...bindings }
  const inputs = tutorial?.inputs ?? ['workspace']
  const labels: Record<TutorialBinding, string> = { workspace: 'Workspace', project: 'Proyecto', node: 'Clase o entidad', attribute: 'Atributo', sourceNode: 'Nodo origen', targetNode: 'Nodo destino', relationship: 'Relación' }
  return <form className="tutor-lesson" onSubmit={event => { event.preventDefault(); if (tutorial) runtime.start(tutorial.id, ids, parameters); else runtime.startJourney(Number(ids.workspace)) }}>
    {!tutorial && <h3>Mi primer diagrama</h3>}
    {!tutorial && scene.screen !== 'dashboard' ? <p>Abre el dashboard para iniciar el recorrido completo.</p> : <>
      {inputs.filter(binding => ids[binding] === undefined || bindings[binding] !== undefined).map(binding => {
        const kind = binding === 'sourceNode' || binding === 'targetNode' ? 'node' : binding
        const choices = scene.items.filter(item => item.kind === kind && !item.locked && !item.structural && (kind !== 'attribute' || item.parentId === ids.node))
        return <label key={binding}>{labels[binding]}<select required value={bindings[binding] ?? ''} onChange={event => {
          const item = choices.find(item => String(item.id) === event.target.value)
          setBindings(current => ({ ...current, [binding]: item?.id, ...(binding === 'node' ? { attribute: undefined } : {}) }))
        }}><option value="">Elige un elemento</option>{choices.map(item => <option key={item.id} value={item.id}>{item.fields.name || item.id}</option>)}</select></label>
      })}
      {tutorial?.parameters.filter(parameter => parameter !== 'projectName' || !tutorial.steps.some(step => step.completion.kind === 'input-valid')).map(parameter => {
        const choices = parameter === 'attributeType' ? ATTRIBUTE_TYPES : parameter.includes('Cardinality') ? DIAGRAM_CARDINALITIES : null
        return <label key={parameter}>{({ projectName: 'Nombre del proyecto', nodeName: 'Nombre del nodo', attributeName: 'Nombre del atributo', attributeType: 'Tipo del atributo', sourceCardinality: 'Cardinalidad origen', targetCardinality: 'Cardinalidad destino' })[parameter]}
          {choices ? <select required value={parameters[parameter] ?? ''} onChange={event => setParameters(current => ({ ...current, [parameter]: event.target.value }))}><option value="">Selecciona</option>{choices.map(value => <option key={value} value={value}>{CARDINALITY_LABELS[value as keyof typeof CARDINALITY_LABELS] ?? value}</option>)}</select>
            : <input required maxLength={100} value={parameters[parameter] ?? ''} onChange={event => setParameters(current => ({ ...current, [parameter]: event.target.value }))} />}</label>
      })}
      <button type="submit">{tutorial ? 'Iniciar guía' : 'Iniciar recorrido completo'}</button>
    </>}
  </form>
}
