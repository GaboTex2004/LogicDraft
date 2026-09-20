import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { register } from "../api/authApi";
import { AuthDiagramPreview } from "../components/AuthDiagramPreview";
import "../styles/auth.css";

export function RegisterPage() {
  const navigate = useNavigate();

  const [nombre, setNombre] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const [showPassword, setShowPassword] = useState(false);
  const [showConfirmPassword, setShowConfirmPassword] = useState(false);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const passwordsMatch =
    confirmPassword.length > 0 && password === confirmPassword;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    if (loading) return;

    setError("");

    if (password.length < 8) {
      setError("La contraseña debe tener al menos 8 caracteres.");
      return;
    }

    if (password !== confirmPassword) {
      setError("Las contraseñas no coinciden.");
      return;
    }

    setLoading(true);

    try {
      const response = await register({
        nombre: nombre.trim(),
        email: email.trim(),
        password,
      });

      localStorage.setItem("token", response.token);
      navigate("/dashboard");
    } catch {
      setError("No se pudo completar el registro. Revisa tus datos.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="ld-auth-page ld-auth-register">
      {/* COLUMNA IZQUIERDA */}
      <section className="ld-auth-panel">
        <header className="ld-auth-brand">
          <span className="ld-auth-brand-icon" aria-hidden="true">
            ◇
          </span>

          <span>LogicDraft</span>
        </header>

        <div className="ld-auth-content">
          <h1>Comienza con LogicDraft</h1>

          <p className="ld-auth-subtitle">
            Crea tu cuenta y transforma tus ideas en diagramas.
          </p>

          <form
            className="ld-auth-form"
            onSubmit={handleSubmit}
            aria-label="Formulario de registro"
          >
            {/* NOMBRE */}
            <div className="ld-auth-field">
              <label htmlFor="register-name">Nombre completo</label>

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
                  <circle cx="12" cy="8" r="4" />
                  <path d="M4 21v-2a8 8 0 0 1 16 0v2" />
                </svg>

                <input
                  id="register-name"
                  name="nombre"
                  type="text"
                  placeholder="Tu nombre completo"
                  autoComplete="name"
                  value={nombre}
                  onChange={(event) => setNombre(event.target.value)}
                  required
                  maxLength={100}
                  disabled={loading}
                />
              </div>
            </div>

            {/* CORREO */}
            <div className="ld-auth-field">
              <label htmlFor="register-email">Correo electrónico</label>

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
                  id="register-email"
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
              <label htmlFor="register-password">Contraseña</label>

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
                  id="register-password"
                  name="password"
                  type={showPassword ? "text" : "password"}
                  placeholder="Crea una contraseña"
                  autoComplete="new-password"
                  minLength={8}
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
                  <PasswordEye visible={showPassword} />
                </button>
              </div>

              <p className="ld-auth-hint">Mínimo 8 caracteres.</p>
            </div>

            {/* CONFIRMAR CONTRASEÑA */}
            <div className="ld-auth-field">
              <label htmlFor="register-confirm-password">
                Confirmar contraseña
              </label>

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
                  id="register-confirm-password"
                  name="confirmPassword"
                  type={showConfirmPassword ? "text" : "password"}
                  placeholder="Repite tu contraseña"
                  autoComplete="new-password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  required
                  aria-invalid={confirmPassword.length > 0 && !passwordsMatch}
                  disabled={loading}
                />

                <button
                  className="ld-auth-password-toggle"
                  type="button"
                  aria-label={
                    showConfirmPassword
                      ? "Ocultar confirmación de contraseña"
                      : "Mostrar confirmación de contraseña"
                  }
                  aria-pressed={showConfirmPassword}
                  onClick={() => setShowConfirmPassword((current) => !current)}
                  disabled={loading}
                >
                  <PasswordEye visible={showConfirmPassword} />
                </button>
              </div>

              {confirmPassword.length > 0 && (
                <p
                  className={
                    passwordsMatch
                      ? "ld-auth-hint ld-auth-hint-success"
                      : "ld-auth-hint ld-auth-hint-error"
                  }
                >
                  {passwordsMatch
                    ? "Las contraseñas coinciden."
                    : "Las contraseñas no coinciden."}
                </p>
              )}
            </div>

            {error && (
              <p className="ld-auth-error" role="alert">
                {error}
              </p>
            )}

            <button className="ld-auth-submit" type="submit" disabled={loading}>
              <span>{loading ? "Creando cuenta..." : "Crear cuenta"}</span>

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

          <p className="ld-auth-switch">
            ¿Ya tienes una cuenta? <Link to="/login">Inicia sesión</Link>
          </p>
        </div>

        <footer className="ld-auth-footer">
          LogicDraft · Diseña, conecta y crea
        </footer>
      </section>

      {/* COLUMNA DERECHA: REUTILIZAMOS EL DIAGRAMA */}
      <aside
        className="ld-auth-visual"
        aria-label="Diagrama interactivo de LogicDraft"
      >
        <header className="ld-auth-visual-header">
          <span>SCHEMA_PREVIEW / LOGICDRAFT</span>
          <span>Arrastra las entidades para explorar</span>
        </header>

        <AuthDiagramPreview />

        <footer className="ld-auth-visual-footer">
          Arquitectura de diagramas asistida por IA
        </footer>
      </aside>
    </main>
  );
}

function PasswordEye({ visible }: { visible: boolean }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {visible ? (
        <>
          <path d="M3 3 21 21" />
          <path d="M10.6 5.1A10.8 10.8 0 0 1 12 5c5 0 9 7 9 7a17 17 0 0 1-3.1 3.5" />
          <path d="M6.2 6.3C3.9 8 3 12 3 12s4 7 9 7c1.4 0 2.7-.4 3.8-1" />
          <path d="M10 10a3 3 0 0 0 4 4" />
        </>
      ) : (
        <>
          <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
          <circle cx="12" cy="12" r="3" />
        </>
      )}
    </svg>
  );
}
