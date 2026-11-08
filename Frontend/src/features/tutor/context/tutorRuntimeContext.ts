import { createContext } from 'react'
import type { TutorRuntimeStore } from '../services/tutorialRuntimeStore.ts'
export const TutorRuntimeContext = createContext<TutorRuntimeStore | null>(null)
