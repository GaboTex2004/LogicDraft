import { createBrowserRouter, Navigate } from 'react-router-dom'
import { LoginPage } from '../features/auth/pages/LoginPage'
import { RegisterPage } from '../features/auth/pages/RegisterPage'
import { CollaborationPage } from '../features/collaboration/pages/CollaborationPage'
import { DashboardPage } from '../features/dashboard/pages/DashboardPage'
import { DiagramEditorPage } from '../features/diagram/pages/DiagramEditorPage'
import { ProjectsPage } from '../features/project/pages/ProjectsPage'
import { ProtectedRoute } from '../shared/components/ProtectedRoute'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <Navigate to="/dashboard" replace />,
  },
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/register',
    element: <RegisterPage />,
  },
  {
    element: <ProtectedRoute />,
    children: [
      {
        path: '/dashboard',
        element: <DashboardPage />,
      },
      {
        path: '/colaboracion',
        element: <CollaborationPage />,
      },
      {
        path: '/workspaces/:workspaceId/proyectos',
        element: <ProjectsPage />,
      },
      {
        path: '/proyectos/:projectId/editor',
        element: <DiagramEditorPage />,
      },
    ],
  },
])
