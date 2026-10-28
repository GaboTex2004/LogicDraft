import type { DiagramaResponse } from '../../diagram/types/diagram.types.ts'

export interface SynchronizationState {
  revision: number | null
  dirty: boolean
  dragging: boolean
  saving: Promise<unknown> | null
}

export interface SynchronizationPorts {
  read: () => Promise<DiagramaResponse>
  state: () => SynchronizationState
  apply: (saved: DiagramaResponse, author?: string) => void
  conflict: (saved: DiagramaResponse, author?: string) => void
  error: (error: unknown) => void
  verified?: () => void
}

/** Reconcile authoritative snapshots, never overlay uncommitted remote drafts. */
export class DiagramSynchronizer {
  private active = true
  private pending = false
  private running: Promise<void> | null = null
  private author: { name: string; revision: number } | undefined
  private readonly ports: SynchronizationPorts

  constructor(ports: SynchronizationPorts) { this.ports = ports }

  dispose(): void { this.active = false }

  refresh(author?: { name: string; revision: number }): Promise<void> {
    if (!this.active) return Promise.resolve()
    this.pending = true
    if (author && (!this.author || author.revision >= this.author.revision)) this.author = author
    if (!this.running) this.running = this.reconcile().finally(() => {
      this.running = null
      if (this.active && this.pending) void this.refresh()
    })
    return this.running
  }

  private async reconcile(): Promise<void> {
    while (this.active && this.pending) {
      this.pending = false
      try {
        await this.ports.state().saving
        if (!this.active) return
        const saved = await this.ports.read()
        if (!this.active) return
        const current = this.ports.state()
        if (current.saving) { this.pending = true; continue }
        if (!Number.isSafeInteger(saved.revision) || saved.revision < 0)
          throw new Error('El servidor devolvió una revisión inválida.')
        this.ports.verified?.()
        // Delayed responses and duplicate deliveries cannot roll the canvas back.
        if (current.revision !== null && saved.revision <= current.revision) continue
        const author = this.author?.revision === saved.revision ? this.author.name : undefined
        if (current.dirty || current.dragging) this.ports.conflict(saved, author)
        else this.ports.apply(saved, author)
      } catch (error) {
        if (this.active) this.ports.error(error)
      }
    }
  }
}
