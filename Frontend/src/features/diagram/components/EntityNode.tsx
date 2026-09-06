import { Handle, Position, type NodeProps } from "@xyflow/react";
import type { EntityFlowNode } from "../types/diagram.types";

export function EntityNode({ data, selected }: NodeProps<EntityFlowNode>) {
  return (
    <article className={`entity-node ${selected ? "is-selected" : ""}`}>
      <Handle
        className="entity-handle"
        type="target"
        position={Position.Left}
      />
      <header>
        <span className="entity-node-icon" aria-hidden="true">
          ▦
        </span>
        <strong>{data.name}</strong>
        <small>entity</small>
      </header>
      <div className="entity-attributes">
        {data.attributes.length === 0 && (
          <span className="entity-empty">Sin atributos</span>
        )}
        {data.attributes.map((attribute) => (
          <div className="entity-attribute" key={attribute.id}>
            <span className={attribute.primaryKey ? "primary-key" : ""}>
              {attribute.primaryKey ? "PK" : "·"} {attribute.name}
            </span>
            <code>{attribute.type}</code>
          </div>
        ))}
      </div>
      <Handle
        className="entity-handle"
        type="source"
        position={Position.Right}
      />
    </article>
  );
}
