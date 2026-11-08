import { useContext, useEffect } from 'react'
import { TutorRuntimeContext } from '../context/tutorRuntimeContext'
import type { TutorScene } from '../services/tutorialRuntimeStore.ts'

export function useTutorRuntime() { return useContext(TutorRuntimeContext) }

/** Publish only committed React state. Dependencies are supplied as a memoized scene. */
export function useTutorScene(scene: TutorScene | null) {
  const runtime = useTutorRuntime()
  useEffect(() => { if (scene) runtime?.publish(scene) }, [runtime, scene])
  const path = scene?.path
  useEffect(() => () => { if (path) runtime?.clearScene(path) }, [runtime, path])
}
