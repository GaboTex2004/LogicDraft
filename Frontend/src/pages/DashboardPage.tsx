import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { obtenerUsuarioActual } from '../features/auth/api/authApi'
import type { UsuarioActual } from '../features/auth/types/auth.types'
import { obtenerMisWorkspaces } from '../features/workspace/api/workspaceApi'
import type { Workspace } from '../features/workspace/types/workspace.types'
import { isUnauthorizedError } from '../shared/api/apiError'

export function DashboardPage() {
  const navigate = useNavigate()
  const [usuario, setUsuario] = useState<UsuarioActual | null>(null)
  const [workspaces, setWorkspaces] = useState<Workspace[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true

    async function cargarDashboard() {
      setLoading(true)
      setError('')

      const [usuarioResult, workspacesResult] = await Promise.allSettled([
        obtenerUsuarioActual(),
        obtenerMisWorkspaces(),
      ])

      const unauthorized = [usuarioResult, workspacesResult].some(
        (result) =>
          result.status === 'rejected' && isUnauthorizedError(result.reason),
      )

      if (unauthorized) {
        localStorage.removeItem('token')
        navigate('/login', { replace: true })
        return
      }

      if (!active) {
        return
      }

      if (
        usuarioResult.status === 'rejected' ||
        workspacesResult.status === 'rejected'
      ) {
        setError('No se pudieron cargar los datos del dashboard.')
        setLoading(false)
        return
      }

      setUsuario(usuarioResult.value)
      setWorkspaces(workspacesResult.value)
      setLoading(false)
    }

    void cargarDashboard()

    return () => {
      active = false
    }
  }, [navigate])

  function handleLogout() {
    localStorage.removeItem('token')
    navigate('/login', { replace: true })
  }

  return (
    <main>
      <h1>Dashboard</h1>

      {loading && <p>Cargando...</p>}
      {error && <p role="alert">{error}</p>}

      {!loading && !error && usuario && (
        <section>
          <h2>{usuario.nombre}</h2>
          <p>Email: {usuario.email}</p>
          <p>Rol del sistema: {usuario.rolSistema}</p>
        </section>
      )}

      {!loading && !error && (
        <section>
          <h2>Workspaces</h2>
          {workspaces.length === 0 ? (
            <p>No tienes workspaces disponibles.</p>
          ) : (
            <ul>
              {workspaces.map((workspace) => (
                <li key={workspace.id}>
                  {workspace.nombre} — {workspace.rol}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}

      <button type="button" onClick={handleLogout}>
        Cerrar sesión
      </button>
    </main>
  )
}
