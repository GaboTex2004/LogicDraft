import { api } from "../../../shared/api/api";
import type { Proyecto } from "../../project/types/project.types";

export interface EnterpriseArchitectImportPreview {
  projectName: string;
  version: number;
  nodes: Record<string, unknown>[];
  edges: Record<string, unknown>[];
  warnings: string[];
}

export async function obtenerVistaPreviaEnterpriseArchitect(
  file: File,
): Promise<EnterpriseArchitectImportPreview> {
  const formData = new FormData();
  formData.append("file", file);

  const response = await api.post<EnterpriseArchitectImportPreview>(
    "/import/enterprise-architect/preview",
    formData,
  );

  return response.data;
}

export async function confirmarImportacionEnterpriseArchitect(
  workspaceId: number,
  file: File,
): Promise<Proyecto> {
  const formData = new FormData();

  formData.append("workspaceId", String(workspaceId));
  formData.append("file", file);

  const response = await api.post<Proyecto>(
    "/import/enterprise-architect",
    formData,
  );

  return response.data;
}
