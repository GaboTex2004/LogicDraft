import { useEffect, useRef, useState } from "react";

export type SaveStatus =
  | "clean"
  | "dirty"
  | "saving"
  | "saved"
  | "error"
  | "forbidden";
export type ExportStatus =
  | "backend"
  | "fullstack"
  | "enterprise-architect"
  | null;
interface DiagramToolbarProps {
  projectName: string;
  hasSelection: boolean;
  saveStatus: SaveStatus;
  onSave: () => void;
  onDeleteSelection: () => void;
  onFitView: () => void;
  exportStatus: ExportStatus;
  exportMessage: string;
  exportDisabled: boolean;
  onExportBackend: () => void;
  onExportFullStack: () => void;
  onExportEnterpriseArchitect?: () => void;
  onToggleSidebar: () => void;
  onToggleProperties: () => void;
  sidebarOpen: boolean;
  propertiesOpen: boolean;
}

const statusLabels: Record<SaveStatus, string> = {
  clean: "Sin cambios",
  dirty: "Cambios sin guardar",
  saving: "Guardando...",
  saved: "Guardado",
  error: "Error al guardar",
  forbidden: "Sin permiso para guardar",
};

export function DiagramToolbar({
  projectName,
  hasSelection,
  saveStatus,
  onSave,
  onDeleteSelection,
  onFitView,
  exportStatus,
  exportMessage,
  exportDisabled,
  onExportBackend,
  onExportFullStack,
  onExportEnterpriseArchitect,
  onToggleSidebar,
  onToggleProperties,
  sidebarOpen,
  propertiesOpen,
}: DiagramToolbarProps) {
  const [exportOpen, setExportOpen] = useState(false);
  const exportMenuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!exportOpen) return;
    const close = (event: MouseEvent) => {
      if (!exportMenuRef.current?.contains(event.target as Node))
        setExportOpen(false);
    };
    document.addEventListener("mousedown", close);
    return () => document.removeEventListener("mousedown", close);
  }, [exportOpen]);

  return (
    <header className="diagram-toolbar">
      <div className="diagram-breadcrumb">
        <span>Proyectos</span>
        <b>/</b>
        <strong>{projectName}</strong>
        <i aria-hidden="true" />
        <small>{statusLabels[saveStatus]}</small>
      </div>
      <div className="diagram-toolbar-actions">
        <button className="diagram-panel-toggle" type="button" aria-pressed={sidebarOpen} onClick={onToggleSidebar}>
          Herramientas
        </button>
        <button className="diagram-panel-toggle" type="button" aria-pressed={propertiesOpen} onClick={onToggleProperties}>
          Propiedades
        </button>
        <button type="button" onClick={onFitView}>
          Ajustar vista
        </button>
        <div
          className="project-export-menu"
          ref={exportMenuRef}
          onKeyDown={(event) => {
            if (event.key === "Escape") setExportOpen(false);
          }}
        >
          <button
            type="button"
            aria-haspopup="menu"
            aria-expanded={exportOpen}
            disabled={exportDisabled || exportStatus !== null}
            onClick={() => setExportOpen((open) => !open)}
          >
            {exportStatus !== null ? "Generando..." : "Exportar"}{" "}
            <span aria-hidden="true">▾</span>
          </button>
          {exportOpen && (
            <div
              className="project-export-options"
              role="menu"
              aria-label={`Exportar ${projectName}`}
            >
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setExportOpen(false);
                  onExportBackend();
                }}
              >
                <strong>Solo backend</strong>
                <small>Spring Boot + PostgreSQL</small>
              </button>
              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setExportOpen(false);
                  onExportFullStack();
                }}
              >
                <strong>Proyecto completo</strong>
                <small>Backend + Flutter</small>
              </button>
              {onExportEnterpriseArchitect && (
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setExportOpen(false);
                    onExportEnterpriseArchitect();
                  }}
                >
                  <strong>Enterprise Architect</strong>
                  <small>Exportar modelo UML (.xmi)</small>
                </button>
              )}
            </div>
          )}
        </div>
        <button
          className="danger-action"
          type="button"
          disabled={!hasSelection}
          onClick={onDeleteSelection}
        >
          Eliminar selección
        </button>
        <button
          className="save-diagram-button"
          type="button"
          disabled={saveStatus === "saving" || saveStatus === "clean"}
          onClick={onSave}
        >
          {saveStatus === "saving" ? "Guardando..." : "Guardar"}
        </button>
        <span className={`persistence-badge is-${saveStatus}`}>
          {statusLabels[saveStatus]}
        </span>
        {exportMessage && (
          <span className="export-message" role="status">
            {exportMessage}
          </span>
        )}
      </div>
    </header>
  );
}
