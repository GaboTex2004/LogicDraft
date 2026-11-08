import { createContext } from 'react'
export interface TutorAudioSettings { enabled: boolean; recording: boolean; message: string; toggle: () => void; repeat: () => void }
export const TutorAudioContext = createContext<TutorAudioSettings | null>(null)
