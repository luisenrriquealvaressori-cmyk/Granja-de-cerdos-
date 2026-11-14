# Granja Porcina San José — Sistema Integral (WEB + APP + DATABASE)

Plataforma de gestión operativa, zootécnica y financiera para una pequeña granja porcina, dividida entre operación móvil offline en campo y análisis lógico-económico en consola web.

## Estructura del Repositorio

- **`WEB/`** — Consola Web ASP.NET Core Razor Pages (`WEB/WEB/WEB/WEB.csproj`):
  - Lógica zootécnica (`Ciclo 114d – 21d – 7d`, `ICA`, `GMD`, silos, corrales `5×3 m`, calendario operativo).
  - Control de costes bimonetario (`$ USD` / `C$ NIO` a tasa `36.80`), presupuestos por centro de costo (`CC-*`), Kardex y compras con separación estricta de permisos por rol (`Administrador`, `Veterinario`, `Operario`).
  - API REST de sincronización móvil (`/api/v1/auth/login`, `/api/v1/movil/catalogo`, `/api/v1/sync/batch`, `/api/v1/lotes/proyeccion-fecha-tope`).
- **`APP/`** — App Móvil Android Offline-First (Kotlin + Jetpack Compose + Room SQLite v4):
  - Registro offline con sincronización outbox hacia la API web.
  - Eventos reproductivos (`SERVICIO_IA`, `PARTO`, `DESTETE`), aplicaciones sanitarias/alimentación, control de tareas y labores físicas de campo (`LABOR_CAMPO`) sin exponer precios a roles operativos/veterinarios.
- **`DATABASE/`** — Scripts PostgreSQL (`00_reset_limpio.sql`, `01_schema_agropig.sql`, `02_views_and_functions.sql`, `03_seed_data.sql`, `04_additional_seed.sql`, `04_usuarios_prueba.sql`, `verify_db.sql`).
- **`API/`** — Proyecto API auxiliar de referencia (`API/API/API`).

## Compilación Rápida

### Consola Web (.NET)
```powershell
dotnet build "WEB/WEB/WEB/WEB.csproj" --no-incremental
```

### App Móvil (Android)
```powershell
cd APP
.\gradlew.bat --no-configuration-cache assembleDebug --console=plain
```
