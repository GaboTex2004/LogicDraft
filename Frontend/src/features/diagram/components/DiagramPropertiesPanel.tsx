import {
  ATTRIBUTE_TYPES,
  DIAGRAM_CARDINALITIES,
  type DiagramEdge,
  type DiagramCardinality,
  type DiagramEntity,
  type EntityAttribute,
} from "../types/diagram.types";
import { cardinalities, CARDINALITY_LABELS } from '../services/relationshipCardinality';

interface DiagramPropertiesPanelProps {
  edge?: DiagramEdge | null;
  onChangeCardinality?: (end: 'sourceCardinality' | 'targetCardinality', value: DiagramCardinality) => void;
  entity: DiagramEntity | null;
  onChangeName: (name: string) => void;
  onAddAttribute: () => void;
  onChangeAttribute: (
    attributeId: string,
    changes: Partial<Omit<EntityAttribute, "id">>,
  ) => void;
  onDeleteAttribute: (attributeId: string) => void;
}

export function DiagramPropertiesPanel({
  edge,
  onChangeCardinality,
  entity,
  onChangeName,
  onAddAttribute,
  onChangeAttribute,
  onDeleteAttribute,
}: DiagramPropertiesPanelProps) {
  if (edge) {
    const cards = cardinalities(edge.data);
    return <aside className="diagram-properties">
      <header>Propiedades de relación</header>
      <div className="properties-content">
        {(['sourceCardinality', 'targetCardinality'] as const).map(end => <label key={end}>
          Cardinalidad {end === 'sourceCardinality' ? 'origen' : 'destino'}
          <select value={cards[end]} onChange={event => onChangeCardinality?.(end, event.target.value as DiagramCardinality)}>
            {DIAGRAM_CARDINALITIES.map(card => <option key={card} value={card}>{CARDINALITY_LABELS[card]}</option>)}
          </select>
        </label>)}
      </div>
    </aside>;
  }
  return (
    <aside className="diagram-properties">
      <header>Propiedades de entidad</header>
      {!entity ? (
        <div className="properties-empty">
          <span aria-hidden="true">◇</span>
          <p>Selecciona una entidad para editar sus propiedades.</p>
        </div>
      ) : (
        <div className="properties-content">
          <label htmlFor="entity-name">Nombre</label>
          <input
            id="entity-name"
            maxLength={100}
            value={entity.name}
            onChange={(event) => onChangeName(event.target.value)}
          />
          <div className="attributes-heading">
            <span>Atributos</span>
            <button type="button" onClick={onAddAttribute}>
              ＋ Agregar
            </button>
          </div>
          <div className="attribute-editor-list">
            {entity.attributes.map((attribute) => (
              <section className="attribute-editor" key={attribute.id}>
                <div className="attribute-name-row">
                  <input
                    aria-label="Nombre del atributo"
                    maxLength={100}
                    value={attribute.name}
                    onChange={(event) =>
                      onChangeAttribute(attribute.id, {
                        name: event.target.value,
                      })
                    }
                  />
                  <button
                    type="button"
                    title="Eliminar atributo"
                    aria-label={`Eliminar ${attribute.name}`}
                    onClick={() => onDeleteAttribute(attribute.id)}
                  >
                    ×
                  </button>
                </div>
                <select
                  aria-label={`Tipo de ${attribute.name}`}
                  value={attribute.type}
                  onChange={(event) =>
                    onChangeAttribute(attribute.id, {
                      type: event.target.value as EntityAttribute["type"],
                    })
                  }
                >
                  {ATTRIBUTE_TYPES.map((type) => (
                    <option key={type} value={type}>
                      {type}
                    </option>
                  ))}
                </select>
                <label className="primary-key-control">
                  <input
                    type="checkbox"
                    checked={attribute.primaryKey}
                    onChange={(event) =>
                      onChangeAttribute(attribute.id, {
                        primaryKey: event.target.checked,
                      })
                    }
                  />{" "}
                  Primary Key
                </label>
              </section>
            ))}
          </div>
          <button
            className="wide-add-attribute"
            type="button"
            onClick={onAddAttribute}
          >
            ＋ Agregar atributo
          </button>
        </div>
      )}
    </aside>
  );
}
