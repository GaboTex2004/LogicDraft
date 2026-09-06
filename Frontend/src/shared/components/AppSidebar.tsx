import { useLocation, useNavigate } from 'react-router-dom'
import type { UsuarioActual } from '../../features/auth/types/auth.types'
import { AppIcon, type AppIconName } from './AppIcon'

interface AppSidebarProps {
  usuario: UsuarioActual | null
  collapsed: boolean
  mobileOpen: boolean
  onCloseMobile: () => void
  onToggle: () => void
  onLogout: () => void
}

const navigation: { label: string; icon: AppIconName; path?: string }[] = [
  { label: 'Dashboard', icon: 'dashboard', path: '/dashboard' },
  { label: 'Proyectos', icon: 'folder' },
  { label: 'Diagramas', icon: 'diagram' },
  { label: 'Workspaces', icon: 'workspace' },
  { label: 'Colaboración', icon: 'people', path: '/colaboracion' },
  { label: 'IA', icon: 'ai' },
  { label: 'Configuración', icon: 'settings' },
]

function initials(name: string) {
  return name.split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase()
}

export function AppSidebar({ usuario, collapsed, mobileOpen, onCloseMobile, onToggle, onLogout }: AppSidebarProps) {
  const location = useLocation()
  const navigate = useNavigate()
  return <>
    <button className={`dashboard-overlay ${mobileOpen ? 'is-visible' : ''}`} aria-label="Cerrar menú" onClick={onCloseMobile} />
    <aside className={`dashboard-sidebar ${collapsed ? 'is-collapsed' : ''} ${mobileOpen ? 'is-open' : ''}`}>
      <div className="dashboard-brand">
        <AppIcon name="diagram" />
        <span className="sidebar-text">LogicDraft</span>
        <button className="sidebar-toggle" type="button" onClick={onToggle} aria-label={collapsed ? 'Expandir barra lateral' : 'Contraer barra lateral'} title={collapsed ? 'Expandir barra lateral' : 'Contraer barra lateral'}>
          <span aria-hidden="true">{collapsed ? '›' : '‹'}</span>
        </button>
      </div>
      <nav className="sidebar-navigation" aria-label="Navegación principal">
        <p className="sidebar-caption">Navegación</p>
        {navigation.map((item) => {
          const active = item.path === location.pathname
          return <button key={item.label} className={`sidebar-link ${active ? 'is-active' : ''}`} type="button" disabled={!item.path} onClick={() => { if (item.path) navigate(item.path) }} title={collapsed || !item.path ? `${item.label}${item.path ? '' : ' · Próximamente'}` : undefined} aria-label={collapsed ? item.label : undefined}>
            <AppIcon name={item.icon} /><span className="sidebar-text">{item.label}</span>{!item.path && <small>Próx.</small>}
          </button>
        })}
      </nav>
      <div className="sidebar-footer">
        <button className="sidebar-link" type="button" disabled title="Soporte · Próximamente" aria-label={collapsed ? 'Soporte' : undefined}><AppIcon name="help" /><span className="sidebar-text">Soporte</span></button>
        {usuario && <div className="user-card" title={collapsed ? `${usuario.nombre} · ${usuario.email}` : undefined}>
          <span className="avatar">{initials(usuario.nombre)}</span>
          <span className="user-details sidebar-text"><strong>{usuario.nombre}</strong><small>{usuario.email}</small></span>
          <button className="icon-button logout-button sidebar-text" type="button" onClick={onLogout} title="Cerrar sesión" aria-label="Cerrar sesión"><AppIcon name="logout" /></button>
        </div>}
      </div>
    </aside>
  </>
}
