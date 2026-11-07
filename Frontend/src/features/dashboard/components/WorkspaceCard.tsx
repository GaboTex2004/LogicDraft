import { Link } from 'react-router-dom'
import type { Workspace } from '../../workspace/types/workspace.types'
import { AppIcon as Icon } from '../../../shared/components/AppIcon'
import { useTutorTargets } from '../../tutor/hooks/useTutorTargets'
export function WorkspaceCard({ workspace }: { workspace: Workspace }) {
  const target = useTutorTargets({ screen: 'dashboard', workspaceId: workspace.id, projectId: null })
  return <article className="workspace-card"><div><h3>{workspace.nombre}</h3><span className="role-badge">{workspace.rol}</span></div><p><Icon name="folder" /> Proyectos del workspace</p><footer><span className="workspace-id">ID {workspace.id}</span><Link ref={target('workspace.open', workspace.id, workspace.nombre, false)} to={`/workspaces/${workspace.id}/proyectos`}>Abrir workspace <Icon name="arrow" /></Link></footer></article>
}
