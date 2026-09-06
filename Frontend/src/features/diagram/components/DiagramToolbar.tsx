export type SaveStatus = 'clean' | 'dirty' | 'saving' | 'saved' | 'error' | 'forbidden'

interface DiagramToolbarProps {
  projectName: string
  hasSelection: boolean
  saveStatus: SaveStatus
  onSave: () => void
  onDeleteSelection: () => void
  onFitView: () => void
}

const statusLabels: Record<SaveStatus, string> = {
  clean: 'Sin cambios',
  dirty: 'Cambios sin guardar',
  saving: 'Guardando...',
  saved: 'Guardado',
  error: 'Error al guardar',
  forbidden: 'Sin permiso para guardar',
}

export function DiagramToolbar({ projectName, hasSelection, saveStatus, onSave, onDeleteSelection, onFitView }: DiagramToolbarProps) {
  return (
    <header className="diagram-toolbar">
      <div className="diagram-breadcrumb">
        <span>Proyectos</span><b>/</b><strong>{projectName}</strong>
        <i aria-hidden="true" />
        <small>{statusLabels[saveStatus]}</small>
      </div>
      <div className="diagram-toolbar-actions">
        <button type="button" onClick={onFitView}>Ajustar vista</button>
        <button className="danger-action" type="button" disabled={!hasSelection} onClick={onDeleteSelection}>Eliminar selección</button>
        <button className="save-diagram-button" type="button" disabled={saveStatus === 'saving' || saveStatus === 'clean'} onClick={onSave}>
          {saveStatus === 'saving' ? 'Guardando...' : 'Guardar'}
        </button>
        <span className={`persistence-badge is-${saveStatus}`}>{statusLabels[saveStatus]}</span>
      </div>
    </header>
  )
}
