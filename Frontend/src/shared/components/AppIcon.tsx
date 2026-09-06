import type { ReactNode, SVGProps } from 'react'

export type AppIconName = 'ai' | 'arrow' | 'dashboard' | 'diagram' | 'folder' | 'help' | 'logout' | 'menu' | 'people' | 'search' | 'settings' | 'workspace'
interface AppIconProps extends SVGProps<SVGSVGElement> { name: AppIconName }
const paths: Record<AppIconName, ReactNode> = {
  ai: <path d="m12 3 1.3 4.2 4.2 1.3-4.2 1.3L12 14l-1.3-4.2-4.2-1.3 4.2-1.3L12 3Zm6 10 .8 2.2L21 16l-2.2.8L18 19l-.8-2.2L15 16l2.2-.8L18 13ZM6 14l1 3 3 1-3 1-1 3-1-3-3-1 3-1 1-3Z" />,
  arrow: <path d="M5 12h14m-5-5 5 5-5 5" />, dashboard: <path d="M4 4h6v6H4V4Zm10 0h6v6h-6V4ZM4 14h6v6H4v-6Zm10 0h6v6h-6v-6Z" />,
  diagram: <path d="M9 4h6v5H9V4ZM4 15h6v5H4v-5Zm10 0h6v5h-6v-5Zm-2-6v3m-5 0h10M7 12v3m10-3v3" />, folder: <path d="M3 6h7l2 2h9v11H3V6Z" />,
  help: <><circle cx="12" cy="12" r="9" /><path d="M9.8 9a2.4 2.4 0 1 1 3.1 2.3c-.9.4-.9 1-.9 1.7m0 3h.01" /></>, logout: <path d="M10 4H4v16h6m5-4 4-4-4-4m4 4H9" />,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />, people: <><circle cx="9" cy="9" r="3" /><circle cx="17" cy="10" r="2" /><path d="M3 20c0-4 2-6 6-6s6 2 6 6m0-5c3 0 5 1.5 5 5" /></>,
  search: <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4 4" /></>, settings: <><circle cx="12" cy="12" r="3" /><path d="M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6 7 7m10 10 1.4 1.4m0-12.8L17 7M7 17l-1.4 1.4" /></>,
  workspace: <><circle cx="12" cy="6" r="3" /><circle cx="6" cy="17" r="3" /><circle cx="18" cy="17" r="3" /><path d="m10 8-3 6m7-6 3 6M9 17h6" /></>,
}
export function AppIcon({ name, ...props }: AppIconProps) { return <svg aria-hidden="true" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="1.7" strokeLinecap="square" strokeLinejoin="miter" {...props}>{paths[name]}</svg> }
