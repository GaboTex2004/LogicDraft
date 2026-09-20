import { api } from "../../../shared/api/api";
import type { Workspace } from "../types/workspace.types";

export interface CrearWorkspaceRequest {
  nombre: string;
}

export async function obtenerMisWorkspaces(): Promise<Workspace[]> {
  const response = await api.get<Workspace[]>("/workspaces");
  return response.data;
}

export async function crearWorkspace(
  request: CrearWorkspaceRequest,
): Promise<Workspace> {
  const response = await api.post<Workspace>("/workspaces", request);

  return response.data;
}
