import { useState, type FormEvent } from "react";
import { login } from "../api/authApi";

interface LoginFormProps {
  onLoginSuccess: () => void;
}

export function LoginForm({ onLoginSuccess }: LoginFormProps) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (loading) return;

    setLoading(true);
    setError("");

    try {
      const response = await login({ email, password });
      localStorage.setItem("token", response.token);
      onLoginSuccess();
    } catch {
      setError("No se pudo iniciar sesión. Verifica tus credenciales.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <form
      className="ld-auth-form"
      onSubmit={handleSubmit}
      aria-label="Formulario de inicio de sesión"
    >
      {/* CORREO ELECTRÓNICO */}
      <div className="ld-auth-field">
        <label htmlFor="login-email">Correo electrónico</label>

        <div className="ld-auth-input-wrapper">
          <svg
            className="ld-auth-input-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <rect x="2" y="4" width="20" height="16" rx="2" />
            <path d="m2 6 10 7 10-7" />
          </svg>

          <input
            id="login-email"
            name="email"
            type="email"
            placeholder="tu@correo.com"
            autoComplete="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            required
            disabled={loading}
          />
        </div>
      </div>

      {/* CONTRASEÑA */}
      <div className="ld-auth-field">
        <label htmlFor="login-password">Contraseña</label>

        <div className="ld-auth-input-wrapper">
          <svg
            className="ld-auth-input-icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <rect x="4" y="10" width="16" height="11" rx="2" />
            <path d="M8 10V7a4 4 0 0 1 8 0v3" />
          </svg>

          <input
            id="login-password"
            name="password"
            type={showPassword ? "text" : "password"}
            placeholder="Ingresa tu contraseña"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            required
            disabled={loading}
          />

          <button
            className="ld-auth-password-toggle"
            type="button"
            aria-label={
              showPassword ? "Ocultar contraseña" : "Mostrar contraseña"
            }
            aria-pressed={showPassword}
            onClick={() => setShowPassword((current) => !current)}
            disabled={loading}
          >
            {showPassword ? (
              // Ojo cerrado: ocultar contraseña
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="M3 3 21 21" />
                <path d="M10.6 5.1A10.8 10.8 0 0 1 12 5c5 0 9 7 9 7a17 17 0 0 1-3.1 3.5" />
                <path d="M6.2 6.3C3.9 8 3 12 3 12s4 7 9 7c1.4 0 2.7-.4 3.8-1" />
                <path d="M10 10a3 3 0 0 0 4 4" />
              </svg>
            ) : (
              // Ojo abierto: mostrar contraseña
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
                <circle cx="12" cy="12" r="3" />
              </svg>
            )}
          </button>
        </div>
      </div>

      {error && (
        <p className="ld-auth-error" role="alert">
          {error}
        </p>
      )}

      {/* BOTÓN DE LOGIN */}
      <button className="ld-auth-submit" type="submit" disabled={loading}>
        <span>{loading ? "Iniciando sesión..." : "Iniciar sesión"}</span>

        {!loading && (
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M5 12h14" />
            <path d="m13 6 6 6-6 6" />
          </svg>
        )}
      </button>
    </form>
  );
}
