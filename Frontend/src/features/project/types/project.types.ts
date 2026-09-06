export interface ProyectoResponse {
  id: number
  nombre: string
  descripcion: string | null
  fechaCreacion: string
  workspaceId: number
  workspaceNombre: string
}

export interface CrearProyectoRequest {
  nombre: string
  descripcion: string | null
  workspaceId: number
}

export interface ActualizarProyectoRequest {
  nombre: string
  descripcion: string | null
}

export type Proyecto = ProyectoResponse

