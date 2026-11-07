import { Outlet } from 'react-router-dom'
import { TutorHost } from '../features/tutor/components/TutorHost'
import { TutorTargetProvider } from '../features/tutor/components/TutorTargetProvider'

export function AuthenticatedLayout() {
  return <TutorTargetProvider><Outlet /><TutorHost /></TutorTargetProvider>
}
