export interface LoginRequest {
  email: string
  password: string
}

export interface RegisterRequest {
  nombre: string
  email: string
  password: string
}

export interface AuthResponse {
  token: string
  tipoToken: string
  usuarioId: number
  nombre: string
  email: string
  rolSistema: string
}

export interface UsuarioActual {
  id: number
  nombre: string
  email: string
  rolSistema: string
}
