import { useState } from 'react'

interface AssociationConversionDialogProps {
  open: boolean
  tableName: string
  error: string
  onCancel: () => void
  onConfirm: (entityName: string) => void
}

export function AssociationConversionDialog({ open, tableName, error, onCancel, onConfirm }: AssociationConversionDialogProps) {
  const [entityName, setEntityName] = useState('')
  if (!open) return null
  return <div className="association-dialog-backdrop" role="presentation" onMouseDown={event => {
    if (event.target === event.currentTarget) onCancel()
  }}>
    <section className="association-dialog" role="dialog" aria-modal="true" aria-labelledby="association-dialog-title">
      <header id="association-dialog-title">Convertir en entidad asociativa</header>
      <p>La tabla derivada se reemplazara por una entidad editable y dos relaciones estructurales.</p>
      <label>Nombre de la entidad
        <input autoFocus maxLength={200} value={entityName} placeholder="Ej. Inscripcion"
          onChange={event => setEntityName(event.target.value)} />
      </label>
      <label>Tabla fisica conservada
        <input value={tableName} readOnly />
      </label>
      {error && <p className="association-dialog-error" role="alert">{error}</p>}
      <footer>
        <button type="button" onClick={onCancel}>Cancelar</button>
        <button type="button" className="primary" disabled={!entityName.trim()}
          onClick={() => onConfirm(entityName)}>Convertir</button>
      </footer>
    </section>
  </div>
}
