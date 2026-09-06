import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { isUnauthorizedError } from '../../../shared/api/apiError'
import { crearProyecto, eliminarProyecto, obtenerProyectos } from '../api/projectApi'
import { ProjectList } from '../components/ProjectList'
import type { Proyecto } from '../types/project.types'
import '../project.css'

function parseId(value: string | undefined): number | null {
  if (!value) return null
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export function ProjectsPage() {
  const { workspaceId: workspaceIdParam } = useParams()
  const workspaceId = parseId(workspaceIdParam)
  const navigate = useNavigate()
  const [projects, setProjects] = useState<Proyecto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [showForm, setShowForm] = useState(false)
  const [nombre, setNombre] = useState('')
  const [descripcion, setDescripcion] = useState('')
  const [saving, setSaving] = useState(false)
  const [deletingProjectId, setDeletingProjectId] = useState<number | null>(null)

  useEffect(() => {
    let active = true

    async function loadProjects() {
      if (workspaceId === null) {
        setError('El workspace indicado no es válido.')
        setLoading(false)
        return
      }
      try {
        const data = await obtenerProyectos(workspaceId)
        if (active) setProjects(data)
      } catch (requestError: unknown) {
        if (isUnauthorizedError(requestError)) {
          localStorage.removeItem('token')
          navigate('/login', { replace: true })
        } else if (active) {
          setError('No se pudieron cargar los proyectos del workspace.')
        }
      } finally {
        if (active) setLoading(false)
      }
    }

    void loadProjects()
    return () => { active = false }
  }, [navigate, workspaceId])

  function redirectIfUnauthorized(requestError: unknown): boolean {
    if (!isUnauthorizedError(requestError)) return false
    localStorage.removeItem('token')
    navigate('/login', { replace: true })
    return true
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (workspaceId === null || !nombre.trim()) return
    setSaving(true)
    setError('')
    try {
      const created = await crearProyecto({ nombre: nombre.trim(), descripcion: descripcion.trim() || null, workspaceId })
      setProjects((current) => [created, ...current])
      setNombre('')
      setDescripcion('')
      setShowForm(false)
    } catch (requestError: unknown) {
      if (!redirectIfUnauthorized(requestError)) setError('No se pudo crear el proyecto. Revisa los datos e inténtalo nuevamente.')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete(project: Proyecto) {
    if (!window.confirm(`¿Eliminar el proyecto "${project.nombre}"? Esta acción no se puede deshacer.`)) return
    setDeletingProjectId(project.id)
    setError('')
    try {
      await eliminarProyecto(project.id)
      setProjects((current) => current.filter((item) => item.id !== project.id))
    } catch (requestError: unknown) {
      if (!redirectIfUnauthorized(requestError)) setError('No se pudo eliminar el proyecto.')
    } finally {
      setDeletingProjectId(null)
    }
  }

  return (
    <main className="projects-page">
      <header className="projects-header">
        <div>
          <Link className="projects-back" to="/dashboard">← Volver al dashboard</Link>
          <span className="projects-eyebrow">Workspace {workspaceIdParam}</span>
          <h1>Proyectos</h1>
          <p>Selecciona un proyecto o crea uno nuevo para comenzar.</p>
        </div>
        <button className="project-button primary new-project-button" type="button" disabled={workspaceId === null} onClick={() => setShowForm((current) => !current)}>
          {showForm ? 'Cancelar' : 'Nuevo proyecto'}
        </button>
      </header>

      {showForm && workspaceId !== null && <section className="project-form-panel" aria-labelledby="new-project-title">
        <h2 id="new-project-title">Nuevo proyecto</h2>
        <form onSubmit={handleCreate}>
          <label htmlFor="project-name">Nombre</label>
          <input id="project-name" maxLength={100} required value={nombre} onChange={(event) => setNombre(event.target.value)} />
          <label htmlFor="project-description">Descripción</label>
          <textarea id="project-description" maxLength={500} rows={4} value={descripcion} onChange={(event) => setDescripcion(event.target.value)} />
          <button className="project-button primary" type="submit" disabled={saving}>{saving ? 'Creando...' : 'Crear proyecto'}</button>
        </form>
      </section>}

      {error && <p className="projects-error" role="alert">{error}</p>}
      {loading ? <div className="projects-loading">Cargando proyectos...</div> : <ProjectList projects={projects} deletingProjectId={deletingProjectId} onOpen={(projectId) => navigate(`/proyectos/${projectId}/editor`)} onDelete={(project) => void handleDelete(project)} />}
    </main>
  )
}

