import { useState, type FormEvent } from "react";
import { crearWorkspace } from "../api/workspaceApi";
import type { Workspace } from "../types/workspace.types";

interface CreateWorkspaceFormProps {
  onCreated: (workspace: Workspace) => void;
  onCancel: () => void;
}

export function CreateWorkspaceForm({
  onCreated,
  onCancel,
}: CreateWorkspaceFormProps) {
  const [nombre, setNombre] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (loading) return;

    const nombreLimpio = nombre.trim();

    if (!nombreLimpio) {
      setError("Ingresa un nombre para el workspace.");
      return;
    }

    if (nombreLimpio.length > 120) {
      setError("El nombre no puede superar los 120 caracteres.");
      return;
    }

    setLoading(true);
    setError("");

    try {
      const workspace = await crearWorkspace({
        nombre: nombreLimpio,
      });

      onCreated(workspace);
    } catch {
      setError(
        "No se pudo crear el workspace. Comprueba tus permisos e inténtalo nuevamente.",
      );
    } finally {
      setLoading(false);
    }
  }

  return (
    <form className="create-workspace-form" onSubmit={handleSubmit}>
      <h3>Nuevo workspace</h3>

      <p>Crea un espacio para organizar tus proyectos y diagramas.</p>

      <label htmlFor="new-workspace-name">Nombre del workspace</label>

      <input
        id="new-workspace-name"
        name="nombre"
        type="text"
        value={nombre}
        onChange={(event) => {
          setNombre(event.target.value);
          if (error) setError("");
        }}
        placeholder="Ej. Mi proyecto de software"
        maxLength={120}
        autoComplete="off"
        autoFocus
        required
        disabled={loading}
      />

      {error && (
        <p className="create-workspace-error" role="alert">
          {error}
        </p>
      )}

      <div className="create-workspace-actions">
        <button type="button" onClick={onCancel} disabled={loading}>
          Cancelar
        </button>

        <button type="submit" disabled={loading || !nombre.trim()}>
          {loading ? "Creando..." : "Crear workspace"}
        </button>
      </div>
    </form>
  );
}
