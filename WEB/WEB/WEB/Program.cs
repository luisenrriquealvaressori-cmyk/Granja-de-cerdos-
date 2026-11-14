using System.Security.Claims;
using System.Threading.RateLimiting;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.IdentityModel.Tokens;
using WEB.Security;

var builder = WebApplication.CreateBuilder(args);

var renderPort = Environment.GetEnvironmentVariable("PORT");
if (!string.IsNullOrWhiteSpace(renderPort) && string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("ASPNETCORE_URLS")))
{
    builder.WebHost.UseUrls($"http://0.0.0.0:{renderPort}");
}

if (args.Contains("--reset-db"))
{
    if (!builder.Environment.IsDevelopment() || !args.Contains("--confirm-reset"))
    {
        Console.Error.WriteLine("[RESET-DB] Operación bloqueada. Requiere entorno Development y el flag explícito '--confirm-reset'.");
        return;
    }
    var connString = PostgresConnectionResolver.Resolve(builder.Configuration);
    Console.WriteLine("[RESET-DB] Conectando a PostgreSQL...");
    var sqlPath = Path.GetFullPath(Path.Combine(builder.Environment.ContentRootPath, "..", "..", "..", "DATABASE", "00_reset_limpio.sql"));
    var sql = await File.ReadAllTextAsync(sqlPath);
    await using var conn = new Npgsql.NpgsqlConnection(connString);
    await conn.OpenAsync();
    await using var cmd = new Npgsql.NpgsqlCommand(sql, conn);
    cmd.CommandTimeout = 120;
    await cmd.ExecuteNonQueryAsync();
    Console.WriteLine("[RESET-DB] Base de datos agropig_db reiniciada con ÉXITO.");
    return;
}

// ---------------------------------------------------------------------------
// Servicios
// ---------------------------------------------------------------------------
builder.Services.AddRazorPages()
    .AddMvcOptions(o => o.Filters.Add<PoliticaPageFilter>());
builder.Services.AddHttpContextAccessor();
builder.Services.Configure<ForwardedHeadersOptions>(options =>
{
    options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    options.KnownIPNetworks.Clear();
    options.KnownProxies.Clear();
});
builder.Services.AddScoped<WEB.Services.UserRoleService>();
builder.Services.AddScoped<WEB.Services.AgroPigDataService>();

// Seguridad: autenticación (cookie para navegador, JWT para la app móvil), auditoría y políticas
var jwtSettings = JwtSettings.From(builder.Configuration);
builder.Services.AddSingleton(jwtSettings);
builder.Services.AddSingleton<JwtTokenService>();
builder.Services.AddSingleton<AuditService>();
builder.Services.AddSingleton<AuthService>();

builder.Services
    .AddAuthentication(o =>
    {
        o.DefaultScheme = "Smart";
        o.DefaultChallengeScheme = "Smart";
        o.DefaultForbidScheme = "Smart";
    })
    .AddPolicyScheme("Smart", "Cookie o JWT", o =>
    {
        // Si la petición trae "Authorization: Bearer ..." se valida como JWT; si no, como cookie de sesión
        o.ForwardDefaultSelector = ctx =>
            ctx.Request.Headers.Authorization.ToString().StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)
                ? JwtBearerDefaults.AuthenticationScheme
                : CookieAuthenticationDefaults.AuthenticationScheme;
    })
    .AddCookie(CookieAuthenticationDefaults.AuthenticationScheme, o =>
    {
        o.LoginPath = "/Login";
        o.LogoutPath = "/Logout";
        o.AccessDeniedPath = "/AccesoDenegado";
        o.Cookie.Name = "agropig.auth";
        o.Cookie.HttpOnly = true;
        o.Cookie.SameSite = SameSiteMode.Lax; // bloquea envío de la cookie en POST/PUT/DELETE entre sitios (CSRF)
        o.Cookie.SecurePolicy = builder.Environment.IsDevelopment()
            ? CookieSecurePolicy.SameAsRequest
            : CookieSecurePolicy.Always;
        o.ExpireTimeSpan = TimeSpan.FromHours(8);
        o.SlidingExpiration = true;
        // En rutas /api devolver 401/403 en lugar de redirigir a la pantalla de login
        o.Events.OnRedirectToLogin = ctx =>
        {
            if (ctx.Request.Path.StartsWithSegments("/api")) ctx.Response.StatusCode = StatusCodes.Status401Unauthorized;
            else ctx.Response.Redirect(ctx.RedirectUri);
            return Task.CompletedTask;
        };
        o.Events.OnRedirectToAccessDenied = ctx =>
        {
            if (ctx.Request.Path.StartsWithSegments("/api")) ctx.Response.StatusCode = StatusCodes.Status403Forbidden;
            else ctx.Response.Redirect(ctx.RedirectUri);
            return Task.CompletedTask;
        };
    })
    .AddJwtBearer(JwtBearerDefaults.AuthenticationScheme, o =>
    {
        o.MapInboundClaims = true;
        o.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = jwtSettings.Issuer,
            ValidateAudience = true,
            ValidAudience = jwtSettings.Audience,
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = jwtSettings.SigningKey,
            ValidateLifetime = true,
            ClockSkew = TimeSpan.FromMinutes(1),
            NameClaimType = ClaimTypes.Name,
            RoleClaimType = ClaimTypes.Role
        };
    });

builder.Services.AddAuthorizationBuilder()
    // Todo requiere sesión salvo que se marque explícitamente [AllowAnonymous]
    .SetFallbackPolicy(new Microsoft.AspNetCore.Authorization.AuthorizationPolicyBuilder().RequireAuthenticatedUser().Build())
    .AddPolicy(Policies.PresupuestosGestionar, p => p.RequireRole(AppRoles.Administrador))
    .AddPolicy(Policies.InventarioGestionar, p => p.RequireRole(AppRoles.Administrador))
    .AddPolicy(Policies.KardexRegistrar, p => p.RequireRole(AppRoles.Administrador, AppRoles.Operario))
    .AddPolicy(Policies.SanidadRegistrar, p => p.RequireRole(AppRoles.Administrador, AppRoles.Veterinario))
    .AddPolicy(Policies.AlimentacionRegistrar, p => p.RequireRole(AppRoles.Administrador, AppRoles.Operario))
    .AddPolicy(Policies.CerdosEditar, p => p.RequireRole(AppRoles.Administrador, AppRoles.Veterinario, AppRoles.Operario))
    .AddPolicy(Policies.CerdosBaja, p => p.RequireRole(AppRoles.Administrador, AppRoles.Veterinario))
    .AddPolicy(Policies.SincronizacionMovil, p => p.RequireRole(AppRoles.Administrador, AppRoles.Veterinario, AppRoles.Operario))
    .AddPolicy(Policies.UsuariosGestionar, p => p.RequireRole(AppRoles.Administrador))
    .AddPolicy(Policies.ComprasGestionar, p => p.RequireRole(AppRoles.Administrador, AppRoles.Veterinario))
    .AddPolicy(Policies.LotesGestionar, p => p.RequireRole(AppRoles.Administrador, AppRoles.Veterinario))
    .AddPolicy(Policies.TareasGestionar, p => p.RequireRole(AppRoles.Administrador, AppRoles.Veterinario));

builder.Services.AddRateLimiter(o =>
{
    o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    // Freno por IP al endpoint de login móvil (además del bloqueo por cuenta en BD)
    o.AddPolicy("login", ctx => RateLimitPartition.GetFixedWindowLimiter(
        ctx.Connection.RemoteIpAddress?.ToString() ?? "desconocida",
        _ => new FixedWindowRateLimiterOptions { PermitLimit = 10, Window = TimeSpan.FromMinutes(1) }));
});

var app = builder.Build();

if (jwtSettings.ClaveEfimera)
{
    app.Logger.LogWarning("Jwt:Key no configurada o menor a 32 bytes: se usa una clave efímera; los tokens móviles se invalidarán al reiniciar. Configure Jwt:Key con user-secrets o variable de entorno.");
}

// ---------------------------------------------------------------------------
// Pipeline HTTP
// ---------------------------------------------------------------------------
app.UseForwardedHeaders();

if (!app.Environment.IsDevelopment())
{
    app.UseExceptionHandler("/Error");
    // The default HSTS value is 30 days. You may want to change this for production scenarios, see https://aka.ms/aspnetcore-hsts.
    app.UseHsts();
}

if (app.Environment.IsDevelopment() || !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("ASPNETCORE_HTTPS_PORT")))
{
    app.UseHttpsRedirection();
}

// Cabeceras de seguridad básicas
app.Use(async (ctx, next) =>
{
    ctx.Response.Headers["X-Content-Type-Options"] = "nosniff";
    ctx.Response.Headers["X-Frame-Options"] = "DENY";
    ctx.Response.Headers["Referrer-Policy"] = "strict-origin-when-cross-origin";
    await next();
});

app.UseRouting();
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();

// Obliga a reemplazar contraseñas iniciales/temporales antes de usar el sistema
app.Use(async (ctx, next) =>
{
    if (ctx.User.Identity?.IsAuthenticated == true
        && ctx.User.FindFirstValue(CustomClaims.DebeCambiarPassword) == "1")
    {
        var esApi = ctx.Request.Path.StartsWithSegments("/api");
        if (esApi && !ctx.Request.Path.StartsWithSegments("/api/v1/auth"))
        {
            // Un token emitido con contraseña temporal solo puede usarse para cambiarla
            ctx.Response.StatusCode = StatusCodes.Status403Forbidden;
            await ctx.Response.WriteAsJsonAsync(new { error = "Debes cambiar tu contraseña antes de continuar.", code = "PASSWORD_CHANGE_REQUIRED" });
            return;
        }
        if (!esApi
            && !ctx.Request.Path.StartsWithSegments("/Cuenta")
            && !ctx.Request.Path.StartsWithSegments("/Logout")
            && !Path.HasExtension(ctx.Request.Path))
        {
            ctx.Response.Redirect("/Cuenta?obligatorio=true");
            return;
        }
    }
    await next();
});

app.MapStaticAssets().AllowAnonymous();
app.MapRazorPages()
   .WithStaticAssets();

// ---------------------------------------------------------------------------
// API REST
// ---------------------------------------------------------------------------

// Health check ligero para balanceadores (Render) sin despertar PostgreSQL innecesariamente
app.MapGet("/healthz", () => Results.Ok(new { status = "ok", service = "agropig-web", timestamp = DateTime.UtcNow }))
   .AllowAnonymous();

// Autenticación para la app móvil: devuelve un JWT de corta/media duración
app.MapPost("/api/v1/auth/login", async (LoginApiRequest req, AuthService auth, JwtTokenService jwt, HttpContext ctx) =>
{
    var identificador = (!string.IsNullOrWhiteSpace(req.Usuario) ? req.Usuario : req.Email) ?? string.Empty;
    var resultado = await auth.ValidarAsync(identificador, req.Password, ctx.Connection.RemoteIpAddress?.ToString());
    switch (resultado.Estado)
    {
        case LoginEstado.Ok:
            var (token, expira) = jwt.Emitir(resultado.Usuario!);
            var u = resultado.Usuario!;
            return Results.Ok(new
            {
                token,
                expiresAt = expira,
                user = new { id = u.Id, nombre = u.Nombre, email = u.Email, rol = u.Rol, matricula = u.Matricula, cargo = u.Cargo, debeCambiarPassword = u.DebeCambiarPassword }
            });
        case LoginEstado.Bloqueado:
            return Results.Json(new { error = "Cuenta bloqueada temporalmente por intentos fallidos.", retryAfterSeconds = (int)(resultado.Espera?.TotalSeconds ?? 600) }, statusCode: StatusCodes.Status429TooManyRequests);
        case LoginEstado.Invalido:
            return Results.Json(new { error = "Credenciales inválidas." }, statusCode: StatusCodes.Status401Unauthorized);
        default:
            return Results.Json(new { error = "Servicio de autenticación no disponible." }, statusCode: StatusCodes.Status503ServiceUnavailable);
    }
}).AllowAnonymous().RequireRateLimiting("login");

// Cambio de contraseña desde la app móvil. Devuelve un token nuevo ya sin la marca de "cambio obligatorio".
app.MapPost("/api/v1/auth/change-password", async (ChangePasswordApiRequest req, ClaimsPrincipal user, AuthService auth, JwtTokenService jwt, HttpContext ctx) =>
{
    var userId = user.GetUserId();
    if (userId is null) return Results.Unauthorized();
    if (req is null || string.IsNullOrEmpty(req.Actual) || string.IsNullOrEmpty(req.Nueva))
        return Results.BadRequest(new { error = "Indica la contraseña actual y la nueva." });

    var (ok, error) = await auth.CambiarPasswordAsync(userId.Value, req.Actual, req.Nueva, ctx.Connection.RemoteIpAddress?.ToString());
    if (!ok) return Results.BadRequest(new { error });

    var rol = user.FindFirstValue(ClaimTypes.Role) ?? AppRoles.Operario;
    var actualizado = new AuthUser(
        userId.Value,
        user.FindFirstValue(ClaimTypes.Name) ?? "",
        user.FindFirstValue(ClaimTypes.Email) ?? "",
        rol,
        user.FindFirstValue(CustomClaims.Matricula),
        user.FindFirstValue(CustomClaims.Cargo),
        Guid.TryParse(user.FindFirstValue(CustomClaims.GranjaId), out var g) ? g : null,
        false);
    var (token, expira) = jwt.Emitir(actualizado);
    return Results.Ok(new { token, expiresAt = expira });
});

// Sincronización Outbox para la App Móvil Android (Campo): requiere JWT de un usuario válido.
// El responsable de cada registro es el usuario del token. Lo que no se pueda aplicar se devuelve en 'rejected'.
app.MapPost("/api/v1/sync/batch", async (WEB.Services.SyncBatchRequest request, ClaimsPrincipal user, WEB.Services.AgroPigDataService dataService) =>
{
    var rechazados = new List<WEB.Services.SyncRechazo>();
    var savedIds = await dataService.ProcesarBatchSyncMovilAsync(
        request,
        rechazados,
        user.FindFirstValue(ClaimTypes.Name),
        user.FindFirstValue(CustomClaims.Matricula));
    return Results.Ok(new
    {
        status = rechazados.Count == 0 ? "success" : "partial",
        savedIds,
        // Objeto { "<id>": "<motivo>" }: es el formato que lee SyncWorker en la app
        rejected = rechazados
            .Where(r => !string.IsNullOrWhiteSpace(r.Id))
            .GroupBy(r => r.Id)
            .ToDictionary(grp => grp.Key, grp => $"{grp.Last().Entidad}: {grp.Last().Motivo}"),
        processedCount = savedIds.Count,
        serverTimestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
    });
}).RequireAuthorization(Policies.SincronizacionMovil);

// Catálogo (cerdas, medicamentos, alimentos, lotes activos) para trabajar en campo sin teclear nombres
app.MapGet("/api/v1/movil/catalogo", async (WEB.Services.AgroPigDataService dataService) =>
    Results.Ok(await dataService.ObtenerCatalogoMovilAsync()))
    .RequireAuthorization(Policies.SincronizacionMovil);

// Comprobación de disponibilidad (sin datos internos)
app.MapGet("/api/v1/sync/status", () => Results.Ok(new { status = "online" })).AllowAnonymous();

// Padrón de Cerdos, Jaulas y Motor de Cálculo Zootécnico
app.MapGet("/api/v1/cerdos", async (string? etapa, string? busqueda, WEB.Services.AgroPigDataService dataService) =>
{
    var pageData = await dataService.GetCerdosPageDataAsync(etapa, busqueda);
    return Results.Ok(pageData);
});

app.MapGet("/api/v1/cerdos/{id:guid}", async (Guid id, WEB.Services.AgroPigDataService dataService) =>
{
    var cerdo = await dataService.GetCerdoByIdAsync(id);
    return cerdo is not null ? Results.Ok(cerdo) : Results.NotFound(new { error = "Cerdo no encontrado" });
});

app.MapPost("/api/v1/cerdos", async (WEB.Services.CerdoInputModel input, WEB.Services.AgroPigDataService dataService) =>
{
    var ok = await dataService.GuardarCerdoAsync(input);
    return ok ? Results.Ok(new { status = "success", message = "Cerdo registrado con éxito" })
              : Results.BadRequest(new { error = "Datos del cerdo inválidos" });
}).RequireAuthorization(Policies.CerdosEditar);

app.MapPut("/api/v1/cerdos/{id:guid}", async (Guid id, WEB.Services.CerdoInputModel input, WEB.Services.AgroPigDataService dataService) =>
{
    input.Id = id;
    var ok = await dataService.GuardarCerdoAsync(input);
    return ok ? Results.Ok(new { status = "success", message = "Cerdo actualizado con éxito" })
              : Results.BadRequest(new { error = "No se pudo actualizar el cerdo" });
}).RequireAuthorization(Policies.CerdosEditar);

app.MapDelete("/api/v1/cerdos/{id:guid}", async (Guid id, WEB.Services.AgroPigDataService dataService) =>
{
    var ok = await dataService.EliminarCerdoAsync(id);
    return ok ? Results.Ok(new { status = "success", message = "Cerdo dado de baja con éxito" })
              : Results.BadRequest(new { error = "No se pudo eliminar el cerdo" });
}).RequireAuthorization(Policies.CerdosBaja);

app.MapGet("/api/v1/jaulas", async (string? etapa, WEB.Services.AgroPigDataService dataService) =>
{
    var jaulas = await dataService.GetJaulasCorralesAsync(etapa);
    return Results.Ok(jaulas);
});

app.MapGet("/api/v1/calculadora-zootecnica", (DateTime? fechaNacimiento, decimal? peso) =>
{
    var fecha = fechaNacimiento ?? DateTime.Today.AddDays(-30);
    var p = peso ?? 12.0m;
    var result = WEB.Services.AgroPigDataService.CalcularPlanZootecnico(fecha, p);
    return Results.Ok(result);
});

app.MapGet("/api/v1/lotes/proyeccion-fecha-tope", async (
    Guid? loteId, 
    int? cabezas, 
    decimal? pesoLbs, 
    DateTime? fechaTope, 
    decimal? gmdLbs, 
    decimal? consumoDiarioLbs, 
    decimal? precioAlimentoQq, 
    decimal? precioVentaLb, 
    decimal? costoManoObraCerdo,
    decimal? costoSanitarioCerdo,
    decimal? costosIndirectosCerdo,
    decimal? tipoCambio, 
    WEB.Services.AgroPigDataService dataService) =>
{
    var proyeccion = await dataService.CalcularProyeccionLoteFechaTopeAsync(
        loteId, cabezas, pesoLbs, fechaTope, gmdLbs, consumoDiarioLbs, 
        precioAlimentoQq, precioVentaLb, costoManoObraCerdo, costoSanitarioCerdo, 
        costosIndirectosCerdo, tipoCambio);
    return Results.Ok(proyeccion);
});

// ---------------------------------------------------------------------------
// Endpoints REST: Cronograma, Horario de Actividades, Tareas y Notificaciones
// ---------------------------------------------------------------------------

// Listado de tareas y actividades programadas
app.MapGet("/api/v1/tareas", async (
    DateTime? fecha, 
    int? mes, 
    int? anio, 
    string? tipo, 
    string? estado, 
    string? rol, 
    WEB.Services.AgroPigDataService dataService) =>
{
    var data = await dataService.GetCalendarioPageDataAsync(fecha, mes, anio, tipo, estado, rol);
    return Results.Ok(new
    {
        fechaSeleccionada = data.FechaSeleccionada,
        totalHoy = data.TareasHoyTotal,
        pendientesHoy = data.TareasHoyPendientes,
        completadasHoy = data.TareasHoyCompletadas,
        urgentesHoy = data.TareasUrgentes,
        tareas = data.TareasHorarioDia,
        tareasMes = data.TareasMes
    });
}).RequireAuthorization();

// Crear o actualizar tarea
app.MapPost("/api/v1/tareas", async (WEB.Services.TareaActividadInputModel input, WEB.Services.AgroPigDataService dataService) =>
{
    if (string.IsNullOrWhiteSpace(input.Titulo))
        return Results.BadRequest(new { error = "El título de la tarea es obligatorio." });

    var ok = await dataService.GuardarTareaAsync(input);
    return ok ? Results.Ok(new { status = "success", message = "Tarea programada con éxito" })
              : Results.BadRequest(new { error = "No se pudo guardar la tarea programada" });
}).RequireAuthorization(WEB.Security.Policies.TareasGestionar);

// Actualizar estado de tarea (completar, iniciar, etc.)
app.MapPut("/api/v1/tareas/{id:guid}/estado", async (
    Guid id, 
    ActualizarEstadoTareaRequest req, 
    System.Security.Claims.ClaimsPrincipal user, 
    WEB.Services.AgroPigDataService dataService) =>
{
    var operario = user.Identity?.Name ?? "Operador";
    var ok = await dataService.ActualizarEstadoTareaAsync(id, req.Estado, req.Observaciones, operario);
    return ok ? Results.Ok(new { status = "success", message = "Estado de tarea actualizado" })
              : Results.NotFound(new { error = "Tarea no encontrada" });
}).RequireAuthorization();

// Eliminar tarea
app.MapDelete("/api/v1/tareas/{id:guid}", async (Guid id, WEB.Services.AgroPigDataService dataService) =>
{
    var ok = await dataService.EliminarTareaAsync(id);
    return ok ? Results.Ok(new { status = "success", message = "Tarea eliminada" })
              : Results.NotFound(new { error = "Tarea no encontrada" });
}).RequireAuthorization(WEB.Security.Policies.TareasGestionar);

// Centro de Notificaciones en vivo
app.MapGet("/api/v1/notificaciones", async (WEB.Services.AgroPigDataService dataService) =>
{
    var notificaciones = await dataService.GetNotificacionesActivasAsync();
    return Results.Ok(new
    {
        total = notificaciones.Count,
        criticas = notificaciones.Count(n => n.Severidad == "critica"),
        notificaciones
    });
}).RequireAuthorization();

// Resolver / descartar notificación
app.MapPost("/api/v1/notificaciones/{id:guid}/resolver", async (Guid id, WEB.Services.AgroPigDataService dataService) =>
{
    var ok = await dataService.ResolverNotificacionAsync(id);
    return ok ? Results.Ok(new { status = "success" }) : Results.NotFound();
}).RequireAuthorization();

// Registro de Baja / Mortalidad
app.MapPost("/api/v1/bajas-mortalidad", async (
    WEB.Services.BajaMortalidadInputModel input, 
    System.Security.Claims.ClaimsPrincipal user, 
    WEB.Services.AgroPigDataService dataService) =>
{
    if (string.IsNullOrWhiteSpace(input.Crotal) || string.IsNullOrWhiteSpace(input.CausaMuerte))
        return Results.BadRequest(new { error = "El crotal y la causa de muerte son obligatorios." });

    if (string.IsNullOrWhiteSpace(input.RegistradoPor))
        input.RegistradoPor = user.Identity?.Name ?? "Operario Veterinario";

    var ok = await dataService.RegistrarBajaMortalidadAsync(input);
    return ok ? Results.Ok(new { status = "success", message = "Baja porcina registrada exitosamente" })
              : Results.BadRequest(new { error = "No se pudo asentar la baja" });
}).RequireAuthorization(WEB.Security.Policies.CerdosBaja);

// ---------------------------------------------------------------------------
// Inicialización de esquema (una sola vez al arrancar)
// ---------------------------------------------------------------------------
using (var scope = app.Services.CreateScope())
{
    try
    {
        await scope.ServiceProvider.GetRequiredService<AuthService>().EnsureSchemaAsync();
        var dataService = scope.ServiceProvider.GetRequiredService<WEB.Services.AgroPigDataService>();
        await dataService.EnsureBaseEntitiesAsync();
    }
    catch (Exception ex)
    {
        // Resiliente en entornos locales: la app arranca aunque la BD aún no esté disponible
        app.Logger.LogWarning(ex, "No se pudo inicializar el esquema base en el arranque.");
    }
}

app.Run();

public record LoginApiRequest(string? Email, string Password, string? Usuario = null);
public record ChangePasswordApiRequest(string Actual, string Nueva);
public record ActualizarEstadoTareaRequest(string Estado, string? Observaciones);

