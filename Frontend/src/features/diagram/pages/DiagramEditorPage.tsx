import { useCallback, useEffect, useRef, useState } from "react";
import axios from "axios";
import {
  Background,
  BackgroundVariant,
  ConnectionMode,
  Controls,
  MarkerType,
  ReactFlow,
  ReactFlowProvider,
  useEdgesState,
  useNodesState,
  useReactFlow,
  type Connection,
  type EdgeChange,
  type NodeChange,
  type OnSelectionChangeParams,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { useNavigate, useParams } from "react-router-dom";
import {
  isForbiddenError,
  isNotFoundError,
  isUnauthorizedError,
} from "../../../shared/api/apiError";
import {
  askProjectAgent,
  authorizeProjectAgentEdit,
} from "../../agent/api/agentApi";
import { AgentPanel } from "../../agent/components/AgentPanel";
import { AgentSession } from "../../agent/services/agentSession";
import {
  prepareAgentResponse,
  proposalAcceptanceError,
} from "../../agent/services/agentProposal";
import type { AgentConversationMessage } from "../../agent/types/agent.types";
import { ProjectPresence } from "../../collaboration/components/ProjectPresence";
import { useProjectCollaboration } from "../../collaboration/hooks/useProjectCollaboration";
import type { CollaborationEvent } from "../../collaboration/types/collaboration.types";
import { obtenerProyecto } from "../../project/api/projectApi";
import type { Proyecto } from "../../project/types/project.types";
import {
  guardarDiagrama,
  obtenerDiagrama,
  descargarEnterpriseArchitectXmi,
  previewEnterpriseArchitectImport,
} from "../api/diagramApi";
import {
  descargarProyectoGenerado,
  type GeneratorExport,
} from "../api/generatorApi";
import { interpretDiagramWithAi } from "../api/diagramAiApi";
import {
  applyDiagramOperations,
  DiagramOperationError,
  prepareDiagramAiProposal,
} from "../services/applyDiagramOperations";
import { AiPromptBar } from "../components/AiPromptBar";
import { DiagramAiProposalDialog } from "../components/DiagramAiProposalDialog";
import { AssociationConversionDialog } from "../components/AssociationConversionDialog";
import { DiagramImportDialog } from "../components/DiagramImportDialog";
import { DiagramPropertiesPanel } from "../components/DiagramPropertiesPanel";
import { DiagramSidebar } from "../components/DiagramSidebar";
import {
  DiagramToolbar,
  type ExportStatus,
  type SaveStatus,
} from "../components/DiagramToolbar";
import { EntityNode } from "../components/EntityNode";
import { RelationshipEdge } from "../components/RelationshipEdge";
import {
  cloneAssociationMetadata,
  parseAssociationMetadata,
} from "../services/associationMetadata";
import {
  AssociationConversionError,
  associationProtectedNodeIds,
  convertManyToManyAssociation,
  structuralRelationshipIds,
  validateAssociationDocument,
} from "../services/associationConversion";
import { deriveJoinTable, postgresType } from "../services/derivedJoinTable";
import {
  commitRelationshipEdge,
  normalizeRelationshipEdge,
  removeRelationshipEdges,
} from "../services/relationshipCardinality";
import type {
  AttributeType,
  DiagramDocument,
  DiagramEdge,
  EntityAttribute,
  EntityFlowNode,
  ExternalMetadata,
  UmlNodeKind,
  UmlMethod,
  UmlRelationKind,
} from "../types/diagram.types";
import { UML_NODE_KINDS, UML_VISIBILITIES } from "../types/diagram.types";
import type { DiagramAiOperation } from "../types/diagramAi.types";
import "../diagram.css";
import { interpretDiagramImage } from "../api/diagramImageApi";
import { destructivePlanImpact } from "../services/diagramActionPresentation";
import { validateDiagramDocument } from "../services/diagramDocumentValidation";
import {
  applyDiagramImport,
  computeDiagramImportDiff,
  defaultImportSelection,
  DiagramImportError,
  type DiagramImportDiff,
  type EntityImportChange,
} from "../services/diagramImportDiff";

const EMPTY_DOCUMENT: DiagramDocument = { version: 1, nodes: [], edges: [] };
const nodeTypes = { entity: EntityNode };
const edgeTypes = { relationship: RelationshipEdge };

function serializeNode(node: EntityFlowNode): EntityFlowNode {
  return {
    id: node.id,
    type: "entity",
    position: { x: node.position.x, y: node.position.y },
    data: {
      id: node.data.id,
      name: node.data.name,
      attributes: node.data.attributes.map((attribute) => ({ ...attribute })),
      ...(node.data.kind ? { kind: node.data.kind } : {}),
      ...(node.data.kind === "CLASS" && node.data.generateCrud === true
        ? { generateCrud: true }
        : {}),
      ...(node.data.methods
        ? {
            methods: node.data.methods.map((method) => ({
              ...method,
              parameters: method.parameters.map((parameter) => ({
                ...parameter,
              })),
            })),
          }
        : {}),
      ...(node.data.literals ? { literals: [...node.data.literals] } : {}),
      ...(node.data.abstract !== undefined
        ? { abstract: node.data.abstract }
        : {}),
      ...(node.data.width !== undefined ? { width: node.data.width } : {}),
      ...(node.data.height !== undefined ? { height: node.data.height } : {}),
      ...(node.data.association
        ? { association: cloneAssociationMetadata(node.data.association) }
        : {}),
      ...(node.data.externalMetadata
        ? { externalMetadata: { ...node.data.externalMetadata } }
        : {}),
    },
  };
}

function serializeEdge(edge: DiagramEdge): DiagramEdge {
  edge = normalizeRelationshipEdge(edge);
  return {
    id: edge.id,
    source: edge.source,
    target: edge.target,
    ...(edge.sourceHandle ? { sourceHandle: edge.sourceHandle } : {}),
    ...(edge.targetHandle ? { targetHandle: edge.targetHandle } : {}),
    ...(edge.type ? { type: edge.type } : {}),
    ...(edge.markerEnd ? { markerEnd: edge.markerEnd } : {}),
    ...(edge.data ? { data: { ...edge.data } } : {}),
  };
}

function serializeDocument(
  nodes: EntityFlowNode[],
  edges: DiagramEdge[],
): DiagramDocument {
  const version =
    nodes.some(
      (node) =>
        (node.data.kind ?? "ENTITY") !== "ENTITY" ||
        node.data.methods?.length ||
        node.data.abstract,
    ) || edges.some((edge) => edge.data?.relationKind)
      ? 2
      : 1;
  return {
    version,
    nodes: nodes.map(serializeNode),
    edges: edges.map(serializeEdge),
  };
}

function normalizeStoredNode(node: EntityFlowNode): EntityFlowNode {
  if (node.data.association === undefined) return node;
  const association = parseAssociationMetadata(node.data.association);
  if (!association)
    throw new Error("Metadatos de entidad asociativa inválidos");
  return { ...node, data: { ...node.data, association } };
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null
    ? (value as Record<string, unknown>)
    : null;
}

async function responseMessage(error: unknown): Promise<string | null> {
  if (!axios.isAxiosError(error)) return null;
  const payload = error.response?.data;
  if (payload instanceof Blob) {
    try {
      const parsed = JSON.parse(await payload.text());
      return typeof parsed?.mensaje === "string" ? parsed.mensaje : null;
    } catch {
      return null;
    }
  }
  const body = record(payload);
  return typeof body?.mensaje === "string" ? body.mensaje : null;
}

function externalMetadata(value: unknown): ExternalMetadata | undefined {
  const metadata = record(value);
  return metadata?.source === "enterprise-architect" &&
    typeof metadata.externalId === "string" &&
    metadata.externalId.length > 0
    ? { source: "enterprise-architect", externalId: metadata.externalId }
    : undefined;
}

function remoteNode(payload: unknown): EntityFlowNode | null {
  const wrapper = record(payload);
  const node = record(wrapper?.node);
  const position = record(node?.position);
  const data = record(node?.data);
  if (
    !node ||
    !position ||
    !data ||
    typeof node.id !== "string" ||
    typeof position.x !== "number" ||
    typeof position.y !== "number" ||
    typeof data.id !== "string" ||
    typeof data.name !== "string" ||
    !Array.isArray(data.attributes)
  )
    return null;
  const attributes: EntityAttribute[] = [];
  for (const item of data.attributes) {
    const attribute = record(item);
    if (
      !attribute ||
      typeof attribute.id !== "string" ||
      typeof attribute.name !== "string" ||
      typeof attribute.type !== "string" ||
      typeof attribute.primaryKey !== "boolean"
    )
      return null;
    const attributeExternalMetadata = externalMetadata(
      attribute.externalMetadata,
    );
    attributes.push({
      id: attribute.id,
      name: attribute.name,
      type: attribute.type as AttributeType,
      primaryKey: attribute.primaryKey,
      ...(typeof attribute.nullable === "boolean"
        ? { nullable: attribute.nullable }
        : {}),
      ...(typeof attribute.visibility === "string" &&
      UML_VISIBILITIES.includes(
        attribute.visibility as (typeof UML_VISIBILITIES)[number],
      )
        ? {
            visibility:
              attribute.visibility as (typeof UML_VISIBILITIES)[number],
          }
        : {}),
      ...(typeof attribute.static === "boolean"
        ? { static: attribute.static }
        : {}),
      ...(attributeExternalMetadata
        ? { externalMetadata: attributeExternalMetadata }
        : {}),
    });
  }
  const association =
    data.association === undefined
      ? undefined
      : parseAssociationMetadata(data.association);
  if (data.association !== undefined && !association) return null;
  const nodeExternalMetadata = externalMetadata(data.externalMetadata);
  const kind =
    typeof data.kind === "string" &&
    UML_NODE_KINDS.includes(data.kind as UmlNodeKind)
      ? (data.kind as UmlNodeKind)
      : undefined;
  const methods: UmlMethod[] = [];
  if (data.methods !== undefined) {
    if (!Array.isArray(data.methods)) return null;
    for (const rawMethod of data.methods) {
      const method = record(rawMethod);
      if (
        !method ||
        typeof method.id !== "string" ||
        typeof method.name !== "string" ||
        typeof method.returnType !== "string" ||
        typeof method.visibility !== "string" ||
        !UML_VISIBILITIES.includes(
          method.visibility as UmlMethod["visibility"],
        ) ||
        !Array.isArray(method.parameters)
      )
        return null;
      const parameters = method.parameters
        .map((raw) => record(raw))
        .map((parameter) =>
          parameter &&
          typeof parameter.id === "string" &&
          typeof parameter.name === "string" &&
          typeof parameter.type === "string"
            ? { id: parameter.id, name: parameter.name, type: parameter.type }
            : null,
        );
      if (parameters.some((parameter) => parameter === null)) return null;
      methods.push({
        id: method.id,
        name: method.name,
        returnType: method.returnType,
        visibility: method.visibility as UmlMethod["visibility"],
        parameters: parameters as UmlMethod["parameters"],
        ...(typeof method.static === "boolean"
          ? { static: method.static }
          : {}),
        ...(typeof method.abstract === "boolean"
          ? { abstract: method.abstract }
          : {}),
      });
    }
  }
  return {
    id: node.id,
    type: "entity",
    position: { x: position.x, y: position.y },
    data: {
      id: data.id,
      name: data.name,
      attributes,
      ...(kind ? { kind } : {}),
      ...(kind === "CLASS" && data.generateCrud === true
        ? { generateCrud: true }
        : {}),
      ...(data.methods !== undefined ? { methods } : {}),
      ...(Array.isArray(data.literals) &&
      data.literals.every((item) => typeof item === "string")
        ? { literals: data.literals as string[] }
        : {}),
      ...(typeof data.abstract === "boolean"
        ? { abstract: data.abstract }
        : {}),
      ...(typeof data.width === "number" ? { width: data.width } : {}),
      ...(typeof data.height === "number" ? { height: data.height } : {}),
      ...(association ? { association } : {}),
      ...(nodeExternalMetadata
        ? { externalMetadata: nodeExternalMetadata }
        : {}),
    },
  };
}

function remoteEdge(payload: unknown): DiagramEdge | null {
  const wrapper = record(payload);
  const edge = record(wrapper?.edge);
  if (
    !edge ||
    typeof edge.id !== "string" ||
    typeof edge.source !== "string" ||
    typeof edge.target !== "string"
  )
    return null;
  return {
    id: edge.id,
    source: edge.source,
    target: edge.target,
    ...(typeof edge.sourceHandle === "string"
      ? { sourceHandle: edge.sourceHandle }
      : {}),
    ...(typeof edge.targetHandle === "string"
      ? { targetHandle: edge.targetHandle }
      : {}),
    ...(typeof edge.type === "string" ? { type: edge.type } : {}),
    markerEnd: { type: MarkerType.ArrowClosed },
    ...(record(edge.data) ? { data: { ...record(edge.data)! } } : {}),
  };
}

function remoteDocument(payload: unknown): DiagramDocument | null {
  const wrapper = record(payload);
  const document = record(wrapper?.document);
  if (
    (document?.version !== 1 && document?.version !== 2) ||
    !Array.isArray(document.nodes) ||
    !Array.isArray(document.edges)
  )
    return null;
  const nodes = document.nodes.map((node) => remoteNode({ node }));
  const edges = document.edges.map((edge) => remoteEdge({ edge }));
  if (
    nodes.some((node) => node === null) ||
    edges.some((edge) => edge === null)
  )
    return null;
  const result: DiagramDocument = {
    version: document.version,
    nodes: nodes as EntityFlowNode[],
    edges: edges as DiagramEdge[],
  };
  try {
    validateAssociationDocument(result.nodes, result.edges);
  } catch {
    return null;
  }
  return result;
}

function DiagramEditorCanvas({
  project,
  initialDocument,
  initialDiagramId,
  initialServerRevision,
}: {
  project: Proyecto;
  initialDocument: DiagramDocument;
  initialDiagramId: number | null;
  initialServerRevision: number | null;
}) {
  const [nodes, setNodes, onNodesChange] = useNodesState<EntityFlowNode>(
    initialDocument.nodes,
  );
  const [edges, setEdges, onEdgesChange] = useEdgesState<DiagramEdge>(
    initialDocument.edges.map(normalizeRelationshipEdge),
  );
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedEdgeIds, setSelectedEdgeIds] = useState<string[]>([]);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>("clean");
  const [documentRevision, setDocumentRevision] = useState(0);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiError, setAiError] = useState("");
  const [aiMessage, setAiMessage] = useState("");
  const [exportStatus, setExportStatus] = useState<ExportStatus>(null);
  const [exportMessage, setExportMessage] = useState("");
  const [conversionOpen, setConversionOpen] = useState(false);
  const [conversionError, setConversionError] = useState("");
  const [importLoading, setImportLoading] = useState(false);
  const [importError, setImportError] = useState("");
  const [pendingImport, setPendingImport] = useState<{
    document: DiagramDocument;
    diff: DiagramImportDiff;
    selected: Set<string>;
    revision: number;
  } | null>(null);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [propertiesOpen, setPropertiesOpen] = useState(false);
  const [selectedRelationKind, setSelectedRelationKind] =
    useState<UmlRelationKind>("ASSOCIATION");
  const [pendingProposal, setPendingProposal] = useState<{
    operations: DiagramAiOperation[];
    impact: string[];
    revision: number;
    source: "editor" | "agent";
  } | null>(null);
  const [agentSession] = useState(
    () => new AgentSession(project.id, initialDiagramId),
  );
  const aiRequest = useRef<AbortController | null>(null);
  const importInputRef = useRef<HTMLInputElement | null>(null);
  const nextEntityNumber = useRef(1);
  const nextAttributeNumber = useRef(1);
  const nodesRef = useRef(nodes);
  const edgesRef = useRef(edges);
  const revisionRef = useRef(0);
  const persistedRevisionRef = useRef(0);
  const serverRevisionRef = useRef<number | null>(initialServerRevision);
  const conflictRef = useRef(false);
  const saveInFlightRef = useRef<Promise<boolean> | null>(null);
  const rejectedDocumentRef = useRef<string | null>(null);
  const { fitView } = useReactFlow();
  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null;
  const selectedEdge =
    !selectedNode && selectedEdgeIds.length === 1
      ? (edges.find((edge) => edge.id === selectedEdgeIds[0]) ?? null)
      : null;
  const selectedJoinTable = selectedEdge
    ? deriveJoinTable(
        selectedEdge,
        nodes.find((node) => node.id === selectedEdge.source)?.data,
        nodes.find((node) => node.id === selectedEdge.target)?.data,
      )
    : null;
  const protectedEdgeIds = structuralRelationshipIds(nodes);
  const protectedNodeIds = associationProtectedNodeIds(nodes);
  const protectedSelection =
    nodes.some((node) => node.selected && protectedNodeIds.has(node.id)) ||
    edges.some((edge) => edge.selected && protectedEdgeIds.has(edge.id));

  useEffect(() => {
    const closePanels = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setSidebarOpen(false);
      setPropertiesOpen(false);
    };
    window.addEventListener("keydown", closePanels);
    return () => window.removeEventListener("keydown", closePanels);
  }, []);
  const selectedAssociationColumns =
    selectedNode?.data.association?.endpoints.map((endpoint) => {
      const referenced = nodes.find((node) => node.id === endpoint.entityId);
      const primaryKey = referenced?.data.attributes.find(
        (attribute) => attribute.primaryKey,
      );
      return {
        name: endpoint.foreignKeyName,
        type: primaryKey ? postgresType(primaryKey.type) : "PK no definida",
      };
    }) ?? [];
  nodesRef.current = nodes;
  edgesRef.current = edges;

  const markDirty = useCallback(() => {
    revisionRef.current += 1;
    setDocumentRevision(revisionRef.current);
    setSaveStatus(conflictRef.current ? "conflict" : "dirty");
  }, []);

  // Transient events are applied in arrival order; confirmed saves are protected by server revisions.
  // Direct state updates here never call publishEvent, preventing echo loops.
  const applyRemoteEvent = useCallback(
    (event: CollaborationEvent) => {
      const payload = record(event.payload);
      if (event.type === "DIAGRAM_BATCH_APPLIED") {
        const document = remoteDocument(event.payload);
        if (!document) return;
        nodesRef.current = document.nodes;
        edgesRef.current = document.edges;
        setNodes(document.nodes);
        setEdges(document.edges);
        setSelectedNodeId(null);
        setSelectedEdgeIds([]);
        agentSession.record("DIAGRAM_SAVED");
      } else if (event.type === "NODE_CREATED") {
        const node = remoteNode(event.payload);
        if (node) {
          setNodes((current) =>
            current.some((item) => item.id === node.id)
              ? current
              : [...current, node],
          );
          agentSession.record("NODE_CREATED", { nodeId: node.id });
        }
      } else if (event.type === "NODE_MOVED") {
        const position = record(payload?.position);
        if (
          typeof payload?.nodeId === "string" &&
          typeof position?.x === "number" &&
          typeof position.y === "number"
        ) {
          setNodes((current) =>
            current.map((node) =>
              node.id === payload.nodeId
                ? {
                    ...node,
                    position: {
                      x: position.x as number,
                      y: position.y as number,
                    },
                  }
                : node,
            ),
          );
        }
      } else if (event.type === "NODE_UPDATED") {
        const node = remoteNode(event.payload);
        if (node) {
          setNodes((current) =>
            current.map((item) =>
              item.id === node.id ? { ...node, selected: item.selected } : item,
            ),
          );
          agentSession.record("NODE_UPDATED", { nodeId: node.id });
        }
      } else if (
        event.type === "NODE_DELETED" &&
        typeof payload?.nodeId === "string"
      ) {
        setNodes((current) =>
          current.filter((node) => node.id !== payload.nodeId),
        );
        setEdges((current) =>
          current.filter(
            (edge) =>
              edge.source !== payload.nodeId && edge.target !== payload.nodeId,
          ),
        );
        setSelectedNodeId((current) =>
          current === payload.nodeId ? null : current,
        );
        agentSession.record("NODE_DELETED", { nodeId: payload.nodeId });
      } else if (
        event.type === "EDGE_CREATED" ||
        event.type === "EDGE_UPDATED"
      ) {
        const edge = remoteEdge(event.payload);
        if (edge) {
          try {
            const changed = commitRelationshipEdge(
              edgesRef.current,
              edge,
              event.type,
              true,
              {
                setEdges: (next) => {
                  edgesRef.current = next;
                  setEdges(next);
                },
                markDirty,
                publish: () => {},
              },
            );
            if (changed) agentSession.record(event.type, { edgeId: edge.id });
          } catch {
            /* Invalid remote cardinalities must not enter local state. */
          }
        }
        return;
      } else if (
        event.type === "EDGE_DELETED" &&
        typeof payload?.edgeId === "string"
      ) {
        const edgeId = payload.edgeId;
        setEdges((current) =>
          removeRelationshipEdges(current, new Set([edgeId])),
        );
        setSelectedEdgeIds((current) => current.filter((id) => id !== edgeId));
        agentSession.record("EDGE_DELETED", { edgeId });
      } else if (event.type === "DIAGRAM_SAVED") {
        agentSession.record("DIAGRAM_SAVED");
      }
      if (event.type !== "DIAGRAM_SAVED") markDirty();
    },
    [agentSession, markDirty, setEdges, setNodes],
  );

  const { status, collaborators, publishEvent } = useProjectCollaboration(
    project.id,
    applyRemoteEvent,
  );

  function commitDiagramOperations(operations: DiagramAiOperation[]) {
    const previousNodeCount = nodesRef.current.length;
    const result = applyDiagramOperations(
      nodesRef.current,
      edgesRef.current,
      operations,
    );
    if (!result.events.length) return false;
    nodesRef.current = result.nodes;
    edgesRef.current = result.edges;
    setNodes(result.nodes);
    setEdges(result.edges);
    markDirty();
    const event = result.events[0];
    publishEvent(event.type, {
      document: serializeDocument(event.document.nodes, event.document.edges),
    });
    if (result.nodes.length > previousNodeCount) {
      window.requestAnimationFrame(() => {
        void fitView({ padding: 0.2, duration: 350 });
      });
    }
    return true;
  }

  async function acceptAiProposal() {
    const proposal = pendingProposal;
    if (!proposal) return;
    setPendingProposal(null);
    const authorizationError = proposalAcceptanceError(
      proposal.revision,
      revisionRef.current,
      saveStatus !== "forbidden",
    );
    if (authorizationError) {
      setAiError(authorizationError);
      return;
    }
    try {
      await authorizeProjectAgentEdit(project.id);
      const refreshedAuthorizationError = proposalAcceptanceError(
        proposal.revision,
        revisionRef.current,
        saveStatus !== "forbidden",
      );
      if (refreshedAuthorizationError) {
        setAiError(refreshedAuthorizationError);
        return;
      }
      const changed = commitDiagramOperations(proposal.operations);
      setAiMessage(
        changed
          ? "Cambios aplicados"
          : "No se encontraron cambios para aplicar",
      );
    } catch (error: unknown) {
      if (isUnauthorizedError(error)) {
        localStorage.removeItem("token");
        window.location.assign("/login");
      } else if (isForbiddenError(error))
        setAiError("No tienes permisos para modificar este proyecto.");
      else
        setAiError(
          error instanceof DiagramOperationError ||
            error instanceof AssociationConversionError
            ? error.message
            : "La propuesta ya no es valida para el diagrama actual.",
        );
    }
  }

  useEffect(
    () => () => {
      aiRequest.current?.abort();
    },
    [],
  );

  async function handleAiPrompt(prompt: string, image?: File) {
    if (aiRequest.current) return;
    if (
      revisionRef.current !== persistedRevisionRef.current ||
      saveInFlightRef.current ||
      nodesRef.current.some((n) => n.dragging)
    ) {
      setAiError(
        "Espera a que termine el guardado del diagrama antes de usar IA.",
      );
      return;
    }
    const request = new AbortController();
    aiRequest.current = request;
    setAiLoading(true);
    setAiError("");
    setAiMessage("");
    agentSession.record("AI_REQUESTED");
    try {
      const selectedSource = selectedEdge
        ? nodesRef.current.find((node) => node.id === selectedEdge.source)
        : null;
      const selectedTarget = selectedEdge
        ? nodesRef.current.find((node) => node.id === selectedEdge.target)
        : null;
      const selection = selectedNode
        ? { kind: "ENTITY" as const, entityName: selectedNode.data.name }
        : selectedEdge && selectedSource && selectedTarget
          ? {
              kind: "RELATIONSHIP" as const,
              sourceEntity: selectedSource.data.name,
              targetEntity: selectedTarget.data.name,
              ...(typeof selectedEdge.data?.name === "string"
                ? { relationshipName: selectedEdge.data.name }
                : {}),
            }
          : undefined;
      const response = image
        ? await interpretDiagramImage(project.id, image, prompt, request.signal)
        : await interpretDiagramWithAi(
            project.id,
            prompt,
            selection,
            request.signal,
          );
      if (request.signal.aborted) return;
      const { operations, preview: result } = prepareDiagramAiProposal(
        response,
        nodesRef.current,
        edgesRef.current,
      );
      if (import.meta.env.DEV)
        console.debug("[Diagram AI]", {
          stage: "react_received",
          count: operations.length,
          types: operations.map((operation) => operation.type),
        });
      // Validate a preview against the latest local state. It remains non-mutating until explicit acceptance.
      if (import.meta.env.DEV)
        console.debug("[Diagram AI]", {
          stage: "react_applied",
          count: result.events.length,
          types: result.events.map((event) => event.type),
        });
      if (!result.events.length) {
        setAiMessage("No se encontraron cambios para aplicar");
        return;
      }
      setPendingProposal({
        operations,
        impact: destructivePlanImpact(
          operations,
          nodesRef.current,
          edgesRef.current,
        ),
        revision: revisionRef.current,
        source: "editor",
      });
      setAiMessage("Propuesta lista para revisar");
    } catch (error: unknown) {
      if (request.signal.aborted) return;
      if (isUnauthorizedError(error)) {
        localStorage.removeItem("token");
        window.location.assign("/login");
      } else if (isForbiddenError(error))
        setAiError("No tienes permisos para modificar este proyecto");
      else if (
        error instanceof DiagramOperationError ||
        error instanceof AssociationConversionError
      )
        setAiError(error.message);
      else if (axios.isAxiosError(error)) {
        const code = error.response?.status;
        const data = record(error.response?.data);
        if (code === 409)
          setAiError(
            typeof data?.mensaje === "string"
              ? data.mensaje
              : "El diagrama cambió o la operación entra en conflicto.",
          );
        else if (code === 422)
          setAiError(
            typeof data?.mensaje === "string"
              ? data.mensaje
              : "La IA devolvió un lote incompleto; no se aplicaron cambios.",
          );
        else if (code === 502)
          setAiError("No se pudo interpretar la instrucción.");
        else if (
          code === 503 ||
          code === 504 ||
          error.code === "ECONNABORTED"
        ) {
          setAiError(
            typeof data?.mensaje === "string"
              ? data.mensaje
              : "El servicio IA no pudo completar la solicitud. Inténtalo de nuevo.",
          );
        } else setAiError("No se pudo interpretar el cambio solicitado.");
      } else setAiError("No se pudo interpretar el cambio solicitado.");
    } finally {
      aiRequest.current = null;
      if (!request.signal.aborted) setAiLoading(false);
    }
  }

  const handleNodesChange = useCallback(
    (changes: NodeChange<EntityFlowNode>[]) => {
      const protectedIds = associationProtectedNodeIds(nodesRef.current);
      const accepted = changes.filter(
        (change) => change.type !== "remove" || !protectedIds.has(change.id),
      );
      onNodesChange(accepted);
      if (
        accepted.some(
          (change) =>
            change.type !== "select" &&
            change.type !== "dimensions" &&
            change.type !== "position",
        )
      )
        markDirty();
    },
    [markDirty, onNodesChange],
  );

  const handleEdgesChange = useCallback(
    (changes: EdgeChange<DiagramEdge>[]) => {
      const structuralIds = structuralRelationshipIds(nodesRef.current);
      const accepted = changes.filter(
        (change) => change.type !== "remove" || !structuralIds.has(change.id),
      );
      onEdgesChange(accepted);
      if (accepted.some((change) => change.type !== "select")) markDirty();
    },
    [markDirty, onEdgesChange],
  );

  const onConnect = useCallback(
    (connection: Connection) => {
      const edge: DiagramEdge = {
        ...connection,
        id: crypto.randomUUID(),
        data: {
          sourceCardinality: "ONE_ONE",
          targetCardinality: "ONE_ONE",
          relationKind: selectedRelationKind,
        },
      };
      try {
        validateDiagramDocument(
          serializeDocument(nodesRef.current, [...edgesRef.current, edge]),
        );
      } catch (error) {
        setAiError(
          error instanceof Error
            ? error.message
            : "La relación UML no es válida.",
        );
        return;
      }
      commitRelationshipEdge(edgesRef.current, edge, "EDGE_CREATED", false, {
        setEdges: (next) => {
          edgesRef.current = next;
          setEdges(next);
        },
        markDirty,
        publish: (kind, updated) => {
          publishEvent(kind, { edge: serializeEdge(updated) });
          agentSession.record(kind, { edgeId: updated.id });
        },
      });
    },
    [agentSession, markDirty, publishEvent, selectedRelationKind, setEdges],
  );

  const handleSelectionChange = useCallback(
    ({
      nodes: selectedNodes,
      edges: selectedEdges,
    }: OnSelectionChangeParams) => {
      setSelectedNodeId(selectedNodes[0]?.id ?? null);
      setSelectedEdgeIds(selectedEdges.map((edge) => edge.id));
      if (selectedNodes[0])
        agentSession.record("NODE_SELECTED", { nodeId: selectedNodes[0].id });
    },
    [agentSession],
  );

  // New nodes must be valid drafts immediately: autosave runs before the user renames them.
  function uniqueDefaultName(
    base: string,
    existing: readonly string[],
  ): string {
    const used = new Set(
      existing.map((value) => value.trim().toLocaleLowerCase()),
    );
    if (!used.has(base.toLocaleLowerCase())) return base;
    let suffix = 2;
    while (used.has(`${base} ${suffix}`.toLocaleLowerCase())) suffix++;
    return `${base} ${suffix}`;
  }

  function addUmlNode(kind: UmlNodeKind = "ENTITY") {
    const number = nextEntityNumber.current++;
    const id = `entity-${crypto.randomUUID()}`;
    const baseName =
      kind === "ENTITY"
        ? "Nueva entidad"
        : kind === "CLASS"
          ? "Nueva clase"
          : kind === "INTERFACE"
            ? "Nueva interfaz"
            : "Nueva enumeración";
    const name = uniqueDefaultName(
      baseName,
      nodesRef.current.map((node) => node.data.name),
    );
    const node: EntityFlowNode = {
      id,
      type: "entity",
      position: { x: 180 + number * 35, y: 180 + number * 35 },
      data: {
        id,
        name,
        attributes: [],
        ...(kind !== "ENTITY"
          ? {
              kind,
              methods: [],
              ...(kind === "ENUMERATION" ? { literals: [] } : {}),
            }
          : {}),
      },
      selected: true,
    };
    setNodes((current) => [
      ...current.map((item) => ({ ...item, selected: false })),
      node,
    ]);
    setSelectedNodeId(id);
    setSelectedEdgeIds([]);
    publishEvent("NODE_CREATED", { node: serializeNode(node) });
    agentSession.record("NODE_CREATED", { nodeId: id });
    markDirty();
  }

  function addMethod() {
    const method: UmlMethod = {
      id: `method-${crypto.randomUUID()}`,
      name: uniqueDefaultName(
        "nuevoMetodo",
        selectedNode?.data.methods?.map((method) => method.name) ?? [],
      ),
      visibility: "public",
      returnType: "void",
      parameters: [],
    };
    updateSelectedEntity((node) => ({
      ...node,
      data: {
        ...node.data,
        kind: node.data.kind ?? "CLASS",
        methods: [...(node.data.methods ?? []), method],
      },
    }));
  }

  function updateSelectedEntity(
    update: (node: EntityFlowNode) => EntityFlowNode,
  ) {
    if (!selectedNode) return;
    const updated = update(selectedNode);
    const candidate = nodesRef.current.map((node) =>
      node.id === updated.id ? updated : node,
    );
    try {
      validateAssociationDocument(candidate, edgesRef.current);
    } catch (error: unknown) {
      setAiMessage(
        error instanceof Error
          ? error.message
          : "El cambio invalida la entidad asociativa.",
      );
      return;
    }
    nodesRef.current = candidate;
    setNodes(candidate);
    publishEvent("NODE_UPDATED", { node: serializeNode(updated) });
    agentSession.record("NODE_UPDATED", { nodeId: updated.id });
    markDirty();
  }

  function changeAttribute(
    attributeId: string,
    changes: Partial<Omit<EntityAttribute, "id">>,
  ) {
    updateSelectedEntity((node) => ({
      ...node,
      data: {
        ...node.data,
        attributes: node.data.attributes.map((attribute) =>
          attribute.id === attributeId
            ? { ...attribute, ...changes }
            : attribute,
        ),
      },
    }));
  }

  function deleteSelection() {
    const currentNodes = nodesRef.current;
    const currentEdges = edgesRef.current;

    const deletedNodes = currentNodes.filter((node) => node.selected);
    const deletedEdges = currentEdges.filter((edge) => edge.selected);

    // Caso especial: eliminación de una clase asociativa.
    const associationNode = deletedNodes.find(
      (node) => node.data.association !== undefined,
    );

    if (associationNode) {
      if (deletedNodes.length !== 1 || deletedEdges.length > 0) {
        setAiMessage(
          "Selecciona solamente una clase asociativa para eliminarla.",
        );
        return;
      }

      const confirmed = window.confirm(
        `¿Eliminar la clase asociativa "${associationNode.data.name}" y sus relaciones estructurales?\n\n` +
          "Las clases originales permanecerán. " +
          "Esta operación también elimina los atributos propios de la clase asociativa.",
      );

      if (!confirmed) return;

      const structuralEdgeIds = new Set(
        associationNode.data.association!.endpoints.map(
          (endpoint) => endpoint.relationshipId,
        ),
      );

      const nextNodes = currentNodes
        .filter((node) => node.id !== associationNode.id)
        .map((node) => ({ ...node, selected: false }));

      const nextEdges = currentEdges
        .filter(
          (edge) =>
            !structuralEdgeIds.has(edge.id) &&
            edge.source !== associationNode.id &&
            edge.target !== associationNode.id,
        )
        .map((edge) => ({ ...edge, selected: false }));

      try {
        validateAssociationDocument(nextNodes, nextEdges);
        validateDiagramDocument(serializeDocument(nextNodes, nextEdges));
      } catch (error) {
        setAiMessage(
          error instanceof Error
            ? error.message
            : "No se puede eliminar la clase asociativa sin invalidar otras relaciones.",
        );
        return;
      }

      const document = serializeDocument(nextNodes, nextEdges);

      nodesRef.current = nextNodes;
      edgesRef.current = nextEdges;

      setNodes(nextNodes);
      setEdges(nextEdges);
      setSelectedNodeId(null);
      setSelectedEdgeIds([]);

      markDirty();

      // Un solo evento para mantener sincronizados
      // a los colaboradores y sus diagramas.
      publishEvent("DIAGRAM_BATCH_APPLIED", { document });

      agentSession.record("NODE_DELETED", {
        nodeId: associationNode.id,
      });

      setAiMessage("Clase asociativa eliminada correctamente.");
      return;
    }

    // Conservar la protección de las entidades que
    // pertenecen a otras asociaciones estructurales.
    if (
      deletedNodes.some((node) =>
        associationProtectedNodeIds(currentNodes).has(node.id),
      ) ||
      deletedEdges.some((edge) =>
        structuralRelationshipIds(currentNodes).has(edge.id),
      )
    ) {
      setAiMessage(
        "Este elemento pertenece a una clase asociativa. Elimina primero la clase asociativa correspondiente.",
      );
      return;
    }

    // Eliminación normal existente.
    deletedNodes.forEach((node) =>
      publishEvent("NODE_DELETED", { nodeId: node.id }),
    );

    deletedEdges.forEach((edge) =>
      publishEvent("EDGE_DELETED", { edgeId: edge.id }),
    );

    deletedNodes.forEach((node) =>
      agentSession.record("NODE_DELETED", { nodeId: node.id }),
    );

    deletedEdges.forEach((edge) =>
      agentSession.record("EDGE_DELETED", { edgeId: edge.id }),
    );

    const deletedNodeIds = new Set(deletedNodes.map((node) => node.id));

    setNodes((current) => current.filter((node) => !node.selected));

    setEdges((current) =>
      current.filter(
        (edge) =>
          !edge.selected &&
          !deletedNodeIds.has(edge.source) &&
          !deletedNodeIds.has(edge.target),
      ),
    );

    setSelectedNodeId(null);
    setSelectedEdgeIds([]);
    markDirty();
  }

  function confirmAssociationConversion(entityName: string) {
    if (!selectedEdge) return;
    try {
      const result = convertManyToManyAssociation(
        nodesRef.current,
        edgesRef.current,
        selectedEdge.id,
        entityName,
      );
      nodesRef.current = result.document.nodes;
      edgesRef.current = result.document.edges;
      setNodes(result.document.nodes);
      setEdges(result.document.edges);
      setSelectedNodeId(result.associationNodeId);
      setSelectedEdgeIds([]);
      setConversionOpen(false);
      setConversionError("");
      markDirty();
      publishEvent("DIAGRAM_BATCH_APPLIED", {
        document: serializeDocument(
          result.document.nodes,
          result.document.edges,
        ),
      });
      agentSession.record("NODE_CREATED", { nodeId: result.associationNodeId });
    } catch (error: unknown) {
      setConversionError(
        error instanceof AssociationConversionError
          ? error.message
          : "No se pudo convertir la relacion N:M.",
      );
    }
  }

  const handleSave = useCallback(
    async function saveCurrentDocument(automatic = false): Promise<void> {
      if (saveInFlightRef.current) {
        const savedSuccessfully = await saveInFlightRef.current;
        if (
          savedSuccessfully &&
          revisionRef.current !== persistedRevisionRef.current
        )
          await saveCurrentDocument(automatic);
        return;
      }

      const savedRevision = revisionRef.current;
      const document = serializeDocument(nodesRef.current, edgesRef.current);
      const documentFingerprint = JSON.stringify(document);
      try {
        // Keep validation failures client-side and visible; never re-send an unchanged invalid draft.
        validateDiagramDocument(document);
      } catch (error) {
        rejectedDocumentRef.current = documentFingerprint;
        setSaveStatus("error");
        setAiError(
          error instanceof Error
            ? error.message
            : "El diagrama no es válido para guardar.",
        );
        return;
      }
      if (automatic && rejectedDocumentRef.current === documentFingerprint) {
        setSaveStatus("error");
        return;
      }
      const request = guardarDiagrama(
        project.id,
        document,
        serverRevisionRef.current,
      )
        .then((saved) => {
          rejectedDocumentRef.current = null;
          serverRevisionRef.current = saved.revision;
          conflictRef.current = false;
          agentSession.setDiagramId(saved.id);
          agentSession.record("DIAGRAM_SAVED");
          persistedRevisionRef.current = savedRevision;
          publishEvent("DIAGRAM_SAVED", {
            savedAt: saved.fechaActualizacion,
            revision: saved.revision,
          });
          setSaveStatus(
            revisionRef.current === savedRevision ? "saved" : "dirty",
          );
          return true;
        })
        .catch((requestError: unknown) => {
          agentSession.record("SAVE_FAILED");
          if (isUnauthorizedError(requestError)) {
            localStorage.removeItem("token");
            window.location.assign("/login");
          } else if (isForbiddenError(requestError)) setSaveStatus("forbidden");
          else if (
            axios.isAxiosError(requestError) &&
            requestError.response?.status === 409
          ) {
            conflictRef.current = true;
            setSaveStatus("conflict");
            setAiError(
              "Otro usuario guardÃ³ una versiÃ³n mÃ¡s reciente. Tus cambios locales se conservaron; recarga para resincronizar.",
            );
          } else if (
            axios.isAxiosError(requestError) &&
            requestError.response?.status === 422
          ) {
            rejectedDocumentRef.current = documentFingerprint;
            const response = record(requestError.response.data);
            setSaveStatus("error");
            setAiError(
              typeof response?.mensaje === "string"
                ? response.mensaje
                : "El diagrama contiene una estructura invÃ¡lida y no fue guardado.",
            );
          } else setSaveStatus("error");
          return false;
        })
        .finally(() => {
          saveInFlightRef.current = null;
        });

      saveInFlightRef.current = request;
      setSaveStatus("saving");
      await request;
    },
    [agentSession, project.id, publishEvent],
  );

  async function handleAgentAsk(
    message: string,
    conversation: AgentConversationMessage[],
  ) {
    try {
      const response = await askProjectAgent(project.id, {
        message,
        selectedNodeId,
        selectedEdgeId: selectedEdge?.id ?? null,
        recentEvents: agentSession.snapshot(),
        conversation,
      });
      const prepared = prepareAgentResponse(
        message,
        response,
        nodesRef.current,
        edgesRef.current,
      );
      if (prepared.proposal?.preview.events.length) {
        setPendingProposal({
          operations: prepared.proposal.operations,
          impact: destructivePlanImpact(
            prepared.proposal.operations,
            nodesRef.current,
            edgesRef.current,
          ),
          revision: revisionRef.current,
          source: "agent",
        });
      }
      return prepared.response;
    } catch (error: unknown) {
      if (isUnauthorizedError(error)) {
        localStorage.removeItem("token");
        window.location.assign("/login");
        throw new Error("La sesión expiró.", { cause: error });
      }
      if (isForbiddenError(error))
        throw new Error("No tienes acceso a este proyecto.", { cause: error });
      if (axios.isAxiosError(error) && error.response?.status === 503) {
        throw new Error(
          "Ollama no está disponible. Inícialo y vuelve a intentarlo.",
          { cause: error },
        );
      }
      if (axios.isAxiosError(error) && error.response?.status === 504) {
        throw new Error(
          "Ollama tardó demasiado en responder. Vuelve a intentarlo.",
          { cause: error },
        );
      }
      if (axios.isAxiosError(error) && error.response?.status === 502) {
        throw new Error(
          "Ollama devolvió una respuesta no válida. Vuelve a intentarlo.",
          { cause: error },
        );
      }
      throw new Error("El agente no pudo responder. Inténtalo de nuevo.", {
        cause: error,
      });
    }
  }

  async function handleEnterpriseArchitectImport(file: File) {
    setImportError("");
    if (!/\.(xmi|xml)$/i.test(file.name)) {
      setImportError("Selecciona un archivo con extensión .xmi o .xml.");
      return;
    }
    if (file.size > 5 * 1024 * 1024) {
      setImportError("El archivo supera el límite de 5 MB.");
      return;
    }
    setImportLoading(true);
    try {
      const preview = await previewEnterpriseArchitectImport(file);
      const imported: DiagramDocument = {
        version: preview.version === 2 ? 2 : 1,
        nodes: preview.nodes.map(normalizeStoredNode),
        edges: preview.edges.map(normalizeRelationshipEdge),
      };
      validateDiagramDocument(imported);
      const diff = computeDiagramImportDiff(
        serializeDocument(nodesRef.current, edgesRef.current),
        imported,
        preview.warnings,
      );
      setPendingImport({
        document: imported,
        diff,
        selected: defaultImportSelection(diff),
        revision: revisionRef.current,
      });
    } catch (requestError: unknown) {
      if (isUnauthorizedError(requestError)) {
        localStorage.removeItem("token");
        window.location.assign("/login");
      } else if (isForbiddenError(requestError)) {
        setImportError("No tienes permisos para importar en este proyecto.");
      } else if (axios.isAxiosError(requestError)) {
        const data = record(requestError.response?.data);
        setImportError(
          typeof data?.mensaje === "string"
            ? data.mensaje
            : "No se pudo interpretar el archivo XMI/XML.",
        );
      } else {
        setImportError(
          requestError instanceof Error
            ? requestError.message
            : "No se pudo preparar la importación.",
        );
      }
    } finally {
      setImportLoading(false);
      if (importInputRef.current) importInputRef.current.value = "";
    }
  }

  function toggleImportChange(id: string, entity?: EntityImportChange) {
    setPendingImport((current) => {
      if (!current) return current;
      const selected = new Set(current.selected);
      const enable = !selected.has(id);
      if (enable) selected.add(id);
      else selected.delete(id);
      if (entity) {
        for (const attribute of entity.attributes.filter(
          (item) => item.status !== "unchanged",
        )) {
          if (enable && attribute.status !== "missing")
            selected.add(attribute.id);
          else selected.delete(attribute.id);
        }
      }
      return { ...current, selected };
    });
  }

  function applyPendingImport() {
    if (!pendingImport) return;
    if (pendingImport.revision !== revisionRef.current) {
      setImportError(
        "El diagrama cambió después de generar la vista previa. Vuelve a seleccionar el archivo.",
      );
      setPendingImport(null);
      return;
    }
    try {
      const document = applyDiagramImport(
        serializeDocument(nodesRef.current, edgesRef.current),
        pendingImport.document,
        pendingImport.selected,
      );
      nodesRef.current = document.nodes;
      edgesRef.current = document.edges;
      setNodes(document.nodes);
      setEdges(document.edges);
      setSelectedNodeId(null);
      setSelectedEdgeIds([]);
      setPendingImport(null);
      setImportError("");
      markDirty();
      publishEvent("DIAGRAM_BATCH_APPLIED", {
        document: serializeDocument(document.nodes, document.edges),
      });
      requestAnimationFrame(
        () => void fitView({ padding: 0.2, duration: 350 }),
      );
    } catch (error: unknown) {
      setImportError(
        error instanceof DiagramImportError || error instanceof Error
          ? error.message
          : "No se pudo aplicar la importación.",
      );
    }
  }

  async function handleEnterpriseArchitectExport() {
    if (exportStatus !== null) return;

    // El servidor exporta el último documento guardado.
    // Evitamos descargar una versión anterior mientras hay cambios pendientes.
    if (
      saveInFlightRef.current ||
      revisionRef.current !== persistedRevisionRef.current ||
      saveStatus === "dirty" ||
      saveStatus === "saving" ||
      saveStatus === "error" ||
      saveStatus === "forbidden"
    ) {
      setExportMessage(
        "Guarda el diagrama antes de exportarlo a Enterprise Architect.",
      );
      return;
    }

    setExportStatus("enterprise-architect");
    setExportMessage("");

    try {
      const blob = await descargarEnterpriseArchitectXmi(project.id);

      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");

      link.href = url;
      link.download = "logicdraft-enterprise-architect.xmi";

      document.body.appendChild(link);
      link.click();
      link.remove();

      window.setTimeout(() => URL.revokeObjectURL(url), 1000);

      setExportMessage(
        "Archivo XMI descargado. Pendiente de verificar su importación en Enterprise Architect.",
      );
    } catch (requestError: unknown) {
      const detailedMessage = await responseMessage(requestError);
      if (isUnauthorizedError(requestError)) {
        localStorage.removeItem("token");
        window.location.assign("/login");
      } else if (isForbiddenError(requestError)) {
        setExportMessage("No tienes permisos para exportar este proyecto.");
      } else if (
        axios.isAxiosError(requestError) &&
        requestError.response?.status === 404
      ) {
        setExportMessage("No se encontró el proyecto o su diagrama guardado.");
      } else if (axios.isAxiosError(requestError) && !requestError.response) {
        setExportMessage(
          "No se pudo conectar con LogicDraft. Comprueba que Spring esté funcionando.",
        );
      } else {
        setExportMessage(
          detailedMessage ??
            "No se pudo exportar el XMI. Verifica que los elementos UML tengan nombres, tipos y relaciones completos.",
        );
      }
    } finally {
      setExportStatus(null);
    }
  }
  async function handleExport(kind: GeneratorExport) {
    if (exportStatus !== null) return;
    setExportStatus(kind);
    setExportMessage("");
    try {
      await descargarProyectoGenerado(project.id, kind, project.nombre);
      const structuralNames = nodesRef.current
        .filter((node) => (node.data.kind ?? "ENTITY") !== "ENTITY")
        .map((node) => node.data.name);
      const warning =
        structuralNames.length > 0
          ? ` Se añadieron tipos UML estructurales sin persistencia ni lógica de negocio: ${structuralNames.join(", ")}.`
          : "";
      setExportMessage(
        kind === "backend"
          ? `Backend generado y descargado correctamente.${warning}`
          : `Proyecto generado y descargado correctamente.${warning}`,
      );
    } catch (requestError: unknown) {
      const detailedMessage = await responseMessage(requestError);
      if (isUnauthorizedError(requestError)) {
        localStorage.removeItem("token");
        window.location.assign("/login");
      } else if (isForbiddenError(requestError)) {
        setExportMessage("No tienes permisos para exportar este proyecto.");
      } else if (
        axios.isAxiosError(requestError) &&
        requestError.response?.status === 409
      ) {
        setExportMessage(
          detailedMessage ??
            "No se puede generar: agrega una entidad persistente con primary key y revisa sus relaciones.",
        );
      } else if (axios.isAxiosError(requestError) && !requestError.response) {
        setExportMessage(
          "No se pudo conectar con LogicDraft. Revisa tu conexión e inténtalo nuevamente.",
        );
      } else if (
        axios.isAxiosError(requestError) &&
        (requestError.response?.status ?? 0) >= 500
      ) {
        setExportMessage(
          "LogicDraft no pudo generar el proyecto. Inténtalo nuevamente más tarde.",
        );
      } else {
        setExportMessage(
          detailedMessage ??
            "No se pudo generar la descarga. Revisa las entidades persistentes y sus primary keys.",
        );
      }
    } finally {
      setExportStatus(null);
    }
  }

  useEffect(() => {
    if (saveStatus !== "dirty") return;
    const timeoutId = window.setTimeout(() => {
      void handleSave(true);
    }, 1500);
    return () => window.clearTimeout(timeoutId);
  }, [documentRevision, handleSave, saveStatus]);

  useEffect(() => {
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      if (
        revisionRef.current === persistedRevisionRef.current &&
        !saveInFlightRef.current
      )
        return;
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, []);

  useEffect(
    () => () => {
      if (
        revisionRef.current !== persistedRevisionRef.current ||
        saveInFlightRef.current
      )
        void handleSave();
    },
    [handleSave],
  );

  return (
    <div
      className={`diagram-editor${sidebarOpen ? " is-sidebar-open" : ""}${propertiesOpen ? " is-properties-open" : ""}`}
    >
      {(sidebarOpen || propertiesOpen) && (
        <button
          className="diagram-panel-backdrop"
          type="button"
          aria-label="Cerrar paneles"
          onClick={() => {
            setSidebarOpen(false);
            setPropertiesOpen(false);
          }}
        />
      )}
      <DiagramSidebar
        projectsPath={`/workspaces/${project.workspaceId}/proyectos`}
        onAddNode={addUmlNode}
        selectedRelationKind={selectedRelationKind}
        onSelectRelationKind={setSelectedRelationKind}
        onClose={() => setSidebarOpen(false)}
      />
      <input
        ref={importInputRef}
        className="diagram-import-input"
        type="file"
        accept=".xmi,.xml"
        onChange={(event) => {
          const file = event.target.files?.[0];
          if (file) void handleEnterpriseArchitectImport(file);
        }}
      />
      <DiagramToolbar
        projectName={project.nombre}
        hasSelection={selectedNodeId !== null || selectedEdgeIds.length > 0}
        saveStatus={saveStatus}
        onSave={() => void handleSave()}
        onDeleteSelection={deleteSelection}
        onFitView={() => void fitView({ padding: 0.2, duration: 350 })}
        onImport={() => importInputRef.current?.click()}
        importDisabled={
          importLoading ||
          saveStatus === "saving" ||
          saveStatus === "conflict" ||
          saveStatus === "forbidden"
        }
        exportStatus={exportStatus}
        exportMessage={exportMessage}
        exportDisabled={
          saveStatus === "dirty" ||
          saveStatus === "saving" ||
          saveStatus === "error" ||
          saveStatus === "conflict" ||
          saveStatus === "forbidden"
        }
        onExportBackend={() => void handleExport("backend")}
        onExportFullStack={() => void handleExport("fullstack")}
        onExportEnterpriseArchitect={() =>
          void handleEnterpriseArchitectExport()
        }
        sidebarOpen={sidebarOpen}
        propertiesOpen={propertiesOpen}
        onToggleSidebar={() => {
          setPropertiesOpen(false);
          setSidebarOpen((open) => !open);
        }}
        onToggleProperties={() => {
          setSidebarOpen(false);
          setPropertiesOpen((open) => !open);
        }}
      />
      {saveStatus === "conflict" && (
        <div className="diagram-import-status is-error" role="alert">
          <span>
            Existe una versiÃ³n confirmada mÃ¡s reciente. Tus cambios locales
            siguen en este navegador.
          </span>
          <button
            type="button"
            onClick={() => {
              if (
                window.confirm(
                  "Recargar reemplazarÃ¡ los cambios locales no guardados por la versiÃ³n confirmada. Â¿Continuar?",
                )
              )
                window.location.reload();
            }}
          >
            Recargar versiÃ³n del servidor
          </button>
        </div>
      )}
      {(importLoading || importError) && (
        <div
          className={`diagram-import-status${importError ? " is-error" : ""}`}
          role={importError ? "alert" : "status"}
        >
          {importError || "Analizando XMI/XML..."}
        </div>
      )}
      <ProjectPresence status={status} collaborators={collaborators} />
      <section className="diagram-canvas" aria-label="Canvas del diagrama">
        <ReactFlow<EntityFlowNode, DiagramEdge>
          nodes={nodes}
          edges={edges}
          connectionMode={ConnectionMode.Loose}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodesChange={handleNodesChange}
          onEdgesChange={handleEdgesChange}
          onConnect={onConnect}
          onSelectionChange={handleSelectionChange}
          onNodeDragStop={(_, node) => {
            publishEvent("NODE_MOVED", {
              nodeId: node.id,
              position: { x: node.position.x, y: node.position.y },
            });
            agentSession.record("NODE_UPDATED", { nodeId: node.id });
            markDirty();
          }}
          onNodesDelete={(deleted) =>
            deleted.forEach((node) => {
              if (associationProtectedNodeIds(nodesRef.current).has(node.id))
                return;
              publishEvent("NODE_DELETED", { nodeId: node.id });
              agentSession.record("NODE_DELETED", { nodeId: node.id });
            })
          }
          onEdgesDelete={(deleted) =>
            deleted.forEach((edge) => {
              if (structuralRelationshipIds(nodesRef.current).has(edge.id))
                return;
              publishEvent("EDGE_DELETED", { edgeId: edge.id });
              agentSession.record("EDGE_DELETED", { edgeId: edge.id });
            })
          }
          deleteKeyCode={protectedSelection ? null : ["Backspace", "Delete"]}
          fitView
          minZoom={0.25}
          maxZoom={2}
        >
          <Background
            variant={BackgroundVariant.Dots}
            gap={24}
            size={1.4}
            color="#34343a"
          />
          <Controls position="bottom-left" showInteractive={false} />
        </ReactFlow>
      </section>
      <DiagramPropertiesPanel
        onClose={() => setPropertiesOpen(false)}
        edge={selectedEdge}
        joinTable={selectedJoinTable}
        structuralRelationship={Boolean(
          selectedEdge && protectedEdgeIds.has(selectedEdge.id),
        )}
        onConvertAssociation={() => {
          setConversionError("");
          setConversionOpen(true);
        }}
        onChangeCardinality={(end, value) => {
          if (!selectedEdge) return;
          if (protectedEdgeIds.has(selectedEdge.id)) {
            setAiMessage(
              "La relacion estructural no puede editarse individualmente.",
            );
            return;
          }
          const data = { ...selectedEdge.data, [end]: value };
          if (
            !(
              data.sourceCardinality?.endsWith("MANY") &&
              data.targetCardinality?.endsWith("MANY")
            )
          ) {
            delete data.joinTableName;
          }
          const changed = commitRelationshipEdge(
            edgesRef.current,
            { ...selectedEdge, data },
            "EDGE_UPDATED",
            false,
            {
              setEdges: (next) => {
                edgesRef.current = next;
                setEdges(next);
              },
              markDirty,
              publish: (kind, edge) => {
                publishEvent(kind, { edge: serializeEdge(edge) });
                agentSession.record("EDGE_UPDATED", { edgeId: edge.id });
              },
            },
          );
          if (!changed)
            setAiMessage(
              "Sin cambios: la relación es equivalente a otra existente.",
            );
        }}
        onChangeRelationshipData={(changes) => {
          if (!selectedEdge) return;
          if (protectedEdgeIds.has(selectedEdge.id)) {
            setAiMessage(
              "La relacion estructural no puede editarse individualmente.",
            );
            return;
          }
          const nextData = { ...selectedEdge.data, ...changes };
          for (const key of Object.keys(changes) as Array<
            keyof typeof changes
          >) {
            if (changes[key] === undefined) delete nextData[key];
          }
          try {
            validateDiagramDocument(
              serializeDocument(
                nodesRef.current,
                edgesRef.current.map((edge) =>
                  edge.id === selectedEdge.id
                    ? { ...edge, data: nextData }
                    : edge,
                ),
              ),
            );
          } catch (error) {
            setAiError(
              error instanceof Error
                ? error.message
                : "La modificación de la relación no es válida.",
            );
            return;
          }
          const changed = commitRelationshipEdge(
            edgesRef.current,
            { ...selectedEdge, data: nextData },
            "EDGE_UPDATED",
            false,
            {
              setEdges: (next) => {
                edgesRef.current = next;
                setEdges(next);
              },
              markDirty,
              publish: (kind, edge) => {
                publishEvent(kind, { edge: serializeEdge(edge) });
                agentSession.record("EDGE_UPDATED", { edgeId: edge.id });
              },
            },
          );
          if (!changed)
            setAiMessage(
              "Sin cambios: revisa que el nombre distinga la relación.",
            );
        }}
        entity={selectedNode?.data ?? null}
        onChangeGenerateCrud={(enabled) =>
          updateSelectedEntity((node) => ({
            ...node,
            data: { ...node.data, generateCrud: enabled },
          }))
        }
        associationColumns={selectedAssociationColumns}
        onChangeName={(name) =>
          updateSelectedEntity((node) => ({
            ...node,
            data: { ...node.data, name },
          }))
        }
        onAddAttribute={() => {
          const number = nextAttributeNumber.current++;
          updateSelectedEntity((node) => ({
            ...node,
            data: {
              ...node.data,
              attributes: [
                ...node.data.attributes,
                {
                  id: `attribute-${crypto.randomUUID()}-${number}`,
                  name: uniqueDefaultName(
                    "nuevo_atributo",
                    node.data.attributes.map((attribute) => attribute.name),
                  ),
                  type: "VARCHAR",
                  primaryKey: false,
                },
              ],
            },
          }));
        }}
        onChangeAttribute={changeAttribute}
        onDeleteAttribute={(attributeId) =>
          updateSelectedEntity((node) => ({
            ...node,
            data: {
              ...node.data,
              attributes: node.data.attributes.filter(
                (attribute) => attribute.id !== attributeId,
              ),
            },
          }))
        }
        onAddMethod={addMethod}
        onChangeMethod={(methodId, changes) =>
          updateSelectedEntity((node) => ({
            ...node,
            data: {
              ...node.data,
              methods: (node.data.methods ?? []).map((method) =>
                method.id === methodId ? { ...method, ...changes } : method,
              ),
            },
          }))
        }
        onDeleteMethod={(methodId) =>
          updateSelectedEntity((node) => ({
            ...node,
            data: {
              ...node.data,
              methods: (node.data.methods ?? []).filter(
                (method) => method.id !== methodId,
              ),
            },
          }))
        }
      />
      <AiPromptBar
        projectId={project.id}
        onSubmit={handleAiPrompt}
        onImageSelected={async (image, prompt) => {
          await handleAiPrompt(prompt, image);
        }}
        loading={aiLoading}
        message={aiMessage}
        error={aiError}
        disabledReason={
          saveStatus === "forbidden"
            ? "No tienes permisos para modificar este proyecto"
            : saveStatus === "dirty" ||
                saveStatus === "saving" ||
                saveStatus === "error" ||
                saveStatus === "conflict"
              ? "Espera a que el diagrama esté guardado para usar IA."
              : undefined
        }
      />
      <AgentPanel
        onAsk={handleAgentAsk}
        disabledReason={
          saveStatus === "dirty" ||
          saveStatus === "saving" ||
          saveStatus === "error" ||
          saveStatus === "conflict"
            ? "Espera a que el diagrama se guarde para consultar su estado actual."
            : undefined
        }
      />
      <AssociationConversionDialog
        key={`${selectedEdge?.id ?? "none"}-${conversionOpen}`}
        open={conversionOpen}
        tableName={selectedJoinTable?.name ?? ""}
        error={conversionError}
        onCancel={() => {
          setConversionOpen(false);
          setConversionError("");
        }}
        onConfirm={confirmAssociationConversion}
      />
      <DiagramAiProposalDialog
        operations={pendingProposal?.operations ?? []}
        impact={pendingProposal?.impact ?? []}
        onCancel={() => {
          setPendingProposal(null);
          setAiMessage("Propuesta cancelada; no se realizaron cambios");
        }}
        onAccept={acceptAiProposal}
      />
      {pendingImport && (
        <DiagramImportDialog
          diff={pendingImport.diff}
          selected={pendingImport.selected}
          onToggle={toggleImportChange}
          onCancel={() => {
            setPendingImport(null);
            setImportError("");
          }}
          onApply={applyPendingImport}
        />
      )}
    </div>
  );
}

export function DiagramEditorPage() {
  const { projectId: projectIdParam } = useParams();
  const navigate = useNavigate();
  const projectId = Number(projectIdParam);
  const validProjectId = Number.isSafeInteger(projectId) && projectId > 0;
  const [project, setProject] = useState<Proyecto | null>(null);
  const [document, setDocument] = useState<DiagramDocument | null>(null);
  const [diagramId, setDiagramId] = useState<number | null>(null);
  const [serverRevision, setServerRevision] = useState<number | null>(null);
  const [loading, setLoading] = useState(validProjectId);
  const [error, setError] = useState(
    validProjectId ? "" : "El proyecto indicado no es válido.",
  );

  useEffect(() => {
    if (!validProjectId) return;
    let active = true;
    async function loadEditor() {
      try {
        const loadedProject = await obtenerProyecto(projectId);
        let loadedDocument = EMPTY_DOCUMENT;
        let loadedDiagramId: number | null = null;
        let loadedServerRevision: number | null = null;
        try {
          const loadedDiagram = await obtenerDiagrama(projectId);
          loadedDiagramId = loadedDiagram.id;
          loadedServerRevision = loadedDiagram.revision;
          const content = loadedDiagram.contenido;
          loadedDocument = {
            ...content,
            nodes: content.nodes.map(normalizeStoredNode),
            edges: content.edges.map(normalizeRelationshipEdge),
          };
        } catch (requestError: unknown) {
          if (!isNotFoundError(requestError)) throw requestError;
        }
        if (active) {
          setProject(loadedProject);
          setDocument(loadedDocument);
          setDiagramId(loadedDiagramId);
          setServerRevision(loadedServerRevision);
        }
      } catch (requestError: unknown) {
        if (isUnauthorizedError(requestError)) {
          localStorage.removeItem("token");
          navigate("/login", { replace: true });
        } else if (active && isForbiddenError(requestError))
          setError("No tienes acceso a este proyecto.");
        else if (active && isNotFoundError(requestError))
          setError("El proyecto no existe.");
        else if (active) setError("No se pudo cargar el diagrama.");
      } finally {
        if (active) setLoading(false);
      }
    }
    void loadEditor();
    return () => {
      active = false;
    };
  }, [navigate, projectId, validProjectId]);

  if (loading)
    return <main className="diagram-state">Cargando diagrama...</main>;
  if (error || !project || !document)
    return (
      <main className="diagram-state is-error" role="alert">
        {error || "Diagrama no disponible."}
      </main>
    );
  return (
    <ReactFlowProvider key={project.id}>
      <DiagramEditorCanvas
        project={project}
        initialDocument={document}
        initialDiagramId={diagramId}
        initialServerRevision={serverRevision}
      />
    </ReactFlowProvider>
  );
}
