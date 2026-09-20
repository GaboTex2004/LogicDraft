import { api } from "../../../shared/api/api";
import type { DiagramaResponse, DiagramDocument } from "../types/diagram.types";

export async function obtenerDiagrama(
  projectId: number,
): Promise<DiagramaResponse> {
  const response = await api.get<DiagramaResponse>(
    `/proyectos/${projectId}/diagrama`,
  );
  return response.data;
}

export async function guardarDiagrama(
  projectId: number,
  document: DiagramDocument,
): Promise<DiagramaResponse> {
  const response = await api.put<DiagramaResponse>(
    `/proyectos/${projectId}/diagrama`,
    document,
  );
  return response.data;
}
export async function descargarEnterpriseArchitectXmi(
  projectId: number,
): Promise<Blob> {
  const response = await api.get<Blob>(
    `/proyectos/${projectId}/export/enterprise-architect`,
    {
      responseType: "blob",
      timeout: 30000,
    },
  );

  return response.data;
}
