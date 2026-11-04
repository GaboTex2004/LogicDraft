import { api } from "../../../shared/api/api";
import type { DiagramaResponse } from "../types/diagram.types";
import { parseImageProposal, type ImageProposal } from "../services/imageProposal";

export async function interpretDiagramImage(
  projectId: number,
  image: File,
  prompt: string,
  signal?: AbortSignal,
): Promise<ImageProposal> {
  const form = new FormData();

  form.append("image", image);
  form.append("prompt", prompt);

  const response = await api.post<unknown>(
    `/proyectos/${projectId}/ai/image/interpret`,
    form,
    {
      signal,
      timeout: 150_000,
    },
  );

  return parseImageProposal(response.data);
}

export async function acceptDiagramImage(projectId: number, proposalId: string, clientId: string): Promise<DiagramaResponse> {
  const response = await api.post<DiagramaResponse>(`/proyectos/${projectId}/ai/image/accept`, { proposalId, clientId });
  return response.data;
}
