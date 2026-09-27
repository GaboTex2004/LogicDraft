import type {
  DiagramImportDiff, EntityImportChange, ImportChangeStatus, RelationImportChange,
} from '../services/diagramImportDiff.ts'

const titles: Record<ImportChangeStatus, string> = {
  added: 'Nuevos', modified: 'Modificados', unchanged: 'Sin cambios', missing: 'No encontrados en el archivo',
}

export function DiagramImportDialog({ diff, selected, onToggle, onCancel, onApply }: {
  diff: DiagramImportDiff
  selected: ReadonlySet<string>
  onToggle: (id: string, entity?: EntityImportChange) => void
  onCancel: () => void
  onApply: () => void
}) {
  type ImportListItem = { kind: 'entity'; change: EntityImportChange }
    | { kind: 'relation'; change: RelationImportChange }
  const items = (status: ImportChangeStatus): ImportListItem[] => [
    ...diff.entities.filter(change => change.status === status).map(change => ({ kind: 'entity' as const, change })),
    ...diff.relations.filter(change => change.status === status).map(change => ({ kind: 'relation' as const, change })),
  ]
  const actionable = [...diff.entities, ...diff.relations].some(change => change.status !== 'unchanged')
  return <div className="association-dialog-backdrop" role="presentation" onMouseDown={event => {
    if (event.target === event.currentTarget) onCancel()
  }}>
    <section className="association-dialog diagram-import-dialog" role="dialog" aria-modal="true"
      aria-labelledby="diagram-import-title">
      <header id="diagram-import-title">Importar cambios</header>
      <p>Revisa el resultado antes de modificar el diagrama. Las eliminaciones están desmarcadas por seguridad.</p>
      {diff.warnings.length > 0 && <aside className="diagram-import-warnings">
        <strong>Advertencias del XMI</strong>
        <ul>{diff.warnings.map((warning, index) => <li key={`${warning}-${index}`}>{warning}</li>)}</ul>
      </aside>}
      <div className="diagram-import-groups">
        {(['added', 'modified', 'unchanged', 'missing'] as const).map(status => {
          const group = items(status)
          if (group.length === 0) return null
          return <section className={`diagram-import-group is-${status}`} key={status}>
            <h3>{titles[status]} ({group.length})</h3>
            {group.map(item => {
              const selectable = status !== 'unchanged'
              if (item.kind === 'relation') return <div className="diagram-import-change" key={item.change.id}>
                <label>
                  {selectable
                    ? <input type="checkbox" checked={selected.has(item.change.id)}
                        onChange={() => onToggle(item.change.id)} />
                    : <span className="diagram-import-unchanged" aria-hidden="true">•</span>}
                  <span>Relación: <b>{item.change.name}</b></span>
                </label>
              </div>
              const change = item.change
              return <div className="diagram-import-change" key={change.id}>
                <label>
                  {selectable
                    ? <input type="checkbox" checked={selected.has(change.id)}
                        onChange={() => onToggle(change.id, change)} />
                    : <span className="diagram-import-unchanged" aria-hidden="true">•</span>}
                  <span>{change.associationClass ? 'Entidad asociativa' : 'Entidad'}: <b>{change.name}</b></span>
                </label>
                {change.attributes.some(attribute => attribute.status !== 'unchanged') &&
                  <ul>{change.attributes.filter(attribute => attribute.status !== 'unchanged').map(attribute =>
                    <li key={attribute.id}>
                      {attribute.status === 'missing' || attribute.status === 'added' || attribute.status === 'modified'
                        ? <label><input type="checkbox" checked={selected.has(attribute.id)}
                            onChange={() => onToggle(attribute.id)} /> {attribute.summary}</label>
                        : attribute.summary}
                    </li>)}</ul>}
              </div>
            })}
          </section>
        })}
        {!actionable && <p className="diagram-import-empty">El archivo no contiene cambios respecto al diagrama actual.</p>}
      </div>
      <footer>
        <button type="button" onClick={onCancel}>Cancelar</button>
        <button type="button" className="primary" disabled={!actionable || selected.size === 0} onClick={onApply}>
          Aplicar cambios
        </button>
      </footer>
    </section>
  </div>
}
