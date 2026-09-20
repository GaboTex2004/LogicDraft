import { useEffect, useRef, useState, type FormEvent } from "react";
import { transcribeDiagramAudio } from "../api/diagramAudioApi";

type AiPromptBarProps = {
  projectId: number;
  onImageSelected?: (image: File, prompt: string) => Promise<void>;
  onSubmit: (prompt: string) => Promise<void>;
  loading: boolean;
  message: string;
  error: string;
  disabledReason?: string;
};

export function AiPromptBar({
  projectId,
  onImageSelected,
  onSubmit,
  loading,
  message,
  error,
  disabledReason,
}: AiPromptBarProps) {
  const [prompt, setPrompt] = useState("");
  const [recording, setRecording] = useState(false);
  const [transcribing, setTranscribing] = useState(false);
  const [audioError, setAudioError] = useState("");

  const imageInputRef = useRef<HTMLInputElement | null>(null);
  const recorderRef = useRef<MediaRecorder | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const chunksRef = useRef<Blob[]>([]);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;

    return () => {
      mountedRef.current = false;

      const recorder = recorderRef.current;
      if (recorder) {
        recorder.onstop = null;

        if (recorder.state !== "inactive") {
          recorder.stop();
        }
      }

      streamRef.current?.getTracks().forEach((track) => track.stop());
    };
  }, []);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (
      loading ||
      recording ||
      transcribing ||
      disabledReason ||
      !prompt.trim()
    ) {
      return;
    }

    void onSubmit(prompt.trim());
  }

  async function startRecording() {
    if (loading || transcribing || disabledReason || recorderRef.current) {
      return;
    }

    setAudioError("");

    if (
      !navigator.mediaDevices?.getUserMedia ||
      typeof MediaRecorder === "undefined"
    ) {
      setAudioError("Tu navegador no admite la grabación de audio.");
      return;
    }

    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        audio: true,
      });

      if (!mountedRef.current) {
        stream.getTracks().forEach((track) => track.stop());
        return;
      }

      streamRef.current = stream;

      const formats = ["audio/webm;codecs=opus", "audio/webm", "audio/mp4"];

      const mimeType = formats.find((format) =>
        MediaRecorder.isTypeSupported(format),
      );

      if (!mimeType) {
        stream.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        setAudioError("El navegador no ofrece un formato de audio compatible.");
        return;
      }

      const recorder = new MediaRecorder(stream, { mimeType });

      chunksRef.current = [];
      recorderRef.current = recorder;

      recorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          chunksRef.current.push(event.data);
        }
      };

      recorder.onerror = () => {
        chunksRef.current = [];
        stream.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        recorderRef.current = null;

        if (mountedRef.current) {
          setRecording(false);
          setAudioError("Ocurrió un error durante la grabación.");
        }
      };

      recorder.onstop = () => {
        stream.getTracks().forEach((track) => track.stop());
        streamRef.current = null;
        recorderRef.current = null;

        if (!mountedRef.current) return;

        setRecording(false);

        const blob = new Blob(chunksRef.current, {
          type: recorder.mimeType,
        });

        chunksRef.current = [];

        if (blob.size === 0) {
          setAudioError("No se grabó ningún audio.");
          return;
        }

        if (blob.size > 10 * 1024 * 1024) {
          setAudioError("La grabación supera el límite de 10 MB.");
          return;
        }

        const filename = recorder.mimeType.includes("mp4")
          ? "grabacion.mp4"
          : "grabacion.webm";

        setTranscribing(true);

        void transcribeDiagramAudio(projectId, blob, filename)
          .then((text) => {
            if (!mountedRef.current) return;

            setPrompt((previous) =>
              previous.trim() ? `${previous.trim()} ${text}` : text,
            );
          })
          .catch(() => {
            if (mountedRef.current) {
              setAudioError(
                "No se pudo transcribir el audio. Comprueba la conexión con el servidor.",
              );
            }
          })
          .finally(() => {
            if (mountedRef.current) {
              setTranscribing(false);
            }
          });
      };

      recorder.start();
      setRecording(true);
    } catch {
      streamRef.current?.getTracks().forEach((track) => track.stop());
      streamRef.current = null;
      recorderRef.current = null;

      if (mountedRef.current) {
        setAudioError(
          "No se pudo acceder al micrófono. Revisa los permisos del navegador.",
        );
      }
    }
  }
  function handleImageSelected(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];

    // Permite seleccionar nuevamente el mismo archivo.
    event.target.value = "";

    if (!file) return;

    setAudioError("");

    const allowedTypes = ["image/png", "image/jpeg", "image/webp"];

    if (!allowedTypes.includes(file.type)) {
      setAudioError("Selecciona una imagen PNG, JPG o WEBP.");
      return;
    }

    if (file.size > 5 * 1024 * 1024) {
      setAudioError("La imagen supera el límite de 5 MB.");
      return;
    }

    if (!onImageSelected) return;

    void onImageSelected(file, prompt.trim());
  }
  function handleMicrophone() {
    if (recording) {
      const recorder = recorderRef.current;

      if (recorder?.state === "recording") {
        recorder.stop();
      }

      return;
    }

    void startRecording();
  }

  return (
    <div className="ai-prompt-wrapper">
      <form className="ai-prompt-bar" onSubmit={handleSubmit}>
        <strong>LogicDraft</strong>

        <input
          maxLength={10000}
          disabled={loading || transcribing}
          value={prompt}
          onChange={(event) => setPrompt(event.target.value)}
          placeholder="Describe el cambio que quieres hacer en el diagrama..."
          aria-label="Instrucción para IA"
        />

        <input
          ref={imageInputRef}
          type="file"
          accept="image/png,image/jpeg,image/webp"
          style={{ display: "none" }}
          onChange={handleImageSelected}
          aria-label="Seleccionar imagen del diagrama"
        />

        <button
          type="button"
          title="Adjuntar imagen"
          aria-label="Adjuntar imagen"
          disabled={
            !onImageSelected ||
            loading ||
            recording ||
            transcribing ||
            !!disabledReason
          }
          onClick={() => imageInputRef.current?.click()}
        >
          ▧
        </button>

        <button
          type="button"
          onClick={handleMicrophone}
          disabled={
            transcribing || (!recording && (loading || !!disabledReason))
          }
          title={recording ? "Detener grabación" : "Grabar voz"}
          aria-label={recording ? "Detener grabación" : "Usar micrófono"}
          aria-pressed={recording}
        >
          {recording ? "■" : "◉"}
        </button>

        <button
          className="ai-send"
          type="submit"
          disabled={
            loading ||
            recording ||
            transcribing ||
            !prompt.trim() ||
            !!disabledReason
          }
          title={disabledReason || "Enviar"}
          aria-label="Enviar instrucción"
        >
          {loading ? "…" : "➤"}
        </button>
      </form>

      <span
        className="ai-coming-soon"
        role={audioError || error ? "alert" : "status"}
      >
        {recording
          ? "Grabando... Pulsa nuevamente para detener."
          : transcribing
            ? "Transcribiendo audio..."
            : audioError || error || disabledReason || message}
      </span>
    </div>
  );
}
