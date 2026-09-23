import {
  ATTRIBUTE_TYPES,
  DIAGRAM_CARDINALITIES,
  type DiagramEdge,
  type DiagramCardinality,
  type DiagramEntity,
  type EntityAttribute,
} from "../types/diagram.types";
import { cardinalities, CARDINALITY_LABELS } from '../services/relationshipCardinality';
import type { DerivedJoinTable } from '../services/derivedJoinTable';

interface DiagramPropertiesPanelProps {
  edge?: DiagramEdge | null;
  joinTable?: DerivedJoinTable | null;
  structuralRelationship?: boolean;
  onConvertAssociation?: () => void;
  associationColumns?: readonly { name: string; type: string }[];
  onChangeCardinality?: (end: 'sourceCardinality' | 'targetCardinality', value: DiagramCardinality) => void;
  onChangeRelationshipData?: (changes: { name?: string; joinTableName?: string }) => void;
  entity: DiagramEntity | null;
  onChangeName: (name: string) => void;
  onAddAttribute: () => void;
  onChangeAttribute: (
    attributeId: string,
    changes: Partial<Omit<EntityAttribute, "id">>,
  ) => void;
  onDeleteAttribute: (attributeId: string) => void;
  onClose: () => void;
}

export function DiagramPropertiesPanel({
  edge,
  joinTable,
  structuralRelationship = false,
  onConvertAssociation,
  associationColumns = [],
  onChangeCardinality,
  onChangeRelationshipData,
  entity,
  onChangeName,
  onAddAttribute,
  onChangeAttribute,
  onDeleteAttribute,
  onClose,
}: DiagramPropertiesPanelProps) {
  if (edge) {
    const cards = cardinalities(edge.data);
    return <aside className="diagram-properties">
      <header><span>Propiedades de relación</span><button className="diagram-panel-close" type="button" aria-label="Cerrar propiedades" onClick={onClose}>×</button></header>
      <div className="properties-content">
        <label>Nombre de la relacion
          <input maxLength={100} value={typeof edge.data?.name === 'string' ? edge.data.name : ''}
            disabled={structuralRelationship}
            placeholder="Opcional; necesario para relaciones paralelas"
            onChange={event => onChangeRelationshipData?.({ name: event.target.value || undefined })} />
        </label>
        {(['sourceCardinality', 'targetCardinality'] as const).map(end => <label key={end}>
          Cardinalidad {end === 'sourceCardinality' ? 'origen' : 'destino'}
          <select value={cards[end]} disabled={structuralRelationship} onChange={event => onChangeCardinality?.(end, event.target.value as DiagramCardinality)}>
            {DIAGRAM_CARDINALITIES.map(card => <option key={card} value={card}>{CARDINALITY_LABELS[card]}</option>)}
          </select>
        </label>)}
        {structuralRelationship && <p className="derived-table-help">Esta relacion mantiene la estructura de una entidad asociativa y no puede editarse ni eliminarse individualmente.</p>}
        {(['sourceCardinality', 'targetCardinality'] as const).every(end => cards[end].endsWith('MANY')) && <>
          <label>Tabla intermedia
            <input maxLength={100} value={typeof edge.data?.joinTableName === 'string' ? edge.data.joinTableName : ''}
              disabled={structuralRelationship}
              placeholder="Derivada automaticamente"
              onChange={event => onChangeRelationshipData?.({ joinTableName: event.target.value || undefined })} />
          </label>
          {joinTable && <section className="derived-table-details" aria-label="Detalles de tabla intermedia">
            <strong>{joinTable.name}</strong>
            {joinTable.columns.map(column => <div key={column.name}><span>FK {column.name}</span><code>{column.type}</code></div>)}
            <small>UNIQUE ({joinTable.columns.map(column => column.name).join(', ')})</small>
            <button type="button" onClick={onConvertAssociation}>Convertir en entidad asociativa</button>
          </section>}
          <p className="derived-table-help">Esta tabla se deriva de la relación N:M y es de solo lectura. Los atributos propios se habilitarán mediante una entidad asociativa.</p>
        </>}
      </div>
    </aside>;
  }
  return (
    <aside className="diagram-properties">
      <header><span>Propiedades de entidad</span><button className="diagram-panel-close" type="button" aria-label="Cerrar propiedades" onClick={onClose}>×</button></header>
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
            {entity.association && <section className="association-structural-fields">
              <strong>Claves foraneas estructurales</strong>
              {entity.association.endpoints.map(endpoint => {
                const column = associationColumns.find(item => item.name === endpoint.foreignKeyName)
                return <span key={endpoint.relationshipId}>FK {endpoint.foreignKeyName} <code>{column?.type ?? 'PK no definida'}</code></span>
              })}
              <small>UNIQUE ({entity.association.endpoints.map(endpoint => endpoint.foreignKeyName).join(', ')})</small>
            </section>}
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
                    disabled={Boolean(entity.association && attribute.primaryKey)}
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
                  disabled={Boolean(entity.association && attribute.primaryKey)}
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
                    disabled={Boolean(entity.association && attribute.primaryKey)}
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
