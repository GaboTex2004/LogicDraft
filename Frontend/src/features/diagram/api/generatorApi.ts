import { api } from '../../../shared/api/api'
import { fallbackFilename, filenameFromDisposition, triggerBlobDownload } from '../services/generatorDownload'

export type GeneratorExport = 'backend' | 'fullstack'

export async function descargarProyectoGenerado(
  projectId: number,
  kind: GeneratorExport,
  projectName: string,
): Promise<void> {
  const response = await api.post<Blob>(
    `/proyectos/${projectId}/generator/${kind}`,
    undefined,
    { responseType: 'blob' },
  )
  const fallback = fallbackFilename(projectName, kind)
  triggerBlobDownload(response.data, filenameFromDisposition(response.headers['content-disposition'], fallback))
}
