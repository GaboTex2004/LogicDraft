import axios from "axios";
import type { AxiosResponse } from "axios";
import { api } from "../../../shared/api/api";

export function audioTranscriptionErrorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) return "No se pudo transcribir el audio.";
  if (error.code === "ECONNABORTED") return "La transcripción excedió el tiempo de espera.";
  switch (error.response?.status) {
    case 400:
    case 422:
      return "No se detectó voz en este fragmento.";
    case 413:
      return "El fragmento de audio supera el tamaño permitido.";
    case 415:
      return "El navegador generó un formato de audio no compatible.";
    case 503:
      return "El servicio de transcripción no está disponible.";
    case 504:
      return "La transcripción excedió el tiempo de espera.";
    default:
      return "No se pudo transcribir el audio.";
  }
}

export async function transcribeDiagramAudio(
  projectId: number,
  audio: Blob,
  filename: string,
): Promise<string> {
  const form = new FormData();

  form.append("audio", audio, filename);

  let response: AxiosResponse<{ text: string }>;
  try {
    response = await api.post<{ text: string }>(
      `/proyectos/${projectId}/ai/audio/transcribe`,
      form,
      { timeout: 120_000 },
    );
  } catch (error: unknown) {
    throw new Error(audioTranscriptionErrorMessage(error), { cause: error });
  }

  if (typeof response.data?.text !== "string" || !response.data.text.trim()) {
    throw new Error("El servidor no devolvió una transcripción válida.");
  }

  return response.data.text.trim();
}
