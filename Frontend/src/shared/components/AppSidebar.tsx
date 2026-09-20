import { useLocation, useNavigate } from "react-router-dom";
import type { UsuarioActual } from "../../features/auth/types/auth.types";
import type { Workspace } from "../../features/workspace/types/workspace.types";
import { AppIcon, type AppIconName } from "./AppIcon";

interface AppSidebarProps {
  usuario: UsuarioActual | null;
  workspaces?: Workspace[];
  collapsed: boolean;
  mobileOpen: boolean;
  onCloseMobile: () => void;
  onToggle: () => void;
  onLogout: () => void;
}

const navigation: {
  label: string;
  icon: AppIconName;
  path: string;
}[] = [
  { label: "Dashboard", icon: "dashboard", path: "/dashboard" },
  { label: "Colaboración", icon: "people", path: "/colaboracion" },
];

function initials(name: string) {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0])
    .join("")
    .toUpperCase();
}

export function AppSidebar({
  usuario,
  workspaces = [],
  collapsed,
  mobileOpen,
  onCloseMobile,
  onToggle,
  onLogout,
}: AppSidebarProps) {
  const location = useLocation();
  const navigate = useNavigate();

  function goTo(path: string) {
    navigate(path);
    onCloseMobile();
  }

  return (
    <>
      <button
        className={`dashboard-overlay ${mobileOpen ? "is-visible" : ""}`}
        type="button"
        aria-label="Cerrar menú"
        onClick={onCloseMobile}
      />

      <aside
        className={`dashboard-sidebar ${collapsed ? "is-collapsed" : ""} ${mobileOpen ? "is-open" : ""}`}
      >
        <div className="dashboard-brand">
          <AppIcon name="diagram" />

          <span className="sidebar-text">LogicDraft</span>

          <button
            className="sidebar-toggle"
            type="button"
            onClick={onToggle}
            aria-label={
              collapsed ? "Expandir barra lateral" : "Contraer barra lateral"
            }
            title={
              collapsed ? "Expandir barra lateral" : "Contraer barra lateral"
            }
          >
            <span aria-hidden="true">{collapsed ? "›" : "‹"}</span>
          </button>
        </div>

        <nav className="sidebar-navigation" aria-label="Navegación principal">
          <p className="sidebar-caption">Navegación</p>

          {navigation.map((item) => (
            <button
              key={item.path}
              className={`sidebar-link ${
                location.pathname === item.path ? "is-active" : ""
              }`}
              type="button"
              onClick={() => goTo(item.path)}
              title={collapsed ? item.label : undefined}
              aria-label={collapsed ? item.label : undefined}
              aria-current={
                location.pathname === item.path ? "page" : undefined
              }
            >
              <AppIcon name={item.icon} />
              <span className="sidebar-text">{item.label}</span>
            </button>
          ))}

          {workspaces.length > 0 && (
            <>
              <p className="sidebar-caption">Proyectos por workspace</p>

              {workspaces.map((workspace) => {
                const path = `/workspaces/${workspace.id}/proyectos`;
                const active = location.pathname === path;

                return (
                  <button
                    key={workspace.id}
                    className={`sidebar-link ${active ? "is-active" : ""}`}
                    type="button"
                    onClick={() => goTo(path)}
                    title={
                      collapsed ? `Proyectos de ${workspace.nombre}` : undefined
                    }
                    aria-label={
                      collapsed ? `Proyectos de ${workspace.nombre}` : undefined
                    }
                    aria-current={active ? "page" : undefined}
                  >
                    <AppIcon name="folder" />

                    <span className="sidebar-text">{workspace.nombre}</span>
                  </button>
                );
              })}
            </>
          )}
        </nav>

        <div className="sidebar-footer">
          {usuario && (
            <div
              className="user-card"
              title={
                collapsed ? `${usuario.nombre} · ${usuario.email}` : undefined
              }
            >
              <span className="avatar">{initials(usuario.nombre)}</span>

              <span className="user-details sidebar-text">
                <strong>{usuario.nombre}</strong>
                <small>{usuario.email}</small>
              </span>

              <button
                className="icon-button logout-button sidebar-text"
                type="button"
                onClick={onLogout}
                title="Cerrar sesión"
                aria-label="Cerrar sesión"
              >
                <AppIcon name="logout" />
              </button>
            </div>
          )}
        </div>
      </aside>
    </>
  );
}
