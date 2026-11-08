# Guía para añadir tutoriales y preparar sus voces

Fecha: 8 de noviembre de 2026.

## Propósito y acuerdo de trabajo

Añadir tutoriales de forma incremental, conservando el editor, permisos, colaboración y diseño existentes. El instructor utiliza contenido predefinido: la IA de voz lee el guion; no decide los pasos ni cuándo se completan.

**Los audios nuevos los genera y revisa el usuario por separado**, usando Gemini TTS con la voz **Puck**. Preparar primero los tutoriales y sus guiones, entregar la lista exacta de grabaciones necesarias y conectar los archivos después de recibirlos. No realizar llamadas de generación, regenerar audios ni consumir cuota automáticamente sin indicación del usuario.

La unidad de audio es **una instrucción de un paso**, no un tutorial entero. Puede haber varios pasos que compartan el mismo audio. Se pueden preparar todas las instrucciones nuevas en un lote de trabajo, pero el resultado debe conservar un archivo por instrucción distinta para permitir pausa, repetición y avance real.

## 1. Definir el tutorial antes de programarlo

Completar esta ficha:

| Campo | Qué decidir |
| --- | --- |
| ID estable | Ejemplo: `inspect_relationship`; no reutilizar IDs con otro significado |
| Propósito | Acción concreta que se aprenderá dentro de LogicDraft |
| Pantalla inicial | `dashboard`, `projects` o `editor` |
| Acceso | OWNER/EDITOR si requiere modificar; VIEWER sólo para lectura/navegación |
| Elementos necesarios | Workspace, proyecto, nodo, atributo o relación, elegidos por ID |
| Parámetros | Nombres, tipos u otros valores que deberá indicar el usuario |
| Prerrequisitos | Qué debe existir y qué guía ofrecer si falta |
| Pasos | Una instrucción breve y una condición comprobable por paso |
| Salidas | IDs reales producidos que usarán pasos o lecciones posteriores |
| Audio | Texto hablado fijo; referencias a pantalla para datos variables |
| Pruebas | Caso correcto, permisos, contexto erróneo, pausas y fallos |

Si el tutorial pretende enseñar modelado conceptual o una función nueva, definir su alcance antes de incorporarlo. El motor actual no evalúa si un modelo es conceptualmente correcto. No basta cambiar `domain` a `modeling` para añadir esa capacidad.

## 2. Archivos del módulo y su función

| Archivo o carpeta | Cuándo intervenir |
| --- | --- |
| `Frontend/src/features/tutor/content/catalog.ts` | Registrar definición, instrucciones, ejemplos, parámetros, pasos y condiciones |
| `content/targets.ts` | Declarar un control lógico nuevo y dónde está en la UI |
| `types/tutorial.types.ts` | Sólo si se necesita una pantalla, parámetro, binding o condición aún no soportada |
| `types/tutorialRuntime.types.ts` | Si la observación/evidencia necesita datos nuevos |
| `hooks/useTutorTargets.ts` y componentes existentes | Registrar refs contextualizadas, sin buscar botones por texto |
| `services/tutorialDiagramAdapter.ts` | Proyectar datos del editor; emitir evidencia desde acciones locales aceptadas |
| `services/tutorialEngine.ts` | Implementar una condición nueva; no duplicarla en componentes |
| `services/tutorialRuntimeStore.ts` | Coordinación de lecciones, contexto, evidencias y salidas |
| `components/TutorLessonControls.tsx` | Formularios de parámetros/bindings y mensajes al usuario |
| `content/firstDiagram.ts` | Sólo si se decide ampliar o cambiar el recorrido de bienvenida |
| `Frontend/scripts/exportTutorAudio.mjs` | Guion hablado y sustituciones de parámetros |
| `Frontend/public/tutor-audio/manifest.json` | Índice generado de pasos, guiones y archivos |
| `Frontend/public/tutor-audio/*.wav` | Audios finales versionables |
| `ai-service/app/tutor/` | Herramientas offline de autoría/comprobación; no endpoints públicos de generación |
| `CATALOGO-TUTORIALES-LOGICDRAFT.md` | Explicación funcional del tutorial y sus activadores |
| `PLAN-TUTOR-LOGICDRAFT.md` | Estado real, validación y pendientes |

Las rutas abreviadas `content/`, `services/`, etc. en esta tabla pertenecen a `Frontend/src/features/tutor/`. Backend y ai-service sólo requieren cambios si la nueva función realmente los necesita. Añadir contenido sobre controles existentes suele poder resolverse dentro de frontend.

## 3. Registrar contenido y controles

1. Añadir una definición en `catalog.ts` respetando `TutorialDefinition`. Usar el helper `step` para los pasos.
2. Reutilizar destinos existentes cuando correspondan. Para uno nuevo, añadirlo a `targets.ts` y registrar su ref con `useTutorTargets` en el componente que lo muestra.
3. Los controles repetidos necesitan la instancia concreta: ID de nodo, atributo, relación, proyecto o workspace. Conservar el ámbito exacto de sesión, pantalla, workspace y proyecto.
4. `integration: 'registered'` significa que se conectó una ref real, no que la acción ya fue implementada ni que el tutorial esté probado.
5. No resaltar controles ocultos, bloqueados, ajenos o deshabilitados. El backend sigue autorizando todas las operaciones.
6. La ficha aparecerá en el catálogo visual al añadir la definición. Esto **no implementa reconocimiento de nuevas preguntas escritas**: el clasificador contextual corresponde a sus etapas del plan.
7. Para una capacidad nueva, revisar también el formulario y los adaptadores: declarar un parámetro en el tipo no crea automáticamente un selector adecuado ni su observación.

Ejemplo de estructura orientativa, no un tutorial listo para copiar sin adaptar:

```ts
{
  id: 'id_nuevo',
  contentVersion: 1,
  domain: 'platform',
  title: 'Acción que se enseñará',
  examples: ['Cómo realizo esta acción'],
  entryScreens: ['editor'],
  roles: ['OWNER', 'EDITOR'],
  documentVersions: [1, 2],
  prerequisites: ['open_editor'],
  inputs: ['project', 'node'],
  parameters: [],
  outputs: [],
  steps: [/* instrucción, destino, binding y condición reales */],
}
```

Los contratos actuales usan `contentVersion: 1` y `catalogVersion: 1`. No aumentar estos números arbitrariamente: un versionado nuevo requiere actualizar tipos, validadores y consumidores. El documento del diagrama, la revisión de concurrencia y la versión del catálogo son conceptos distintos.

## 4. Elegir la evidencia de cada paso

| Condición actual | Qué acredita |
| --- | --- |
| `screen` | La pantalla esperada y el workspace/proyecto elegido |
| `control-ready` | Un control exacto montado, visible, habilitado y permitido |
| `input-valid` | Actualmente sólo nombre de proyecto libre no vacío, hasta 100 caracteres |
| `input-matches` | Entrada igual al parámetro solicitado |
| `selected` | Selección del elemento ligado al paso |
| `field-matches` | Valor del campo del elemento correcto |
| `primary-key-enabled` | Clave primaria habilitada en el atributo elegido |
| `relation-kind` | Modo ASSOCIATION seleccionado |
| `created` | Creación local aceptada, nueva y todavía presente; captura su ID |
| `saved-revision` | Confirmación de guardado local que cubre los cambios pendientes |

Para cualquier acción nueva:

1. Capturar el ticket del tutor al iniciar la acción, antes de operaciones asíncronas.
2. Emitir evidencia sólo después de aceptar la edición y obtener locks cuando correspondan.
3. Publicar el estado confirmado de React. El store conserva la evidencia mientras espera ese resultado.
4. Usar valores reales: identidad, ámbito, campo, valor, IDs, tipo y padres/extremos cuando se requieran.
5. No acreditar una acción por un clic, un temporizador, el fin de audio o un diff genérico del diagrama. No atribuir eventos remotos al alumno.
6. No completar un guardado por `saveState: saved` o por `DIAGRAM_SAVED` genérico: se requiere el ACK de la petición local y sus revisiones.

Un paso inicialmente satisfecho puede registrarse como `already-satisfied`; no se debe fingir que el alumno acaba de realizar una edición. Los borradores temporalmente incompletos no se envían al servidor por autoguardado; los rechazos reales y conflictos siguen visibles. Omitir una creación no inventa outputs. Anterior no deshace datos.

## 5. Preparar los guiones sin llamar a Gemini

Desde `Frontend`:

```powershell
npm.cmd run tutor:audio:manifest
```

Este comando **no genera audio ni consume cuota**. Exporta el catálogo a `public/tutor-audio/manifest.json` con:

- Clave `tutorialId/stepId`.
- `source`: texto original que verifica el reproductor contra el paso vigente.
- `spoken`: frase fija que debe leer Puck.
- `file`: nombre exacto del WAV esperado.
- `files`: archivos que ya existen; la comprobación profunda se hace después.

Revisar las sustituciones de parámetros en `exportTutorAudio.mjs`. Por ejemplo, hablar «el nombre indicado en pantalla» en vez de generar una grabación por cada nombre que escriba un usuario. No dejar llaves `{{...}}` sin resolver ni leer IDs internos.

Si se cambia la redacción hablada, hacerlo en el exportador y volver a exportar. **No editar `spoken` ni `file` a mano en el manifest**: el nombre del archivo se calcula a partir del modelo, la voz y el guion. Un guion idéntico reutiliza audio; un guion cambiado obtiene un nombre distinto. No borrar automáticamente los WAV anteriores: revisar primero si siguen referenciados.

## 6. Entregar al usuario el lote exacto que falta

Desde la raíz, esta consulta de PowerShell lista instrucciones pendientes del tutorial elegido, sin hacer llamadas a la API:

```powershell
$tutorialElegido = 'id_nuevo'
$indiceVoces = Get-Content -LiteralPath 'Frontend/public/tutor-audio/manifest.json' -Raw | ConvertFrom-Json
$pendientesVoz = $indiceVoces.entries.PSObject.Properties |
  Where-Object {
    $_.Name.StartsWith($tutorialElegido + '/') -and
    $_.Value.file -notin $indiceVoces.files
  }
$pendientesVoz | ForEach-Object {
  [pscustomobject]@{
    Paso = $_.Name
    Texto = $_.Value.spoken
    Archivo = $_.Value.file
    Voz = $indiceVoces.voice
    Modelo = $indiceVoces.model
  }
} | Format-List
```

Entregar esa información **antes de generar**. Si varios pasos tienen el mismo `Archivo`, sólo necesitan una grabación. Para un lote de varios tutoriales, repetir la selección y unir por nombre de archivo; no regenerar lo que ya existe.

No editar el manifest para ocultar los tutoriales antiguos y así limitar un lote: rompería el índice usado por la plataforma.

## 7. Generar las voces aparte, bajo control del usuario

### Opción A: generación externa/manual con Gemini TTS

En Google AI Studio o en tu herramienta Gemini TTS:

1. Elegir el modelo indicado en el manifest y la voz **Puck**. El modelo actual es `gemini-2.5-flash-preview-tts`; su disponibilidad debe comprobarse al usarlo en el futuro.
2. Usar el campo `spoken` como texto que debe recitar, con esta dirección de voz:

   > Español latinoamericano. Tono cercano, claro y tranquilo de instructor. Ritmo moderado y pausas naturales. Sin música ni explicaciones adicionales. Lee únicamente el texto proporcionado.

3. Generar una grabación por guion distinto. No unir todas las instrucciones en un único audio largo: el reproductor actual no tiene segmentación por marcas de tiempo.
4. Escuchar la grabación completa y comprobar que no omite, inventa o cambia instrucciones.
5. Descargar como WAV PCM reproducible. Si la herramienta entrega PCM crudo, convertirlo correctamente a WAV; no basta cambiarle la extensión. `voice_samples.audio_as_wav` sirve para el formato PCM16 mono de 24 kHz esperado de Gemini 2.5, no como conversor universal de MP3 u otros formatos.
6. Guardar el WAV con **el nombre exacto de `file`** dentro de `Frontend/public/tutor-audio/`.

La grabación y su guion deben coincidir. No renombrar un audio de otro paso para cubrir un faltante ni usar la muestra de presentación como audio de todas las instrucciones.

### Opción B: ejecutar tú el lote offline del proyecto

Si decides que el proyecto haga las llamadas, desde `ai-service`:

```powershell
.\venv\Scripts\python.exe -u -m app.tutor.generate_catalog
```

Este comando **sí consume cuota Gemini**. Usa la configuración del ai-service, omite archivos existentes, separa las solicitudes y se detiene ante un error. No imprime claves ni cambia facturación.

**Límite actual importante:** genera todos los faltantes del manifest, no sólo un tutorial. No existe actualmente `--tutorial`, `--dry-run` ni un filtro equivalente. Si sólo faltan los nuevos audios, funciona como lote de esos faltantes. Si hay pendientes ajenos que no quieres generar, usa la opción manual o implementa y valida previamente un selector de lote; no asumir que ese filtro ya existe.

El generador actual rechaza catálogos de más de 40 audios únicos. Antes de superar ese tamaño, revisar explícitamente su límite y el control de lotes/cuota; añadir tutoriales no amplía automáticamente ese tope.

`voice_samples` es una herramienta de comparación con un texto fijo; no genera las instrucciones nuevas del catálogo.

## 8. Conectar y comprobar los archivos entregados

Después de copiar los WAV manuales, desde `Frontend`, volver a ejecutar:

```powershell
npm.cmd run tutor:audio:manifest
```

Esto actualiza `files` sin consumir la API. Luego, desde `ai-service`:

```powershell
.\venv\Scripts\python.exe -m app.tutor.check_catalog
```

La comprobación no usa Gemini. Verifica que los WAV estén completos y coincidan con la disponibilidad declarada, y genera la página de revisión. Si faltan audios devuelve salida distinta de cero y muestra el conteo; no declarar el catálogo completo hasta que pase.

Con Vite activo, escuchar el lote en:

```text
http://localhost:5173/tutor-audio/review.html
```

También puede abrirse `Frontend/public/tutor-audio/review.html` directamente desde el Explorador. La página no reproduce automáticamente. Revisar pronunciación, coherencia de voz, volumen, ritmo y correspondencia con el texto. Las comprobaciones técnicas no sustituyen esa escucha.

El reproductor ya consume el manifest; no hace falta añadir un audio a mano a `TutorAudioProvider` por cada tutorial. Espera el control correcto antes de iniciar, permite repetir/silenciar y detiene la voz ante pausas, cambio de paso, micrófono y otras interrupciones. La finalización del WAV no avanza el motor.

## 9. Validar el tutorial antes de entregarlo

Desde `Frontend`:

```powershell
npm.cmd run test:tutor
npm.cmd run test:diagram-ai
npm.cmd run build
npm.cmd run lint
```

Si cambia una condición o evidencia, añadir casos de prueba relevantes en el motor/store/adaptador. Comprobar tanto el caso correcto como rechazo por ID, padre, proyecto o rol incorrecto y por evento remoto o antiguo. Si sólo se cambia texto o audio, comprobar la cobertura del manifest y los archivos afectados.

Para la suite existente de navegador, iniciar Vite de prueba en otra terminal:

```powershell
npm.cmd run dev -- --host 127.0.0.1 --port 5175 --strictPort
```

Después:

```powershell
npm.cmd run test:tutor:browser
```

Usa Edge y APIs simuladas. **No incorpora automáticamente un escenario completo del tutorial nuevo**: añadirlo cuando corresponda y realizar además la prueba con backend real. Si se cambió Python o Java, ejecutar también sus suites; el entorno Python actual puede necesitar pytest para la suite general. Registrar el comando y error si el bloqueo es ambiental.

Prueba manual final:

- Iniciar la ficha individual con parámetros y elementos reales.
- Completarla desde la pantalla correcta y probar contexto equivocado.
- Si se añadió al recorrido, comprobar también la transición entre lecciones y sus outputs.
- Validar teclados, paneles cerrados, scroll y varios elementos con nombres similares.
- Confirmar que los cambios remotos no se atribuyen al alumno.
- Probar guardar con éxito y con fallo/conflicto, sin perder datos.
- Activar Puck y comprobar cada paso nuevo, repetición, pausa, cierre y micrófono.
- Verificar que sin audio el tutorial siga funcionando en texto.

## 10. Cierre y mantenimiento

Actualizar el catálogo descriptivo y el plan con archivos afectados, pruebas ejecutadas, resultados, revisión auditiva y pendientes. Entregar al usuario para que pruebe y decida si hace commit o solicita reparación. No avanzar automáticamente de subfase con fallos funcionales ni crear commits/push sin petición.

Versionar juntos el código del tutorial, exportador si cambió, manifest y WAV aprobados. `dist/` es un artefacto de build y no debe añadirse a Git. Las muestras de `ai-service/voice-samples.local/` siguen siendo locales.

Si un texto cambia después de grabar, exportar otra vez, identificar el archivo nuevo y entregar su guion al usuario para que lo regenere. El reproductor comprueba `source` para evitar usar instrucciones obsoletas. La disponibilidad de archivos puede cambiar sin editar el reproductor; el manifest se solicita sin caché, aunque conviene recargar la sesión después de actualizar el catálogo.

**Secuencia de trabajo para futuras tareas:** definir → implementar catálogo/controles/evidencia → probar en texto → exportar guiones → entregar lote al usuario → usuario genera y aprueba Puck → incorporar WAV → verificar y probar audio → documentar → revisión del usuario y decisión de commit.
