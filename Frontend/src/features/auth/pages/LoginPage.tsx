import { Link, useNavigate } from 'react-router-dom'
import { LoginForm } from '../components/LoginForm'

export function LoginPage() {
  const navigate = useNavigate()

  return (
    <main>
      <h1>Iniciar sesión</h1>
      <LoginForm onLoginSuccess={() => navigate('/dashboard')} />
      <p>
        ¿No tienes una cuenta? <Link to="/register">Regístrate</Link>
      </p>
    </main>
  )
}
