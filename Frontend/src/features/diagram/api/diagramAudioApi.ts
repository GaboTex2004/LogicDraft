import { api } from "../../../shared/api/api";

export async function transcribeDiagramAudio(
  projectId: number,
  audio: Blob,
  filename: string,
): Promise<string> {
  const form = new FormData();

  form.append("audio", audio, filename);

  const response = await api.post<{ text: string }>(
    `/proyectos/${projectId}/ai/audio/transcribe`,
    form,
    { timeout: 120_000 },
  );

  if (typeof response.data?.text !== "string" || !response.data.text.trim()) {
    throw new Error("El servidor no devolvió una transcripción válida.");
  }

  return response.data.text.trim();
}
