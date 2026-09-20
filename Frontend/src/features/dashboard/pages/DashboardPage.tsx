import { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";

import { obtenerUsuarioActual } from "../../auth/api/authApi";
import type { UsuarioActual } from "../../auth/types/auth.types";

import { obtenerMisWorkspaces } from "../../workspace/api/workspaceApi";
import type { Workspace } from "../../workspace/types/workspace.types";
import { CreateWorkspaceForm } from "../../workspace/components/CreateWorkspaceForm";

import { obtenerProyectos } from "../../project/api/projectApi";
import type { Proyecto } from "../../project/types/project.types";

import { isUnauthorizedError } from "../../../shared/api/apiError";
import { AppIcon as Icon } from "../../../shared/components/AppIcon";
import { AppSidebar } from "../../../shared/components/AppSidebar";
import {
  SIDEBAR_STORAGE_KEY,
  useSidebarCollapsed,
} from "../../../shared/components/useAppSidebar";

import { DashboardHeader } from "../components/DashboardHeader";
import { StatsCard } from "../components/StatsCard";
import { WorkspaceCard } from "../components/WorkspaceCard";

import "../dashboard.css";

function obtenerProyectoReciente(proyectos: Proyecto[]): Proyecto | null {
  const ordenados = [...proyectos].sort((a, b) => {
    const fechaA = Date.parse(a.fechaCreacion);
    const fechaB = Date.parse(b.fechaCreacion);

    return (
      (Number.isNaN(fechaB) ? 0 : fechaB) - (Number.isNaN(fechaA) ? 0 : fechaA)
    );
  });

  return ordenados[0] ?? null;
}

export function DashboardPage() {
  const navigate = useNavigate();

  const [usuario, setUsuario] = useState<UsuarioActual | null>(null);
  const [workspaces, setWorkspaces] = useState<Workspace[]>([]);
  const [proyectoReciente, setProyectoReciente] = useState<Proyecto | null>(
    null,
  );

  const [totalProyectos, setTotalProyectos] = useState(0);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [projectsError, setProjectsError] = useState("");

  const [menuOpen, setMenuOpen] = useState(false);
  const [showCreateWorkspace, setShowCreateWorkspace] = useState(false);

  const [sidebarCollapsed, setSidebarCollapsed] = useSidebarCollapsed();

  useEffect(() => {
    let active = true;

    async function cargarDashboard() {
      setLoading(true);
      setError("");
      setProjectsError("");

      const [usuarioResult, workspacesResult] = await Promise.allSettled([
        obtenerUsuarioActual(),
        obtenerMisWorkspaces(),
      ]);

      if (!active) return;

      const unauthorized = [usuarioResult, workspacesResult].some(
        (result) =>
          result.status === "rejected" && isUnauthorizedError(result.reason),
      );

      if (unauthorized) {
        localStorage.removeItem("token");
        navigate("/login", { replace: true });
        return;
      }

      if (
        usuarioResult.status === "rejected" ||
        workspacesResult.status === "rejected"
      ) {
        setError("No se pudo cargar el dashboard.");
        setLoading(false);
        return;
      }

      const misWorkspaces = workspacesResult.value;

      setUsuario(usuarioResult.value);
      setWorkspaces(misWorkspaces);

      if (misWorkspaces.length === 0) {
        setTotalProyectos(0);
        setProyectoReciente(null);
        setLoading(false);
        return;
      }

      const resultados = await Promise.allSettled(
        misWorkspaces.map((workspace) => obtenerProyectos(workspace.id)),
      );

      if (!active) return;

      const sesionExpirada = resultados.some(
        (result) =>
          result.status === "rejected" && isUnauthorizedError(result.reason),
      );

      if (sesionExpirada) {
        localStorage.removeItem("token");
        navigate("/login", { replace: true });
        return;
      }

      const proyectos = resultados.flatMap((result) =>
        result.status === "fulfilled" ? result.value : [],
      );

      const huboErrores = resultados.some(
        (result) => result.status === "rejected",
      );

      setTotalProyectos(proyectos.length);
      setProyectoReciente(obtenerProyectoReciente(proyectos));

      if (huboErrores) {
        setProjectsError(
          "No se pudieron consultar todos los proyectos. Los datos pueden estar incompletos.",
        );
      }

      setLoading(false);
    }

    void cargarDashboard();

    return () => {
      active = false;
    };
  }, [navigate]);

  function handleLogout() {
    localStorage.removeItem("token");
    navigate("/login", { replace: true });
  }

  function handleToggleSidebar() {
    setSidebarCollapsed((current) => {
      const next = !current;

      localStorage.setItem(SIDEBAR_STORAGE_KEY, String(next));

      return next;
    });
  }

  function handleWorkspaceCreated(workspace: Workspace) {
    // Mostrar inmediatamente el nuevo workspace.
    setWorkspaces((current) => [
      workspace,
      ...current.filter((item) => item.id !== workspace.id),
    ]);

    setShowCreateWorkspace(false);
  }

  return (
    <div
      className={`dashboard-shell ${
        sidebarCollapsed ? "sidebar-collapsed" : ""
      }`}
    >
      <AppSidebar
        usuario={usuario}
        workspaces={workspaces}
        collapsed={sidebarCollapsed}
        mobileOpen={menuOpen}
        onCloseMobile={() => setMenuOpen(false)}
        onToggle={handleToggleSidebar}
        onLogout={handleLogout}
      />

      <div className="dashboard-panel">
        <DashboardHeader onMenuClick={() => setMenuOpen(true)} />

        <main className="dashboard-main">
          {loading && (
            <div className="dashboard-message">
              <span className="loader" />
              Cargando tu espacio...
            </div>
          )}

          {error && (
            <div className="dashboard-message is-error" role="alert">
              {error}
            </div>
          )}

          {!loading && !error && usuario && (
            <>
              {/* BIENVENIDA */}
              <section className="welcome">
                <span className="eyebrow">Panel principal</span>

                <h1>Hola, {usuario.nombre}.</h1>

                <p>Continúa trabajando en tus diagramas y proyectos.</p>
              </section>

              {/* ESTADÍSTICAS */}
              <section
                className="stats-grid"
                aria-label="Resumen de tu espacio"
              >
                <StatsCard
                  label="Proyectos"
                  value={projectsError ? undefined : totalProyectos}
                  icon="folder"
                  placeholder={Boolean(projectsError)}
                />

                <article className="stats-card">
                  <span>Diagramas</span>

                  <div>
                    <strong>—</strong>
                    <Icon name="diagram" />
                  </div>

                  <small>Dato no disponible</small>
                </article>

                <StatsCard
                  label="Workspaces"
                  value={workspaces.length}
                  icon="workspace"
                />

                <article className="stats-card">
                  <span>Colaboradores</span>

                  <div>
                    <strong>—</strong>
                    <Icon name="people" />
                  </div>

                  <small>Dato no disponible</small>
                </article>
              </section>

              {projectsError && (
                <p className="dashboard-data-warning" role="alert">
                  {projectsError}
                </p>
              )}

              {/* WORKSPACES */}
              <section
                className="content-section"
                aria-labelledby="workspaces-title"
              >
                <div className="section-heading">
                  <h2 id="workspaces-title">Tus workspaces</h2>

                  <span>{workspaces.length} en total</span>
                </div>

                <div className="dashboard-workspace-grid">
                  {workspaces.length > 0 && (
                    <WorkspaceCard workspace={workspaces[0]} />
                  )}

                  <button
                    className="dashboard-create-workspace"
                    type="button"
                    onClick={() => setShowCreateWorkspace(true)}
                    aria-expanded={showCreateWorkspace}
                    aria-controls="dashboard-create-workspace-form"
                  >
                    <span className="dashboard-create-icon" aria-hidden="true">
                      +
                    </span>

                    <strong>Nuevo workspace</strong>

                    <small>Organiza tus proyectos en un nuevo espacio.</small>
                  </button>
                </div>

                {showCreateWorkspace && (
                  <div
                    className="dashboard-create-panel"
                    id="dashboard-create-workspace-form"
                  >
                    <CreateWorkspaceForm
                      onCreated={handleWorkspaceCreated}
                      onCancel={() => setShowCreateWorkspace(false)}
                    />
                  </div>
                )}

                {workspaces.length > 1 && (
                  <p className="dashboard-workspace-note">
                    Tienes más workspaces. Puedes acceder a todos desde el menú
                    lateral.
                  </p>
                )}
              </section>

              {/* PROYECTO MÁS RECIENTE */}
              <section
                className="content-section"
                aria-labelledby="recent-projects-title"
              >
                <div className="section-heading">
                  <h2 id="recent-projects-title">Proyectos recientes</h2>

                  <span>Último creado</span>
                </div>

                <div className="dashboard-project-table-wrapper">
                  <table className="dashboard-project-table">
                    <thead>
                      <tr>
                        <th scope="col">Nombre</th>
                        <th scope="col">Workspace</th>
                        <th scope="col">Creado</th>
                        <th scope="col">Acción</th>
                      </tr>
                    </thead>

                    <tbody>
                      {proyectoReciente ? (
                        <tr>
                          <td>
                            <div className="dashboard-project-name">
                              <Icon name="diagram" />

                              <div>
                                <strong>{proyectoReciente.nombre}</strong>

                                <small>
                                  {proyectoReciente.descripcion?.trim() ||
                                    "Sin descripción"}
                                </small>
                              </div>
                            </div>
                          </td>

                          <td>{proyectoReciente.workspaceNombre}</td>

                          <td>
                            {proyectoReciente.fechaCreacion
                              ? new Date(
                                  proyectoReciente.fechaCreacion,
                                ).toLocaleDateString("es-BO")
                              : "Sin fecha"}
                          </td>

                          <td>
                            <Link
                              to={`/proyectos/${proyectoReciente.id}/editor`}
                            >
                              Abrir
                              <Icon name="arrow" />
                            </Link>
                          </td>
                        </tr>
                      ) : (
                        <tr>
                          <td colSpan={4}>
                            {projectsError
                              ? "No hay proyectos disponibles con los datos obtenidos."
                              : "Todavía no tienes proyectos. Abre un workspace para crear uno."}
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </section>
            </>
          )}
        </main>
      </div>
    </div>
  );
}
