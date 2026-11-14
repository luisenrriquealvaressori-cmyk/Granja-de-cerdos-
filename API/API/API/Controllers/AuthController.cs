using Microsoft.AspNetCore.Mvc;
using Npgsql;

namespace API.Controllers;

public class LoginRequest
{
    public string Email { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;
    public string? Rol { get; set; }
}

public class UserSessionDto
{
    public string Id { get; set; } = string.Empty;
    public string Nombre { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string Rol { get; set; } = string.Empty;
    public string Cargo { get; set; } = string.Empty;
    public string Matricula { get; set; } = string.Empty;
    public string Granja { get; set; } = "Granja San José (Principal)";
    public List<string> Permisos { get; set; } = new();
}

[ApiController]
[Route("api/[controller]")]
public class AuthController : ControllerBase
{
    private readonly string _connectionString;

    public AuthController(IConfiguration configuration)
    {
        _connectionString = configuration.GetConnectionString("PostgreSQL")
            ?? throw new InvalidOperationException("Falta 'ConnectionStrings:PostgreSQL'.");
    }

    [HttpPost("login")]
    public async Task<IActionResult> Login([FromBody] LoginRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Email) || string.IsNullOrWhiteSpace(request.Password))
        {
            return BadRequest(new { success = false, message = "El usuario/correo y la contraseña son requeridos." });
        }

        await using var conn = new NpgsqlConnection(_connectionString);
        await conn.OpenAsync();

        await using var cmd = new NpgsqlCommand(@"
            SELECT id, nombre, COALESCE(email, nombre_usuario, ''), rol, COALESCE(matricula_profesional, ''),
                   activo, (password_hash = crypt(@p, password_hash)) AS ok
            FROM usuarios_operadores
            WHERE lower(COALESCE(nombre_usuario, '')) = lower(@e)
               OR lower(COALESCE(email, '')) = lower(@e)
            LIMIT 1;", conn);
        cmd.Parameters.AddWithValue("e", request.Email.Trim());
        cmd.Parameters.AddWithValue("p", request.Password);

        await using var r = await cmd.ExecuteReaderAsync();
        if (!await r.ReadAsync() || !r.GetBoolean(5) || r.IsDBNull(6) || !r.GetBoolean(6))
        {
            return Unauthorized(new { success = false, message = "Credenciales inválidas." });
        }

        var rolRaw = r.GetString(3).ToLowerInvariant();
        var rol = rolRaw.Contains("admin") ? "Administrador"
                : rolRaw.Contains("vet") ? "Veterinario"
                : "Operario";

        var user = new UserSessionDto
        {
            Id = r.GetGuid(0).ToString(),
            Nombre = r.GetString(1),
            Email = r.GetString(2),
            Rol = rol,
            Cargo = rol == "Administrador" ? "Administrador General" : rol == "Veterinario" ? "Médico Veterinario" : "Operario de Campo",
            Matricula = r.GetString(4)
        };

        return Ok(new
        {
            success = true,
            message = "Autenticación exitosa en AgroPig Suite",
            user,
            expiresAt = DateTime.UtcNow.AddHours(8)
        });
    }
}

