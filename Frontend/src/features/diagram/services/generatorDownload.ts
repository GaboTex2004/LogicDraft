export interface DownloadAnchor {
  href: string
  download: string
  click: () => void
  remove: () => void
}

export interface DownloadPlatform {
  createObjectUrl: (blob: Blob) => string
  revokeObjectUrl: (url: string) => void
  createAnchor: () => DownloadAnchor
  appendAnchor: (anchor: DownloadAnchor) => void
  defer: (callback: () => void) => void
}

function safeFilename(value: string | undefined): string | null {
  const filename = value?.trim().replace(/^['"]|['"]$/g, '')
  return filename && !/[\\/]/.test(filename) ? filename : null
}

export function filenameFromDisposition(value: string | undefined, fallback: string): string {
  const encoded = value?.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)?.[1]
  if (encoded) {
    try {
      const decoded = safeFilename(decodeURIComponent(encoded))
      if (decoded) return decoded
    } catch {
      // Continue with the regular filename or the safe fallback.
    }
  }
  const regular = value?.match(/filename\s*=\s*(?:"([^"]+)"|([^;]+))/i)
  return safeFilename(regular?.[1] ?? regular?.[2]) ?? fallback
}

export function technicalFilenameBase(projectName: string): string {
  const normalized = projectName.normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
  return normalized || 'proyecto'
}

export function fallbackFilename(projectName: string, kind: 'backend' | 'fullstack'): string {
  const base = technicalFilenameBase(projectName)
  return kind === 'backend' ? `${base}-backend.zip` : `${base}.zip`
}

function browserPlatform(): DownloadPlatform {
  return {
    createObjectUrl: (blob) => URL.createObjectURL(blob),
    revokeObjectUrl: (url) => URL.revokeObjectURL(url),
    createAnchor: () => document.createElement('a'),
    appendAnchor: (anchor) => document.body.appendChild(anchor as HTMLAnchorElement),
    defer: (callback) => window.setTimeout(callback, 0),
  }
}

export function triggerBlobDownload(blob: Blob, filename: string, platform: DownloadPlatform = browserPlatform()): void {
  const url = platform.createObjectUrl(blob)
  const anchor = platform.createAnchor()
  anchor.href = url
  anchor.download = filename
  platform.appendAnchor(anchor)
  anchor.click()
  anchor.remove()
  platform.defer(() => platform.revokeObjectUrl(url))
}
