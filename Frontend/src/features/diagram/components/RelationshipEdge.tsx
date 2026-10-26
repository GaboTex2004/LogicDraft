import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  useInternalNode,
  useReactFlow,
  type EdgeProps,
} from "@xyflow/react";
import type { DiagramEdge, EntityFlowNode } from "../types/diagram.types";
import { deriveJoinTable } from "../services/derivedJoinTable";
import {
  useRef,
  useState,
  type PointerEvent as ReactPointerEvent,
} from "react";
import {
  cardinalities,
  CARDINALITY_LABELS,
} from "../services/relationshipCardinality";
import {
  associationClassProjection,
  type AssociationClassProjection,
} from "../services/associationClassProjection";

function AssociationClassRelationship({
  props,
  projection,
}: {
  props: EdgeProps<DiagramEdge>;
  projection: AssociationClassProjection;
}) {
  const sourceNode = useInternalNode<EntityFlowNode>(projection.sourceNodeId);
  const targetNode = useInternalNode<EntityFlowNode>(projection.targetNodeId);
  const associationNode = useInternalNode<EntityFlowNode>(
    projection.associationNodeId,
  );

  if (!sourceNode || !targetNode || !associationNode) return null;

  const center = (node: NonNullable<typeof sourceNode>) => ({
    x:
      node.internals.positionAbsolute.x +
      (node.measured.width ?? node.data.width ?? 250) / 2,
    y:
      node.internals.positionAbsolute.y +
      (node.measured.height ?? node.data.height ?? 120) / 2,
    width: node.measured.width ?? node.data.width ?? 250,
    height: node.measured.height ?? node.data.height ?? 120,
  });

  const boundary = (
    from: ReturnType<typeof center>,
    toward: ReturnType<typeof center>,
  ) => {
    const dx = toward.x - from.x;
    const dy = toward.y - from.y;
    const divisor = Math.max(
      Math.abs(dx) / (from.width / 2),
      Math.abs(dy) / (from.height / 2),
    );
    return divisor > 0
      ? { x: from.x + dx / divisor, y: from.y + dy / divisor }
      : { x: from.x, y: from.y };
  };

  const sourceCenter = center(sourceNode);
  const targetCenter = center(targetNode);
  const classCenter = center(associationNode);

  const source = boundary(sourceCenter, targetCenter);
  const target = boundary(targetCenter, sourceCenter);

  const midpoint = {
    x: (source.x + target.x) / 2,
    y: (source.y + target.y) / 2,
    width: 1,
    height: 1,
  };

  const classEndpoint = boundary(classCenter, midpoint);

  const mainPath = `M ${source.x},${source.y} L ${target.x},${target.y}`;
  const classPath = `M ${classEndpoint.x},${classEndpoint.y} L ${midpoint.x},${midpoint.y}`;

  const label = (
    start: { x: number; y: number },
    end: { x: number; y: number },
    ratio: number,
  ) => ({
    x: start.x + (end.x - start.x) * ratio,
    y: start.y + (end.y - start.y) * ratio - 16,
  });

  const sourceLabel = label(source, target, 0.12);
  const targetLabel = label(source, target, 0.88);

  return (
    <>
      <BaseEdge
        id={props.id}
        path={mainPath}
        interactionWidth={24}
        style={{
          ...props.style,
          strokeWidth: props.selected ? 2.5 : props.style?.strokeWidth,
        }}
      />
      <BaseEdge
        id={`${props.id}-association-class`}
        path={classPath}
        style={{
          ...props.style,
          strokeDasharray: "7 5",
          pointerEvents: "none",
        }}
        interactionWidth={0}
      />

      <EdgeLabelRenderer>
        <span
          className="relationship-cardinality nodrag nopan"
          style={{
            transform: `translate(-50%, -50%) translate(${sourceLabel.x}px, ${sourceLabel.y}px)`,
          }}
        >
          {CARDINALITY_LABELS[
            projection.sourceMultiplicity as keyof typeof CARDINALITY_LABELS
          ] ?? projection.sourceMultiplicity}
        </span>

        <span
          className="relationship-cardinality nodrag nopan"
          style={{
            transform: `translate(-50%, -50%) translate(${targetLabel.x}px, ${targetLabel.y}px)`,
          }}
        >
          {CARDINALITY_LABELS[
            projection.targetMultiplicity as keyof typeof CARDINALITY_LABELS
          ] ?? projection.targetMultiplicity}
        </span>
      </EdgeLabelRenderer>
    </>
  );
}

function DerivedAssociationClassPreview({
  props,
  labelX,
  labelY,
}: {
  props: EdgeProps<DiagramEdge>;
  labelX: number;
  labelY: number;
}) {
  const flow = useReactFlow<EntityFlowNode, DiagramEdge>();

  const sourceNode = useInternalNode<EntityFlowNode>(props.source);
  const targetNode = useInternalNode<EntityFlowNode>(props.target);

  type Offset = { x: number; y: number };

  const DEFAULT_OFFSET: Offset = { x: 0, y: 150 };

  const storageKey =
    typeof window !== "undefined"
      ? `logicdraft:derived-position:${window.location.pathname}:${props.id}`
      : `logicdraft:derived-position:${props.id}`;

  // Posición visual independiente de la relación principal.
  const [offset, setOffset] = useState<Offset>(() => {
    if (typeof window === "undefined") return DEFAULT_OFFSET;

    try {
      const saved = window.localStorage.getItem(storageKey);
      if (saved) {
        const parsed = JSON.parse(saved);

        if (Number.isFinite(parsed.x) && Number.isFinite(parsed.y)) {
          return { x: parsed.x, y: parsed.y };
        }
      }
    } catch {
      // Si falla el almacenamiento, usar posición predeterminada.
    }

    return DEFAULT_OFFSET;
  });

  const [dragging, setDragging] = useState(false);

  const dragRef = useRef<{
    pointerId: number;
    startX: number;
    startY: number;
    initial: Offset;
    current: Offset;
  } | null>(null);

  const joinTable =
    sourceNode && targetNode
      ? deriveJoinTable(props, sourceNode.data, targetNode.data)
      : null;

  // Mantener el orden de los hooks incluso si no hay tabla derivada.
  if (!joinTable) return null;

  const panelX = labelX + offset.x;
  const panelY = labelY + offset.y;

  // La línea punteada llega al panel y se actualiza al moverlo.
  const connectorPath = `M ${labelX},${labelY} L ${panelX},${panelY}`;

  function beginDrag(event: ReactPointerEvent<HTMLElement>) {
    if (event.button !== 0) return;

    event.preventDefault();
    event.stopPropagation();

    event.currentTarget.setPointerCapture(event.pointerId);

    dragRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      initial: { ...offset },
      current: { ...offset },
    };

    setDragging(true);
  }

  function moveDrag(event: ReactPointerEvent<HTMLElement>) {
    const drag = dragRef.current;

    if (!drag || drag.pointerId !== event.pointerId) return;

    event.preventDefault();
    event.stopPropagation();

    // Convertir el movimiento de pantalla a coordenadas React Flow.
    // Esto funciona incluso cuando se utiliza zoom.
    const zoom = Math.max(flow.getZoom(), 0.01);

    const nextOffset = {
      x: drag.initial.x + (event.clientX - drag.startX) / zoom,
      y: drag.initial.y + (event.clientY - drag.startY) / zoom,
    };

    drag.current = nextOffset;
    setOffset(nextOffset);
  }

  function endDrag(event: ReactPointerEvent<HTMLElement>) {
    const drag = dragRef.current;

    if (!drag || drag.pointerId !== event.pointerId) return;

    event.stopPropagation();

    // Recordar la posición en este navegador.
    try {
      window.localStorage.setItem(storageKey, JSON.stringify(drag.current));
    } catch {
      // El arrastre sigue funcionando aunque localStorage no esté disponible.
    }

    dragRef.current = null;
    setDragging(false);

    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  function resetPosition() {
    const initial = { ...DEFAULT_OFFSET };
    setOffset(initial);

    try {
      window.localStorage.removeItem(storageKey);
    } catch {
      // No bloquear la interfaz.
    }
  }

  function selectAssociation() {
    flow.setNodes((nodes) =>
      nodes.map((node) => ({ ...node, selected: false })),
    );

    flow.setEdges((edges) =>
      edges.map((edge) => ({
        ...edge,
        selected: edge.id === props.id,
      })),
    );
  }

  return (
    <>
      {/* Línea punteada: exclusivamente visual */}
      <BaseEdge
        id={`${props.id}-derived-association-link`}
        path={connectorPath}
        interactionWidth={0}
        style={{
          stroke: "#a5b4fc",
          strokeWidth: 1.5,
          strokeDasharray: "7 5",
          pointerEvents: "none",
        }}
      />

      <EdgeLabelRenderer>
        <article
          className={`derived-association-class nodrag nopan ${
            props.selected ? "is-selected" : ""
          } ${dragging ? "is-dragging" : ""}`}
          role="button"
          tabIndex={0}
          aria-label={`Clase asociativa derivada ${joinTable.name}`}
          style={{
            position: "absolute",
            pointerEvents: "all",
            transform: `translate(-50%, -50%) translate(${panelX}px, ${panelY}px)`,
          }}
          onClick={selectAssociation}
          onKeyDown={(event) => {
            if (event.key === "Enter" || event.key === " ") {
              event.preventDefault();
              selectAssociation();
            }
          }}
        >
          {/* Solo la cabecera sirve como área de arrastre */}
          <header
            title="Arrastra para mover. Doble clic para restablecer."
            onPointerDown={beginDrag}
            onPointerMove={moveDrag}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
            onDoubleClick={(event) => {
              event.stopPropagation();
              resetPosition();
            }}
            style={{
              cursor: dragging ? "grabbing" : "grab",
              touchAction: "none",
              userSelect: "none",
            }}
          >
            <strong>{joinTable.name}</strong>
            <small>association_class</small>
          </header>

          <div className="derived-association-attributes">
            {joinTable.columns.map((column) => (
              <div key={`${column.referencedEntity}-${column.name}`}>
                <span>
                  <b>FK</b> {column.name}
                </span>
                <code>{column.type}</code>
              </div>
            ))}
          </div>

          <footer>Vista derivada · Sin métodos</footer>
        </article>
      </EdgeLabelRenderer>
    </>
  );
}

export function RelationshipEdge(props: EdgeProps<DiagramEdge>) {
  const flow = useReactFlow<EntityFlowNode, DiagramEdge>();
  const sourceNode = useInternalNode<EntityFlowNode>(props.source);
  const targetNode = useInternalNode<EntityFlowNode>(props.target);

  const associationProjection = associationClassProjection(
    props,
    flow.getNodes(),
    flow.getEdges(),
  );

  if (associationProjection?.hidden) return null;
  if (associationProjection) {
    return (
      <AssociationClassRelationship
        props={props}
        projection={associationProjection}
      />
    );
  }

  const [path, labelX, labelY] = getBezierPath(props);
  const cards = cardinalities(props.data);
  const joinTable = deriveJoinTable(props, sourceNode?.data, targetNode?.data);

  const relationKind = props.data?.relationKind ?? "ASSOCIATION";
  const dashed =
    relationKind === "REALIZATION" || relationKind === "DEPENDENCY";
  const aggregationEnd = props.data?.aggregationEnd ?? "SOURCE";

  const diamondStart =
    relationKind === "COMPOSITION"
      ? "url(#uml-solid-diamond)"
      : relationKind === "AGGREGATION"
        ? "url(#uml-hollow-diamond)"
        : undefined;

  const diamondEnd =
    relationKind === "COMPOSITION"
      ? "url(#uml-solid-diamond-end)"
      : relationKind === "AGGREGATION"
        ? "url(#uml-hollow-diamond-end)"
        : undefined;

  const markerEnd =
    aggregationEnd === "TARGET" && diamondEnd
      ? diamondEnd
      : relationKind === "GENERALIZATION" || relationKind === "REALIZATION"
        ? "url(#uml-hollow-triangle)"
        : relationKind === "DEPENDENCY"
          ? "url(#uml-open-arrow)"
          : undefined;

  const markerStart = aggregationEnd === "SOURCE" ? diamondStart : undefined;

  return (
    <>
      <defs>
        <marker
          id="uml-hollow-triangle"
          viewBox="0 0 14 12"
          refX="13"
          refY="6"
          markerWidth="14"
          markerHeight="12"
          orient="auto"
        >
          <path d="M 1 1 L 13 6 L 1 11 Z" className="uml-marker-hollow" />
        </marker>

        <marker
          id="uml-open-arrow"
          viewBox="0 0 12 12"
          refX="11"
          refY="6"
          markerWidth="12"
          markerHeight="12"
          orient="auto"
        >
          <path d="M 1 1 L 11 6 L 1 11" className="uml-marker-open" />
        </marker>

        <marker
          id="uml-hollow-diamond"
          viewBox="0 0 16 12"
          refX="1"
          refY="6"
          markerWidth="16"
          markerHeight="12"
          orient="auto"
        >
          <path d="M 1 6 L 8 1 L 15 6 L 8 11 Z" className="uml-marker-hollow" />
        </marker>

        <marker
          id="uml-solid-diamond"
          viewBox="0 0 16 12"
          refX="1"
          refY="6"
          markerWidth="16"
          markerHeight="12"
          orient="auto"
        >
          <path d="M 1 6 L 8 1 L 15 6 L 8 11 Z" className="uml-marker-solid" />
        </marker>

        <marker
          id="uml-hollow-diamond-end"
          viewBox="0 0 16 12"
          refX="15"
          refY="6"
          markerWidth="16"
          markerHeight="12"
          orient="auto"
        >
          <path d="M 1 6 L 8 1 L 15 6 L 8 11 Z" className="uml-marker-hollow" />
        </marker>

        <marker
          id="uml-solid-diamond-end"
          viewBox="0 0 16 12"
          refX="15"
          refY="6"
          markerWidth="16"
          markerHeight="12"
          orient="auto"
        >
          <path d="M 1 6 L 8 1 L 15 6 L 8 11 Z" className="uml-marker-solid" />
        </marker>
      </defs>

      <BaseEdge
        id={props.id}
        path={path}
        markerStart={markerStart}
        markerEnd={markerEnd}
        style={{
          ...props.style,
          strokeDasharray: dashed ? "7 5" : undefined,
        }}
        interactionWidth={24}
      />

      <EdgeLabelRenderer>
        {(["source", "target"] as const).map((end) => {
          const x = end === "source" ? props.sourceX : props.targetX;
          const y = end === "source" ? props.sourceY : props.targetY;
          const position =
            end === "source" ? props.sourcePosition : props.targetPosition;
          const dx = position === "left" ? -30 : position === "right" ? 30 : 0;
          const dy =
            position === "top" ? -24 : position === "bottom" ? 24 : -18;

          const explicitMultiplicity =
            end === "source"
              ? props.data?.sourceMultiplicity
              : props.data?.targetMultiplicity;

          const cardinality =
            cards[end === "source" ? "sourceCardinality" : "targetCardinality"];

          return (
            <span
              key={end}
              className="relationship-cardinality nodrag nopan"
              style={{
                transform: `translate(-50%, -50%) translate(${x + dx}px, ${y + dy}px)`,
              }}
            >
              {typeof explicitMultiplicity === "string" &&
              explicitMultiplicity.trim()
                ? explicitMultiplicity
                : CARDINALITY_LABELS[cardinality]}
            </span>
          );
        })}

        {!joinTable &&
          typeof props.data?.name === "string" &&
          props.data.name.trim() && (
            <span
              className="relationship-cardinality nodrag nopan"
              style={{
                transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
              }}
            >
              {props.data.name}
            </span>
          )}
      </EdgeLabelRenderer>

      {joinTable && (
        <DerivedAssociationClassPreview
          props={props}
          labelX={labelX}
          labelY={labelY}
        />
      )}
    </>
  );
}
