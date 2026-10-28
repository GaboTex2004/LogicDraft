import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
} from "react";
import axios from "axios";
import {
  Background,
  BackgroundVariant,
  ConnectionMode,
  Controls,
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
import {
  listarConversacionesProyecto,
  listarMensajesConversacion,
} from "../../agent/api/agentConversationsApi";
import { AgentPanel } from "../../agent/components/AgentPanel";
import { AgentConversationStore } from "../../agent/services/agentConversationStore";
import { AgentSession } from "../../agent/services/agentSession";
import {
  prepareAgentResponse,
  proposalAcceptanceError,
} from "../../agent/services/agentProposal";
import type { AgentConversationMessage } from "../../agent/types/agent.types";
import {
  adquirirElementLocks,
  extractElementLockedRejection,
  liberarElementLocks,
  listarElementLocks,
  renovarElementLocks,
} from "../../collaboration/api/elementLocksApi";
import { ProjectPresence } from "../../collaboration/components/ProjectPresence";
import { RemoteCursorsOverlay } from "../../collaboration/components/RemoteCursorsOverlay";
import { ElementLocksProvider } from "../../collaboration/context/ElementLocksContext";
import { useProjectCollaboration } from "../../collaboration/hooks/useProjectCollaboration";
import {
  canonicalJson,
  diffLocalElementsAgainstBase,
  projectRemotePreviewOntoLocal,
  rebaseLocalOntoServer,
} from "../../collaboration/services/diagramThreeWayMerge";
import { DiagramSynchronizer } from "../../collaboration/services/diagramSynchronization";
import {
  formatLockedByMessage,
  LOCK_HEARTBEAT_INTERVAL_MS,
  LOCK_IDLE_RELEASE_MS,
  requiredLockIdsForAssociationConversion,
  requiredLockIdsForConnection,
  requiredLockIdsForEdgeEdit,
  requiredLockIdsForNodeDelete,
  requiredLockIdsForNodeEdit,
} from "../../collaboration/services/elementLocks";
import { applyLiveDiagramEvent } from "../../collaboration/services/liveDiagramEvents";
import { CursorPublisherThrottle } from "../../collaboration/services/remoteCursors";
import {
  isConversationCollaborationEvent,
  type CollaborationEvent,
} from "../../collaboration/types/collaboration.types";
import { triggerBlobDownload } from "../services/generatorDownload";
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
} from "../services/relationshipCardinality";
import type {
  DiagramDocument,
  DiagramEdge,
  EntityAttribute,
  EntityFlowNode,
  UmlNodeKind,
  UmlMethod,
  UmlRelationKind,
} from "../types/diagram.types";
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
  const [syncMessage, setSyncMessage] = useState("");
  const [syncError, setSyncError] = useState("");
  const synchronizerRef = useRef<DiagramSynchronizer | null>(null);
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
  const [conversationStore] = useState(
    () => new AgentConversationStore(project.id),
  );
  const conversationStoreRef = useRef(conversationStore);
  const clientIdRef = useRef<string>("");
  const syncConversationsRef = useRef<(() => Promise<void>) | null>(null);
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
  const remotePreviewAtRef = useRef<number | null>(null);
  const cursorThrottleRef = useRef<CursorPublisherThrottle | null>(null);
  const lastDragPublishRef = useRef<Record<string, number>>({});
  const nodeUpdateThrottleRef = useRef<{
    lastSentAt: number;
    timer: number | null;
    nodeId: string | null;
  }>({ lastSentAt: 0, timer: null, nodeId: null });
  const [confirmedDocument, setConfirmedDocument] = useState<DiagramDocument>(
    () =>
      serializeDocument(
        initialDocument.nodes,
        initialDocument.edges.map(normalizeRelationshipEdge),
      ),
  );
  const confirmedDocumentRef = useRef<DiagramDocument>(confirmedDocument);
  const [remotePreview, setRemotePreview] = useState<{
    nodes: EntityFlowNode[];
    edges: DiagramEdge[];
    authorName?: string;
  } | null>(null);
  const remotePreviewRef = useRef<{
    nodes: EntityFlowNode[];
    edges: DiagramEdge[];
    authorName?: string;
  } | null>(null);
  const [previewMeasured, setPreviewMeasured] = useState<
    Record<string, { width: number; height: number }>
  >({});
  const [lockNotice, setLockNotice] = useState("");
  const lockNoticeTimerRef = useRef<number | null>(null);
  const pendingLockAcquiresRef = useRef<Map<string, Promise<boolean>>>(
    new Map(),
  );
  const lastLockActivityAtRef = useRef<number>(0);
  const syncLocksRef = useRef<(() => Promise<void>) | null>(null);
  const dragInitialPositionsRef = useRef<
    Record<string, { x: number; y: number }>
  >({});
  const dragDeniedRef = useRef<Set<string>>(new Set());
  const lockedDragProbeRef = useRef<{
    nodeId: string;
    ownerLabel: string;
    startX: number;
    startY: number;
    notified: boolean;
  } | null>(null);
  const { fitView, screenToFlowPosition } = useReactFlow();

  useEffect(() => {
    nodesRef.current = nodes;
    edgesRef.current = edges;
  }, [edges, nodes]);

  const updateConfirmedDocument = useCallback((nextDocument: DiagramDocument) => {
    confirmedDocumentRef.current = nextDocument;
    setConfirmedDocument(nextDocument);
  }, []);

  const clearRemotePreview = useCallback(() => {
    remotePreviewAtRef.current = null;
    remotePreviewRef.current = null;
    setRemotePreview(null);
    setPreviewMeasured((current) =>
      Object.keys(current).length > 0 ? {} : current,
    );
  }, []);

  const showLockNotice = useCallback((message: string) => {
    setLockNotice(message);
    if (lockNoticeTimerRef.current !== null) {
      window.clearTimeout(lockNoticeTimerRef.current);
    }
    lockNoticeTimerRef.current = window.setTimeout(() => {
      lockNoticeTimerRef.current = null;
      setLockNotice("");
    }, 3_600);
  }, []);

  useEffect(
    () => () => {
      if (lockNoticeTimerRef.current !== null) {
        window.clearTimeout(lockNoticeTimerRef.current);
      }
    },
    [],
  );

  const markDirty = useCallback(() => {
    revisionRef.current += 1;
    setDocumentRevision(revisionRef.current);
    setSaveStatus(conflictRef.current ? "conflict" : "dirty");
  }, []);

  const synchronize = useCallback(() => {
    void synchronizerRef.current?.refresh();
    void syncLocksRef.current?.();
    void syncConversationsRef.current?.();
  }, []);

  const applyRemoteEvent = useCallback(
    (event: CollaborationEvent) => {
      if (isConversationCollaborationEvent(event.type)) {
        conversationStoreRef.current.applyCollaborationEvent(
          event,
          clientIdRef.current || undefined,
        );
        return;
      }
      if (event.type === "DIAGRAM_COMMITTED") {
        const payload = record(event.payload);
        void synchronizerRef.current?.refresh(
          typeof payload?.revision === "number"
            ? { name: event.name, revision: payload.revision }
            : undefined,
        );
        return;
      }
      if (event.type === "USER_LEFT") {
        if (remotePreviewAtRef.current !== null) {
          remotePreviewAtRef.current = 1;
          window.setTimeout(() => {
            void synchronizerRef.current?.refresh();
          }, 400);
        }
        return;
      }
      if (conflictRef.current) return;
      const basePreview = remotePreviewRef.current ?? {
        nodes: confirmedDocumentRef.current.nodes,
        edges: confirmedDocumentRef.current.edges,
      };
      const result = applyLiveDiagramEvent(event, {
        nodes: basePreview.nodes,
        edges: basePreview.edges,
        dirty: false,
        conflict: false,
      });
      if (!result.applied) return;
      remotePreviewAtRef.current = Date.now();
      const nextPreview = {
        nodes: result.nodes,
        edges: result.edges,
        authorName: event.name,
      };
      remotePreviewRef.current = nextPreview;
      setRemotePreview(nextPreview);
    },
    [],
  );

  const {
    status,
    collaborators,
    publishLiveEvent,
    cursorStore,
    lockStore,
    clientId,
  } = useProjectCollaboration(project.id, applyRemoteEvent, synchronize);

  const syncConversations = useCallback(async () => {
    try {
      const snap = conversationStore.getSnapshot();
      const list = await listarConversacionesProyecto(
        project.id,
        snap.includeArchived,
      );
      conversationStore.setConversationsList(list);
      const activeConvId = conversationStore.getSnapshot().activeConversationId;
      if (activeConvId !== null) {
        const page = await listarMensajesConversacion(
          project.id,
          activeConvId,
          { limit: 40 },
        );
        conversationStore.replaceInitialMessagesPage(page);
      }
    } catch {
      // Transient network errors recover on next STOMP reconnect or manual interaction.
    }
  }, [conversationStore, project.id]);

  useEffect(() => {
    clientIdRef.current = clientId;
    conversationStoreRef.current = conversationStore;
    syncConversationsRef.current = syncConversations;
  }, [clientId, conversationStore, syncConversations]);

  const activeLocksMap = useSyncExternalStore(
    lockStore.subscribe,
    lockStore.getMapSnapshot,
    lockStore.getMapSnapshot,
  );

  const syncLocks = useCallback(async () => {
    try {
      const locks = await listarElementLocks(project.id);
      lockStore.replaceAll(locks, clientId);
    } catch {
      // Transient failures recover on next STOMP frame or periodic sync.
    }
  }, [clientId, lockStore, project.id]);

  useEffect(() => {
    syncLocksRef.current = syncLocks;
    void syncLocks();
  }, [syncLocks]);

  const ensureLocks = useCallback(
    async (elementIds: readonly string[]): Promise<boolean> => {
      const ids = [...new Set(elementIds.filter(Boolean))].sort();
      if (ids.length === 0) return true;
      if (saveStatus === "forbidden") return false;
      const foreign = lockStore.findForeignLock(ids);
      if (foreign) {
        showLockNotice(formatLockedByMessage(foreign.label));
        return false;
      }
      if (lockStore.areAllOwnedBySelf(ids)) {
        lastLockActivityAtRef.current = Date.now();
        return true;
      }
      const key = ids.join("|");
      const existing = pendingLockAcquiresRef.current.get(key);
      if (existing) return existing;
      const request = adquirirElementLocks(project.id, clientId, ids)
        .then((result) => {
          if (result.granted) {
            lastLockActivityAtRef.current = Date.now();
            lockStore.upsertLocks(result.locks, clientId);
            return true;
          }
          showLockNotice(result.message);
          void syncLocks();
          return false;
        })
        .catch(() => {
          void syncLocks();
          return false;
        })
        .finally(() => {
          pendingLockAcquiresRef.current.delete(key);
        });
      pendingLockAcquiresRef.current.set(key, request);
      return request;
    },
    [clientId, lockStore, project.id, saveStatus, showLockNotice, syncLocks],
  );

  const releaseOwnedLocks = useCallback(
    (elementIds?: readonly string[]) => {
      const targetIds = elementIds
        ? elementIds.filter((id) => lockStore.getLock(id)?.ownedBySelf)
        : lockStore.ownedElementIds();
      if (targetIds.length === 0) return;
      lockStore.removeElements(targetIds, clientId);
      void liberarElementLocks(project.id, clientId, targetIds).catch(() => {
        void syncLocks();
      });
    },
    [clientId, lockStore, project.id, syncLocks],
  );

  const releaseUnusedOwnedLocks = useCallback(
    (keepExtraIds?: Iterable<string>) => {
      const owned = lockStore.ownedElementIds();
      if (owned.length === 0) return;
      const dirtyDiff = diffLocalElementsAgainstBase(
        confirmedDocumentRef.current,
        nodesRef.current,
        edgesRef.current,
      );
      const keep = new Set<string>([
        ...dirtyDiff.lockProtectedIds,
        ...dirtyDiff.allTouchedIds,
      ]);
      for (const node of nodesRef.current) {
        if (node.dragging) {
          for (const id of requiredLockIdsForNodeEdit(
            node.id,
            nodesRef.current,
          )) {
            keep.add(id);
          }
        }
      }
      if (keepExtraIds) {
        for (const id of keepExtraIds) keep.add(id);
      }
      const toRelease = owned.filter((id) => !keep.has(id));
      if (toRelease.length > 0) {
        releaseOwnedLocks(toRelease);
      }
    },
    [lockStore, releaseOwnedLocks],
  );

  const selectedEdgeIdSet = useMemo(
    () => new Set(selectedEdgeIds),
    [selectedEdgeIds],
  );
  const projectedGraph = useMemo(
    () =>
      remotePreview
        ? projectRemotePreviewOntoLocal(
            confirmedDocument,
            remotePreview,
            nodes,
            edges,
            previewMeasured,
            { nodeId: selectedNodeId, edgeIds: selectedEdgeIdSet },
          )
        : { nodes, edges },
    [
      confirmedDocument,
      edges,
      nodes,
      previewMeasured,
      remotePreview,
      selectedEdgeIdSet,
      selectedNodeId,
    ],
  );
  const localNodeIdSet = useMemo(
    () => new Set(nodes.map((node) => node.id)),
    [nodes],
  );
  const localEdgeIdSet = useMemo(
    () => new Set(edges.map((edge) => edge.id)),
    [edges],
  );
  const renderedNodes = useMemo(
    () =>
      projectedGraph.nodes.map((node) => {
        const requiredIds = requiredLockIdsForNodeEdit(
          node.id,
          projectedGraph.nodes,
        );
        const lockedByOther = requiredIds.some((id) => {
          const lock = activeLocksMap.get(id);
          return Boolean(lock && !lock.ownedBySelf);
        });
        const previewOnly = !localNodeIdSet.has(node.id);
        if (lockedByOther || previewOnly || saveStatus === "forbidden") {
          return { ...node, draggable: false };
        }
        return node;
      }),
    [activeLocksMap, localNodeIdSet, projectedGraph.nodes, saveStatus],
  );
  const renderedEdges = projectedGraph.edges;

  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null;
  const selectedEdge =
    !selectedNode && selectedEdgeIds.length === 1
      ? (edges.find((edge) => edge.id === selectedEdgeIds[0]) ?? null)
      : null;

  const displaySelectedNode =
    projectedGraph.nodes.find((node) => node.id === selectedNodeId) ?? null;
  const displaySelectedEdge =
    !displaySelectedNode && selectedEdgeIds.length === 1
      ? (projectedGraph.edges.find((edge) => edge.id === selectedEdgeIds[0]) ??
        null)
      : null;
  const selectedJoinTable = displaySelectedEdge
    ? deriveJoinTable(
        displaySelectedEdge,
        projectedGraph.nodes.find(
          (node) => node.id === displaySelectedEdge.source,
        )?.data,
        projectedGraph.nodes.find(
          (node) => node.id === displaySelectedEdge.target,
        )?.data,
      )
    : null;
  const selectedLockedBy = useMemo(() => {
    if (displaySelectedNode) {
      const ids = requiredLockIdsForNodeEdit(
        displaySelectedNode.id,
        projectedGraph.nodes,
      );
      for (const id of ids) {
        const lock = activeLocksMap.get(id);
        if (lock && !lock.ownedBySelf) {
          return { label: lock.label, color: lock.color };
        }
      }
      if (!localNodeIdSet.has(displaySelectedNode.id)) {
        return {
          label: remotePreview?.authorName ?? "otro usuario",
          color: "#38bdf8",
        };
      }
    } else if (displaySelectedEdge) {
      const ids = requiredLockIdsForEdgeEdit(
        displaySelectedEdge.id,
        projectedGraph.nodes,
      );
      for (const id of ids) {
        const lock = activeLocksMap.get(id);
        if (lock && !lock.ownedBySelf) {
          return { label: lock.label, color: lock.color };
        }
      }
      if (!localEdgeIdSet.has(displaySelectedEdge.id)) {
        return {
          label: remotePreview?.authorName ?? "otro usuario",
          color: "#38bdf8",
        };
      }
    }
    return null;
  }, [
    activeLocksMap,
    displaySelectedEdge,
    displaySelectedNode,
    localEdgeIdSet,
    localNodeIdSet,
    projectedGraph.nodes,
    remotePreview?.authorName,
  ]);

  const protectedEdgeIds = structuralRelationshipIds(projectedGraph.nodes);
  const protectedNodeIds = associationProtectedNodeIds(projectedGraph.nodes);
  const protectedSelection =
    projectedGraph.nodes.some(
      (node) => node.selected && protectedNodeIds.has(node.id),
    ) ||
    projectedGraph.edges.some(
      (edge) => edge.selected && protectedEdgeIds.has(edge.id),
    );
  const selectionLockedByOther = useMemo(() => {
    for (const node of projectedGraph.nodes) {
      if (!node.selected) continue;
      if (!localNodeIdSet.has(node.id)) {
        return {
          label: remotePreview?.authorName ?? "otro usuario",
          color: "#38bdf8",
        };
      }
      const ids = requiredLockIdsForNodeDelete(
        node.id,
        projectedGraph.nodes,
        projectedGraph.edges,
      );
      for (const id of ids) {
        const lock = activeLocksMap.get(id);
        if (lock && !lock.ownedBySelf) {
          return { label: lock.label, color: lock.color };
        }
      }
    }
    for (const edge of projectedGraph.edges) {
      if (!edge.selected) continue;
      if (!localEdgeIdSet.has(edge.id)) {
        return {
          label: remotePreview?.authorName ?? "otro usuario",
          color: "#38bdf8",
        };
      }
      const ids = requiredLockIdsForEdgeEdit(edge.id, projectedGraph.nodes);
      for (const id of ids) {
        const lock = activeLocksMap.get(id);
        if (lock && !lock.ownedBySelf) {
          return { label: lock.label, color: lock.color };
        }
      }
    }
    return null;
  }, [
    activeLocksMap,
    localEdgeIdSet,
    localNodeIdSet,
    projectedGraph.edges,
    projectedGraph.nodes,
    remotePreview?.authorName,
  ]);

  const activePropertiesFocusIds = useCallback((): string[] => {
    if (Date.now() - lastLockActivityAtRef.current >= LOCK_IDLE_RELEASE_MS) {
      return [];
    }
    const activeEl = document.activeElement;
    if (
      !(activeEl instanceof HTMLElement) ||
      !activeEl.closest(".diagram-properties")
    ) {
      return [];
    }
    if (selectedNodeId) {
      return requiredLockIdsForNodeEdit(selectedNodeId, nodesRef.current);
    }
    if (selectedEdgeIds.length === 1) {
      return requiredLockIdsForEdgeEdit(selectedEdgeIds[0], nodesRef.current);
    }
    return [];
  }, [selectedEdgeIds, selectedNodeId]);

  useEffect(() => {
    releaseUnusedOwnedLocks(activePropertiesFocusIds());
  }, [
    activePropertiesFocusIds,
    conversionOpen,
    propertiesOpen,
    releaseUnusedOwnedLocks,
    selectedEdgeIds,
    selectedNodeId,
  ]);

  useEffect(() => {
    const heartbeat = window.setInterval(() => {
      releaseUnusedOwnedLocks(activePropertiesFocusIds());
      const owned = lockStore.ownedElementIds();
      if (owned.length === 0) return;
      void renovarElementLocks(project.id, clientId, owned)
        .then((renewed) => {
          lockStore.upsertLocks(renewed, clientId);
        })
        .catch(() => {
          void syncLocks();
        });
    }, LOCK_HEARTBEAT_INTERVAL_MS);
    return () => {
      window.clearInterval(heartbeat);
    };
  }, [
    activePropertiesFocusIds,
    clientId,
    lockStore,
    project.id,
    releaseUnusedOwnedLocks,
    syncLocks,
  ]);

  useEffect(
    () => () => {
      void liberarElementLocks(project.id, clientId).catch(() => {});
    },
    [clientId, project.id],
  );

  useEffect(() => {
    const closePanels = (event: KeyboardEvent) => {
      if (
        (event.key === "Delete" || event.key === "Backspace") &&
        selectionLockedByOther
      ) {
        const target = event.target;
        const inEditableField =
          target instanceof HTMLInputElement ||
          target instanceof HTMLTextAreaElement ||
          target instanceof HTMLSelectElement ||
          (target instanceof HTMLElement && target.isContentEditable);
        if (!inEditableField) {
          event.preventDefault();
          showLockNotice(formatLockedByMessage(selectionLockedByOther.label));
          return;
        }
      }
      if (event.key !== "Escape") return;
      setSidebarOpen(false);
      setPropertiesOpen(false);
      releaseUnusedOwnedLocks();
    };
    window.addEventListener("keydown", closePanels);
    return () => window.removeEventListener("keydown", closePanels);
  }, [releaseUnusedOwnedLocks, selectionLockedByOther, showLockNotice]);

  const selectedAssociationColumns =
    displaySelectedNode?.data.association?.endpoints.map((endpoint) => {
      const referenced = projectedGraph.nodes.find(
        (node) => node.id === endpoint.entityId,
      );
      const primaryKey = referenced?.data.attributes.find(
        (attribute) => attribute.primaryKey,
      );
      return {
        name: endpoint.foreignKeyName,
        type: primaryKey ? postgresType(primaryKey.type) : "PK no definida",
      };
    }) ?? [];

  const emitValidNodePreview = useCallback(
    (nodeId: string) => {
      if (saveStatus === "forbidden") return;
      const target = nodesRef.current.find((item) => item.id === nodeId);
      if (!target) return;
      try {
        validateDiagramDocument(
          serializeDocument(nodesRef.current, edgesRef.current),
        );
      } catch {
        return;
      }
      publishLiveEvent("NODE_UPDATED", { node: serializeNode(target) });
    },
    [publishLiveEvent, saveStatus],
  );

  const scheduleNodeUpdatedPreview = useCallback(
    (nodeId: string) => {
      if (saveStatus === "forbidden") return;
      const tracker = nodeUpdateThrottleRef.current;
      const now = Date.now();
      const elapsed = now - tracker.lastSentAt;
      if (elapsed >= 80) {
        if (tracker.timer !== null) {
          window.clearTimeout(tracker.timer);
          tracker.timer = null;
        }
        tracker.lastSentAt = now;
        tracker.nodeId = null;
        emitValidNodePreview(nodeId);
        return;
      }
      tracker.nodeId = nodeId;
      if (tracker.timer === null) {
        tracker.timer = window.setTimeout(() => {
          tracker.timer = null;
          tracker.lastSentAt = Date.now();
          const pendingId = tracker.nodeId;
          tracker.nodeId = null;
          if (pendingId) emitValidNodePreview(pendingId);
        }, Math.max(1, 80 - elapsed));
      }
    },
    [emitValidNodePreview, saveStatus],
  );

  useEffect(() => {
    const throttle = new CursorPublisherThrottle(
      (point) => publishLiveEvent("CURSOR_MOVED", { x: point.x, y: point.y }),
      () => publishLiveEvent("CURSOR_LEFT", {}),
    );
    const tracker = nodeUpdateThrottleRef.current;
    cursorThrottleRef.current = throttle;
    const keepAliveInterval = window.setInterval(() => {
      if (document.visibilityState === "visible") throttle.keepAlive();
    }, 5_000);
    const handleVisibilityChange = () => {
      if (document.visibilityState !== "visible") throttle.leave();
    };
    document.addEventListener("visibilitychange", handleVisibilityChange);
    return () => {
      window.clearInterval(keepAliveInterval);
      document.removeEventListener("visibilitychange", handleVisibilityChange);
      throttle.dispose();
      cursorThrottleRef.current = null;
      if (tracker.timer !== null) {
        window.clearTimeout(tracker.timer);
        tracker.timer = null;
      }
    };
  }, [publishLiveEvent]);

  useEffect(() => {
    const synchronizer = new DiagramSynchronizer({
      read: () => obtenerDiagrama(project.id),
      verified: () => setSyncError(""),
      state: () => ({
        revision: serverRevisionRef.current,
        dirty: conflictRef.current || revisionRef.current !== persistedRevisionRef.current,
        dragging: nodesRef.current.some((node) => node.dragging),
        saving: saveInFlightRef.current,
        remotePreviewStale:
          remotePreviewAtRef.current !== null &&
          Date.now() - remotePreviewAtRef.current >= 8_000,
      }),
      apply: (saved, author) => {
        if (saved.proyectoId !== project.id) throw new Error("Proyecto de respuesta incorrecto.");
        const document = {
          ...saved.contenido,
          nodes: saved.contenido.nodes.map(normalizeStoredNode),
          edges: saved.contenido.edges.map(normalizeRelationshipEdge),
        };
        validateDiagramDocument(document);
        clearRemotePreview();
        updateConfirmedDocument(
          serializeDocument(document.nodes, document.edges),
        );
        const nodeIds = new Set(document.nodes.map((node) => node.id));
        const edgeIds = new Set(document.edges.map((edge) => edge.id));
        const activeSelectedNodes = new Set(
          nodesRef.current.filter((n) => n.selected && nodeIds.has(n.id)).map((n) => n.id),
        );
        const activeSelectedEdges = new Set(
          edgesRef.current.filter((e) => e.selected && edgeIds.has(e.id)).map((e) => e.id),
        );
        const reconciledNodes = document.nodes.map((node) =>
          activeSelectedNodes.has(node.id) ? { ...node, selected: true } : node,
        );
        const reconciledEdges = document.edges.map((edge) =>
          activeSelectedEdges.has(edge.id) ? { ...edge, selected: true } : edge,
        );
        nodesRef.current = reconciledNodes;
        edgesRef.current = reconciledEdges;
        setNodes(reconciledNodes);
        setEdges(reconciledEdges);
        serverRevisionRef.current = saved.revision;
        revisionRef.current += 1;
        persistedRevisionRef.current = revisionRef.current;
        setDocumentRevision(revisionRef.current);
        setSelectedNodeId((current) =>
          current && nodeIds.has(current) ? current : null,
        );
        setSelectedEdgeIds((current) =>
          current.filter((id) => edgeIds.has(id)),
        );
        setPendingProposal(null);
        setPendingImport(null);
        rejectedDocumentRef.current = null;
        agentSession.setDiagramId(saved.id);
        agentSession.record("DIAGRAM_SAVED");
        setSaveStatus("saved");
        setSyncMessage(author ? `Cambios guardados por ${author}.` : "Diagrama actualizado desde la versión confirmada del servidor.");
        void syncLocksRef.current?.();
      },
      reconcileDirty: (saved, author) => {
        if (saved.proyectoId !== project.id) return "conflict";
        if (conflictRef.current) return "conflict";
        let serverDocument: DiagramDocument;
        try {
          serverDocument = {
            ...saved.contenido,
            nodes: saved.contenido.nodes.map(normalizeStoredNode),
            edges: saved.contenido.edges.map(normalizeRelationshipEdge),
          };
          validateDiagramDocument(serverDocument);
        } catch {
          return "conflict";
        }
        const rebased = rebaseLocalOntoServer(
          confirmedDocumentRef.current,
          serverDocument,
          nodesRef.current,
          edgesRef.current,
        );
        if (rebased.outcome === "conflict") {
          return "conflict";
        }
        clearRemotePreview();
        updateConfirmedDocument(
          serializeDocument(serverDocument.nodes, serverDocument.edges),
        );
        serverRevisionRef.current = saved.revision;
        nodesRef.current = rebased.nodes;
        edgesRef.current = rebased.edges;
        setNodes(rebased.nodes);
        setEdges(rebased.edges);
        const survivingNodeIds = new Set(rebased.nodes.map((node) => node.id));
        const survivingEdgeIds = new Set(rebased.edges.map((edge) => edge.id));
        setSelectedNodeId((current) =>
          current && survivingNodeIds.has(current) ? current : null,
        );
        setSelectedEdgeIds((current) =>
          current.filter((id) => survivingEdgeIds.has(id)),
        );
        setPendingProposal(null);
        setPendingImport(null);
        agentSession.setDiagramId(saved.id);
        if (!rebased.stillDirty) {
          persistedRevisionRef.current = revisionRef.current;
          setSaveStatus("saved");
        } else {
          setSaveStatus("dirty");
        }
        setSyncMessage(
          author
            ? `Cambios de ${author} integrados automáticamente.`
            : "Cambios remotos integrados automáticamente.",
        );
        void syncLocksRef.current?.();
        return "rebased";
      },
      conflict: () => {
        conflictRef.current = true;
        setSaveStatus("conflict");
        setPendingProposal(null);
        setAiError("Otro usuario guardó una versión más reciente. Tus cambios locales se conservaron; descarga el borrador antes de recargar.");
      },
      error: (error) => {
        if (isNotFoundError(error) && serverRevisionRef.current === null) return;
        if (isForbiddenError(error)) {
          conflictRef.current = true;
          setSaveStatus("forbidden");
        }
        if (isUnauthorizedError(error)) {
          localStorage.removeItem("token");
          window.location.assign("/login");
          return;
        }
        setSyncError("No se pudo sincronizar. El borrador local se conserva; se reintentará la lectura.");
      },
    });
    synchronizerRef.current = synchronizer;
    // Covers the initial GET/subscribe gap, reconnect, missed notifications and tab resume.
    void synchronizer.refresh();
    const interval = window.setInterval(() => {
      if (document.visibilityState === "visible") void synchronizer.refresh();
    }, 15_000);
    window.addEventListener("focus", synchronize);
    window.addEventListener("online", synchronize);
    return () => {
      synchronizer.dispose();
      synchronizerRef.current = null;
      window.clearInterval(interval);
      window.removeEventListener("focus", synchronize);
      window.removeEventListener("online", synchronize);
    };
  }, [
    agentSession,
    clearRemotePreview,
    project.id,
    setEdges,
    setNodes,
    synchronize,
    updateConfirmedDocument,
  ]);

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
    if (saveStatus !== "forbidden") {
      publishLiveEvent("DIAGRAM_BATCH_APPLIED", {
        document: serializeDocument(result.nodes, result.edges),
      });
    }
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
      await synchronizerRef.current?.refresh();
      await authorizeProjectAgentEdit(project.id);
      const refreshedAuthorizationError = proposalAcceptanceError(
        proposal.revision,
        revisionRef.current,
        saveStatus !== "forbidden" && !conflictRef.current,
      );
      if (refreshedAuthorizationError) {
        setAiError(refreshedAuthorizationError);
        return;
      }
      const preview = applyDiagramOperations(
        nodesRef.current,
        edgesRef.current,
        proposal.operations,
      );
      const aiLockIds = diffLocalElementsAgainstBase(
        confirmedDocumentRef.current,
        preview.nodes,
        preview.edges,
      ).lockProtectedIds;
      if (!(await ensureLocks(aiLockIds))) {
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
      conflictRef.current || revisionRef.current !== persistedRevisionRef.current ||
      saveInFlightRef.current ||
      nodesRef.current.some((n) => n.dragging)
    ) {
      setAiError(
        "Espera a que termine el guardado del diagrama antes de usar IA.",
      );
      return;
    }
    const request = new AbortController();
    const requestedRevision = revisionRef.current;
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
      await synchronizerRef.current?.refresh();
      if (conflictRef.current || requestedRevision !== revisionRef.current) {
        setAiError("El diagrama cambió durante la consulta. Solicita una propuesta nueva.");
        return;
      }
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
      const localIds = new Set(nodesRef.current.map((node) => node.id));
      let previewDimensionsUpdates:
        | Record<string, { width: number; height: number }>
        | null = null;
      for (const change of changes) {
        if (
          change.type === "dimensions" &&
          change.dimensions &&
          !localIds.has(change.id)
        ) {
          previewDimensionsUpdates ??= {};
          previewDimensionsUpdates[change.id] = {
            width: change.dimensions.width,
            height: change.dimensions.height,
          };
        }
      }
      if (previewDimensionsUpdates) {
        const updates = previewDimensionsUpdates;
        setPreviewMeasured((current) => {
          let changed = false;
          const next = { ...current };
          for (const [id, dims] of Object.entries(updates)) {
            if (
              current[id]?.width !== dims.width ||
              current[id]?.height !== dims.height
            ) {
              next[id] = dims;
              changed = true;
            }
          }
          return changed ? next : current;
        });
      }

      const protectedIds = associationProtectedNodeIds(nodesRef.current);
      const accepted = changes.filter((change) => {
        if (change.type === "remove") {
          if (protectedIds.has(change.id) || !localIds.has(change.id)) {
            return false;
          }
          const foreign = lockStore.findForeignLock(
            requiredLockIdsForNodeDelete(
              change.id,
              nodesRef.current,
              edgesRef.current,
            ),
          );
          if (foreign) {
            showLockNotice(formatLockedByMessage(foreign.label));
            return false;
          }
        }
        if (change.type === "position" && !localIds.has(change.id)) {
          return false;
        }
        return true;
      });
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
    [lockStore, markDirty, onNodesChange, showLockNotice],
  );

  const handleEdgesChange = useCallback(
    (changes: EdgeChange<DiagramEdge>[]) => {
      const localIds = new Set(edgesRef.current.map((edge) => edge.id));
      const structuralIds = structuralRelationshipIds(nodesRef.current);
      const accepted = changes.filter((change) => {
        if (change.type === "remove") {
          if (structuralIds.has(change.id) || !localIds.has(change.id)) {
            return false;
          }
          const foreign = lockStore.findForeignLock(
            requiredLockIdsForEdgeEdit(change.id, nodesRef.current),
          );
          if (foreign) {
            showLockNotice(formatLockedByMessage(foreign.label));
            return false;
          }
        }
        return true;
      });
      onEdgesChange(accepted);
      if (accepted.some((change) => change.type !== "select")) markDirty();
    },
    [lockStore, markDirty, onEdgesChange, showLockNotice],
  );

  const onConnect = useCallback(
    (connection: Connection) => {
      if (
        !connection.source ||
        !connection.target ||
        saveStatus === "forbidden"
      ) {
        return;
      }
      const lockIds = requiredLockIdsForConnection(
        connection.source,
        connection.target,
      );
      const foreign = lockStore.findForeignLock(lockIds);
      if (foreign) {
        showLockNotice(formatLockedByMessage(foreign.label));
        return;
      }
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
      void (async () => {
        if (!(await ensureLocks(lockIds))) return;
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
            publishLiveEvent(kind, { edge: serializeEdge(updated) });
            agentSession.record(kind, { edgeId: updated.id });
          },
        });
      })();
    },
    [
      agentSession,
      ensureLocks,
      lockStore,
      markDirty,
      publishLiveEvent,
      saveStatus,
      selectedRelationKind,
      setEdges,
      showLockNotice,
    ],
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
    const nextNodes = [
      ...nodesRef.current.map((item) => ({ ...item, selected: false })),
      node,
    ];
    nodesRef.current = nextNodes;
    setNodes(nextNodes);
    setSelectedNodeId(id);
    setSelectedEdgeIds([]);
    if (saveStatus !== "forbidden") {
      void ensureLocks([id]);
      publishLiveEvent("NODE_CREATED", { node: serializeNode(node) });
    }
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
    if (!selectedNode || saveStatus === "forbidden") {
      if (selectedLockedBy) {
        showLockNotice(formatLockedByMessage(selectedLockedBy.label));
      }
      return;
    }
    const lockIds = requiredLockIdsForNodeEdit(
      selectedNode.id,
      nodesRef.current,
    );
    const foreign = lockStore.findForeignLock(lockIds);
    if (foreign) {
      showLockNotice(formatLockedByMessage(foreign.label));
      return;
    }
    const previousSnapshot = selectedNode;
    const alreadyOwned = lockStore.areAllOwnedBySelf(lockIds);
    lastLockActivityAtRef.current = Date.now();
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
    scheduleNodeUpdatedPreview(updated.id);
    agentSession.record("NODE_UPDATED", { nodeId: updated.id });
    markDirty();
    if (!alreadyOwned) {
      void ensureLocks(lockIds).then((granted) => {
        if (!granted) {
          const restored = nodesRef.current.map((node) =>
            node.id === previousSnapshot.id ? previousSnapshot : node,
          );
          nodesRef.current = restored;
          setNodes(restored);
        }
      });
    }
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

  async function deleteSelection() {
    if (saveStatus === "forbidden") return;
    if (selectionLockedByOther) {
      showLockNotice(formatLockedByMessage(selectionLockedByOther.label));
      return;
    }
    const currentNodes = nodesRef.current;
    const currentEdges = edgesRef.current;

    const deletedNodes = currentNodes.filter((node) => node.selected);
    const deletedEdges = currentEdges.filter((edge) => edge.selected);

    const requiredDeleteLockIds = new Set<string>();
    for (const node of deletedNodes) {
      for (const id of requiredLockIdsForNodeDelete(
        node.id,
        currentNodes,
        currentEdges,
      )) {
        requiredDeleteLockIds.add(id);
      }
    }
    for (const edge of deletedEdges) {
      for (const id of requiredLockIdsForEdgeEdit(edge.id, currentNodes)) {
        requiredDeleteLockIds.add(id);
      }
    }
    if (
      requiredDeleteLockIds.size > 0 &&
      !(await ensureLocks([...requiredDeleteLockIds]))
    ) {
      return;
    }

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

      if (!confirmed) {
        releaseUnusedOwnedLocks();
        return;
      }

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

      nodesRef.current = nextNodes;
      edgesRef.current = nextEdges;

      setNodes(nextNodes);
      setEdges(nextEdges);
      setSelectedNodeId(null);
      setSelectedEdgeIds([]);

      markDirty();
      publishLiveEvent("DIAGRAM_BATCH_APPLIED", {
        document: serializeDocument(nextNodes, nextEdges),
      });

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
      publishLiveEvent("NODE_DELETED", { nodeId: node.id }),
    );
    deletedEdges.forEach((edge) =>
      publishLiveEvent("EDGE_DELETED", { edgeId: edge.id }),
    );

    deletedNodes.forEach((node) =>
      agentSession.record("NODE_DELETED", { nodeId: node.id }),
    );

    deletedEdges.forEach((edge) =>
      agentSession.record("EDGE_DELETED", { edgeId: edge.id }),
    );

    const deletedNodeIds = new Set(deletedNodes.map((node) => node.id));

    const nextNodes = nodesRef.current.filter((node) => !node.selected);
    const nextEdges = edgesRef.current.filter(
      (edge) =>
        !edge.selected &&
        !deletedNodeIds.has(edge.source) &&
        !deletedNodeIds.has(edge.target),
    );
    nodesRef.current = nextNodes;
    edgesRef.current = nextEdges;
    setNodes(nextNodes);
    setEdges(nextEdges);

    setSelectedNodeId(null);
    setSelectedEdgeIds([]);
    markDirty();
  }

  async function confirmAssociationConversion(entityName: string) {
    if (!selectedEdge || saveStatus === "forbidden") return;
    const lockIds = requiredLockIdsForAssociationConversion(selectedEdge);
    if (!(await ensureLocks(lockIds))) return;
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
      publishLiveEvent("DIAGRAM_BATCH_APPLIED", {
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
      if (conflictRef.current) return;
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
        clientId,
      )
        .then((saved) => {
          rejectedDocumentRef.current = null;
          clearRemotePreview();
          const savedDocument: DiagramDocument = {
            ...saved.contenido,
            nodes: saved.contenido.nodes.map(normalizeStoredNode),
            edges: saved.contenido.edges.map(normalizeRelationshipEdge),
          };
          const canonicalSaved = serializeDocument(
            savedDocument.nodes,
            savedDocument.edges,
          );
          updateConfirmedDocument(canonicalSaved);
          serverRevisionRef.current = saved.revision;
          conflictRef.current = false;
          agentSession.setDiagramId(saved.id);
          agentSession.record("DIAGRAM_SAVED");

          if (canonicalJson(canonicalSaved) !== canonicalJson(document)) {
            const postSaveRebase = rebaseLocalOntoServer(
              document,
              savedDocument,
              nodesRef.current,
              edgesRef.current,
            );
            if (postSaveRebase.outcome !== "conflict") {
              nodesRef.current = postSaveRebase.nodes;
              edgesRef.current = postSaveRebase.edges;
              setNodes(postSaveRebase.nodes);
              setEdges(postSaveRebase.edges);
              if (!postSaveRebase.stillDirty) {
                persistedRevisionRef.current = revisionRef.current;
                setSaveStatus("saved");
              } else {
                setSaveStatus("dirty");
              }
            } else {
              persistedRevisionRef.current = savedRevision;
              setSaveStatus(
                revisionRef.current === savedRevision ? "saved" : "dirty",
              );
            }
          } else {
            persistedRevisionRef.current = savedRevision;
            setSaveStatus(
              revisionRef.current === savedRevision ? "saved" : "dirty",
            );
          }

          releaseUnusedOwnedLocks(activePropertiesFocusIds());
          return true;
        })
        .catch((requestError: unknown) => {
          agentSession.record("SAVE_FAILED");
          const lockRejection = extractElementLockedRejection(requestError);
          if (lockRejection) {
            showLockNotice(lockRejection.message);
            void syncLocks();
            setSaveStatus("error");
            return false;
          }
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
              "Otro usuario guardó una versión más reciente. Tus cambios locales se conservaron; descarga el borrador antes de recargar.",
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
                : "El diagrama contiene una estructura inválida y no fue guardado.",
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
    [
      activePropertiesFocusIds,
      agentSession,
      clearRemotePreview,
      clientId,
      project.id,
      releaseUnusedOwnedLocks,
      setEdges,
      setNodes,
      showLockNotice,
      syncLocks,
      updateConfirmedDocument,
    ],
  );

  async function handleAgentAsk(
    message: string,
    conversation: AgentConversationMessage[],
  ) {
    if (conflictRef.current || revisionRef.current !== persistedRevisionRef.current || saveInFlightRef.current) {
      throw new Error("Guarda o resincroniza el diagrama antes de consultar al agente.");
    }
    const requestedRevision = revisionRef.current;
    try {
      const response = await askProjectAgent(project.id, {
        message,
        selectedNodeId,
        selectedEdgeId: selectedEdge?.id ?? null,
        recentEvents: agentSession.snapshot(),
        conversation,
      });
      await synchronizerRef.current?.refresh();
      if (conflictRef.current || requestedRevision !== revisionRef.current) {
        throw new DiagramOperationError("El diagrama cambió durante la consulta. Solicita una propuesta nueva.");
      }
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
      if (error instanceof DiagramOperationError) throw error;
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

  async function applyPendingImport() {
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
      const importLockIds = diffLocalElementsAgainstBase(
        confirmedDocumentRef.current,
        document.nodes,
        document.edges,
      ).lockProtectedIds;
      if (!(await ensureLocks(importLockIds))) {
        return;
      }
      nodesRef.current = document.nodes;
      edgesRef.current = document.edges;
      setNodes(document.nodes);
      setEdges(document.edges);
      setSelectedNodeId(null);
      setSelectedEdgeIds([]);
      setPendingImport(null);
      setImportError("");
      markDirty();
      if (saveStatus !== "forbidden") {
        publishLiveEvent("DIAGRAM_BATCH_APPLIED", {
          document: serializeDocument(document.nodes, document.edges),
        });
      }
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
    <ElementLocksProvider store={lockStore}>
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
        onDeleteSelection={() => void deleteSelection()}
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
            Existe una versión confirmada más reciente. Tus cambios locales
            siguen en este navegador.
          </span>
          <button type="button" onClick={() => triggerBlobDownload(
            new Blob([JSON.stringify(serializeDocument(nodesRef.current, edgesRef.current), null, 2)],
              { type: "application/json" }),
            `logicdraft-${project.id}-borrador.json`,
          )}>Descargar borrador local</button>
          <button
            type="button"
            onClick={() => {
              if (
                window.confirm(
                  "Recargar reemplazará los cambios locales no guardados. Descarga el borrador primero si quieres conservarlos. ¿Continuar?",
                )
              )
                window.location.reload();
            }}
          >
            Recargar versión del servidor
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
      {lockNotice && (
        <div className="diagram-lock-notice" role="status">
          {lockNotice}
        </div>
      )}
      {(syncError || syncMessage) && !lockNotice && saveStatus !== "conflict" && (
        <div className={`diagram-import-status${syncError ? " is-error" : ""}`} role="status">{syncError || syncMessage}</div>
      )}
      <ProjectPresence status={status} collaborators={collaborators} />
      <section
        className="diagram-canvas"
        aria-label="Canvas del diagrama"
        onPointerDownCapture={(event) => {
          const targetEl =
            event.target instanceof HTMLElement
              ? event.target.closest(".react-flow__node[data-id]")
              : null;
          const nodeId = targetEl?.getAttribute("data-id");
          if (!nodeId) {
            lockedDragProbeRef.current = null;
            return;
          }
          const foreign = lockStore.findForeignLock(
            requiredLockIdsForNodeEdit(nodeId, nodesRef.current),
          );
          if (foreign) {
            lockedDragProbeRef.current = {
              nodeId,
              ownerLabel: foreign.label,
              startX: event.clientX,
              startY: event.clientY,
              notified: false,
            };
          } else {
            lockedDragProbeRef.current = null;
          }
        }}
        onPointerMove={(event) => {
          const probe = lockedDragProbeRef.current;
          if (
            probe &&
            !probe.notified &&
            Math.hypot(
              event.clientX - probe.startX,
              event.clientY - probe.startY,
            ) >= 5
          ) {
            probe.notified = true;
            showLockNotice(formatLockedByMessage(probe.ownerLabel));
          }
          const point = screenToFlowPosition({
            x: event.clientX,
            y: event.clientY,
          });
          cursorThrottleRef.current?.move(point);
        }}
        onPointerUp={() => {
          lockedDragProbeRef.current = null;
        }}
        onPointerCancel={() => {
          lockedDragProbeRef.current = null;
        }}
        onPointerLeave={() => {
          lockedDragProbeRef.current = null;
          cursorThrottleRef.current?.leave();
        }}
      >
        <ReactFlow<EntityFlowNode, DiagramEdge>
          nodes={renderedNodes}
          edges={renderedEdges}
          connectionMode={ConnectionMode.Loose}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onNodesChange={handleNodesChange}
          onEdgesChange={handleEdgesChange}
          onConnect={onConnect}
          onSelectionChange={handleSelectionChange}
          onNodeDoubleClick={(_, node) => {
            const foreign = lockStore.findForeignLock(
              requiredLockIdsForNodeEdit(node.id, nodesRef.current),
            );
            if (foreign) {
              showLockNotice(formatLockedByMessage(foreign.label));
            }
          }}
          onNodeDragStart={(_, node) => {
            if (saveStatus === "forbidden") return;
            dragInitialPositionsRef.current[node.id] = { ...node.position };
            dragDeniedRef.current.delete(node.id);
            const lockIds = requiredLockIdsForNodeEdit(
              node.id,
              nodesRef.current,
            );
            void ensureLocks(lockIds).then((granted) => {
              if (!granted) {
                dragDeniedRef.current.add(node.id);
                const origin = dragInitialPositionsRef.current[node.id];
                if (origin) {
                  const reverted = nodesRef.current.map((item) =>
                    item.id === node.id
                      ? { ...item, position: { ...origin }, dragging: false }
                      : item,
                  );
                  nodesRef.current = reverted;
                  setNodes(reverted);
                }
              }
            });
          }}
          onNodeDrag={(_, node) => {
            if (saveStatus === "forbidden") return;
            const lockIds = requiredLockIdsForNodeEdit(
              node.id,
              nodesRef.current,
            );
            if (
              dragDeniedRef.current.has(node.id) ||
              lockStore.findForeignLock(lockIds)
            ) {
              const origin = dragInitialPositionsRef.current[node.id];
              if (origin) {
                const reverted = nodesRef.current.map((item) =>
                  item.id === node.id
                    ? { ...item, position: { ...origin }, dragging: false }
                    : item,
                );
                nodesRef.current = reverted;
                setNodes(reverted);
              }
              return;
            }
            lastLockActivityAtRef.current = Date.now();
            const now = Date.now();
            const last = lastDragPublishRef.current[node.id] ?? 0;
            if (now - last < 50) return;
            lastDragPublishRef.current[node.id] = now;
            publishLiveEvent("NODE_MOVED", {
              nodeId: node.id,
              position: { x: node.position.x, y: node.position.y },
              dragging: true,
            });
          }}
          onNodeDragStop={(_, node) => {
            delete lastDragPublishRef.current[node.id];
            const origin = dragInitialPositionsRef.current[node.id];
            delete dragInitialPositionsRef.current[node.id];
            const lockIds = requiredLockIdsForNodeEdit(
              node.id,
              nodesRef.current,
            );
            if (
              dragDeniedRef.current.has(node.id) ||
              lockStore.findForeignLock(lockIds)
            ) {
              dragDeniedRef.current.delete(node.id);
              if (origin) {
                const reverted = nodesRef.current.map((item) =>
                  item.id === node.id
                    ? { ...item, position: { ...origin }, dragging: false }
                    : item,
                );
                nodesRef.current = reverted;
                setNodes(reverted);
              }
              return;
            }
            if (saveStatus !== "forbidden") {
              publishLiveEvent("NODE_MOVED", {
                nodeId: node.id,
                position: { x: node.position.x, y: node.position.y },
                dragging: false,
              });
            }
            agentSession.record("NODE_UPDATED", { nodeId: node.id });
            markDirty();
          }}
          onNodesDelete={(deleted) =>
            deleted.forEach((node) => {
              if (associationProtectedNodeIds(nodesRef.current).has(node.id))
                return;
              if (saveStatus !== "forbidden") {
                void ensureLocks([node.id]);
                publishLiveEvent("NODE_DELETED", { nodeId: node.id });
              }
              agentSession.record("NODE_DELETED", { nodeId: node.id });
            })
          }
          onEdgesDelete={(deleted) =>
            deleted.forEach((edge) => {
              if (structuralRelationshipIds(nodesRef.current).has(edge.id))
                return;
              if (saveStatus !== "forbidden") {
                void ensureLocks([edge.id]);
                publishLiveEvent("EDGE_DELETED", { edgeId: edge.id });
              }
              agentSession.record("EDGE_DELETED", { edgeId: edge.id });
            })
          }
          deleteKeyCode={
            protectedSelection || selectionLockedByOther
              ? null
              : ["Backspace", "Delete"]
          }
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
          <RemoteCursorsOverlay store={cursorStore} />
        </ReactFlow>
      </section>
      <DiagramPropertiesPanel
        onClose={() => {
          setPropertiesOpen(false);
          releaseUnusedOwnedLocks();
        }}
        edge={displaySelectedEdge}
        joinTable={selectedJoinTable}
        structuralRelationship={Boolean(
          displaySelectedEdge && protectedEdgeIds.has(displaySelectedEdge.id),
        )}
        lockedBy={selectedLockedBy}
        onAttemptLockedEdit={(ownerName) =>
          showLockNotice(formatLockedByMessage(ownerName))
        }
        onEditIntent={() => {
          if (selectedNode) {
            void ensureLocks(
              requiredLockIdsForNodeEdit(selectedNode.id, nodesRef.current),
            );
          } else if (
            selectedEdge &&
            !protectedEdgeIds.has(selectedEdge.id)
          ) {
            void ensureLocks(
              requiredLockIdsForEdgeEdit(selectedEdge.id, nodesRef.current),
            );
          }
        }}
        onConvertAssociation={() => {
          if (!selectedEdge) return;
          void ensureLocks(
            requiredLockIdsForAssociationConversion(selectedEdge),
          ).then((granted) => {
            if (!granted) return;
            setConversionError("");
            setConversionOpen(true);
          });
        }}
        onChangeCardinality={(end, value) => {
          if (!selectedEdge) {
            if (selectedLockedBy) {
              showLockNotice(formatLockedByMessage(selectedLockedBy.label));
            }
            return;
          }
          if (protectedEdgeIds.has(selectedEdge.id)) {
            setAiMessage(
              "La relacion estructural no puede editarse individualmente.",
            );
            return;
          }
          const edgeLockIds = requiredLockIdsForEdgeEdit(
            selectedEdge.id,
            nodesRef.current,
          );
          const foreign = lockStore.findForeignLock(edgeLockIds);
          if (foreign) {
            showLockNotice(formatLockedByMessage(foreign.label));
            return;
          }
          lastLockActivityAtRef.current = Date.now();
          if (!lockStore.areAllOwnedBySelf(edgeLockIds)) {
            void ensureLocks(edgeLockIds);
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
                if (saveStatus !== "forbidden") {
                  publishLiveEvent(kind, { edge: serializeEdge(edge) });
                }
                agentSession.record(kind, { edgeId: edge.id });
              },
            },
          );
          if (!changed)
            setAiMessage(
              "Sin cambios: la relación es equivalente a otra existente.",
            );
        }}
        onChangeRelationshipData={(changes) => {
          if (!selectedEdge) {
            if (selectedLockedBy) {
              showLockNotice(formatLockedByMessage(selectedLockedBy.label));
            }
            return;
          }
          if (protectedEdgeIds.has(selectedEdge.id)) {
            setAiMessage(
              "La relacion estructural no puede editarse individualmente.",
            );
            return;
          }
          const edgeLockIds = requiredLockIdsForEdgeEdit(
            selectedEdge.id,
            nodesRef.current,
          );
          const foreign = lockStore.findForeignLock(edgeLockIds);
          if (foreign) {
            showLockNotice(formatLockedByMessage(foreign.label));
            return;
          }
          const previousEdgesSnapshot = edgesRef.current;
          const alreadyOwned = lockStore.areAllOwnedBySelf(edgeLockIds);
          lastLockActivityAtRef.current = Date.now();
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
                if (saveStatus !== "forbidden") {
                  publishLiveEvent(kind, { edge: serializeEdge(edge) });
                }
                agentSession.record(kind, { edgeId: edge.id });
              },
            },
          );
          if (!changed)
            setAiMessage(
              "Sin cambios: revisa que el nombre distinga la relación.",
            );
          else if (!alreadyOwned) {
            void ensureLocks(edgeLockIds).then((granted) => {
              if (!granted) {
                edgesRef.current = previousEdgesSnapshot;
                setEdges(previousEdgesSnapshot);
              }
            });
          }
        }}
        entity={displaySelectedNode?.data ?? null}
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
        projectId={project.id}
        store={conversationStore}
        clientId={clientId}
        collaborationStatus={status}
        nodes={projectedGraph.nodes}
        edges={projectedGraph.edges}
        selectedNodeId={selectedNodeId}
        selectedEdgeId={selectedEdge?.id ?? null}
        getRecentEvents={() => agentSession.snapshot()}
        saveStatus={saveStatus}
        onLegacyAsk={handleAgentAsk}
        onBeforeAcceptProposal={async () => {
          if (conflictRef.current) return false;
          if (
            saveInFlightRef.current ||
            revisionRef.current !== persistedRevisionRef.current
          ) {
            await handleSave(true);
          }
          return (
            !conflictRef.current &&
            revisionRef.current === persistedRevisionRef.current
          );
        }}
        onProposalApplied={(proposal) => {
          void synchronizerRef.current?.refresh(
            typeof proposal.aplicadaRevision === "number"
              ? { name: "LogicDraft IA", revision: proposal.aplicadaRevision }
              : undefined,
          );
          setAiMessage("Propuesta compartida aplicada al diagrama.");
        }}
        onLockConflict={(message) => {
          showLockNotice(message);
          void syncLocks();
        }}
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
          releaseUnusedOwnedLocks();
        }}
        onConfirm={(entityName) => void confirmAssociationConversion(entityName)}
      />
      <DiagramAiProposalDialog
        operations={pendingProposal?.operations ?? []}
        impact={pendingProposal?.impact ?? []}
        onCancel={() => {
          setPendingProposal(null);
          setAiMessage("Propuesta cancelada; no se realizaron cambios");
          releaseUnusedOwnedLocks();
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
            releaseUnusedOwnedLocks();
          }}
          onApply={() => void applyPendingImport()}
        />
      )}
    </div>
    </ElementLocksProvider>
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
