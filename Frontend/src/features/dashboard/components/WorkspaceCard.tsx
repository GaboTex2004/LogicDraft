import { Link } from 'react-router-dom'
import type { Workspace } from '../../workspace/types/workspace.types'
import { AppIcon as Icon } from '../../../shared/components/AppIcon'
export function WorkspaceCard({ workspace }: { workspace: Workspace }) { return <article className="workspace-card"><div><h3>{workspace.nombre}</h3><span className="role-badge">{workspace.rol}</span></div><p><Icon name="folder" /> Proyectos del workspace</p><footer><span className="workspace-id">ID {workspace.id}</span><Link to={`/workspaces/${workspace.id}/proyectos`}>Abrir workspace <Icon name="arrow" /></Link></footer></article> }
