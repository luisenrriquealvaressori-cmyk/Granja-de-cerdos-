# Guía de Despliegue en Render — Porky Gestión (`WEB` + `API` + Neon PostgreSQL)

Esta guía explica paso a paso cómo desplegar los dos servicios **.NET 10** de **AgroPig / Porky Gestión** en **Render** mediante Docker, conectados a la base de datos serverless **Neon PostgreSQL (`neondb`)** y listos para sincronizar con la app móvil Android (`PorciApp-v2.0.apk`).

---

## 1. Arquitectura del Despliegue

| Servicio | Carpeta en el Repo | Tipo en Render | Puerto interno | Base de Datos | Función principal |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`agropig-web`** | `WEB/WEB/WEB` | Web Service (Docker) | `8080` (o `$PORT`) | **Neon PostgreSQL (`neondb`)** | Consola Web administrativa/financiera (Razor Pages) + API móvil principal (`/api/v1/auth/*`, `/api/v1/movil/catalogo`, `/api/v1/sync/batch`, `/api/v1/sync/status`, `/api/v1/cerdos`, `/api/v1/tareas`) |
| **`agropig-api`** | `API/API/API` | Web Service (Docker) | `8080` (o `$PORT`) | InMemory EF Core (`GranjaPorcinaDb`) | Servicio REST auxiliar independiente (`/api/auth`, `/api/v1/cerdos`, `/api/v1/lotes`, `/api/v1/inventarios`, `/api/v1/presupuestos`, `/api/v1/dashboard`, `/api/v1/sync`) |
| **Neon `neondb`** | `DATABASE/00_reset_limpio.sql` | PostgreSQL Cloud | `5432` (TLS) | — | Tablas operativas, vistas BI de costos/kardex y usuarios (`admin`, `vet`, `operario`) ya inicializados |

> [!IMPORTANT]
> **¿A qué URL debe conectarse la app móvil?**
> Apunta la app móvil (`PorciApp`) a la URL HTTPS de **`agropig-web`** (`https://<tu-servicio-web>.onrender.com`), ya que este servicio implementa la autenticación JWT (`/api/v1/auth/login`), el catálogo móvil (`/api/v1/movil/catalogo`) y la sincronización bidireccional (`/api/v1/sync/batch`) persistiendo directamente en Neon PostgreSQL.

> [!NOTE]
> **Ahorro de horas activas en Neon Serverless:**
> Ambos servicios exponen `GET /healthz` sin realizar consultas SQL. Así Render verifica la disponibilidad del contenedor sin despertar ni mantener activa la instancia de Neon cuando nadie está operando la granja.

---

## 2. Requisito Previo: Subir los Últimos Cambios a GitHub (`main`)

Render construye las imágenes Docker directamente desde el repositorio de GitHub:
`https://github.com/luisenrriquealvaressori-cmyk/Granja-de-cerdos-`

Antes de crear los servicios en Render, asegúrate de haber subido a la rama `main`:
- `render.yaml` (Blueprint multi-servicio en la raíz)
- `WEB/WEB/WEB/Dockerfile`, `WEB/WEB/WEB/Program.cs` y `WEB/WEB/WEB/Security/PostgresConnectionResolver.cs`
- `API/API/API/Dockerfile`, `API/API/.dockerignore` y `API/API/API/Program.cs`
- `DATABASE/00_reset_limpio.sql` y `DESPLIEGUE_RENDER.md`

Desde la raíz del proyecto (`GRANJA PROCINA`), ejecuta:

```powershell
git status
git add .
git commit -m "feat(deploy): configuracion Docker y Blueprint para Render + soporte URI Neon"
git push origin main
```

---

## 3. Opción A (Recomendada): Despliegue Automático con Blueprint (`render.yaml`)

El archivo [`render.yaml`](./render.yaml) ya define ambos servicios (`agropig-web` y `agropig-api`) con sus rutas de contexto Docker, chequeo de salud y variables base.

1. Inicia sesión en [Render Dashboard](https://dashboard.render.com).
2. Haz clic en **New +** -> **Blueprint**.
3. Conecta tu cuenta de GitHub (si aún no está conectada) y selecciona el repositorio **`Granja-de-cerdos-`** (rama **`main`**).
4. Render detectará automáticamente [`render.yaml`](./render.yaml) con los servicios **`agropig-web`** y **`agropig-api`**.
5. Cuando Render solicite las variables marcadas con `sync: false`, pega la cadena de conexión de Neon en **`DATABASE_URL`** para ambos servicios:
   ```text
   postgresql://<USUARIO>:<PASSWORD>@<HOST_NEON>/neondb?sslmode=require&channel_binding=require
   ```
   *(Obtén la cadena exacta desde la consola de Neon -> **Connect** -> **Connection string**).*
6. Haz clic en **Apply** / **Deploy Blueprint**.
7. Render compilará las dos imágenes Docker `.NET 10` (la primera compilación toma entre 2 y 5 minutos) y validará `GET /healthz` en cada servicio.

> [!TIP]
> `Jwt__Key` en `agropig-web` usa `generateValue: true`, por lo que Render genera automáticamente una clave criptográfica segura de más de 32 bytes sin exponerla en el repositorio Git.

---

## 4. Opción B: Despliegue Manual Servicio por Servicio en Render

Si prefieres crear cada servicio de forma individual en el panel de Render (`New +` -> **Web Service** -> **Build and deploy from a Git repository** -> repo `Granja-de-cerdos-`), utiliza exactamente estos parámetros:

### 4.1. Parámetros de Compilación Docker

| Campo en Render | Servicio 1: **`agropig-web`** | Servicio 2: **`agropig-api`** |
| :--- | :--- | :--- |
| **Name** | `agropig-web` | `agropig-api` |
| **Runtime / Language** | **Docker** | **Docker** |
| **Branch** | `main` | `main` |
| **Region** | La misma región o más cercana a Neon (ej. `Ohio (US East)`) | La misma región que `agropig-web` |
| **Root Directory** | *(dejar vacío)* | *(dejar vacío)* |
| **Docker Build Context Directory** | `./WEB/WEB` | `./API/API` |
| **Dockerfile Path** | `./WEB/WEB/WEB/Dockerfile` | `./API/API/API/Dockerfile` |
| **Instance Type** | `Free` (o superior en producción continua) | `Free` (o superior) |
| **Health Check Path** *(Advanced)* | `/healthz` | `/healthz` |

> [!WARNING]
> No dejes `Docker Build Context Directory` en `.` ni cambies `Root Directory`, ya que tanto `WEB/WEB/WEB/Dockerfile` como `API/API/API/Dockerfile` ejecutan `COPY ["WEB/WEB.csproj", ...]` y `COPY ["API/API.csproj", ...]` esperando como contexto `./WEB/WEB` y `./API/API` respectivamente.

### 4.2. Variables de Entorno (`Environment Variables`)

#### Para `agropig-web`

| Key | Value | Notas |
| :--- | :--- | :--- |
| `ASPNETCORE_ENVIRONMENT` | `Production` | Activa cookies `Secure`, HSTS y cabeceras de seguridad |
| `PORT` | `8080` | Puerto HTTP interno que enlaza `Program.cs` (`0.0.0.0:$PORT`) |
| `ASPNETCORE_FORWARDEDHEADERS_ENABLED` | `true` | Respeta `X-Forwarded-Proto` y `X-Forwarded-For` del proxy TLS de Render |
| `DATABASE_URL` | `postgresql://<USUARIO>:<PASSWORD>@<HOST_NEON>/neondb?sslmode=require&channel_binding=require` | `PostgresConnectionResolver` convierte la URI automáticamente para `Npgsql` |
| `Jwt__Key` | *(cadena aleatoria >= 32 caracteres)* | Doble guion bajo `__`. Clave para firmar tokens JWT móviles |
| `Jwt__Issuer` | `AgroPig.Web` | Emisor esperado en validación JWT |
| `Jwt__Audience` | `AgroPig.Movil` | Audiencia esperada en validación JWT |

#### Para `agropig-api`

| Key | Value | Notas |
| :--- | :--- | :--- |
| `ASPNETCORE_ENVIRONMENT` | `Production` | Modo producción |
| `PORT` | `8080` | Puerto HTTP interno del contenedor |
| `ASPNETCORE_FORWARDEDHEADERS_ENABLED` | `true` | Procesamiento de cabeceras proxy |
| `DATABASE_URL` | `postgresql://<USUARIO>:<PASSWORD>@<HOST_NEON>/neondb?sslmode=require&channel_binding=require` | Reservada para futura persistencia directa en PostgreSQL |
| `CORS_ALLOWED_ORIGINS` *(opcional)* | `https://mi-dominio-propio.com` | Por defecto ya permite `localhost`, `127.0.0.1`, `192.168.*` y `*.onrender.com` |

#### Cómo generar una `Jwt__Key` segura (si despliegas manual)

En PowerShell local puedes generar una clave aleatoria de 48 bytes en Base64:

```powershell
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }) -as [byte[]])
```

---

## 5. Verificación Post-Despliegue

Cuando ambos servicios muestren estado **Live** en Render:

1. **Comprobar salud de `agropig-web`:**
   Abre `https://<tu-servicio-web>.onrender.com/healthz`
   Debe responder `200 OK` con JSON:
   `{"status":"ok","service":"agropig-web","timestamp":"..."}`

2. **Comprobar salud de `agropig-api`:**
   Abre `https://<tu-servicio-api>.onrender.com/healthz`
   Debe responder `200 OK` con JSON:
   `{"status":"ok","service":"agropig-api","timestamp":"..."}`

3. **Probar inicio de sesión Web + conexión real con Neon:**
   Abre `https://<tu-servicio-web>.onrender.com/Login` en el navegador e inicia sesión con tu cuenta inicial (`admin`, `vet` u `operario`).
   - El sistema obligará a cambiar la contraseña temporal al primer ingreso (`/Cuenta?obligatorio=true`).
   - Verifica que carguen los lotes, inventario de alimento y presupuestos desde Neon `neondb`.

4. **Verificar endpoint de estado de sincronización móvil:**
   Abre `https://<tu-servicio-web>.onrender.com/api/v1/sync/status` -> debe responder `{"status":"online"}`.

---

## 6. Conectar la App Móvil (`PorciApp-v2.0.apk`) al Servidor en Render

La app Android (`APP/PorciApp-v2.0.apk`) trae por defecto la IP de red local, pero permite apuntar a cualquier servidor en la nube sin recompilar el APK:

1. Abre **PorciApp** en el teléfono y ve a la pantalla de inicio de sesión (**GRANJA SAN JOSÉ**).
2. Toca el botón superior **`Servidor: ... ⚙️`** debajo del título.
3. En el campo **URL del Servidor**, borra la IP local y escribe la URL HTTPS pública de tu servicio **`agropig-web`** en Render:
   ```text
   https://<tu-servicio-web>.onrender.com
   ```
   - Incluye siempre **`https://`** (si lo omites, `SessionManager.normalizeUrl` antepone `http://`).
   - No agregues barras finales ni rutas (`/Login`, `/api/...`).
4. Pulsa **Guardar** e inicia sesión con tu usuario y contraseña actualizada.

> [!NOTE]
> **Comportamiento del Plan Free de Render (Cold Start):**
> En el plan gratuito de Render, si nadie usa el servicio durante **15 minutos**, el contenedor entra en reposo y tarda **30 a 50 segundos** en despertar ante la primera petición. Si un operario registra aplicaciones, partos o labores de campo mientras el servidor está dormido o sin cobertura, **PorciApp** guarda el registro localmente en Room (`syncQueue`) y lo sincroniza automáticamente en cuanto el servidor responde.

---

## 7. Solución Rápida de Problemas Comunes

| Síntoma | Causa probable | Solución |
| :--- | :--- | :--- |
| Error de build Docker: `COPY failed: WEB/WEB.csproj not found` | Contexto Docker incorrecto en Render | Verifica que **Docker Build Context Directory** sea `./WEB/WEB` (y `./API/API` para API), con **Root Directory** vacío |
| El navegador redirige en bucle tras hacer Login en `agropig-web` | Proxy inverso sin `X-Forwarded-Proto` | Asegúrate de tener `ASPNETCORE_FORWARDEDHEADERS_ENABLED=true` y el `Program.cs` actualizado con `app.UseForwardedHeaders()` |
| El token JWT móvil deja de ser válido cada vez que Render reinicia | `Jwt__Key` vacía o menor a 32 bytes (`ClaveEfimera`) | Configura `Jwt__Key` con al menos 32 caracteres en Render y guarda cambios (redeploy automático) |
| Error `SSL connection` o fallo de autenticación en `agropig-web` | Falta `DATABASE_URL` o parámetros `sslmode` en Neon | Copia nuevamente la URI completa de Neon incluyendo `?sslmode=require&channel_binding=require` en `DATABASE_URL` |
| Error `429` o demora en la primera petición móvil | Bloqueo temporal por intentos fallidos o *cold start* en plan Free | Espera 1 minuto tras despertar el servicio o revisa los logs en **Render -> Logs** |
