import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
} from "react";

type NodeId = "usuario" | "proyecto" | "diagrama" | "ia";
type Position = { x: number; y: number };
type Connection = { source: NodeId; target: NodeId };
type Point = { x: number; y: number };

const INITIAL_POSITIONS: Record<NodeId, Position> = {
  usuario: { x: 2, y: 8 },
  proyecto: { x: 68, y: 8 },
  diagrama: { x: 68, y: 67 },
  ia: { x: 2, y: 67 },
};

const CONNECTIONS: Connection[] = [
  { source: "usuario", target: "proyecto" },
  { source: "proyecto", target: "diagrama" },
  { source: "ia", target: "diagrama" },
];

const NODE_IDS: NodeId[] = ["usuario", "proyecto", "diagrama", "ia"];

const NODE_DATA: Record<NodeId, { title: string; attributes: string[] }> = {
  usuario: {
    title: "Usuario",
    attributes: ["PK · id: UUID", "nombre: VARCHAR", "email: VARCHAR"],
  },
  proyecto: {
    title: "Proyecto",
    attributes: ["PK · id: UUID", "FK · usuario_id", "nombre: VARCHAR"],
  },
  diagrama: {
    title: "Diagrama",
    attributes: ["PK · id: UUID", "FK · proyecto_id", "contenido: JSON"],
  },
  ia: {
    title: "✦ LogicDraft AI",
    attributes: ["Asistente inteligente", "Generando diagramas..."],
  },
};

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

export function AuthDiagramPreview() {
  const canvasRef = useRef<HTMLDivElement>(null);

  const nodeRefs = useRef<Record<NodeId, HTMLDivElement | null>>({
    usuario: null,
    proyecto: null,
    diagrama: null,
    ia: null,
  });

  const dragRef = useRef<{
    id: NodeId;
    pointerId: number;
    offsetX: number;
    offsetY: number;
  } | null>(null);

  const [positions, setPositions] = useState(INITIAL_POSITIONS);

  const [canvasSize, setCanvasSize] = useState({
    width: 760,
    height: 540,
  });

  const [paths, setPaths] = useState<string[]>([]);

  const measureConnections = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const canvasRect = canvas.getBoundingClientRect();

    if (canvasRect.width === 0 || canvasRect.height === 0) {
      return;
    }

    setCanvasSize({
      width: canvasRect.width,
      height: canvasRect.height,
    });

    function getPoint(
      id: NodeId,
      side: "left" | "right" | "top" | "bottom",
    ): Point | null {
      const node = nodeRefs.current[id];
      if (!node) return null;

      const rect = node.getBoundingClientRect();

      const centerX = rect.left - canvasRect.left + rect.width / 2;

      const centerY = rect.top - canvasRect.top + rect.height / 2;

      switch (side) {
        case "left":
          return {
            x: rect.left - canvasRect.left,
            y: centerY,
          };

        case "right":
          return {
            x: rect.right - canvasRect.left,
            y: centerY,
          };

        case "top":
          return {
            x: centerX,
            y: rect.top - canvasRect.top,
          };

        case "bottom":
          return {
            x: centerX,
            y: rect.bottom - canvasRect.top,
          };
      }
    }

    const nextPaths = CONNECTIONS.map((connection) => {
      const vertical =
        connection.source === "proyecto" && connection.target === "diagrama";

      const start = getPoint(connection.source, vertical ? "bottom" : "right");

      const end = getPoint(connection.target, vertical ? "top" : "left");

      if (!start || !end) return "";

      if (vertical) {
        const middleY = (start.y + end.y) / 2;

        return [
          `M ${start.x} ${start.y}`,
          `C ${start.x} ${middleY},`,
          `${end.x} ${middleY},`,
          `${end.x} ${end.y}`,
        ].join(" ");
      }

      const middleX = (start.x + end.x) / 2;

      return [
        `M ${start.x} ${start.y}`,
        `C ${middleX} ${start.y},`,
        `${middleX} ${end.y},`,
        `${end.x} ${end.y}`,
      ].join(" ");
    });

    setPaths(nextPaths);
  }, []);

  // Recalcular conexiones después de mover los nodos.
  useLayoutEffect(() => {
    measureConnections();
  }, [positions, measureConnections]);

  // Ajustar conexiones al cambiar el tamaño de la ventana.
  useEffect(() => {
    const observer = new ResizeObserver(measureConnections);

    if (canvasRef.current) {
      observer.observe(canvasRef.current);
    }

    for (const id of NODE_IDS) {
      const node = nodeRefs.current[id];

      if (node) observer.observe(node);
    }

    return () => observer.disconnect();
  }, [measureConnections]);

  function handlePointerDown(event: PointerEvent<HTMLDivElement>, id: NodeId) {
    if (event.button !== 0 && event.pointerType === "mouse") {
      return;
    }

    const nodeRect = event.currentTarget.getBoundingClientRect();

    dragRef.current = {
      id,
      pointerId: event.pointerId,
      offsetX: event.clientX - nodeRect.left,
      offsetY: event.clientY - nodeRect.top,
    };

    event.currentTarget.setPointerCapture(event.pointerId);
  }

  function handlePointerMove(event: PointerEvent<HTMLDivElement>) {
    const drag = dragRef.current;
    const canvas = canvasRef.current;

    if (!drag || !canvas || drag.pointerId !== event.pointerId) {
      return;
    }

    const canvasRect = canvas.getBoundingClientRect();
    const nodeRect = event.currentTarget.getBoundingClientRect();

    if (canvasRect.width <= 0 || canvasRect.height <= 0) return;

    const left = event.clientX - canvasRect.left - drag.offsetX;

    const top = event.clientY - canvasRect.top - drag.offsetY;

    const x = clamp(left, 0, Math.max(0, canvasRect.width - nodeRect.width));

    const y = clamp(top, 0, Math.max(0, canvasRect.height - nodeRect.height));

    setPositions((current) => ({
      ...current,
      [drag.id]: {
        x: (x / canvasRect.width) * 100,
        y: (y / canvasRect.height) * 100,
      },
    }));
  }

  function handlePointerEnd(event: PointerEvent<HTMLDivElement>) {
    if (dragRef.current?.pointerId !== event.pointerId) {
      return;
    }

    dragRef.current = null;

    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>, id: NodeId) {
    const movement: Record<string, Position> = {
      ArrowLeft: { x: -2, y: 0 },
      ArrowRight: { x: 2, y: 0 },
      ArrowUp: { x: 0, y: -2 },
      ArrowDown: { x: 0, y: 2 },
    };

    const delta = movement[event.key];

    if (!delta) return;

    event.preventDefault();

    const canvas = canvasRef.current;
    const node = nodeRefs.current[id];

    if (!canvas || !node) return;

    const canvasRect = canvas.getBoundingClientRect();
    const nodeRect = node.getBoundingClientRect();

    const maxX = 100 * Math.max(0, 1 - nodeRect.width / canvasRect.width);

    const maxY = 100 * Math.max(0, 1 - nodeRect.height / canvasRect.height);

    setPositions((current) => ({
      ...current,
      [id]: {
        x: clamp(current[id].x + delta.x, 0, maxX),
        y: clamp(current[id].y + delta.y, 0, maxY),
      },
    }));
  }

  return (
    <div className="ld-auth-canvas" ref={canvasRef}>
      {/* Las conexiones se recalculan según la posición real */}
      <svg
        className="ld-auth-wire"
        viewBox={`0 0 ${canvasSize.width} ${canvasSize.height}`}
        preserveAspectRatio="none"
        aria-hidden="true"
      >
        {paths.map((path, index) => (
          <path key={index} d={path} />
        ))}
      </svg>

      {NODE_IDS.map((id) => {
        const node = NODE_DATA[id];
        const position = positions[id];

        return (
          <div
            key={id}
            ref={(element) => {
              nodeRefs.current[id] = element;
            }}
            className={`ld-auth-node ld-auth-node-${id}`}
            role="button"
            tabIndex={0}
            aria-label={`${node.title}. Arrastra para mover. También puedes utilizar las flechas del teclado.`}
            onPointerDown={(event) => handlePointerDown(event, id)}
            onPointerMove={handlePointerMove}
            onPointerUp={handlePointerEnd}
            onPointerCancel={handlePointerEnd}
            onKeyDown={(event) => handleKeyDown(event, id)}
            style={{
              left: `${position.x}%`,
              top: `${position.y}%`,
              right: "auto",
              bottom: "auto",
              touchAction: "none",
              userSelect: "none",
              cursor: "grab",
            }}
          >
            <strong>{node.title}</strong>

            {node.attributes.map((attribute) => (
              <span key={attribute}>{attribute}</span>
            ))}
          </div>
        );
      })}
    </div>
  );
}
