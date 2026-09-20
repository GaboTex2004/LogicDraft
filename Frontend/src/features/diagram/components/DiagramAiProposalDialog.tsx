import type { DiagramAiOperation } from '../types/diagramAi.types'

function operationLabel(operation: DiagramAiOperation): string {
  switch (operation.type) {
    case 'ADD_ENTITY': return `Agregar entidad ${operation.entity.name}`
    case 'ADD_ATTRIBUTE': return `Agregar atributo ${operation.attribute.name} a ${operation.entityName}`
    case 'ADD_RELATIONSHIP': return `Relacionar ${operation.relationship.sourceEntity} con ${operation.relationship.targetEntity}`
    case 'CONVERT_MANY_TO_MANY_ASSOCIATION': return `Convertir la relación en ${operation.conversion.associationEntityName}`
  }
}

export function DiagramAiProposalDialog({ operations, onAccept, onCancel }: {
  operations: DiagramAiOperation[]
  onAccept: () => void
  onCancel: () => void
}) {
  if (!operations.length) return null
  return <div className="association-dialog-backdrop" role="presentation">
    <section className="association-dialog" role="dialog" aria-modal="true" aria-labelledby="ai-proposal-title">
      <h2 id="ai-proposal-title">Propuesta de cambios</h2>
      <p>LogicDraft validó {operations.length} operación{operations.length === 1 ? '' : 'es'}. Revisa y acepta para modificar el diagrama.</p>
      <ul>{operations.map((operation, index) => <li key={`${operation.type}-${index}`}>{operationLabel(operation)}</li>)}</ul>
      <div className="association-dialog-actions">
        <button type="button" onClick={onCancel}>Cancelar</button>
        <button type="button" onClick={onAccept}>Aplicar cambios</button>
      </div>
    </section>
  </div>
}
