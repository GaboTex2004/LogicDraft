import { AppIcon as Icon, type AppIconName as IconName } from '../../../shared/components/AppIcon'
interface StatsCardProps { label: string; value?: number; icon: IconName; placeholder?: boolean }
export function StatsCard({ label, value, icon, placeholder = false }: StatsCardProps) { return <article className="stats-card"><span>{label}</span><div><strong>{placeholder ? '—' : value}</strong><Icon name={icon} /></div>{placeholder && <small>Próximamente</small>}</article> }
