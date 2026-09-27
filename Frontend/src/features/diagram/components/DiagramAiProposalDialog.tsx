import type { DiagramAiOperation } from '../types/diagramAi.types'
import { diagramOperationLabel } from '../services/diagramActionPresentation.ts'

export function DiagramAiProposalDialog({ operations, impact = [], onAccept, onCancel }: {
  operations: DiagramAiOperation[]
  impact?: string[]
  onAccept: () => void
  onCancel: () => void
}) {
  if (!operations.length) return null
  return <div className="association-dialog-backdrop" role="presentation">
    <section className="association-dialog" role="dialog" aria-modal="true" aria-labelledby="ai-proposal-title">
      <h2 id="ai-proposal-title">Propuesta de cambios</h2>
      <p>LogicDraft validó {operations.length} operación{operations.length === 1 ? '' : 'es'}. Revisa y acepta para modificar el diagrama.</p>
      <ul>{operations.map((operation, index) => <li key={`${operation.type}-${index}`}>{diagramOperationLabel(operation)}</li>)}</ul>
      {impact.length > 0 && <aside className="diagram-ai-impact">
        <strong>Esta eliminación también afectará:</strong>
        <ul>{impact.map(item => <li key={item}>{item}</li>)}</ul>
      </aside>}
      <div className="association-dialog-actions">
        <button type="button" onClick={onCancel}>Cancelar</button>
        <button type="button" onClick={onAccept}>Aplicar cambios</button>
      </div>
    </section>
  </div>
}
