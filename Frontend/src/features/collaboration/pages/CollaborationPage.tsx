import axios from 'axios'
import { type FormEvent, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { obtenerUsuarioActual } from '../../auth/api/authApi'
import type { UsuarioActual } from '../../auth/types/auth.types'
import { DashboardHeader } from '../../dashboard/components/DashboardHeader'
import { obtenerMisWorkspaces } from '../../workspace/api/workspaceApi'
import type { Workspace } from '../../workspace/types/workspace.types'
import { isUnauthorizedError } from '../../../shared/api/apiError'
import { AppIcon } from '../../../shared/components/AppIcon'
import { AppSidebar } from '../../../shared/components/AppSidebar'
import { SIDEBAR_STORAGE_KEY, useSidebarCollapsed } from '../../../shared/components/useAppSidebar'
import { addWorkspaceMember, changeWorkspaceMemberRole, getWorkspaceMembers, removeWorkspaceMember } from '../api/workspaceMemberApi'
import type { AddWorkspaceMemberRequest, WorkspaceMember, WorkspaceRole } from '../types/collaboration.types'
import '../../dashboard/dashboard.css'
import '../collaboration.css'

function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error) && typeof error.response?.data === 'object' && error.response.data !== null) {
    const message: unknown = (error.response.data as Record<string, unknown>).mensaje
    if (typeof message === 'string') return message
  }
  return 'No se pudo completar la operación.'
}

function initials(name: string): string {
  return name.trim().split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? '').join('') || '?'
}

export function CollaborationPage() {
  const navigate = useNavigate()
  const [usuario, setUsuario] = useState<UsuarioActual | null>(null)
  const [workspaces, setWorkspaces] = useState<Workspace[]>([])
  const [selectedWorkspaceId, setSelectedWorkspaceId] = useState<number | null>(null)
  const [members, setMembers] = useState<WorkspaceMember[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<AddWorkspaceMemberRequest['rol']>('EDITOR')
  const [submitting, setSubmitting] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useSidebarCollapsed()
  const canManage = members.find((member) => member.userId === usuario?.id)?.rol === 'OWNER'

  function handleRequestError(requestError: unknown) {
    if (isUnauthorizedError(requestError)) {
      localStorage.removeItem('token')
      navigate('/login', { replace: true })
    } else setError(errorMessage(requestError))
  }

  useEffect(() => {
    let active = true
    async function loadPage() {
      try {
        const [currentUser, availableWorkspaces] = await Promise.all([
          obtenerUsuarioActual(), obtenerMisWorkspaces(),
        ])
        if (!active) return
        setUsuario(currentUser)
        setWorkspaces(availableWorkspaces)
        const firstWorkspace = availableWorkspaces[0]
        if (firstWorkspace) {
          setSelectedWorkspaceId(firstWorkspace.id)
          setMembers(await getWorkspaceMembers(firstWorkspace.id))
        }
      } catch (requestError: unknown) {
        if (isUnauthorizedError(requestError)) {
          localStorage.removeItem('token')
          navigate('/login', { replace: true })
          return
        }
        if (active) setError(errorMessage(requestError))
      } finally {
        if (active) setLoading(false)
      }
    }
    void loadPage()
    return () => { active = false }
  }, [navigate])

  async function selectWorkspace(workspaceId: number) {
    setSelectedWorkspaceId(workspaceId)
    setLoading(true)
    setError('')
    try {
      setMembers(await getWorkspaceMembers(workspaceId))
    } catch (requestError: unknown) {
      handleRequestError(requestError)
    } finally {
      setLoading(false)
    }
  }

  async function refreshMembers() {
    if (selectedWorkspaceId !== null) setMembers(await getWorkspaceMembers(selectedWorkspaceId))
  }

  async function submitMember(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (selectedWorkspaceId === null) return
    setSubmitting(true)
    setError('')
    try {
      await addWorkspaceMember(selectedWorkspaceId, { email: email.trim(), rol: role })
      await refreshMembers()
      setEmail('')
      setRole('EDITOR')
      setModalOpen(false)
    } catch (requestError: unknown) {
      handleRequestError(requestError)
    } finally {
      setSubmitting(false)
    }
  }

  async function updateRole(userId: number, nextRole: WorkspaceRole) {
    if (selectedWorkspaceId === null) return
    setError('')
    try {
      await changeWorkspaceMemberRole(selectedWorkspaceId, userId, nextRole)
      await refreshMembers()
    } catch (requestError: unknown) {
      handleRequestError(requestError)
    }
  }

  async function removeMember(member: WorkspaceMember) {
    if (selectedWorkspaceId === null || !window.confirm(`¿Eliminar a ${member.nombre} del workspace?`)) return
    setError('')
    try {
      await removeWorkspaceMember(selectedWorkspaceId, member.userId)
      await refreshMembers()
    } catch (requestError: unknown) {
      handleRequestError(requestError)
    }
  }

  function logout() { localStorage.removeItem('token'); navigate('/login', { replace: true }) }
  function toggleSidebar() {
    setSidebarCollapsed((current) => {
      const next = !current
      localStorage.setItem(SIDEBAR_STORAGE_KEY, String(next))
      return next
    })
  }

  return <div className={`dashboard-shell ${sidebarCollapsed ? 'sidebar-collapsed' : ''}`}>
    <AppSidebar usuario={usuario} collapsed={sidebarCollapsed} mobileOpen={menuOpen} onCloseMobile={() => setMenuOpen(false)} onToggle={toggleSidebar} onLogout={logout} />
    <div className="dashboard-panel">
      <DashboardHeader statusLabel="Colaboración" onMenuClick={() => setMenuOpen(true)} />
      <main className="collaboration-main">
        <section className="collaboration-heading">
          <div><span className="eyebrow">Workspace</span><h1>Colaboradores</h1><p>Gestiona quién puede ver y editar los proyectos de tu workspace.</p></div>
          <label>Workspace<select value={selectedWorkspaceId ?? ''} onChange={(event) => void selectWorkspace(Number(event.target.value))} disabled={workspaces.length === 0}>{workspaces.map((workspace) => <option key={workspace.id} value={workspace.id}>{workspace.nombre}</option>)}</select></label>
        </section>
        {error && <div className="collaboration-alert" role="alert">{error}</div>}
        <section className="members-panel">
          <header><div><h2>Miembros</h2><span>{members.length} usuario{members.length === 1 ? '' : 's'}</span></div>{canManage && <button className="primary-member-action" type="button" onClick={() => setModalOpen(true)}>+ Agregar colaborador</button>}</header>
          {loading && <div className="members-empty"><span className="loader" /> Cargando colaboradores...</div>}
          {!loading && workspaces.length === 0 && <div className="members-empty">No tienes workspaces disponibles.</div>}
          {!loading && members.map((member) => <article className="member-row" key={member.userId}>
            <span className="member-avatar">{initials(member.nombre)}</span>
            <div className="member-identity"><strong>{member.nombre}</strong><small>{member.email}</small></div>
            {canManage ? <select aria-label={`Rol de ${member.nombre}`} value={member.rol} onChange={(event) => void updateRole(member.userId, event.target.value as WorkspaceRole)}><option value="OWNER">OWNER</option><option value="EDITOR">EDITOR</option><option value="VIEWER">VIEWER</option></select> : <span className={`role-badge is-${member.rol.toLowerCase()}`}>{member.rol}</span>}
            {canManage && <button className="remove-member" type="button" disabled={member.rol === 'OWNER'} onClick={() => void removeMember(member)} title={member.rol === 'OWNER' ? 'No se puede eliminar un OWNER' : 'Eliminar colaborador'}><AppIcon name="logout" /><span>Eliminar</span></button>}
          </article>)}
        </section>
      </main>
    </div>
    {modalOpen && <div className="member-modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setModalOpen(false) }}><section className="member-modal" role="dialog" aria-modal="true" aria-labelledby="add-member-title"><header><span className="eyebrow">Nuevo acceso</span><h2 id="add-member-title">Agregar colaborador</h2></header><form onSubmit={(event) => void submitMember(event)}><label>Email<input type="email" required autoFocus value={email} onChange={(event) => setEmail(event.target.value)} placeholder="usuario@email.com" /></label><label>Rol<select value={role} onChange={(event) => setRole(event.target.value as AddWorkspaceMemberRequest['rol'])}><option value="EDITOR">EDITOR</option><option value="VIEWER">VIEWER</option></select></label><footer><button type="button" onClick={() => setModalOpen(false)}>Cancelar</button><button className="primary-member-action" type="submit" disabled={submitting}>{submitting ? 'Agregando...' : 'Agregar'}</button></footer></form></section></div>}
  </div>
}
