import { AppIcon as Icon, type AppIconName as IconName } from '../../../shared/components/AppIcon'
const actions: { label: string; icon: IconName }[] = [{ label: 'Crear proyecto', icon: 'folder' }, { label: 'Crear diagrama', icon: 'diagram' }, { label: 'Generar con IA', icon: 'ai' }]
export function QuickActions() { return <aside className="quick-actions"><h2>Acciones rápidas</h2>{actions.map((action) => <button key={action.label} type="button" disabled title="Próximamente"><span><Icon name={action.icon} />{action.label}</span><Icon name="arrow" /></button>)}</aside> }
