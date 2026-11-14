using API.Application.Services;
using API.Infrastructure.Data;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

var renderPort = Environment.GetEnvironmentVariable("PORT");
if (!string.IsNullOrWhiteSpace(renderPort) && string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("ASPNETCORE_URLS")))
{
    builder.WebHost.UseUrls($"http://0.0.0.0:{renderPort}");
}

// Add services to the container.
builder.Services.AddControllers();
builder.Services.AddOpenApi();
builder.Services.Configure<ForwardedHeadersOptions>(options =>
{
    options.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    options.KnownIPNetworks.Clear();
    options.KnownProxies.Clear();
});

// Configuración de Base de Datos EF Core
builder.Services.AddDbContext<GranjaPorcinaDbContext>(options =>
{
    options.UseInMemoryDatabase("GranjaPorcinaDb");
});

// Registrar Motor de Presupuestos Automáticos
builder.Services.AddScoped<BudgetEngine>();

// Orígenes permitidos en red local de granja y despliegue en Render (+ CORS_ALLOWED_ORIGINS opcional)
var extraOrigins = (Environment.GetEnvironmentVariable("CORS_ALLOWED_ORIGINS") ?? string.Empty)
    .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
    .ToHashSet(StringComparer.OrdinalIgnoreCase);

builder.Services.AddCors(options =>
{
    options.AddPolicy("LocalFarmOnly", policy =>
    {
        policy.SetIsOriginAllowed(origin =>
                  extraOrigins.Contains(origin) ||
                  (Uri.TryCreate(origin, UriKind.Absolute, out var uri) &&
                   (uri.Host == "localhost"
                    || uri.Host == "127.0.0.1"
                    || uri.Host.StartsWith("192.168.")
                    || uri.Host.EndsWith(".onrender.com", StringComparison.OrdinalIgnoreCase))))
              .AllowAnyHeader()
              .AllowAnyMethod();
    });
});

var app = builder.Build();

app.UseForwardedHeaders();

using (var scope = app.Services.CreateScope())
{
    var context = scope.ServiceProvider.GetRequiredService<GranjaPorcinaDbContext>();
    context.Database.EnsureCreated();
}

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

app.UseCors("LocalFarmOnly");

if (app.Environment.IsDevelopment() || !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("ASPNETCORE_HTTPS_PORT")))
{
    app.UseHttpsRedirection();
}

app.UseAuthorization();

app.MapGet("/", () => Results.Ok(new { service = "agropig-api", status = "online", health = "/healthz" }));
app.MapGet("/healthz", () => Results.Ok(new { status = "ok", service = "agropig-api", timestamp = DateTime.UtcNow }));

app.MapControllers();

app.Run();
