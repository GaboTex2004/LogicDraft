import { Client, type IMessage } from "@stomp/stompjs";
import type {
  CollaborationEvent,
  CollaborationEventType,
  DiagramCollaborationEventType,
} from "../types/collaboration.types";

interface CollaborationCallbacks {
  onConnect: () => void;
  onDisconnect: () => void;
  onEvent: (event: CollaborationEvent) => void;
  onError: (message: string) => void;
}

export interface ProjectCollaborationConnection {
  sendPing: (message: string) => void;
  publishEvent: (type: DiagramCollaborationEventType, payload: unknown) => void;
  disconnect: () => Promise<void>;
}

const eventTypes = new Set<CollaborationEventType>([
  "USER_JOINED",
  "USER_LEFT",
  "PING",
  "NODE_CREATED",
  "NODE_MOVED",
  "NODE_UPDATED",
  "NODE_DELETED",
  "EDGE_CREATED",
  "EDGE_UPDATED",
  "EDGE_DELETED",
  "DIAGRAM_BATCH_APPLIED",
  "DIAGRAM_SAVED",
]);

function websocketUrl(): string {
  const configuredUrl: unknown = import.meta.env.VITE_WS_URL;

  if (typeof configuredUrl !== "string" || configuredUrl.length === 0) {
    throw new Error("VITE_WS_URL no está configurada");
  }

  const url = new URL(configuredUrl, window.location.origin);

  if (url.protocol === "https:") {
    url.protocol = "wss:";
  } else if (url.protocol === "http:") {
    url.protocol = "ws:";
  }

  if (url.protocol !== "ws:" && url.protocol !== "wss:") {
    throw new Error("VITE_WS_URL debe utilizar WebSocket");
  }

  return url.toString();
}

function parseEvent(message: IMessage): CollaborationEvent | null {
  try {
    const value: unknown = JSON.parse(message.body);
    if (typeof value !== "object" || value === null) return null;
    const candidate = value as Record<string, unknown>;
    if (
      typeof candidate.type !== "string" ||
      !eventTypes.has(candidate.type as CollaborationEventType)
    )
      return null;
    if (
      typeof candidate.eventId !== "string" ||
      typeof candidate.timestamp !== "string"
    )
      return null;
    if (
      typeof candidate.userId !== "number" ||
      typeof candidate.name !== "string" ||
      typeof candidate.projectId !== "number"
    )
      return null;
    if (
      candidate.clientId !== null &&
      candidate.clientId !== undefined &&
      typeof candidate.clientId !== "string"
    )
      return null;
    if (
      candidate.message !== null &&
      candidate.message !== undefined &&
      typeof candidate.message !== "string"
    )
      return null;
    return {
      eventId: candidate.eventId,
      type: candidate.type as CollaborationEventType,
      userId: candidate.userId,
      name: candidate.name,
      projectId: candidate.projectId,
      clientId:
        typeof candidate.clientId === "string" ? candidate.clientId : null,
      timestamp: candidate.timestamp,
      payload: candidate.payload ?? null,
      message: typeof candidate.message === "string" ? candidate.message : null,
    };
  } catch {
    return null;
  }
}

export function connectToProject(
  projectId: number,
  token: string,
  clientId: string,
  callbacks: CollaborationCallbacks,
): ProjectCollaborationConnection {
  const client = new Client({
    brokerURL: websocketUrl(),
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 5_000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    onConnect: () => {
      client.subscribe(`/topic/proyectos/${projectId}`, (message) => {
        const event = parseEvent(message);
        if (event?.projectId === projectId) callbacks.onEvent(event);
      });
      callbacks.onConnect();
    },
    onStompError: (frame) =>
      callbacks.onError(frame.headers.message ?? "Error STOMP"),
    onWebSocketError: () =>
      callbacks.onError("No se pudo conectar con colaboración"),
    onWebSocketClose: callbacks.onDisconnect,
  });
  client.activate();

  return {
    sendPing(message) {
      if (!client.connected) return;
      client.publish({
        destination: `/app/proyectos/${projectId}/eventos`,
        body: JSON.stringify({ type: "PING", clientId, message }),
      });
    },
    publishEvent(type, payload) {
      if (!client.connected) return;
      client.publish({
        destination: `/app/proyectos/${projectId}/eventos`,
        body: JSON.stringify({ type, clientId, payload }),
      });
    },
    async disconnect() {
      await client.deactivate();
    },
  };
}
