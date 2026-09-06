import type { Proyecto } from '../types/project.types'
import { ProjectCard } from './ProjectCard'

interface ProjectListProps {
  projects: Proyecto[]
  deletingProjectId: number | null
  onOpen: (projectId: number) => void
  onDelete: (project: Proyecto) => void
}

export function ProjectList({ projects, deletingProjectId, onOpen, onDelete }: ProjectListProps) {
  if (projects.length === 0) {
    return <div className="projects-empty"><h2>Aún no hay proyectos</h2><p>Crea el primero para comenzar a preparar tu diagrama.</p></div>
  }

  return <div className="project-grid">{projects.map((project) => <ProjectCard key={project.id} project={project} deleting={deletingProjectId === project.id} onOpen={onOpen} onDelete={onDelete} />)}</div>
}

