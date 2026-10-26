import { Handle, Position, type NodeProps } from "@xyflow/react";
import type { EntityFlowNode } from "../types/diagram.types";

const SYMBOL = {
  public: "+",
  private: "-",
  protected: "#",
  package: "~",
} as const;

export function EntityNode({ data, selected }: NodeProps<EntityFlowNode>) {
  const kind = data.kind ?? "ENTITY";

  const members = [
    data.name,
    ...data.attributes.map(
      (attribute) => `${attribute.name}: ${attribute.type}`,
    ),
    ...(data.methods ?? []).map(
      (method) =>
        `${method.name}(${method.parameters
          .map((parameter) => `${parameter.name}: ${parameter.type}`)
          .join(", ")}): ${method.returnType}`,
    ),
  ];

  const contentWidth = Math.min(
    520,
    72 + Math.max(...members.map((member) => member.length), 18) * 7,
  );

  const width = Math.max(250, data.width ?? 0, contentWidth);

  const rowCount =
    Math.max(
      1,
      kind === "ENUMERATION"
        ? (data.literals ?? []).length
        : data.attributes.length,
    ) +
    (kind !== "ENTITY" && kind !== "ENUMERATION"
      ? Math.max(1, (data.methods ?? []).length)
      : 0);

  const height = Math.max(data.height ?? 0, 46 + rowCount * 31);

  const structuralForeignKeys = new Set(
    data.association?.endpoints.map((endpoint) =>
      endpoint.foreignKeyName.toLocaleLowerCase(),
    ) ?? [],
  );

  return (
    <article
      className={`entity-node uml-${kind.toLowerCase()} ${selected ? "is-selected" : ""}`}
      style={{ width, minHeight: height }}
    >
      {/* Puntos anteriores: se conservan para compatibilidad */}
      <Handle
        className="entity-handle"
        type="target"
        position={Position.Left}
      />

      <Handle
        className="entity-handle"
        type="source"
        position={Position.Right}
      />

      {/* Nuevos puntos de conexión */}
      <Handle
        id="uml-top"
        className="entity-handle"
        type="source"
        position={Position.Top}
      />

      <Handle
        id="uml-bottom"
        className="entity-handle"
        type="target"
        position={Position.Bottom}
      />

      <header>
        <span className="entity-node-icon" aria-hidden="true">
          ◇
        </span>
        <strong className={data.abstract ? "uml-abstract" : ""}>
          {data.name}
        </strong>
        <small>
          {kind === "INTERFACE"
            ? "«interface»"
            : kind === "ENUMERATION"
              ? "«enumeration»"
              : kind.toLowerCase()}
        </small>
      </header>

      <div className="entity-attributes">
        {data.attributes.length === 0 && kind !== "ENUMERATION" && (
          <span className="entity-empty">Sin atributos</span>
        )}

        {data.attributes.map((attribute) => (
          <div className="entity-attribute" key={attribute.id}>
            <span
              className={
                attribute.primaryKey
                  ? "primary-key"
                  : structuralForeignKeys.has(
                        attribute.name.toLocaleLowerCase(),
                      )
                    ? "foreign-key"
                    : ""
              }
            >
              {attribute.primaryKey
                ? "PK"
                : structuralForeignKeys.has(attribute.name.toLocaleLowerCase())
                  ? "FK"
                  : attribute.visibility
                    ? SYMBOL[attribute.visibility]
                    : "·"}{" "}
              {attribute.name}
              {attribute.static ? " {static}" : ""}
            </span>
            <code>{attribute.type}</code>
          </div>
        ))}

        {kind === "ENUMERATION" &&
          (data.literals ?? []).map((literal) => (
            <div className="entity-attribute" key={literal}>
              <span>{literal}</span>
            </div>
          ))}
      </div>

      {kind !== "ENTITY" && kind !== "ENUMERATION" && (
        <div className="entity-attributes uml-methods">
          {(data.methods ?? []).length === 0 && (
            <span className="entity-empty">Sin métodos</span>
          )}

          {(data.methods ?? []).map((method) => (
            <div
              className={`entity-attribute${method.static ? " is-static" : ""}${method.abstract ? " is-abstract" : ""}`}
              key={method.id}
            >
              <span>
                {SYMBOL[method.visibility]} {method.name}(
                {method.parameters
                  .map((parameter) => `${parameter.name}: ${parameter.type}`)
                  .join(", ")}
                )
              </span>
              <code>{method.returnType}</code>
            </div>
          ))}
        </div>
      )}
    </article>
  );
}
