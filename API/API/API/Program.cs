using API.Application.Services;
using API.Infrastructure.Data;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddControllers();
builder.Services.AddOpenApi();

// Configuración de Base de Datos EF Core
builder.Services.AddDbContext<GranjaPorcinaDbContext>(options =>
{
    var connectionString = builder.Configuration.GetConnectionString("PostgreSQL");
    options.UseInMemoryDatabase("GranjaPorcinaDb");
});

// Registrar Motor de Presupuestos Automáticos
builder.Services.AddScoped<BudgetEngine>();

// Configurar CORS restringido a orígenes locales de la granja
builder.Services.AddCors(options =>
{
    options.AddPolicy("LocalFarmOnly", policy =>
    {
        policy.SetIsOriginAllowed(origin =>
                  Uri.TryCreate(origin, UriKind.Absolute, out var uri) &&
                  (uri.Host == "localhost" || uri.Host == "127.0.0.1" || uri.Host.StartsWith("192.168.")))
              .AllowAnyHeader()
              .AllowAnyMethod();
    });
});

var app = builder.Build();

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

app.UseHttpsRedirection();

app.UseAuthorization();

app.MapControllers();

app.Run();
