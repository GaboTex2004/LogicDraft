import { useSyncExternalStore } from 'react'
import { useViewport } from '@xyflow/react'
import type { RemoteCursorStore } from '../services/remoteCursors'

export function RemoteCursorsOverlay({ store }: { store: RemoteCursorStore }) {
  const cursors = useSyncExternalStore(store.subscribe, store.getSnapshot, store.getSnapshot)
  const viewport = useViewport()

  if (cursors.length === 0) return null

  const inverseScale = 1 / Math.max(viewport.zoom, 0.1)

  return (
    <div
      className="remote-cursors-layer"
      aria-hidden="true"
      style={{
        transform: `translate3d(${viewport.x}px, ${viewport.y}px, 0) scale(${viewport.zoom})`,
      }}
    >
      {cursors.map((cursor) => (
        <div
          key={cursor.sessionKey}
          className="remote-cursor"
          style={{
            transform: `translate3d(${cursor.x}px, ${cursor.y}px, 0)`,
          }}
        >
          <div
            className="remote-cursor-visual"
            style={{
              transform: `scale(${inverseScale})`,
            }}
          >
            <svg
              className="remote-cursor-pointer"
              width="18"
              height="22"
              viewBox="0 0 18 22"
              fill="none"
            >
              <path
                d="M1.5 1.5L15.8 11.2L9.2 12.6L6.1 19.2L1.5 1.5Z"
                fill={cursor.color}
                stroke="#09090b"
                strokeWidth="1.6"
                strokeLinejoin="round"
              />
            </svg>
            <span
              className="remote-cursor-label"
              style={{ backgroundColor: cursor.color }}
            >
              {cursor.label}
            </span>
          </div>
        </div>
      ))}
    </div>
  )
}
