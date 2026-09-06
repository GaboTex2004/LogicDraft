import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { obtenerUsuarioActual } from '../../auth/api/authApi'
import type { UsuarioActual } from '../../auth/types/auth.types'
import { obtenerMisWorkspaces } from '../../workspace/api/workspaceApi'
import type { Workspace } from '../../workspace/types/workspace.types'
import { isUnauthorizedError } from '../../../shared/api/apiError'
import { AppIcon as Icon } from '../../../shared/components/AppIcon'
import { AppSidebar } from '../../../shared/components/AppSidebar'
import { SIDEBAR_STORAGE_KEY, useSidebarCollapsed } from '../../../shared/components/useAppSidebar'
import { DashboardHeader } from '../components/DashboardHeader'
import { QuickActions } from '../components/QuickActions'
import { StatsCard } from '../components/StatsCard'
import { WorkspaceSection } from '../components/WorkspaceSection'
import '../dashboard.css'

export function DashboardPage() {
  const navigate = useNavigate()
  const [usuario, setUsuario] = useState<UsuarioActual | null>(null)
  const [workspaces, setWorkspaces] = useState<Workspace[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [menuOpen, setMenuOpen] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useSidebarCollapsed()

  useEffect(() => {
    let active = true
    async function cargarDashboard() {
      setLoading(true)
      setError('')
      const [usuarioResult, workspacesResult] = await Promise.allSettled([obtenerUsuarioActual(), obtenerMisWorkspaces()])
      const unauthorized = [usuarioResult, workspacesResult].some((result) => result.status === 'rejected' && isUnauthorizedError(result.reason))
      if (unauthorized) { localStorage.removeItem('token'); navigate('/login', { replace: true }); return }
      if (!active) return
      if (usuarioResult.status === 'rejected' || workspacesResult.status === 'rejected') { setError('No se pudieron cargar los datos del dashboard.'); setLoading(false); return }
      setUsuario(usuarioResult.value)
      setWorkspaces(workspacesResult.value)
      setLoading(false)
    }
    void cargarDashboard()
    return () => { active = false }
  }, [navigate])

  function handleLogout() { localStorage.removeItem('token'); navigate('/login', { replace: true }) }

  function handleToggleSidebar() {
    setSidebarCollapsed((current) => {
      const next = !current
      localStorage.setItem(SIDEBAR_STORAGE_KEY, String(next))
      return next
    })
  }

  return <div className={`dashboard-shell ${sidebarCollapsed ? 'sidebar-collapsed' : ''}`}>
    <AppSidebar usuario={usuario} collapsed={sidebarCollapsed} mobileOpen={menuOpen} onCloseMobile={() => setMenuOpen(false)} onToggle={handleToggleSidebar} onLogout={handleLogout} />
    <div className="dashboard-panel">
      <DashboardHeader onMenuClick={() => setMenuOpen(true)} />
      <main className="dashboard-main">
        {loading && <div className="dashboard-message"><span className="loader" />Cargando tu espacio...</div>}
        {error && <div className="dashboard-message is-error" role="alert">{error}</div>}
        {!loading && !error && usuario && <>
          <section className="welcome"><span className="eyebrow">Panel principal</span><h1>Hola, {usuario.nombre}.</h1><p>Continúa trabajando en tus diagramas y proyectos.</p></section>
          <section className="stats-grid" aria-label="Resumen"><StatsCard label="Proyectos activos" icon="folder" placeholder /><StatsCard label="Diagramas" icon="diagram" placeholder /><StatsCard label="Workspaces" value={workspaces.length} icon="workspace" /><StatsCard label="Colaboradores" icon="people" placeholder /></section>
          <section className="ai-banner"><div><span><Icon name="ai" /> LogicDraft AI · Próximamente</span><h2>Describe tu sistema y prepara tu próximo diagrama.</h2><p>La generación con IA se habilitará cuando su funcionalidad esté conectada.</p></div><button type="button" disabled>Próximamente <Icon name="arrow" /></button></section>
          <div className="dashboard-columns"><div><WorkspaceSection workspaces={workspaces} /><section className="content-section projects-placeholder"><div className="section-heading"><h2>Proyectos recientes</h2><span>Sin conexión todavía</span></div><div className="empty-state"><Icon name="diagram" /><h3>No hay proyectos para mostrar</h3><p>Esta sección se completará cuando el módulo de proyectos esté conectado en el frontend.</p></div></section></div><QuickActions /></div>
        </>}
      </main>
    </div>
  </div>
}
