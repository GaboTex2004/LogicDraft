import { api } from "../../../shared/api/api";
import type { DiagramaResponse, DiagramDocument } from "../types/diagram.types";

export interface EnterpriseArchitectImportPreview {
  projectName: string;
  version: 1;
  nodes: DiagramDocument["nodes"];
  edges: DiagramDocument["edges"];
  warnings: string[];
}

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

export async function previewEnterpriseArchitectImport(
  file: File,
): Promise<EnterpriseArchitectImportPreview> {
  const form = new FormData();
  form.append("file", file);
  const response = await api.post<EnterpriseArchitectImportPreview>(
    "/import/enterprise-architect/preview",
    form,
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
