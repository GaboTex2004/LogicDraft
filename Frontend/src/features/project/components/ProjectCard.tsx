import type { Proyecto } from '../types/project.types'

interface ProjectCardProps {
  project: Proyecto
  deleting: boolean
  onOpen: (projectId: number) => void
  onDelete: (project: Proyecto) => void
}

function formatDate(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat('es-BO', { dateStyle: 'medium' }).format(date)
}

export function ProjectCard({ project, deleting, onOpen, onDelete }: ProjectCardProps) {
  return (
    <article className="project-card">
      <div className="project-card-heading">
        <div>
          <span className="project-label">Proyecto</span>
          <h2>{project.nombre}</h2>
        </div>
        <span className="project-id">#{project.id}</span>
      </div>
      {project.descripcion ? <p>{project.descripcion}</p> : <p className="project-description-empty">Sin descripción</p>}
      <footer>
        <time dateTime={project.fechaCreacion}>Creado el {formatDate(project.fechaCreacion)}</time>
        <div className="project-actions">
          <button className="project-button secondary danger" type="button" disabled={deleting} onClick={() => onDelete(project)}>
            {deleting ? 'Eliminando...' : 'Eliminar'}
          </button>
          <button className="project-button primary" type="button" onClick={() => onOpen(project.id)}>Abrir</button>
        </div>
      </footer>
    </article>
  )
}

