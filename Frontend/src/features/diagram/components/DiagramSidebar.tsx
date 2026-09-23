import { Link } from "react-router-dom";

interface DiagramSidebarProps {
  projectsPath: string;
  onAddEntity: () => void;
  onClose: () => void;
}

export function DiagramSidebar({
  projectsPath,
  onAddEntity,
  onClose,
}: DiagramSidebarProps) {
  return (
    <aside className="diagram-sidebar">
      <div className="diagram-brand">
        <span>LogicDraft</span>
        <small>MVP</small>
        <button className="diagram-panel-close" type="button" aria-label="Cerrar herramientas" onClick={onClose}>
          ×
        </button>
      </div>
      <div className="diagram-sidebar-content">
        <p className="diagram-section-label">Elementos</p>
        <button
          className="add-entity-button"
          type="button"
          onClick={() => {
            onAddEntity();
            onClose();
          }}
        >
          <span aria-hidden="true">＋</span> Entidad
        </button>
        <p className="diagram-help">
          Añade entidades y arrastra sus conectores para crear relaciones.
        </p>
      </div>
      <nav className="diagram-sidebar-nav">
        <Link to="/dashboard">Dashboard</Link>
        <Link to={projectsPath}>Proyectos</Link>
      </nav>
    </aside>
  );
}
