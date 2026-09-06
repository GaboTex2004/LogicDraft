import { api } from '../../../shared/api/api'
import type {
  ActualizarProyectoRequest,
  CrearProyectoRequest,
  Proyecto,
} from '../types/project.types'

export async function obtenerProyectos(workspaceId: number): Promise<Proyecto[]> {
  const response = await api.get<Proyecto[]>('/proyectos', {
    params: { workspaceId },
  })
  return response.data
}

export async function obtenerProyecto(id: number): Promise<Proyecto> {
  const response = await api.get<Proyecto>(`/proyectos/${id}`)
  return response.data
}

export async function crearProyecto(request: CrearProyectoRequest): Promise<Proyecto> {
  const response = await api.post<Proyecto>('/proyectos', request)
  return response.data
}

export async function actualizarProyecto(
  id: number,
  request: ActualizarProyectoRequest,
): Promise<Proyecto> {
  const response = await api.put<Proyecto>(`/proyectos/${id}`, request)
  return response.data
}

export async function eliminarProyecto(id: number): Promise<void> {
  await api.delete(`/proyectos/${id}`)
}

