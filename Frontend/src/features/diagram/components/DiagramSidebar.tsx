import { Link } from "react-router-dom";
import { UML_RELATION_KINDS, type UmlNodeKind, type UmlRelationKind } from "../types/diagram.types";

interface DiagramSidebarProps {
  projectsPath: string;
  onAddNode: (kind: UmlNodeKind) => void;
  selectedRelationKind: UmlRelationKind;
  onSelectRelationKind: (kind: UmlRelationKind) => void;
  onClose: () => void;
}

const ELEMENTS: ReadonlyArray<readonly [UmlNodeKind, string]> = [
  ["ENTITY", "Entidad"], ["CLASS", "Clase UML"], ["INTERFACE", "Interfaz"], ["ENUMERATION", "Enumeración"],
];

export function DiagramSidebar({ projectsPath, onAddNode, selectedRelationKind, onSelectRelationKind, onClose }: DiagramSidebarProps) {
  return <aside className="diagram-sidebar">
    <div className="diagram-brand"><span>LogicDraft</span><small>UML v2</small>
      <button className="diagram-panel-close" type="button" aria-label="Cerrar herramientas" onClick={onClose}>×</button>
    </div>
    <div className="diagram-sidebar-content"><p className="diagram-section-label">Elementos</p>
      {ELEMENTS.map(([kind, label]) => <button key={kind} className="add-entity-button" type="button" onClick={() => { onAddNode(kind); onClose(); }}>
        <span aria-hidden="true">＋</span> {label}
      </button>)}
      <p className="diagram-section-label">Relaciones UML</p>
      {UML_RELATION_KINDS.map(kind => <button key={kind} className={`add-entity-button${selectedRelationKind === kind ? ' is-active' : ''}`} type="button" onClick={() => onSelectRelationKind(kind)}>{kind}</button>)}
      <p className="diagram-help">Las entidades siguen siendo persistentes; los demás elementos son UML.</p>
    </div>
    <nav className="diagram-sidebar-nav"><Link to="/dashboard">Dashboard</Link><Link to={projectsPath}>Proyectos</Link></nav>
  </aside>;
}
