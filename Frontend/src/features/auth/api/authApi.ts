import { api } from '../../../shared/api/api'
import type {
  AuthResponse,
  LoginRequest,
  RegisterRequest,
  UsuarioActual,
} from '../types/auth.types'

export async function login(request: LoginRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>('/auth/login', request)
  return response.data
}

export async function register(request: RegisterRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>('/auth/register', request)
  return response.data
}

export async function obtenerUsuarioActual(): Promise<UsuarioActual> {
  const response = await api.get<UsuarioActual>('/auth/me')
  return response.data
}
