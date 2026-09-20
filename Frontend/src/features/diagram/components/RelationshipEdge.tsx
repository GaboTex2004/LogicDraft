import { BaseEdge, EdgeLabelRenderer, getBezierPath, useInternalNode, useReactFlow, type EdgeProps } from '@xyflow/react'
import type { DiagramEdge, EntityFlowNode } from '../types/diagram.types'
import { deriveJoinTable } from '../services/derivedJoinTable'
import { cardinalities, CARDINALITY_LABELS } from '../services/relationshipCardinality'

export function RelationshipEdge(props: EdgeProps<DiagramEdge>) {
  const flow = useReactFlow<EntityFlowNode, DiagramEdge>()
  const [path, labelX, labelY] = getBezierPath(props)
  const cards = cardinalities(props.data)
  const sourceNode = useInternalNode<EntityFlowNode>(props.source)
  const targetNode = useInternalNode<EntityFlowNode>(props.target)
  const joinTable = deriveJoinTable(props, sourceNode?.data, targetNode?.data)
  return <>
    <BaseEdge id={props.id} path={path} style={props.style} interactionWidth={24} />
    <EdgeLabelRenderer>
      {(['source', 'target'] as const).map(end => {
        const x = end === 'source' ? props.sourceX : props.targetX
        const y = end === 'source' ? props.sourceY : props.targetY
        const position = end === 'source' ? props.sourcePosition : props.targetPosition
        const dx = position === 'left' ? -30 : position === 'right' ? 30 : 0
        const dy = position === 'top' ? -24 : position === 'bottom' ? 24 : -18
        return <span key={end} className="relationship-cardinality nodrag nopan"
          style={{ transform: `translate(-50%, -50%) translate(${x + dx}px, ${y + dy}px)` }}>
          {CARDINALITY_LABELS[cards[end === 'source' ? 'sourceCardinality' : 'targetCardinality']]}
        </span>
      })}
      {!joinTable && typeof props.data?.name === 'string' && props.data.name.trim() && <span
        className="relationship-cardinality nodrag nopan"
        style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}>
        {props.data.name}
      </span>}
      {joinTable && <article className={`derived-join-table nodrag nopan ${props.selected ? 'is-selected' : ''}`}
        role="button" tabIndex={0} onClick={() => {
          flow.setNodes(nodes => nodes.map(node => ({ ...node, selected: false })))
          flow.setEdges(edges => edges.map(edge => ({ ...edge, selected: edge.id === props.id })))
        }} onKeyDown={event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault()
            flow.setNodes(nodes => nodes.map(node => ({ ...node, selected: false })))
            flow.setEdges(edges => edges.map(edge => ({ ...edge, selected: edge.id === props.id })))
          }
        }}
        aria-label={`Tabla intermedia derivada ${joinTable.name}`}
        style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}>
        <header>
          <strong>{joinTable.name}</strong>
          <small>tabla derivada</small>
        </header>
        <div className="derived-join-columns">
          {joinTable.columns.map(column => <div key={`${column.referencedEntity}-${column.name}`}>
            <span><b>FK</b> {column.name}</span>
            <code>{column.type}</code>
          </div>)}
        </div>
        <footer>
          <span title={joinTable.uniqueConstraint}>UNIQUE ({joinTable.columns.map(column => column.name).join(', ')})</span>
          <small>Solo lectura · atributos propios requieren entidad asociativa</small>
        </footer>
      </article>}
    </EdgeLabelRenderer>
  </>
}
