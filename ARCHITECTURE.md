# Arquitectura de LogicDraft

## 1. Visión general

LogicDraft es una plataforma SaaS colaborativa orientada a crear y organizar diagramas de clases y de bases de datos. En el estado actual, el repositorio contiene una aplicación web React, una API Spring Boot y PostgreSQL para persistencia.

Arquitectura implementada:

```text
Frontend React + TypeScript
          |
          | HTTP + JSON
          | Authorization: Bearer <JWT>
          v
API Spring Boot (monolito modular)
          |
          | Spring Data JPA / Hibernate
          v
PostgreSQL 17 (Docker)
```

La integración de inteligencia artificial está en el roadmap, pero **no está implementada** y actualmente no existe un directorio `ai-service/`. La dirección prevista es:

```text
Spring Boot
    |
    | API interna controlada
    v
AI Service futuro (FastAPI/Python)
    |
    +-- Ollama local
    +-- Proveedor remoto
```

El servicio de IA futuro propondría estructuras o acciones; Spring Boot seguiría validando autorización, reglas de negocio y persistencia. La IA no debe ejecutar SQL arbitrario directamente.

## 2. Principios de arquitectura

- El backend es un **monolito modular**: una sola aplicación desplegable, organizada internamente por dominios.
- No se usan microservicios para cada funcionalidad. Esto reduce complejidad operativa en la etapa actual.
- Los módulos backend se agrupan por dominio (`auth`, `tenant`, `workspace`, etc.).
- El frontend se organiza por features y separa configuración global, código compartido y funcionalidad específica.
- PostgreSQL es la base de datos principal.
- La autenticación utiliza JWT stateless; el servidor no mantiene una sesión HTTP.
- La autorización multitenant se resuelve en Spring Boot. El frontend y `ProtectedRoute` no constituyen una barrera de seguridad.
- Un futuro servicio de IA será auxiliar: no reemplazará la validación ni las reglas de negocio del backend.

## 3. Estructura del repositorio

```text
Examen/
├── Frontend/                 # React 19 + TypeScript + Vite
├── backend/                  # Java 21 + Spring Boot
├── docker-compose.yml        # PostgreSQL 17
├── code.html                 # Referencia visual externa de Stitch
└── ARCHITECTURE.md
```

No existe actualmente `ai-service/`.

## 4. Backend

El código principal está bajo `backend/src/main/java/com/sw1/backend`:

```text
com.sw1.backend/
├── auth/
│   ├── controller/
│   ├── dto/request/
│   ├── dto/response/
│   ├── security/
│   └── service/
├── common/exception/
├── proyecto/
│   ├── controller/
│   ├── dto/request/
│   ├── dto/response/
│   ├── model/
│   ├── repository/
│   └── service/
├── tenant/
│   ├── model/
│   ├── repository/
│   └── service/
├── usuario/
│   ├── model/
│   └── repository/
├── workspace/
│   ├── controller/
│   ├── dto/response/
│   ├── model/
│   ├── repository/
│   └── service/
└── BackendApplication.java
```

### Responsabilidades de los módulos

| Módulo | Responsabilidad actual |
|---|---|
| `auth` | Registro, login, consulta del usuario autenticado, emisión y validación JWT, configuración de Spring Security. |
| `usuario` | Entidad `Usuario`, rol global y repositorio por email. |
| `tenant` | Entidades de organización y membresía, roles del tenant y verificación de acceso. No tiene controller CRUD actualmente. |
| `workspace` | Entidades de workspace y membresía, listado de workspaces del usuario y control de acceso/roles. |
| `proyecto` | CRUD de proyectos, siempre sujeto al workspace autorizado. |
| `common.exception` | Excepciones de dominio y respuestas HTTP centralizadas mediante `GlobalExceptionHandler`. |

### Entidades y relaciones

Entidades JPA existentes:

- `Usuario`: nombre, email único, hash de contraseña, `RolSistema` y fecha de creación.
- `Tenant`: organización SaaS y fecha de creación.
- `MiembroTenant`: relación única usuario–tenant, con `RolTenant` y fecha de unión.
- `Workspace`: pertenece obligatoriamente a un tenant.
- `MiembroWorkspace`: relación única usuario–workspace, con `RolWorkspace` y fecha de unión.
- `Proyecto`: pertenece obligatoriamente a un workspace; contiene nombre, descripción y fecha de creación.

```text
Usuario
  |
  +-- MiembroTenant --------> Tenant
  |                              |
  |                              +-- Workspace
  |                                      |
  +-- MiembroWorkspace ------------------+
                                         |
                                         +-- Proyecto
```

Las asociaciones hacia `Usuario`, `Tenant` y `Workspace` usan `@ManyToOne`; las tablas de membresía incluyen restricciones únicas para evitar duplicar una membresía del mismo usuario en el mismo ámbito.

## 5. Multitenancy y autorización

### Modelo

- **Tenant**: cliente u organización dentro del SaaS.
- **Workspace**: espacio colaborativo que pertenece a un tenant.
- **Proyecto**: recurso que pertenece a un workspace.

Roles implementados:

| Ámbito | Roles |
|---|---|
| Sistema (`RolSistema`) | `USER`, `ADMIN` |
| Tenant (`RolTenant`) | `OWNER`, `ADMIN`, `MEMBER` |
| Workspace (`RolWorkspace`) | `OWNER`, `EDITOR`, `VIEWER` |

`RolSistema.ADMIN` representa un rol global de plataforma, pero el código actual **no lo usa como bypass** para acceder a tenants o workspaces ajenos. El acceso continúa dependiendo de las membresías.

### Reglas de aislamiento

- Un usuario accede a un tenant solamente si existe su `MiembroTenant`.
- Un usuario accede a un workspace solamente si tiene acceso a su tenant **y** existe su `MiembroWorkspace`.
- Los proyectos se listan por un `workspaceId` autorizado.
- Consultar, actualizar o eliminar un proyecto primero identifica su workspace y valida el acceso correspondiente.
- Crear, actualizar y eliminar proyectos requiere rol `OWNER` o `EDITOR` del workspace.
- Leer proyectos permite cualquier membresía válida, incluido `VIEWER`.
- No se debe exponer un `findAll()` sin scope para recursos multitenant. Aunque los repositorios heredan métodos generales de JPA, los servicios públicos actuales aplican el scope antes de operar.
- No deben crearse endpoints de recursos multitenant sin un tenant o workspace verificable.
- `tenantId` no se almacena en el JWT: un usuario puede pertenecer a múltiples tenants y su contexto se valida por recurso.

### Servicios de acceso

- `UsuarioActualService`: obtiene el email del `SecurityContext`, busca el `Usuario` correspondiente y falla si no hay una identidad autenticada válida.
- `TenantAccessService`: obtiene la membresía usuario–tenant y permite verificar acceso o uno de los roles de tenant requeridos.
- `WorkspaceAccessService`: carga el workspace, comprueba primero el acceso al tenant y luego la membresía usuario–workspace; también valida roles del workspace.

No existen todavía endpoints para crear, editar o administrar tenants, workspaces o sus membresías.

## 6. Autenticación

### Registro

`AuthService.registrar` ejecuta toda la operación dentro de una transacción:

```text
POST /api/auth/register
          |
          v
Validar email no duplicado
          |
          v
Crear Usuario (RolSistema.USER, contraseña BCrypt)
          |
          v
Crear Tenant "Organización de <nombre>"
          |
          v
Crear MiembroTenant OWNER
          |
          v
Crear Workspace inicial
          |
          v
Crear MiembroWorkspace OWNER
          |
          v
Emitir JWT
```

### Login

```text
email + password
       |
       v
Buscar Usuario por email
       |
       v
PasswordEncoder.matches (BCrypt)
       |
       v
Emitir JWT
```

El JWT contiene:

- `sub`: email del usuario.
- claim `rolSistema`.
- fechas de emisión y expiración.
- firma HMAC con `JWT_SECRET`.

No contiene contraseña ni `tenantId`. El cliente lo envía así:

```http
Authorization: Bearer <token>
```

Spring Security está configurado con `SessionCreationPolicy.STATELESS`, CSRF deshabilitado, login de formulario y HTTP Basic deshabilitados. Solamente registro y login son públicos; todas las demás solicitudes requieren autenticación. Las contraseñas se almacenan codificadas con `BCryptPasswordEncoder`.

## 7. Endpoints implementados

Todos los paths parten del backend en `http://localhost:8081`.

| Método | Endpoint | JWT | Comportamiento y scope |
|---|---|---:|---|
| `POST` | `/api/auth/register` | No | Registra usuario y crea tenant, membresías y workspace inicial. Devuelve `201`. |
| `POST` | `/api/auth/login` | No | Valida email/contraseña y devuelve JWT. |
| `GET` | `/api/auth/me` | Sí | Devuelve id, nombre, email y rol global del usuario autenticado. |
| `GET` | `/api/workspaces` | Sí | Lista únicamente los workspaces donde el usuario tiene membresía. |
| `POST` | `/api/proyectos` | Sí | Crea un proyecto. Body: `nombre`, `descripcion`, `workspaceId`. Requiere `OWNER` o `EDITOR`. |
| `GET` | `/api/proyectos?workspaceId={id}` | Sí | Lista los proyectos del workspace autorizado indicado. `workspaceId` es obligatorio. |
| `GET` | `/api/proyectos/{id}` | Sí | Obtiene un proyecto después de validar acceso a su workspace. |
| `PUT` | `/api/proyectos/{id}` | Sí | Actualiza nombre y descripción. Requiere `OWNER` o `EDITOR` del workspace. |
| `DELETE` | `/api/proyectos/{id}` | Sí | Elimina el proyecto. Requiere `OWNER` o `EDITOR`; devuelve `204`. |

Las solicitudes usan DTO y Bean Validation. `GlobalExceptionHandler` normaliza errores frecuentes: validación (`400`), credenciales inválidas (`401`), acceso denegado (`403`), recurso inexistente (`404`), email duplicado (`409`) y método no permitido (`405`).

## 8. Frontend

Tecnologías detectadas: React 19, TypeScript 6, Vite 8, React Router 7 y Axios 1.20.

```text
Frontend/src/
├── app/
│   ├── App.tsx
│   └── router.tsx
├── features/
│   ├── auth/
│   │   ├── api/
│   │   ├── components/
│   │   ├── pages/
│   │   └── types/
│   ├── dashboard/
│   │   ├── components/
│   │   ├── pages/
│   │   └── dashboard.css
│   └── workspace/
│       ├── api/
│       └── types/
├── pages/
│   └── DashboardPage.tsx       # Archivo anterior; el router usa el del feature
├── shared/
│   ├── api/
│   └── components/
├── index.css
└── main.tsx
```

- `app/` contiene el montaje general y el router.
- `shared/` contiene infraestructura reutilizable: instancia Axios, clasificación del error 401 y `ProtectedRoute`.
- `features/` agrupa cada capacidad de negocio con sus propias APIs, tipos, páginas y componentes.
- `authApi.ts` implementa login, registro y `/auth/me`.
- `workspaceApi.ts` obtiene `/workspaces`.
- La página Dashboard consulta usuario y workspaces reales en paralelo, conserva estados de carga/error, maneja el `401` borrando el token y mantiene logout.

Rutas actuales:

| Ruta | Acceso |
|---|---|
| `/login` | Pública |
| `/register` | Pública |
| `/dashboard` | Envuelta por `ProtectedRoute` |
| `/` | Redirige a `/dashboard` |

La instancia Axios compartida toma la base URL de `VITE_API_URL`. Su interceptor lee `token` de `localStorage` y agrega `Authorization: Bearer ...`.

Guardar el JWT en `localStorage` es una decisión aceptada para este MVP académico. Para producción conviene reevaluarla por el riesgo de robo de tokens ante XSS, junto con una política CSP, manejo de refresh tokens y alternativas con cookies `HttpOnly`, `Secure` y `SameSite`.

El frontend aún no consume el CRUD de proyectos. El Dashboard muestra esa zona, IA y otras acciones como placeholders explícitos.

## 9. Variables de entorno

### Frontend

Existe `Frontend/.env.example`:

```dotenv
VITE_API_URL=http://localhost:8081/api
```

Para desarrollo se crea `Frontend/.env` con esa variable. Toda variable prefijada con `VITE_` se incorpora al bundle y queda visible en el navegador; nunca debe contener contraseñas, claves JWT ni otros secretos.

### Backend

`backend/src/main/resources/application.properties` usa:

| Variable | Obligatoria | Uso |
|---|---:|---|
| `JWT_SECRET` | Sí | Clave HMAC usada para firmar y validar JWT. |
| `JWT_EXPIRATION_MS` | No | Duración del JWT; el valor predeterminado es `86400000` ms (24 horas). |

Ejemplo seguro, sin una clave real:

```powershell
$env:JWT_SECRET="<TU_CLAVE_SEGURA_DE_LONGITUD_ADECUADA>"
$env:JWT_EXPIRATION_MS="86400000"
```

JJWT requiere que la clave tenga longitud suficiente para el algoritmo HMAC. No se debe versionar una clave real.

La URL, usuario y contraseña de la base de datos están actualmente escritos directamente en `application.properties` y coordinados con `docker-compose.yml`. Esto es aceptable únicamente como configuración local de desarrollo; antes de producción deben externalizarse a variables o gestión de secretos.

## 10. Base de datos y Docker

`docker-compose.yml` define un único servicio `postgres`:

| Propiedad | Valor real actual |
|---|---|
| Imagen | `postgres:17` |
| Contenedor | `diagram-postgres` |
| Base de datos | `diagram_db` |
| Usuario de desarrollo | `diagram_user` |
| Puerto host | `5433` |
| Puerto contenedor | `5432` |
| Volumen | `postgres_data` |

La contraseña de desarrollo está definida en el Compose y coincide con `application.properties`; debe revisarse directamente en esos archivos y no reutilizarse como credencial de producción.

Docker Desktop debe estar ejecutándose antes de iniciar el backend:

```text
Docker Desktop
      |
      v
docker compose up -d
      |
      v
PostgreSQL localhost:5433
      |
      v
Spring Boot localhost:8081
      |
      v
Frontend localhost:5173
```

Comandos habituales desde la raíz:

```powershell
docker compose up -d
docker ps
docker compose down
```

No se recomienda ejecutar normalmente `docker compose down -v`: la opción `-v` elimina `postgres_data` y puede borrar los datos locales de desarrollo.

### Creación y evolución del esquema

No se detectaron Flyway ni Liquibase. La propiedad real es:

```properties
spring.jpa.hibernate.ddl-auto=update
```

Hibernate crea o ajusta tablas a partir de las entidades al arrancar. Esto ayuda durante el desarrollo, pero no proporciona migraciones versionadas ni un historial reproducible. Una instalación nueva empieza con una base vacía y Hibernate crea el esquema; no existen seeders detectados y no se trasladan datos de otra computadora.

## 11. Ejecutar localmente

### Requisitos

- Git.
- Java 21 (declarado en `backend/pom.xml`).
- Node.js y npm compatibles con Vite 8.
- Docker Desktop y Docker Compose.

El proyecto usa Maven Wrapper (`mvnw` y `mvnw.cmd`), por lo que no es obligatorio instalar Maven globalmente.

### A. Clonar

No se encontró metadata Git ni una URL remota disponible en la copia inspeccionada. Sustituir el marcador por la URL real:

```powershell
git clone <URL_DEL_REPOSITORIO>
cd <NOMBRE_DEL_REPOSITORIO>
```

### B. Iniciar PostgreSQL

Con Docker Desktop abierto, desde la raíz:

```powershell
docker compose up -d
docker ps
```

Verificar que `diagram-postgres` esté activo y publique `5433:5432`.

### C. Iniciar el backend en PowerShell

```powershell
cd backend
$env:JWT_SECRET="<TU_CLAVE_DE_DESARROLLO_SEGURA>"
$env:JWT_EXPIRATION_MS="86400000"
.\mvnw.cmd spring-boot:run
```

`$env:JWT_SECRET` existe solo dentro de esa sesión de PowerShell. Al cerrar la terminal deberá definirse nuevamente. El backend queda disponible en:

```text
http://localhost:8081
```

### D. Iniciar el frontend

Desde otra terminal, partiendo de la raíz:

```powershell
cd Frontend
npm install
Copy-Item .env.example .env
npm run dev
```

El frontend queda normalmente en:

```text
http://localhost:5173
```

Orden recomendado:

1. Abrir Docker Desktop.
2. Levantar PostgreSQL.
3. Iniciar Spring Boot.
4. Iniciar Vite.

## 12. Configurar el proyecto en otra computadora

1. Instalar los requisitos de la sección anterior.
2. Clonar el repositorio.
3. Crear `Frontend/.env` a partir de `Frontend/.env.example`.
4. Definir `JWT_SECRET` en la terminal donde se ejecutará el backend.
5. Levantar PostgreSQL con Docker Compose.
6. Iniciar backend y frontend en ese orden.

Los archivos `.env` suelen representar configuración local y no deberían versionarse si pueden contener secretos. En cambio, `.env.example` sí debe versionarse con nombres de variables y valores de ejemplo no sensibles. El `.gitignore` actual del frontend ignora `*.local`, pero **no contiene una regla general para `.env`**; se debe comprobar el estado del repositorio antes de cada commit para no subir configuración sensible por accidente.

El volumen Docker `postgres_data` pertenece a la computadora local y no viaja con Git. Al clonar en otro equipo:

- PostgreSQL comienza sin los datos del equipo original.
- Hibernate genera/actualiza el esquema por `ddl-auto=update`.
- No hay migraciones ni seeders detectados que creen datos iniciales.
- Los usuarios, tenants, workspaces y proyectos deberán crearse nuevamente o trasladarse mediante un respaldo explícito de PostgreSQL.

## 13. Puertos

| Servicio | Puerto |
|---|---:|
| Frontend Vite | `5173` |
| Backend Spring Boot | `8081` |
| PostgreSQL accesible desde el host | `5433` |
| PostgreSQL dentro del contenedor | `5432` |

Spring Security permite CORS únicamente desde `http://localhost:5173` y acepta `GET`, `POST`, `PUT`, `DELETE` y `OPTIONS`, con headers `Authorization` y `Content-Type`.

## 14. Estado actual

### Implementado

- API Spring Boot como monolito modular.
- Persistencia PostgreSQL mediante JPA/Hibernate.
- Registro y login con BCrypt.
- Emisión, validación y filtro de autenticación JWT.
- Endpoint de usuario actual.
- Creación automática de tenant, workspace y membresías `OWNER` durante el registro.
- Modelos y roles de tenant y workspace.
- Servicios de aislamiento multitenant.
- Listado de workspaces del usuario autenticado.
- CRUD backend de proyectos con scope y roles por workspace.
- Manejo global de excepciones y validación de DTO.
- Login, registro, logout y ruta protegida en React.
- Dashboard responsive con usuario y workspaces reales.
- Docker Compose para PostgreSQL.

### En desarrollo o integración pendiente

- Consumo del CRUD de proyectos desde React.
- Pantallas y navegación funcional para proyectos.
- Gestión CRUD de tenants, workspaces y membresías.
- Las métricas, acciones rápidas, búsqueda y secciones visuales no conectadas del Dashboard son placeholders.
- Cobertura de pruebas: solo se detectó la prueba básica de contexto generada para Spring Boot.

### Roadmap (no implementado)

- Editor visual de diagramas (por ejemplo, una futura evaluación de React Flow; no es dependencia actual).
- Modelado persistente de diagramas.
- Colaboración en tiempo real.
- IA desde texto.
- IA desde audio/voz.
- Reconocimiento o importación desde imágenes.
- Importación y exportación de diagramas.
- Generación de backend Spring Boot.
- Servicio FastAPI separado con Ollama o proveedor remoto.
- Planes y suscripciones.

## 15. Decisiones de diseño

### Monolito modular frente a microservicios

La escala actual no justifica el coste de despliegue, observabilidad, comunicación y consistencia distribuida de múltiples servicios. El monolito modular mantiene límites de dominio claros y permite evolucionar más rápido. Un servicio de IA sí puede separarse en el futuro porque tiene runtime, dependencias y escalado diferentes.

### Features en el frontend

Agrupar API, tipos, páginas y componentes por funcionalidad reduce el acoplamiento y evita que una carpeta global crezca sin límites. `app` conserva la composición general y `shared` solamente lo reutilizable entre features.

### Instancia Axios compartida

Centraliza `VITE_API_URL` y la incorporación del JWT, evitando repetir configuración en cada feature.

### Tenant separado de Workspace

El tenant representa al cliente SaaS y el workspace una unidad de colaboración interna. Esta separación permite que una organización tenga varios espacios, miembros y permisos diferenciados.

### JWT sin `tenantId`

La identidad es global y una persona puede pertenecer a múltiples tenants. Resolver el tenant desde el recurso y verificar membresías evita fijar un único tenant dentro del token o confiar en un scope obsoleto.

### PostgreSQL en Docker

Proporciona una versión y puertos reproducibles sin requerir una instalación local manual. El volumen preserva datos entre reinicios normales del contenedor.

### Servicio IA futuro separado

Python/FastAPI facilitaría integrar modelos y librerías de IA sin introducirlas en el proceso principal Java. Aun separado, solo propondría resultados; Spring Boot conservaría la autoridad sobre acceso y persistencia.

## 16. Seguridad

- Nunca subir un `JWT_SECRET` real al repositorio.
- Nunca reutilizar usuarios o contraseñas de desarrollo en producción.
- Nunca incluir secretos en variables `VITE_*`.
- Mantener contraseñas de usuario codificadas con BCrypt; no registrarlas ni incluirlas en respuestas o tokens.
- Validar siempre el tenant y workspace en backend antes de leer o modificar recursos.
- No asumir que `RolSistema.ADMIN` concede acceso a datos de otros tenants.
- Evitar repositorios o endpoints multitenant sin scope.
- Considerar todo dato enviado por React como no confiable.
- `ProtectedRoute` solo mejora la experiencia de navegación; un usuario puede modificar el frontend o llamar a la API directamente.
- La autorización real ocurre en Spring Security y en los servicios `TenantAccessService`/`WorkspaceAccessService`.
- Para producción deben revisarse CORS, almacenamiento de tokens, HTTPS, rotación/expiración de credenciales, secretos externos y migraciones de base de datos.

## 17. Troubleshooting

### `Connection to localhost:5433 refused`

Causa habitual: Docker Desktop no está abierto o el contenedor PostgreSQL está detenido.

```powershell
docker compose up -d
docker ps
```

Comprobar que `diagram-postgres` figura activo y que no exista otro proceso ocupando el puerto `5433`.

### El frontend muestra `ERR_CONNECTION_REFUSED` hacia `:8081`

El backend Spring Boot no está ejecutándose o falló durante el arranque. Revisar su terminal, la conexión PostgreSQL y que `JWT_SECRET` esté definida.

### El backend no inicia por la clave JWT

Definir una clave segura y suficientemente larga en la misma sesión de PowerShell:

```powershell
$env:JWT_SECRET="<TU_CLAVE_DE_DESARROLLO_SEGURA>"
.\mvnw.cmd spring-boot:run
```

### Respuesta `401 Unauthorized`

Revisar:

- que exista `token` en `localStorage`;
- que la petición incluya `Authorization: Bearer <token>`;
- que el JWT no haya expirado;
- que backend haya sido iniciado con la clave correcta;
- que el usuario referenciado por el token todavía exista.

El Dashboard borra el token y redirige a `/login` cuando sus llamadas iniciales reciben `401`.

### Respuesta `403 Forbidden`

La autenticación puede ser válida, pero el usuario no tiene la membresía o rol exigido por el tenant/workspace. No debe solucionarse omitiendo el control de acceso: hay que revisar las membresías.

### Error CORS

La configuración actual permite solamente:

```text
http://localhost:5173
```

Verificar que Vite use ese origen exacto. Si el puerto cambia, la lista permitida de Spring Security también deberá configurarse conscientemente.

### El frontend no encuentra la API

Comprobar `Frontend/.env`:

```dotenv
VITE_API_URL=http://localhost:8081/api
```

Reiniciar Vite después de modificar el archivo, porque sus variables se cargan al iniciar el servidor.

### La base de datos está vacía en otra computadora

Es el comportamiento esperado: el volumen local no se incluye en Git. Con `ddl-auto=update`, Hibernate crea el esquema, pero no hay seeders ni migraciones detectados que recreen los datos del equipo anterior.
