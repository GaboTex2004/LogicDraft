import { useCallback, useEffect, useRef, useState } from 'react'
import { connectToProject, type ProjectCollaborationConnection } from '../services/collaborationService'
import type { CollaborationEvent, CollaborationStatus, Collaborator, DiagramCollaborationEventType } from '../types/collaboration.types'

export function useProjectCollaboration(
  projectId: number,
  onDiagramEvent?: (event: CollaborationEvent) => void,
) {
  const connectionRef = useRef<ProjectCollaborationConnection | null>(null)
  const [clientId] = useState(() => crypto.randomUUID())
  const onDiagramEventRef = useRef(onDiagramEvent)
  const [status, setStatus] = useState<CollaborationStatus>(() =>
    localStorage.getItem('token') ? 'connecting' : 'error')
  const [collaborators, setCollaborators] = useState<Collaborator[]>([])
  const [lastPing, setLastPing] = useState<CollaborationEvent | null>(null)

  useEffect(() => {
    onDiagramEventRef.current = onDiagramEvent
  }, [onDiagramEvent])

  useEffect(() => {
    const token = localStorage.getItem('token')
    if (!token) return

    const connection = connectToProject(projectId, token, clientId, {
      onConnect: () => {
        console.info('[WS] conectado')
        setStatus('connected')
      },
      onDisconnect: () => setStatus('disconnected'),
      onError: () => setStatus('error'),
      onEvent: (event) => {
        if (event.type === 'USER_JOINED') {
          setCollaborators((current) => {
            const withoutUser = current.filter((user) => user.userId !== event.userId)
            return [...withoutUser, { userId: event.userId, name: event.name }]
          })
        } else if (event.type === 'USER_LEFT') {
          setCollaborators((current) => current.filter((user) => user.userId !== event.userId))
        } else if (event.type === 'PING') {
          setLastPing(event)
          console.info(`[Collaboration] PING de ${event.name}:`, event.message)
        } else if (event.clientId !== clientId) {
          console.info(`[WS] ${event.type} recibido`)
          onDiagramEventRef.current?.(event)
        }
      },
    })
    connectionRef.current = connection

    return () => {
      connectionRef.current = null
      void connection.disconnect()
    }
  }, [clientId, projectId])

  const sendPing = useCallback(() => {
    connectionRef.current?.sendPing(`PING ${new Date().toISOString()}`)
  }, [])

  const publishEvent = useCallback((type: DiagramCollaborationEventType, payload: unknown) => {
    connectionRef.current?.publishEvent(type, payload)
  }, [])

  return { status, collaborators, lastPing, sendPing, publishEvent }
}
