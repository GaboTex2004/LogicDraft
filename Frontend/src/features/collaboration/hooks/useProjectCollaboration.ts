import { useCallback, useEffect, useRef, useState } from 'react'
import { connectToProject, type ProjectCollaborationConnection } from '../services/collaborationService'
import type { CollaborationEvent, CollaborationStatus, Collaborator } from '../types/collaboration.types'

export function useProjectCollaboration(
  projectId: number,
  onDiagramEvent?: (event: CollaborationEvent) => void,
  onConnected?: () => void,
) {
  const connectionRef = useRef<ProjectCollaborationConnection | null>(null)
  const [clientId] = useState(() => crypto.randomUUID())
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
    const token = localStorage.getItem('token')
    if (!token) return
    seenEventIds.current.clear()
    let active = true

    const connection = connectToProject(projectId, token, clientId, {
      onConnect: () => {
        if (!active) return
        console.info('[WS] conectado')
        setStatus('connected')
        onConnectedRef.current?.()
      },
      onDisconnect: () => { if (active) { setStatus('disconnected'); setCollaborators([]) } },
      onError: () => { if (active) setStatus('error') },
      onEvent: (event) => {
        if (!active || event.projectId !== projectId) return
        if (seenEventIds.current.has(event.eventId)) return
        seenEventIds.current.add(event.eventId)
        if (seenEventIds.current.size > 1000) {
          const oldest = seenEventIds.current.values().next().value
          if (oldest) seenEventIds.current.delete(oldest)
        }
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
        } else if (event.type === 'DIAGRAM_COMMITTED') {
          // Own notifications also trigger reconciliation: a REST response may be lost.
          // The revision gate prevents applying our own commit twice.
          console.info(`[WS] ${event.type} recibido`)
          onDiagramEventRef.current?.(event)
        }
      },
    })
    connectionRef.current = connection

    return () => {
      active = false
      connectionRef.current = null
      void connection.disconnect()
    }
  }, [clientId, projectId])

  const sendPing = useCallback(() => {
    connectionRef.current?.sendPing(`PING ${new Date().toISOString()}`)
  }, [])

  return { status, collaborators, lastPing, sendPing, clientId }
}
