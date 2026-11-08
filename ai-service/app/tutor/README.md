# Autoría de audio del instructor

Para añadir tutoriales y entregar guiones al usuario para generación externa/manual con Puck, seguir [la guía de ampliación](../../../../GUIA-AMPLIAR-TUTORIALES-Y-AUDIOS.md). Ese es el flujo acordado para futuros tutoriales; no generar audios nuevos sin indicación del usuario.

## Catálogo hablado Puck — E4

Desde `Frontend`, `npm.cmd run tutor:audio:manifest` prepara `public/tutor-audio/manifest.json` desde el catálogo real. Mantiene el texto fuente de cada paso y sustituye nombres/tipos/cardinalidades variables por referencias a la instrucción de pantalla. Los archivos se deduplican por voz, modelo y guion; 28 pasos necesitan 21 WAV únicos.

Desde `ai-service`, generar los pendientes con:

```powershell
.\venv\Scripts\python.exe -u -m app.tutor.generate_catalog
```

Usa Puck y la clave Gemini existente. Valida los WAV existentes, no los sobrescribe y separa las nuevas solicitudes 30 segundos. Se detiene ante el primer error, conserva el progreso y publica la lista `files` disponible en el manifest. No hay endpoint ni generación durante el uso de la plataforma. Los audios estáticos se incluyen en el build de Vite y pueden versionarse junto al manifest; las muestras comparativas permanecen excluidas de Git.

Estado actualizado: 21 de 21 audios generados y verificados, cubriendo los 28 pasos del catálogo. Se reutilizaron los 11 existentes; las solicitudes restantes se espaciaron y se conservaron los progresos ante HTTP 429. No se cambió facturación ni modelo para eludir límites. La interfaz conserva su respuesta en texto si falta un archivo o falla su reproducción.

Verificar los WAV y crear la página de escucha sin consumir API:

```powershell
.\venv\Scripts\python.exe -m app.tutor.check_catalog
```

Comprueba datos PCM completos e igualdad entre archivos e índice de disponibilidad. Genera `Frontend/public/tutor-audio/review.html`, accesible con Vite en `/tutor-audio/review.html` y también abriendo el archivo directamente. Las validaciones técnicas no acreditan que la voz haya recitado correctamente cada frase; esa revisión auditiva corresponde al usuario.

Pendiente escuchar y revisar cada audio por exactitud, pronunciación, acento y ritmo. La disponibilidad/decodificación técnica no equivale a aprobación auditiva. La bienvenida tras registro sigue siendo otro trabajo de E4.

**Voz seleccionada: Puck**, confirmada por el usuario después de escuchar las muestras. Para el catálogo utilizar Puck, español latinoamericano y el tono de instructor cercano, claro y tranquilo de la muestra. El generador de muestras conserva su función comparativa; la generación del catálogo y su integración en el tutor están pendientes. No es necesario volver a generar la muestra elegida.

Primera entrega E4: comparar voces naturales con dos muestras del mismo texto fijo en español. La generación ocurre mediante CLI local; no se publica un endpoint ni se añade gasto a las consultas del tutor. Se usa el SDK `google-genai` existente y la configuración habitual del servidor, sin imprimir secretos.

Desde `ai-service`:

```powershell
.\venv\Scripts\python.exe -m app.tutor.voice_samples
```

Genera Kore y Puck con `gemini-2.5-flash-preview-tts`. Opcionalmente `--voice Zephyr` selecciona otra voz; `--model` o `TUTOR_TTS_MODEL` permiten cambiar el modelo sin alterar el modelo de diagramas. Son llamadas reales que consumen la cuota del proyecto de Gemini. No hay reintentos automáticos ni sobrescritura de audio existente.

Abrir `ai-service/voice-samples.local/index.html` para escuchar. La carpeta queda excluida de Git y todavía no contiene los audios del catálogo ni se integra en React. La página no reproduce automáticamente y usa el mismo texto para comparar voces. Antes de publicar audios, revisar pronunciación, exactitud del texto, acento, ritmo y calidad real escuchándolos; las pruebas técnicas no verifican estas cualidades.

Pruebas sin acceso a la API ni dependencias adicionales:

```powershell
.\venv\Scripts\python.exe -m unittest discover -s tests -p test_tutor_voice_samples.py -v
```

Se valida conversión de PCM16 a WAV, formato de audio, ausencia de candidatos y página de escucha manual. La suite general se ejecuta por separado con `python -m pytest -q` cuando pytest esté disponible.

Próximo paso tras elegir voz: guion hablado estable por paso, manifest de audios/versiones, generación controlada y reproductor React con repetición, silencio y cancelación coordinada con los pasos y el micrófono. Los nombres variables deben reemplazarse por referencias al elemento visible, sin generar audio de usuarios durante el recorrido.

Fuente del contrato de Gemini TTS: https://ai.google.dev/gemini-api/docs/speech-generation
