import { useCallback, useEffect, useRef, useState } from 'react'
import { connectToProject, type ProjectCollaborationConnection } from '../services/collaborationService'
import { ElementLockStore } from '../services/elementLocks'
import { RemoteCursorStore } from '../services/remoteCursors'
import {
  isConversationCollaborationEvent,
  type CollaborationEvent,
  type CollaborationStatus,
  type Collaborator,
  type EphemeralCollaborationEventType,
} from '../types/collaboration.types'

export function useProjectCollaboration(
  projectId: number,
  onDiagramEvent?: (event: CollaborationEvent) => void,
  onConnected?: () => void,
) {
  const connectionRef = useRef<ProjectCollaborationConnection | null>(null)
  const [clientId] = useState(() => crypto.randomUUID())
  const [cursorStore] = useState(() => new RemoteCursorStore())
  const [lockStore] = useState(() => new ElementLockStore())
  const onDiagramEventRef = useRef(onDiagramEvent)
  const onConnectedRef = useRef(onConnected)
  const [status, setStatus] = useState<CollaborationStatus>(() =>
    localStorage.getItem('token') ? 'connecting' : 'error')
  const [collaborators, setCollaborators] = useState<Collaborator[]>([])
  const [lastPing, setLastPing] = useState<CollaborationEvent | null>(null)
  const seenEventIds = useRef(new Set<string>())

  useEffect(() => {
    onDiagramEventRef.current = onDiagramEvent
    onConnectedRef.current = onConnected
  }, [onDiagramEvent, onConnected])

  useEffect(() => {
    const pruneInterval = window.setInterval(() => {
      const now = Date.now()
      cursorStore.pruneExpired(now)
      lockStore.pruneExpired(now)
    }, 2_000)
    return () => {
      window.clearInterval(pruneInterval)
      cursorStore.clear()
      lockStore.clear()
    }
  }, [cursorStore, lockStore])

  useEffect(() => {
    const token = localStorage.getItem('token')
    if (!token) return
    seenEventIds.current.clear()
    cursorStore.clear()
    lockStore.clear()
    let active = true

    const connection = connectToProject(projectId, token, clientId, {
      onConnect: () => {
        if (!active) return
        console.info('[WS] conectado')
        setStatus('connected')
        onConnectedRef.current?.()
      },
      onDisconnect: () => {
        if (!active) return
        setStatus('disconnected')
        setCollaborators([])
        cursorStore.clear()
      },
      onError: () => { if (active) setStatus('error') },
      onEvent: (event) => {
        if (!active || event.projectId !== projectId) return

        // High-frequency cursor events bypass the 1000-slot commit deduplication ring
        // so cursor frames never evict DIAGRAM_COMMITTED event IDs.
        if (event.type === 'CURSOR_MOVED' || event.type === 'CURSOR_LEFT') {
          cursorStore.applyEvent(event, clientId)
          return
        }

        if (seenEventIds.current.has(event.eventId)) return
        seenEventIds.current.add(event.eventId)
        if (seenEventIds.current.size > 1000) {
          const oldest = seenEventIds.current.values().next().value
          if (oldest) seenEventIds.current.delete(oldest)
        }

        if (event.type === 'ELEMENT_LOCKED' || event.type === 'ELEMENT_UNLOCKED') {
          lockStore.applyEvent(event, clientId)
          return
        }

        if (event.type === 'USER_JOINED') {
          setCollaborators((current) => {
            const withoutUser = current.filter((user) => user.userId !== event.userId)
            return [...withoutUser, { userId: event.userId, name: event.name }]
          })
        } else if (event.type === 'USER_LEFT') {
          cursorStore.applyEvent(event, clientId)
          lockStore.applyEvent(event, clientId)
          setCollaborators((current) => current.filter((user) => user.userId !== event.userId))
          onDiagramEventRef.current?.(event)
        } else if (event.type === 'PING') {
          setLastPing(event)
          console.info(`[Collaboration] PING de ${event.name}:`, event.message)
        } else if (event.type === 'DIAGRAM_COMMITTED') {
          // Own notifications also trigger reconciliation: a REST response may be lost.
          // The revision gate prevents applying our own commit twice.
          console.info(`[WS] ${event.type} recibido`)
          onDiagramEventRef.current?.(event)
        } else if (isConversationCollaborationEvent(event.type)) {
          // Shared conversation events are server-authoritative and idempotent;
          // deliver to all tabs (including own clientId) so typing status, proposals,
          // and multi-tab states stay synchronized without affecting diagram events.
          onDiagramEventRef.current?.(event)
        } else if (event.clientId !== clientId) {
          onDiagramEventRef.current?.(event)
        }
      },
    })
    connectionRef.current = connection

    return () => {
      active = false
      connectionRef.current = null
      cursorStore.clear()
      lockStore.clear()
      void connection.disconnect()
    }
  }, [clientId, cursorStore, lockStore, projectId])

  const sendPing = useCallback(() => {
    connectionRef.current?.sendPing(`PING ${new Date().toISOString()}`)
  }, [])

  const publishLiveEvent = useCallback(
    (type: EphemeralCollaborationEventType, payload: Record<string, unknown>) => {
      connectionRef.current?.publishEvent(type, payload)
    },
    [],
  )

  return {
    status,
    collaborators,
    lastPing,
    sendPing,
    publishLiveEvent,
    cursorStore,
    lockStore,
    clientId,
  }
}
