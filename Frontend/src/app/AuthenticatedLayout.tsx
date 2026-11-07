import { Outlet } from 'react-router-dom'
import { TutorHost } from '../features/tutor/components/TutorHost'

export function AuthenticatedLayout() {
  return <><Outlet /><TutorHost /></>
}
