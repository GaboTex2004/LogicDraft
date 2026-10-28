import type { CSSProperties, FocusEvent, KeyboardEvent } from "react";
import {
  ATTRIBUTE_TYPES,
  DIAGRAM_CARDINALITIES,
  type DiagramEdge,
  type DiagramCardinality,
  type DiagramEntity,
  type EntityAttribute,
  type UmlMethod,
  type RelationshipData,
  UML_RELATION_KINDS,
} from "../types/diagram.types";
import { cardinalities, CARDINALITY_LABELS } from '../services/relationshipCardinality';
import type { DerivedJoinTable } from '../services/derivedJoinTable';
import { formatLockedByMessage } from '../../collaboration/services/elementLocks';

interface DiagramPropertiesPanelProps {
  edge?: DiagramEdge | null;
  joinTable?: DerivedJoinTable | null;
  structuralRelationship?: boolean;
  lockedBy?: { label: string; color: string } | null;
  onEditIntent?: () => void;
  onAttemptLockedEdit?: (ownerName: string) => void;
  onConvertAssociation?: () => void;
  associationColumns?: readonly { name: string; type: string }[];
  onChangeCardinality?: (end: 'sourceCardinality' | 'targetCardinality', value: DiagramCardinality) => void;
  onChangeRelationshipData?: (changes: Partial<RelationshipData>) => void;
  entity: DiagramEntity | null;
  onChangeName: (name: string) => void;
  onChangeGenerateCrud?: (enabled: boolean) => void;
  onAddAttribute: () => void;
  onChangeAttribute: (
    attributeId: string,
    changes: Partial<Omit<EntityAttribute, "id">>,
  ) => void;
  onDeleteAttribute: (attributeId: string) => void;
  onAddMethod: () => void;
  onChangeMethod: (methodId: string, changes: Partial<Omit<UmlMethod, 'id'>>) => void;
  onDeleteMethod: (methodId: string) => void;
  onClose: () => void;
}

export function DiagramPropertiesPanel({
  edge,
  joinTable,
  structuralRelationship = false,
  lockedBy = null,
  onEditIntent,
  onAttemptLockedEdit,
  onConvertAssociation,
  associationColumns = [],
  onChangeCardinality,
  onChangeRelationshipData,
  entity,
  onChangeName,
  onChangeGenerateCrud,
  onAddAttribute,
  onChangeAttribute,
  onDeleteAttribute,
  onAddMethod,
  onChangeMethod,
  onDeleteMethod,
  onClose,
}: DiagramPropertiesPanelProps) {
  const isLockedByOther = Boolean(lockedBy);

  function notifyLockedAttempt() {
    if (lockedBy) {
      onAttemptLockedEdit?.(lockedBy.label);
    }
  }

  function handleLockedTextKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (!isLockedByOther) return;
    if (
      event.key.length === 1 ||
      event.key === "Backspace" ||
      event.key === "Delete" ||
      event.key === "Enter"
    ) {
      event.preventDefault();
      notifyLockedAttempt();
    }
  }

  function handleContentFocusCapture(event: FocusEvent<HTMLDivElement>) {
    if (isLockedByOther || structuralRelationship) return;
    const target = event.target;
    if (
      target instanceof HTMLInputElement ||
      target instanceof HTMLSelectElement ||
      target instanceof HTMLTextAreaElement
    ) {
      onEditIntent?.();
    }
  }

  const lockBanner = lockedBy ? (
    <div
      className="properties-lock-banner"
      role="status"
      style={{ "--lock-color": lockedBy.color } as CSSProperties}
    >
      <span
        className="properties-lock-dot"
        style={{ backgroundColor: lockedBy.color }}
        aria-hidden="true"
      />
      <div>
        <strong>{lockedBy.label} editando</strong>
        <small>{formatLockedByMessage(lockedBy.label)}</small>
      </div>
    </div>
  ) : null;

  if (edge) {
    const cards = cardinalities(edge.data);
    return <aside className="diagram-properties">
      <header><span>Propiedades de relación</span><button className="diagram-panel-close" type="button" aria-label="Cerrar propiedades" onClick={onClose}>×</button></header>
      <div className={`properties-content${isLockedByOther ? ' is-locked-readonly' : ''}`} onFocusCapture={handleContentFocusCapture}>
        {lockBanner}
        <label>Tipo UML
          <select value={typeof edge.data?.relationKind === 'string' ? edge.data.relationKind : 'ASSOCIATION'} disabled={structuralRelationship}
            aria-disabled={isLockedByOther || structuralRelationship || undefined}
            onMouseDown={event => {
              if (isLockedByOther) {
                event.preventDefault();
                notifyLockedAttempt();
              }
            }}
            onChange={event => {
              if (isLockedByOther) {
                notifyLockedAttempt();
                return;
              }
              onChangeRelationshipData?.({ relationKind: event.target.value as RelationshipData['relationKind'] });
            }}>
            {UML_RELATION_KINDS.map(kind => <option key={kind} value={kind}>{kind}</option>)}
          </select>
        </label>
        <label>Nombre de la relacion
          <input maxLength={100} value={typeof edge.data?.name === 'string' ? edge.data.name : ''}
            disabled={structuralRelationship}
            readOnly={isLockedByOther}
            aria-disabled={isLockedByOther || structuralRelationship || undefined}
            placeholder="Opcional; necesario para relaciones paralelas"
            onClick={() => { if (isLockedByOther) notifyLockedAttempt(); }}
            onKeyDown={handleLockedTextKeyDown}
            onChange={event => {
              if (isLockedByOther) {
                notifyLockedAttempt();
                return;
              }
              onChangeRelationshipData?.({ name: event.target.value || undefined });
            }} />
        </label>
        {(['sourceCardinality', 'targetCardinality'] as const).map(end => <label key={end}>
          Cardinalidad {end === 'sourceCardinality' ? 'origen' : 'destino'}
          <select value={cards[end]} disabled={structuralRelationship}
            aria-disabled={isLockedByOther || structuralRelationship || undefined}
            onMouseDown={event => {
              if (isLockedByOther) {
                event.preventDefault();
                notifyLockedAttempt();
              }
            }}
            onChange={event => {
              if (isLockedByOther) {
                notifyLockedAttempt();
                return;
              }
              onChangeCardinality?.(end, event.target.value as DiagramCardinality);
            }}>
            {DIAGRAM_CARDINALITIES.map(card => <option key={card} value={card}>{CARDINALITY_LABELS[card]}</option>)}
          </select>
        </label>)}
        {structuralRelationship && <p className="derived-table-help">Esta relacion mantiene la estructura de una entidad asociativa y no puede editarse ni eliminarse individualmente.</p>}
        {(['sourceCardinality', 'targetCardinality'] as const).every(end => cards[end].endsWith('MANY')) && <>
          <label>Tabla intermedia
            <input maxLength={100} value={typeof edge.data?.joinTableName === 'string' ? edge.data.joinTableName : ''}
              disabled={structuralRelationship}
              readOnly={isLockedByOther}
              aria-disabled={isLockedByOther || structuralRelationship || undefined}
              placeholder="Derivada automaticamente"
              onClick={() => { if (isLockedByOther) notifyLockedAttempt(); }}
              onKeyDown={handleLockedTextKeyDown}
              onChange={event => {
                if (isLockedByOther) {
                  notifyLockedAttempt();
                  return;
                }
                onChangeRelationshipData?.({ joinTableName: event.target.value || undefined });
              }} />
          </label>
          {joinTable && <section className="derived-table-details" aria-label="Detalles de tabla intermedia">
            <strong>{joinTable.name}</strong>
            {joinTable.columns.map(column => <div key={column.name}><span>FK {column.name}</span><code>{column.type}</code></div>)}
            <small>UNIQUE ({joinTable.columns.map(column => column.name).join(', ')})</small>
            <button type="button" aria-disabled={isLockedByOther || undefined}
              onClick={() => {
                if (isLockedByOther) {
                  notifyLockedAttempt();
                  return;
                }
                onConvertAssociation?.();
              }}>Convertir en entidad asociativa</button>
          </section>}
          <p className="derived-table-help">Esta tabla se deriva de la relación N:M y es de solo lectura. Los atributos propios se habilitarán mediante una entidad asociativa.</p>
        </>}
      </div>
    </aside>;
  }
  return (
    <aside className="diagram-properties">
      <header><span>Propiedades {entity?.kind && entity.kind !== 'ENTITY' ? 'UML' : 'de entidad'}</span><button className="diagram-panel-close" type="button" aria-label="Cerrar propiedades" onClick={onClose}>×</button></header>
      {!entity ? (
        <div className="properties-empty">
          <span aria-hidden="true">◇</span>
          <p>Selecciona una entidad para editar sus propiedades.</p>
        </div>
      ) : (
        <div className={`properties-content${isLockedByOther ? ' is-locked-readonly' : ''}`} onFocusCapture={handleContentFocusCapture}>
          {lockBanner}
          <label htmlFor="entity-name">Nombre</label>
          <input
            id="entity-name"
            maxLength={100}
            value={entity.name}
            readOnly={isLockedByOther}
            aria-disabled={isLockedByOther || undefined}
            onClick={() => { if (isLockedByOther) notifyLockedAttempt(); }}
            onKeyDown={handleLockedTextKeyDown}
            onChange={(event) => {
              if (isLockedByOther) {
                notifyLockedAttempt();
                return;
              }
              onChangeName(event.target.value);
            }}
          />
          {entity.kind === 'CLASS' && (
            <label className="primary-key-control">
              <input
                type="checkbox"
                checked={entity.generateCrud === true}
                aria-disabled={isLockedByOther || undefined}
                onClick={(event) => {
                  if (isLockedByOther) {
                    event.preventDefault();
                    notifyLockedAttempt();
                  }
                }}
                onChange={(event) => {
                  if (isLockedByOther) {
                    notifyLockedAttempt();
                    return;
                  }
                  onChangeGenerateCrud?.(event.target.checked);
                }}
              />
              Generar CRUD (Spring Boot + Flutter)
              <small>Requiere exactamente una Primary Key, sin métodos ni herencia.
                No modifica el tipo UML ni el XMI.</small>
            </label>
          )}
          <div className="attributes-heading">
            <span>Atributos</span>
            <button
              type="button"
              aria-disabled={isLockedByOther || undefined}
              onClick={() => {
                if (isLockedByOther) {
                  notifyLockedAttempt();
                  return;
                }
                onAddAttribute();
              }}
            >
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
                    readOnly={isLockedByOther}
                    aria-disabled={isLockedByOther || undefined}
                    onClick={() => { if (isLockedByOther) notifyLockedAttempt(); }}
                    onKeyDown={handleLockedTextKeyDown}
                    onChange={(event) => {
                      if (isLockedByOther) {
                        notifyLockedAttempt();
                        return;
                      }
                      onChangeAttribute(attribute.id, {
                        name: event.target.value,
                      });
                    }}
                  />
                  <button
                    type="button"
                    disabled={Boolean(entity.association && attribute.primaryKey)}
                    aria-disabled={isLockedByOther || Boolean(entity.association && attribute.primaryKey) || undefined}
                    title="Eliminar atributo"
                    aria-label={`Eliminar ${attribute.name}`}
                    onClick={() => {
                      if (isLockedByOther) {
                        notifyLockedAttempt();
                        return;
                      }
                      onDeleteAttribute(attribute.id);
                    }}
                  >
                    ×
                  </button>
                </div>
                <select
                  aria-label={`Tipo de ${attribute.name}`}
                  value={attribute.type}
                  disabled={Boolean(entity.association && attribute.primaryKey)}
                  aria-disabled={isLockedByOther || Boolean(entity.association && attribute.primaryKey) || undefined}
                  onMouseDown={(event) => {
                    if (isLockedByOther) {
                      event.preventDefault();
                      notifyLockedAttempt();
                    }
                  }}
                  onChange={(event) => {
                    if (isLockedByOther) {
                      notifyLockedAttempt();
                      return;
                    }
                    onChangeAttribute(attribute.id, {
                      type: event.target.value as EntityAttribute["type"],
                    });
                  }}
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
                    aria-disabled={isLockedByOther || Boolean(entity.association && attribute.primaryKey) || undefined}
                    onClick={(event) => {
                      if (isLockedByOther) {
                        event.preventDefault();
                        notifyLockedAttempt();
                      }
                    }}
                    onChange={(event) => {
                      if (isLockedByOther) {
                        notifyLockedAttempt();
                        return;
                      }
                      onChangeAttribute(attribute.id, {
                        primaryKey: event.target.checked,
                      });
                    }}
                  />{" "}
                  Primary Key
                </label>
              </section>
            ))}
          </div>
          <button
            className="wide-add-attribute"
            type="button"
            aria-disabled={isLockedByOther || undefined}
            onClick={() => {
              if (isLockedByOther) {
                notifyLockedAttempt();
                return;
              }
              onAddAttribute();
            }}
          >
            ＋ Agregar atributo
          </button>
          {entity.kind && entity.kind !== 'ENTITY' && entity.kind !== 'ENUMERATION' && <>
            <div className="attributes-heading"><span>Métodos</span><button type="button" aria-disabled={isLockedByOther || undefined} onClick={() => {
              if (isLockedByOther) {
                notifyLockedAttempt();
                return;
              }
              onAddMethod();
            }}>＋ Agregar</button></div>
            <div className="attribute-editor-list">{(entity.methods ?? []).map(method => <section className="attribute-editor" key={method.id}>
              <div className="attribute-name-row"><input aria-label="Nombre del método" maxLength={100} value={method.name} readOnly={isLockedByOther} aria-disabled={isLockedByOther || undefined} onClick={() => { if (isLockedByOther) notifyLockedAttempt(); }} onKeyDown={handleLockedTextKeyDown} onChange={event => {
                if (isLockedByOther) {
                  notifyLockedAttempt();
                  return;
                }
                onChangeMethod(method.id, { name: event.target.value });
              }} />
                <button type="button" aria-label={`Eliminar ${method.name}`} aria-disabled={isLockedByOther || undefined} onClick={() => {
                  if (isLockedByOther) {
                    notifyLockedAttempt();
                    return;
                  }
                  onDeleteMethod(method.id);
                }}>×</button></div>
              <select aria-label={`Visibilidad de ${method.name}`} value={method.visibility} aria-disabled={isLockedByOther || undefined} onMouseDown={event => {
                if (isLockedByOther) {
                  event.preventDefault();
                  notifyLockedAttempt();
                }
              }} onChange={event => {
                if (isLockedByOther) {
                  notifyLockedAttempt();
                  return;
                }
                onChangeMethod(method.id, { visibility: event.target.value as UmlMethod['visibility'] });
              }}>
                <option value="public">public</option><option value="private">private</option><option value="protected">protected</option><option value="package">package</option>
              </select>
              <input aria-label={`Retorno de ${method.name}`} maxLength={100} value={method.returnType} readOnly={isLockedByOther} aria-disabled={isLockedByOther || undefined} onClick={() => { if (isLockedByOther) notifyLockedAttempt(); }} onKeyDown={handleLockedTextKeyDown} onChange={event => {
                if (isLockedByOther) {
                  notifyLockedAttempt();
                  return;
                }
                onChangeMethod(method.id, { returnType: event.target.value });
              }} />
            </section>)}</div>
          </>}
        </div>
      )}
    </aside>
  );
}
