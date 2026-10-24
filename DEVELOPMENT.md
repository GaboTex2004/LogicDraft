# Desarrollo local de LogicDraft

## Estrategia de esquema PostgreSQL

LogicDraft conserva dos modos locales para evitar migrar silenciosamente una base anterior:

- El perfil predeterminado mantiene temporalmente `Flyway` desactivado y usa `ddl-auto=validate`. Es un modo
  seguro de transicion: nunca modifica una base local preexistente y falla de forma explicita si falta V2.
- El perfil `migrated` usa exclusivamente las migraciones versionadas y configura Hibernate con
  `ddl-auto=validate`. Es el modo obligatorio para bases nuevas y el objetivo para todo desarrollo futuro.

### Base nueva o desechable

Con una base vacia, iniciar Spring con `--spring.profiles.active=migrated`. Flyway aplicara
`V1__initial_schema.sql` y luego `V2__diagram_revision.sql`. La segunda migracion agrega la revision de
concurrencia sin modificar el JSON guardado.

### Base local existente

No activar el perfil `migrated` directamente sobre una base con tablas y sin historial Flyway: V1 intentaria
crear objetos existentes. La transicion segura es manual y se realiza una sola vez:

1. Crear un respaldo de PostgreSQL.
2. Verificar que existen las tablas y restricciones descritas por V1 y que `diagramas` conserva su contenido.
3. Detener todas las instancias de backend para evitar escrituras concurrentes.
4. Aplicar manualmente el SQL aditivo de `V2__diagram_revision.sql` si se necesita arrancar en modo seguro antes
   de adoptar Flyway. El script no elimina ni reescribe datos.
5. Registrar V1 como baseline **solo en esa base verificada** mediante las propiedades temporales de baseline
   de Spring/Flyway; no guardar `baselineOnMigrate=true` en ningun archivo del repositorio.
6. Ejecutar Flyway con el perfil `migrated`; V2 es aditiva e idempotente respecto de la columna `revision`.
7. Quitar inmediatamente las propiedades temporales de baseline y reiniciar siempre con el perfil `migrated`.
8. Confirmar que Flyway queda en version 2 y que Hibernate inicia en modo `validate`.

Esta fase no ejecuta esos pasos sobre la base local ni sobre AWS. Si el esquema real no coincide exactamente con
V1, detener la transicion y preparar una migracion de reconciliacion especifica; no usar baseline para ocultarlo.

Esta guía levanta LogicDraft completo en Windows PowerShell. `D:\Universidad\Sw1\Examen` se usa únicamente como ejemplo: sustituye esa ruta por la ubicación de tu clon.

## Servicios y puertos

| Servicio | Puerto local | Señal de funcionamiento |
| --- | ---: | --- |
| PostgreSQL | 5433 | `docker compose ps` muestra `diagram-postgres` como `Up`/`healthy` |
| Backend LogicDraft | 8081 | El log muestra `Started BackendApplication` y `Tomcat started on port 8081` |
| Ollama | 11434 | `ollama list` responde y enumera los modelos instalados |
| AI Service | 8000 | `http://localhost:8000/api/health` responde con `status: "ok"` |
| Frontend React | 5173 | Vite muestra `Local: http://localhost:5173/` y la página abre en el navegador |

Si Vite encuentra 5173 ocupado puede seleccionar el siguiente puerto; en ese caso ajusta `CORS_ALLOWED_ORIGIN` en `backend/.env`.

## Preparación de variables

No copies secretos reales a documentación ni al repositorio. Usa los archivos de ejemplo como base:

```powershell
Copy-Item backend\.env.example backend\.env
Copy-Item ai-service\.env.example ai-service\.env
Copy-Item Frontend\.env.example Frontend\.env
```

`backend/.env.example` define `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `JWT_EXPIRATION_MS`, `AI_SERVICE_URL`, `AI_SERVICE_TIMEOUT_SECONDS` y `CORS_ALLOWED_ORIGIN`. Sustituye los placeholders de contraseña y JWT localmente.

`ai-service/.env.example` define `AI_PROVIDER`, `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, `AI_REQUEST_TIMEOUT`, `AI_SERVICE_HOST` y `AI_SERVICE_PORT`. `OLLAMA_MODEL` debe coincidir con un modelo instalado.

`Frontend/.env.example` define `VITE_API_URL=http://localhost:8081/api` y `VITE_WS_URL=ws://localhost:8081/ws`.

## 1. Terminal 1 — Docker Desktop y PostgreSQL

Inicia Docker Desktop. Después, desde la raíz:

```powershell
cd D:\Universidad\Sw1\Examen
docker compose up -d
docker compose ps
```

El compose real publica PostgreSQL 17 en `localhost:5433` y mantiene 5432 dentro del contenedor. Antes de iniciar Spring comprueba que `diagram-postgres` figure `Up` o `healthy`.

Comandos útiles:

```powershell
docker compose logs
docker compose logs postgres
docker compose down
```

`docker compose down` detiene los servicios; el volumen `postgres_data` conserva los datos.

## 2. Terminal 2 — Backend Spring Boot de LogicDraft

Deja PostgreSQL abierto y, desde la raíz, ejecuta:

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

El Maven Wrapper es la opción principal y usa la versión prevista por el proyecto. LogicDraft escucha normalmente en `http://localhost:8081`. Espera mensajes equivalentes a:

```text
Tomcat started on port 8081
Started BackendApplication
```

### Port already in use

Si 8081 está ocupado, cambia el puerto solo para esa terminal:

```powershell
$env:SERVER_PORT="8082"
.\mvnw.cmd spring-boot:run
```

También puede hacerse en una línea:

```powershell
$env:SERVER_PORT="8082"; .\mvnw.cmd spring-boot:run
```

Para eliminar después la variable temporal:

```powershell
Remove-Item Env:SERVER_PORT
```

No confundas los dos servidores: el backend de **LogicDraft** usa normalmente 8081; un backend **generado por LogicDraft** usa normalmente 8080.

## 3. Terminal 3 — Ollama

Comprueba los modelos y deja Ollama abierto:

```powershell
ollama list
ollama pull llama3.2
ollama serve
```

La descarga solo es necesaria si `llama3.2` no está instalado. Puedes probarlo por separado con:

```powershell
ollama run llama3.2
```

Configura `OLLAMA_MODEL=llama3.2` en `ai-service/.env` si ese es el modelo elegido.

### Troubleshooting de Ollama/CUDA: forzar CPU

Esto no es obligatorio en todas las máquinas. Úsalo solamente si CUDA/GPU produce fallos:

```powershell
Stop-Process -Name ollama -Force
$env:CUDA_VISIBLE_DEVICES="-1"
$env:OLLAMA_LLM_LIBRARY="cpu"
ollama serve
```

Las variables afectan únicamente a esa terminal de PowerShell.

## 4. Terminal 4 — AI Service FastAPI

La primera vez, crea el entorno e instala las dependencias reales de `requirements.txt`:

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

En ejecuciones posteriores:

```powershell
cd ai-service
.\.venv\Scripts\Activate.ps1
python -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000
```

Deja esta terminal abierta. Comprueba `http://localhost:8000/api/health`; la documentación interactiva está en `http://localhost:8000/docs`.

## 5. Terminal 5 — Frontend React + Vite

La primera vez, instala dependencias:

```powershell
cd Frontend
npm install
```

Después inicia Vite y deja la terminal abierta:

```powershell
npm run dev
```

Abre `http://localhost:5173`. El frontend usa el JWT del login y las URLs configuradas en `Frontend/.env`; no hay que copiar tokens manualmente.

## Consulta rápida

```powershell
# Terminal 1 - PostgreSQL (desde la raíz)
docker compose up -d

# Terminal 2 - Backend LogicDraft
cd backend
.\mvnw.cmd spring-boot:run

# Terminal 3 - Ollama
ollama serve

# Terminal 4 - AI Service
cd ai-service
.\.venv\Scripts\Activate.ps1
python -m uvicorn app.main:app --reload --host 127.0.0.1 --port 8000

# Terminal 5 - Frontend
cd Frontend
npm run dev
```

## Ejecutar un proyecto fullstack generado

El ZIP completo contiene `backend/`, `frontend/` y su `README.md`.

Backend generado:

```powershell
cd backend
docker compose up -d
mvn spring-boot:run
```

Si 8080 está ocupado:

```powershell
$env:SERVER_PORT="8082"
mvn spring-boot:run
```

Flutter generado:

```powershell
cd frontend
flutter create --platforms=android,windows .
flutter pub get
flutter analyze
flutter test
flutter run -d windows --dart-define=API_BASE_URL=http://localhost:8080
```

Si Spring está en 8082:

```powershell
flutter run -d windows --dart-define=API_BASE_URL=http://localhost:8082
```

`SERVER_PORT` controla el puerto del backend generado y `API_BASE_URL` indica a Flutter dónde encontrarlo; ambos deben apuntar al mismo backend.


### Ollama intenta usar CUDA y se cae

Síntoma:

```text
CUDA error
llama-server process has terminated

netstat -ano | findstr :11434
Get-Process -Id <PID>
Stop-Process -Id <PID> -Force

$env:CUDA_VISIBLE_DEVICES="-1"
$env:OLLAMA_LLM_LIBRARY="cpu"
ollama serve
ollama run llama3.2 "Responde solamente: funcionando"


docker start logicdraft-migration-test
docker start logicdraft-ai-test
docker start logicdraft-backend-test
docker start logicdraft-frontend-test

http://localhost:18080
