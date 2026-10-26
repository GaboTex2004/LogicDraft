# Instrucciones persistentes para LogicDraft

## Propósito y arquitectura

LogicDraft es una plataforma colaborativa para modelar diagramas y generar aplicaciones. Este repositorio es el producto existente y debe evolucionarse incrementalmente; no debe reconstruirse desde cero.

- `Frontend/`: React, TypeScript, Vite y React Flow (`@xyflow/react`).
- `backend/`: Java 21, Spring Boot, Spring Security/JWT, JPA, PostgreSQL, WebSocket/STOMP, Flyway, importación/exportación XMI de Enterprise Architect y generadores Spring Boot/Flutter.
- `ai-service/`: FastAPI con proveedores Ollama y Gemini. La IA propone operaciones; Spring Boot conserva la autoridad de autorización, validación y persistencia.
- `compose.deploy.yml`: ejecución local integrada. AWS queda fuera de alcance hasta la Fase 6.

El documento de diagrama persistido actual es `version: 1`, con `nodes` y `edges`. `revision` es la versión de concurrencia del registro y no debe confundirse con `version`. Al guardar un diagrama existente, el cliente envía `baseRevision`; una revisión obsoleta produce HTTP 409.

## Convenciones de desarrollo

- Mantener los módulos por dominio del backend y las features del frontend; evitar lógica de negocio duplicada entre UI, API e IA.
- Tratar los contratos de diagrama como contratos compartidos. Un cambio debe revisarse de extremo a extremo en TypeScript, Java, Python, colaboración, generadores y XMI.
- Conservar compatibilidad explícita con documentos persistidos `version: 1` o introducir una migración/versionado documentado; nunca reinterpretarlos silenciosamente.
- Mantener operaciones de IA estructuradas, validadas y atómicas. Una consulta, sugerencia o intención ambigua no autoriza mutaciones.
- Mantener eventos colaborativos idempotentes y por operación/lote semántico; no emitir eventos por píxel ni aplicar de nuevo eventos del mismo cliente.
- Usar Maven Wrapper cuando funcione en el entorno, `npm`/`npm.cmd` mediante los scripts declarados y el entorno virtual de Python cuando exista.
- No introducir dependencias, cambiar generadores ni implementar una fase futura sin alcance explícito.

## Seguridad y autorización

- El backend es la barrera de seguridad. Nunca confiar en permisos, IDs, revisiones o contenido validados sólo por React o por el modelo de IA.
- Toda lectura o escritura multitenant debe comprobar tenant, workspace y proyecto mediante los servicios de acceso existentes.
- `OWNER` y `EDITOR` pueden modificar diagramas; `VIEWER` sólo puede leer. No crear bypass por rol global sin una decisión explícita.
- Autenticar conexiones STOMP y autorizar suscripciones/publicaciones por proyecto. No aceptar identidad, rol o proyecto declarados únicamente por el cliente.
- Validar estructura, referencias, tipos, cardinalidades, tamaño y revisión antes de persistir. Conservar 409 para conflictos y 422 para documentos estructuralmente inválidos.
- No leer, imprimir, registrar ni versionar secretos. Nunca colocar secretos en variables `VITE_*`. Usar `.env.example` sólo como catálogo sin valores reales.

## Pruebas y validación

Desde la raíz, usar los comandos adecuados al shell:

```powershell
cd Frontend
npm.cmd run test:diagram-ai
npm.cmd run build
npm.cmd run lint

cd ..\ai-service
python -m pytest -q

cd ..\backend
.\mvnw.cmd test
```

Si el Wrapper falla por el entorno, se puede diagnosticar con el Maven instalado, pero no se debe declarar la suite aprobada hasta ejecutar el conjunto correspondiente con éxito. Las pruebas que requieren Docker, PostgreSQL, Flutter, modelos locales o credenciales se documentan por separado y no se simulan como aprobadas.

Cada subfase debe terminar con: contrato documentado, pruebas unitarias relevantes, pruebas de integración necesarias, compatibilidad con datos existentes y revisión del diff. No continuar a la subfase siguiente si la actual tiene fallos funcionales; si el bloqueo es sólo ambiental, registrarlo expresamente con el comando y el error.

## Infraestructura y datos

- No modificar AWS ni asumir que el despliegue remoto coincide con el entorno local.
- No modificar, borrar, reinicializar ni migrar una base existente sin autorización y respaldo verificado.
- Para bases nuevas, el perfil `migrated` aplica V1 y V2. Para bases existentes sin historial Flyway, seguir `DEVELOPMENT.md`; no habilitar `baselineOnMigrate` permanentemente.
- No ejecutar `docker compose down -v`, eliminar volúmenes ni descargar modelos de IA salvo solicitud explícita.
- El comando integrado conocido es `docker compose --env-file .env.deploy -f compose.deploy.yml up -d --build`; no mostrar el contenido de `.env.deploy`.
- Antes de terminar una tarea, confirmar que sólo cambiaron archivos autorizados y no crear commits ni hacer push salvo petición expresa.

