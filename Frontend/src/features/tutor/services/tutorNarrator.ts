export interface NarrationAudio {
  src: string; currentTime: number
  play(): Promise<void>
  pause(): void
  onended: ((event: Event) => void) | null
  onerror: ((event: Event | string) => void) | null
}

/** One player, no queue. Completion of audio never calls the tutorial engine. */
export function createTutorNarrator(factory: () => NarrationAudio, notify: (message: string) => void) {
  let audio: NarrationAudio | null = null, generation = 0, lastKey: string | null = null
  function stop() { generation++; if (audio) { audio.onended = null; audio.onerror = null; audio.pause(); audio.src = '' }; audio = null; lastKey = null }
  return {
    stop,
    async play(key: string, url: string, repeat = false) {
      if (!repeat && key === lastKey) return
      stop(); lastKey = key
      const token = generation
      const player = factory(); audio = player; player.src = url
      player.onended = () => { if (token === generation) notify('') }
      player.onerror = () => { if (token === generation) { stop(); notify('No se pudo reproducir la voz. Puedes continuar leyendo la instrucción.') } }
      try { await player.play() } catch { if (token === generation) { stop(); notify('Pulsa Repetir para permitir la reproducción o continúa con el texto.') } }
    },
  }
}
