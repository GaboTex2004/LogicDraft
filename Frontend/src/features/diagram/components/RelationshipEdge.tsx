import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from '@xyflow/react'
import type { DiagramEdge } from '../types/diagram.types'
import { cardinalities, CARDINALITY_LABELS } from '../services/relationshipCardinality'

export function RelationshipEdge(props: EdgeProps<DiagramEdge>) {
  const [path] = getBezierPath(props)
  const cards = cardinalities(props.data)
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
    </EdgeLabelRenderer>
  </>
}
