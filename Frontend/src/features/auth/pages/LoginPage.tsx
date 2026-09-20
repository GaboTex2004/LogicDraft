import { Link, useNavigate } from "react-router-dom";
import { LoginForm } from "../components/LoginForm";
import "../styles/auth.css";
import { AuthDiagramPreview } from "../components/AuthDiagramPreview";

export function LoginPage() {
  const navigate = useNavigate();

  return (
    <main className="ld-auth-page">
      {/* COLUMNA IZQUIERDA: LOGIN */}
      <section className="ld-auth-panel">
        <header className="ld-auth-brand">
          <span className="ld-auth-brand-icon" aria-hidden="true">
            ◇
          </span>
          <span>LogicDraft</span>
        </header>

        <div className="ld-auth-content">
          <h1>Bienvenido de nuevo</h1>

          <p className="ld-auth-subtitle">
            Continúa diseñando, colaborando y creando diagramas inteligentes.
          </p>

          <LoginForm onLoginSuccess={() => navigate("/dashboard")} />

          <p className="ld-auth-switch">
            ¿No tienes cuenta? <Link to="/register">Regístrate</Link>
          </p>
        </div>

        <footer className="ld-auth-footer">
          LogicDraft · Diseña, conecta y crea
        </footer>
      </section>

      {/* COLUMNA DERECHA: DIAGRAMA DECORATIVO */}
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
