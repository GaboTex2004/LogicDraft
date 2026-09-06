import { useCallback, useEffect, useRef, useState } from 'react'
import axios from 'axios'
import {
  Background, BackgroundVariant, Controls, MarkerType, ReactFlow,
  ReactFlowProvider, useEdgesState, useNodesState, useReactFlow,
  type Connection, type EdgeChange, type NodeChange, type OnSelectionChangeParams,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useNavigate, useParams } from 'react-router-dom'
import { isForbiddenError, isNotFoundError, isUnauthorizedError } from '../../../shared/api/apiError'
import { askProjectAgent } from '../../agent/api/agentApi'
import { AgentPanel } from '../../agent/components/AgentPanel'
import { AgentSession } from '../../agent/services/agentSession'
import { ProjectPresence } from '../../collaboration/components/ProjectPresence'
import { useProjectCollaboration } from '../../collaboration/hooks/useProjectCollaboration'
import type { CollaborationEvent } from '../../collaboration/types/collaboration.types'
import { obtenerProyecto } from '../../project/api/projectApi'
import type { Proyecto } from '../../project/types/project.types'
import { guardarDiagrama, obtenerDiagrama } from '../api/diagramApi'
import { interpretDiagramWithAi } from '../api/diagramAiApi'
import { applyDiagramOperations, DiagramOperationError, parseDiagramAiResponse } from '../services/applyDiagramOperations'
import { AiPromptBar } from '../components/AiPromptBar'
import { DiagramPropertiesPanel } from '../components/DiagramPropertiesPanel'
import { DiagramSidebar } from '../components/DiagramSidebar'
import { DiagramToolbar, type SaveStatus } from '../components/DiagramToolbar'
import { EntityNode } from '../components/EntityNode'
import { RelationshipEdge } from '../components/RelationshipEdge'
import { commitRelationshipEdge, normalizeRelationshipEdge } from '../services/relationshipCardinality'
import type { AttributeType, DiagramDocument, DiagramEdge, EntityAttribute, EntityFlowNode } from '../types/diagram.types'
import '../diagram.css'

const EMPTY_DOCUMENT: DiagramDocument = { version: 1, nodes: [], edges: [] }
const nodeTypes = { entity: EntityNode }
const edgeTypes = { relationship: RelationshipEdge }

function serializeNode(node: EntityFlowNode): EntityFlowNode {
  return {
    id: node.id,
    type: 'entity',
    position: { x: node.position.x, y: node.position.y },
    data: { id: node.data.id, name: node.data.name, attributes: node.data.attributes.map((attribute) => ({ ...attribute })) },
  }
}

function serializeEdge(edge: DiagramEdge): DiagramEdge {
  edge = normalizeRelationshipEdge(edge)
  return {
    id: edge.id, source: edge.source, target: edge.target,
    ...(edge.sourceHandle ? { sourceHandle: edge.sourceHandle } : {}),
    ...(edge.targetHandle ? { targetHandle: edge.targetHandle } : {}),
    ...(edge.type ? { type: edge.type } : {}),
    ...(edge.markerEnd ? { markerEnd: edge.markerEnd } : {}),
    ...(edge.data ? { data: edge.data } : {}),
  }
}

function serializeDocument(nodes: EntityFlowNode[], edges: DiagramEdge[]): DiagramDocument {
  return { version: 1, nodes: nodes.map(serializeNode), edges: edges.map(serializeEdge) }
}

function record(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? value as Record<string, unknown> : null
}

function remoteNode(payload: unknown): EntityFlowNode | null {
  const wrapper = record(payload)
  const node = record(wrapper?.node)
  const position = record(node?.position)
  const data = record(node?.data)
  if (!node || !position || !data || typeof node.id !== 'string'
    || typeof position.x !== 'number' || typeof position.y !== 'number'
    || typeof data.id !== 'string' || typeof data.name !== 'string'
    || !Array.isArray(data.attributes)) return null
  const attributes: EntityAttribute[] = []
  for (const item of data.attributes) {
    const attribute = record(item)
    if (!attribute || typeof attribute.id !== 'string' || typeof attribute.name !== 'string'
      || typeof attribute.type !== 'string' || typeof attribute.primaryKey !== 'boolean') return null
    attributes.push({ id: attribute.id, name: attribute.name, type: attribute.type as AttributeType, primaryKey: attribute.primaryKey,
      ...(typeof attribute.nullable === 'boolean' ? { nullable: attribute.nullable } : {}) })
  }
  return { id: node.id, type: 'entity', position: { x: position.x, y: position.y }, data: { id: data.id, name: data.name, attributes } }
}

function remoteEdge(payload: unknown): DiagramEdge | null {
  const wrapper = record(payload)
  const edge = record(wrapper?.edge)
  if (!edge || typeof edge.id !== 'string' || typeof edge.source !== 'string' || typeof edge.target !== 'string') return null
  return {
    id: edge.id, source: edge.source, target: edge.target,
    ...(typeof edge.sourceHandle === 'string' ? { sourceHandle: edge.sourceHandle } : {}),
    ...(typeof edge.targetHandle === 'string' ? { targetHandle: edge.targetHandle } : {}),
    ...(typeof edge.type === 'string' ? { type: edge.type } : {}),
    markerEnd: { type: MarkerType.ArrowClosed },
    ...(record(edge.data) ? { data: record(edge.data)! } : {}),
  }
}

function DiagramEditorCanvas({ project, initialDocument, initialDiagramId }: { project: Proyecto; initialDocument: DiagramDocument; initialDiagramId: number | null }) {
  const [nodes, setNodes, onNodesChange] = useNodesState<EntityFlowNode>(initialDocument.nodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState<DiagramEdge>(initialDocument.edges.map(normalizeRelationshipEdge))
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [selectedEdgeIds, setSelectedEdgeIds] = useState<string[]>([])
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('clean')
  const [documentRevision, setDocumentRevision] = useState(0)
  const [aiLoading, setAiLoading] = useState(false)
  const [aiError, setAiError] = useState('')
  const [aiMessage, setAiMessage] = useState('')
  const [agentSession] = useState(() => new AgentSession(project.id, initialDiagramId))
  const aiRequest = useRef<AbortController | null>(null)
  const nextEntityNumber = useRef(1)
  const nextAttributeNumber = useRef(1)
  const nodesRef = useRef(nodes)
  const edgesRef = useRef(edges)
  const revisionRef = useRef(0)
  const persistedRevisionRef = useRef(0)
  const saveInFlightRef = useRef<Promise<boolean> | null>(null)
  const { fitView } = useReactFlow()
  const selectedNode = nodes.find((node) => node.id === selectedNodeId) ?? null
  const selectedEdge = !selectedNode && selectedEdgeIds.length === 1 ? edges.find(edge => edge.id === selectedEdgeIds[0]) ?? null : null
  nodesRef.current = nodes
  edgesRef.current = edges

  const markDirty = useCallback(() => {
    revisionRef.current += 1
    setDocumentRevision(revisionRef.current)
    setSaveStatus('dirty')
  }, [])

  // MVP conflict policy: events are applied in arrival order (last write wins).
  // Direct state updates here never call publishEvent, preventing echo loops.
  const applyRemoteEvent = useCallback((event: CollaborationEvent) => {
    const payload = record(event.payload)
    if (event.type === 'NODE_CREATED') {
      const node = remoteNode(event.payload)
      if (node) {
        setNodes((current) => current.some((item) => item.id === node.id) ? current : [...current, node])
        agentSession.record('NODE_CREATED', { nodeId: node.id })
      }
    } else if (event.type === 'NODE_MOVED') {
      const position = record(payload?.position)
      if (typeof payload?.nodeId === 'string' && typeof position?.x === 'number' && typeof position.y === 'number') {
        setNodes((current) => current.map((node) => node.id === payload.nodeId ? { ...node, position: { x: position.x as number, y: position.y as number } } : node))
      }
    } else if (event.type === 'NODE_UPDATED') {
      const node = remoteNode(event.payload)
      if (node) {
        setNodes((current) => current.map((item) => item.id === node.id ? { ...node, selected: item.selected } : item))
        agentSession.record('NODE_UPDATED', { nodeId: node.id })
      }
    } else if (event.type === 'NODE_DELETED' && typeof payload?.nodeId === 'string') {
      setNodes((current) => current.filter((node) => node.id !== payload.nodeId))
      setEdges((current) => current.filter((edge) => edge.source !== payload.nodeId && edge.target !== payload.nodeId))
      setSelectedNodeId((current) => current === payload.nodeId ? null : current)
      agentSession.record('NODE_DELETED', { nodeId: payload.nodeId })
    } else if (event.type === 'EDGE_CREATED' || event.type === 'EDGE_UPDATED') {
      const edge = remoteEdge(event.payload)
      if (edge) {
        try {
          const changed = commitRelationshipEdge(edgesRef.current, edge, event.type, true, {
            setEdges: next => { edgesRef.current = next; setEdges(next) }, markDirty, publish: () => {},
          })
          if (changed) agentSession.record(event.type, { edgeId: edge.id })
        } catch { /* Invalid remote cardinalities must not enter local state. */ }
      }
      return
    } else if (event.type === 'EDGE_DELETED' && typeof payload?.edgeId === 'string') {
      setEdges((current) => current.filter((edge) => edge.id !== payload.edgeId))
      setSelectedEdgeIds((current) => current.filter((id) => id !== payload.edgeId))
      agentSession.record('EDGE_DELETED', { edgeId: payload.edgeId })
    } else if (event.type === 'DIAGRAM_SAVED') {
      agentSession.record('DIAGRAM_SAVED')
    }
    if (event.type !== 'DIAGRAM_SAVED') markDirty()
  }, [agentSession, markDirty, setEdges, setNodes])

  const { status, collaborators, publishEvent } = useProjectCollaboration(project.id, applyRemoteEvent)

  useEffect(() => () => { aiRequest.current?.abort() }, [])

  async function handleAiPrompt(prompt: string) {
    if (aiRequest.current) return
    if (revisionRef.current !== persistedRevisionRef.current || saveInFlightRef.current || nodesRef.current.some(n => n.dragging)) {
      setAiError('Espera a que termine el guardado del diagrama antes de usar IA.')
      return
    }
    const request = new AbortController()
    aiRequest.current = request
    setAiLoading(true)
    setAiError('')
    setAiMessage('')
    agentSession.record('AI_REQUESTED')
    try {
      const response = await interpretDiagramWithAi(project.id, prompt, request.signal)
      if (request.signal.aborted) return
      const operations = parseDiagramAiResponse(response)
      // Revalidate against the latest local state, including edits made during inference.
      const result = applyDiagramOperations(nodesRef.current, edgesRef.current, operations)
      if (!result.events.length) {
        setAiMessage('No se encontraron cambios para aplicar')
        return
      }
      nodesRef.current = result.nodes
      edgesRef.current = result.edges
      setNodes(result.nodes)
      setEdges(result.edges)
      markDirty()
      for (const event of result.events) {
        if (event.type === 'EDGE_CREATED') {
          publishEvent(event.type, { edge: serializeEdge(event.edge) })
          agentSession.record('EDGE_CREATED', { edgeId: event.edge.id })
        } else {
          publishEvent(event.type, { node: serializeNode(event.node) })
          agentSession.record(event.type, { nodeId: event.node.id })
        }
      }
      if (result.events.some(event => event.type === 'NODE_CREATED')) {
        window.requestAnimationFrame(() => {
          if (!request.signal.aborted) void fitView({ padding: 0.2, duration: 350 })
        })
      }
      setAiMessage('Cambios aplicados')
    } catch (error: unknown) {
      if (request.signal.aborted) return
      if (isUnauthorizedError(error)) { localStorage.removeItem('token'); window.location.assign('/login') }
      else if (isForbiddenError(error)) setAiError('No tienes permisos para modificar este proyecto')
      else if (error instanceof DiagramOperationError) setAiError(error.message)
      else if (axios.isAxiosError(error)) {
        const code = error.response?.status
        const data = record(error.response?.data)
        if (code === 409) setAiError(typeof data?.mensaje === 'string' ? data.mensaje : 'El diagrama cambió o la operación entra en conflicto.')
        else if (code === 502 || code === 503 || code === 504 || error.code === 'ECONNABORTED') setAiError('El servicio IA no pudo completar la solicitud. Inténtalo de nuevo.')
        else setAiError('No se pudo interpretar el cambio solicitado.')
      } else setAiError('No se pudo interpretar el cambio solicitado.')
    } finally {
      aiRequest.current = null
      if (!request.signal.aborted) setAiLoading(false)
    }
  }

  const handleNodesChange = useCallback((changes: NodeChange<EntityFlowNode>[]) => {
    onNodesChange(changes)
    if (changes.some((change) => change.type !== 'select' && change.type !== 'dimensions' && change.type !== 'position')) markDirty()
  }, [markDirty, onNodesChange])

  const handleEdgesChange = useCallback((changes: EdgeChange<DiagramEdge>[]) => {
    onEdgesChange(changes)
    if (changes.some((change) => change.type !== 'select')) markDirty()
  }, [markDirty, onEdgesChange])

  const onConnect = useCallback((connection: Connection) => {
    const edge: DiagramEdge = { ...connection, id: crypto.randomUUID(), data: { sourceCardinality: 'ONE_ONE', targetCardinality: 'ONE_ONE' } }
    commitRelationshipEdge(edgesRef.current, edge, 'EDGE_CREATED', false, {
      setEdges: next => { edgesRef.current = next; setEdges(next) }, markDirty,
      publish: (kind, updated) => {
        publishEvent(kind, { edge: serializeEdge(updated) })
        agentSession.record(kind, { edgeId: updated.id })
      },
    })
  }, [agentSession, markDirty, publishEvent, setEdges])

  const handleSelectionChange = useCallback(({ nodes: selectedNodes, edges: selectedEdges }: OnSelectionChangeParams) => {
    setSelectedNodeId(selectedNodes[0]?.id ?? null)
    setSelectedEdgeIds(selectedEdges.map((edge) => edge.id))
    if (selectedNodes[0]) agentSession.record('NODE_SELECTED', { nodeId: selectedNodes[0].id })
  }, [agentSession])

  function addEntity() {
    const number = nextEntityNumber.current++
    const id = `entity-${crypto.randomUUID()}`
    const node: EntityFlowNode = { id, type: 'entity', position: { x: 180 + number * 35, y: 180 + number * 35 }, data: { id, name: 'Nueva entidad', attributes: [] }, selected: true }
    setNodes((current) => [...current.map((item) => ({ ...item, selected: false })), node])
    setSelectedNodeId(id)
    setSelectedEdgeIds([])
    publishEvent('NODE_CREATED', { node: serializeNode(node) })
    agentSession.record('NODE_CREATED', { nodeId: id })
    markDirty()
  }

  function updateSelectedEntity(update: (node: EntityFlowNode) => EntityFlowNode) {
    if (!selectedNode) return
    const updated = update(selectedNode)
    setNodes((current) => current.map((node) => node.id === updated.id ? updated : node))
    publishEvent('NODE_UPDATED', { node: serializeNode(updated) })
    agentSession.record('NODE_UPDATED', { nodeId: updated.id })
    markDirty()
  }

  function changeAttribute(attributeId: string, changes: Partial<Omit<EntityAttribute, 'id'>>) {
    updateSelectedEntity((node) => ({ ...node, data: { ...node.data, attributes: node.data.attributes.map((attribute) => attribute.id === attributeId ? { ...attribute, ...changes } : attribute) } }))
  }

  function deleteSelection() {
    const deletedNodes = nodes.filter((node) => node.selected)
    const deletedEdges = edges.filter((edge) => edge.selected)
    deletedNodes.forEach((node) => publishEvent('NODE_DELETED', { nodeId: node.id }))
    deletedEdges.forEach((edge) => publishEvent('EDGE_DELETED', { edgeId: edge.id }))
    deletedNodes.forEach((node) => agentSession.record('NODE_DELETED', { nodeId: node.id }))
    deletedEdges.forEach((edge) => agentSession.record('EDGE_DELETED', { edgeId: edge.id }))
    const deletedNodeIds = new Set(deletedNodes.map((node) => node.id))
    setNodes((current) => current.filter((node) => !node.selected))
    setEdges((current) => current.filter((edge) => !edge.selected && !deletedNodeIds.has(edge.source) && !deletedNodeIds.has(edge.target)))
    setSelectedNodeId(null)
    setSelectedEdgeIds([])
    markDirty()
  }

  const handleSave = useCallback(async function saveCurrentDocument(): Promise<void> {
    if (saveInFlightRef.current) {
      const savedSuccessfully = await saveInFlightRef.current
      if (savedSuccessfully && revisionRef.current !== persistedRevisionRef.current) await saveCurrentDocument()
      return
    }

    const savedRevision = revisionRef.current
    const request = guardarDiagrama(project.id, serializeDocument(nodesRef.current, edgesRef.current))
      .then((saved) => {
        agentSession.setDiagramId(saved.id)
        agentSession.record('DIAGRAM_SAVED')
        persistedRevisionRef.current = savedRevision
        publishEvent('DIAGRAM_SAVED', { savedAt: saved.fechaActualizacion })
        setSaveStatus(revisionRef.current === savedRevision ? 'saved' : 'dirty')
        return true
      })
      .catch((requestError: unknown) => {
        agentSession.record('SAVE_FAILED')
        if (isUnauthorizedError(requestError)) { localStorage.removeItem('token'); window.location.assign('/login') }
        else if (isForbiddenError(requestError)) setSaveStatus('forbidden')
        else setSaveStatus('error')
        return false
      })
      .finally(() => { saveInFlightRef.current = null })

    saveInFlightRef.current = request
    setSaveStatus('saving')
    await request
  }, [agentSession, project.id, publishEvent])

  async function handleAgentAsk(message: string): Promise<string> {
    try {
      const response = await askProjectAgent(project.id, {
        message,
        selectedNodeId,
        selectedEdgeId: selectedEdge?.id ?? null,
        recentEvents: agentSession.snapshot(),
      })
      return response.answer
    } catch (error: unknown) {
      if (isUnauthorizedError(error)) {
        localStorage.removeItem('token')
        window.location.assign('/login')
        throw new Error('La sesión expiró.', { cause: error })
      }
      if (isForbiddenError(error)) throw new Error('No tienes acceso a este proyecto.', { cause: error })
      throw new Error('El agente no pudo responder. Inténtalo de nuevo.', { cause: error })
    }
  }

  useEffect(() => {
    if (saveStatus !== 'dirty') return
    const timeoutId = window.setTimeout(() => { void handleSave() }, 1500)
    return () => window.clearTimeout(timeoutId)
  }, [documentRevision, handleSave, saveStatus])

  useEffect(() => {
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      if (revisionRef.current === persistedRevisionRef.current && !saveInFlightRef.current) return
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [])

  useEffect(() => () => {
    if (revisionRef.current !== persistedRevisionRef.current || saveInFlightRef.current) void handleSave()
  }, [handleSave])

  return <div className="diagram-editor">
    <DiagramSidebar projectsPath={`/workspaces/${project.workspaceId}/proyectos`} onAddEntity={addEntity} />
    <DiagramToolbar projectName={project.nombre} hasSelection={selectedNodeId !== null || selectedEdgeIds.length > 0} saveStatus={saveStatus} onSave={() => void handleSave()} onDeleteSelection={deleteSelection} onFitView={() => void fitView({ padding: 0.2, duration: 350 })} />
    <ProjectPresence status={status} collaborators={collaborators} />
    <section className="diagram-canvas" aria-label="Canvas del diagrama">
      <ReactFlow<EntityFlowNode, DiagramEdge>
        nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
        onNodesChange={handleNodesChange} onEdgesChange={handleEdgesChange}
        onConnect={onConnect} onSelectionChange={handleSelectionChange}
        onNodeDragStop={(_, node) => {
          publishEvent('NODE_MOVED', { nodeId: node.id, position: { x: node.position.x, y: node.position.y } })
          agentSession.record('NODE_UPDATED', { nodeId: node.id })
          markDirty()
        }}
        onNodesDelete={(deleted) => deleted.forEach((node) => {
          publishEvent('NODE_DELETED', { nodeId: node.id })
          agentSession.record('NODE_DELETED', { nodeId: node.id })
        })}
        onEdgesDelete={(deleted) => deleted.forEach((edge) => {
          publishEvent('EDGE_DELETED', { edgeId: edge.id })
          agentSession.record('EDGE_DELETED', { edgeId: edge.id })
        })}
        deleteKeyCode={['Backspace', 'Delete']} fitView minZoom={0.25} maxZoom={2}
      >
        <Background variant={BackgroundVariant.Dots} gap={24} size={1.4} color="#34343a" />
        <Controls position="bottom-left" showInteractive={false} />
      </ReactFlow>
    </section>
    <DiagramPropertiesPanel edge={selectedEdge} onChangeCardinality={(end, value) => {
      if (!selectedEdge) return
      const changed = commitRelationshipEdge(edgesRef.current, { ...selectedEdge, data: { ...selectedEdge.data, [end]: value } }, 'EDGE_UPDATED', false, {
        setEdges: next => { edgesRef.current = next; setEdges(next) }, markDirty,
        publish: (kind, edge) => {
          publishEvent(kind, { edge: serializeEdge(edge) })
          agentSession.record('EDGE_UPDATED', { edgeId: edge.id })
        },
      })
      if (!changed) setAiMessage('Sin cambios: la relación es equivalente a otra existente.')
    }} entity={selectedNode?.data ?? null} onChangeName={(name) => updateSelectedEntity((node) => ({ ...node, data: { ...node.data, name } }))} onAddAttribute={() => { const number = nextAttributeNumber.current++; updateSelectedEntity((node) => ({ ...node, data: { ...node.data, attributes: [...node.data.attributes, { id: `attribute-${crypto.randomUUID()}-${number}`, name: 'nuevo_atributo', type: 'VARCHAR', primaryKey: false }] } })) }} onChangeAttribute={changeAttribute} onDeleteAttribute={(attributeId) => updateSelectedEntity((node) => ({ ...node, data: { ...node.data, attributes: node.data.attributes.filter((attribute) => attribute.id !== attributeId) } }))} />
    <AiPromptBar onSubmit={handleAiPrompt} loading={aiLoading} message={aiMessage} error={aiError}
      disabledReason={saveStatus === 'forbidden' ? 'No tienes permisos para modificar este proyecto'
        : (saveStatus === 'dirty' || saveStatus === 'saving' || saveStatus === 'error') ? 'Espera a que el diagrama esté guardado para usar IA.' : undefined} />
    <AgentPanel onAsk={handleAgentAsk}
      disabledReason={saveStatus === 'forbidden' ? 'No tienes acceso para consultar este proyecto.'
        : (saveStatus === 'dirty' || saveStatus === 'saving') ? 'Espera a que el diagrama se guarde para consultar su estado actual.' : undefined} />
  </div>
}

export function DiagramEditorPage() {
  const { projectId: projectIdParam } = useParams()
  const navigate = useNavigate()
  const projectId = Number(projectIdParam)
  const validProjectId = Number.isSafeInteger(projectId) && projectId > 0
  const [project, setProject] = useState<Proyecto | null>(null)
  const [document, setDocument] = useState<DiagramDocument | null>(null)
  const [diagramId, setDiagramId] = useState<number | null>(null)
  const [loading, setLoading] = useState(validProjectId)
  const [error, setError] = useState(validProjectId ? '' : 'El proyecto indicado no es válido.')

  useEffect(() => {
    if (!validProjectId) return
    let active = true
    async function loadEditor() {
      try {
        const loadedProject = await obtenerProyecto(projectId)
        let loadedDocument = EMPTY_DOCUMENT
        let loadedDiagramId: number | null = null
        try {
          const loadedDiagram = await obtenerDiagrama(projectId)
          loadedDiagramId = loadedDiagram.id
          const content = loadedDiagram.contenido
          loadedDocument = { ...content, edges: content.edges.map(normalizeRelationshipEdge) }
        }
        catch (requestError: unknown) { if (!isNotFoundError(requestError)) throw requestError }
        if (active) { setProject(loadedProject); setDocument(loadedDocument); setDiagramId(loadedDiagramId) }
      } catch (requestError: unknown) {
        if (isUnauthorizedError(requestError)) { localStorage.removeItem('token'); navigate('/login', { replace: true }) }
        else if (active && isForbiddenError(requestError)) setError('No tienes acceso a este proyecto.')
        else if (active && isNotFoundError(requestError)) setError('El proyecto no existe.')
        else if (active) setError('No se pudo cargar el diagrama.')
      } finally { if (active) setLoading(false) }
    }
    void loadEditor()
    return () => { active = false }
  }, [navigate, projectId, validProjectId])

  if (loading) return <main className="diagram-state">Cargando diagrama...</main>
  if (error || !project || !document) return <main className="diagram-state is-error" role="alert">{error || 'Diagrama no disponible.'}</main>
  return <ReactFlowProvider key={project.id}><DiagramEditorCanvas project={project} initialDocument={document} initialDiagramId={diagramId} /></ReactFlowProvider>
}
