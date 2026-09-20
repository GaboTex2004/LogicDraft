import { api } from "../../../shared/api/api";

export async function interpretDiagramImage(
  projectId: number,
  image: File,
  prompt: string,
  signal?: AbortSignal,
): Promise<unknown> {
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

  return response.data;
}
