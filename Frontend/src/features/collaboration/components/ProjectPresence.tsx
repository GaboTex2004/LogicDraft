import type { CollaborationStatus, Collaborator } from '../types/collaboration.types'

interface ProjectPresenceProps {
  status: CollaborationStatus
  collaborators: Collaborator[]
}

const statusLabels = {
  connecting: 'Conectando',
  connected: 'En línea',
  disconnected: 'Desconectado',
  error: 'Sin conexión',
} as const

function initials(name: string): string {
  return name.trim().split(/\s+/).slice(0, 2).map((part) => part[0]?.toUpperCase() ?? '').join('') || '?'
}

export function ProjectPresence({ status, collaborators }: ProjectPresenceProps) {
  return (
    <aside className="project-presence" aria-label="Colaboradores conectados">
      <div className="presence-summary">
        <span className={`presence-status is-${status}`} aria-hidden="true" />
        <strong>{statusLabels[status]}</strong>
        <span>{collaborators.length} conectado{collaborators.length === 1 ? '' : 's'}</span>
      </div>
      <div className="presence-users">
        {collaborators.slice(0, 4).map((user) => (
          <span className="presence-avatar" key={user.userId} title={user.name}>{initials(user.name)}</span>
        ))}
        {collaborators.length > 4 && <span className="presence-more">+{collaborators.length - 4}</span>}
      </div>
    </aside>
  )
}
