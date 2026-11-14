using Npgsql;

namespace WEB.Security;

/// <summary>
/// Registro de auditoría (quién hizo qué, cuándo y desde dónde).
/// Un fallo de auditoría nunca debe romper la operación de negocio, pero sí se registra en logs.
/// </summary>
public class AuditService
{
    private readonly string _connectionString;
    private readonly ILogger<AuditService> _logger;

    public AuditService(IConfiguration configuration, ILogger<AuditService> logger)
    {
        _connectionString = configuration.GetConnectionString("PostgreSQL")
            ?? throw new InvalidOperationException("Falta la cadena de conexión 'ConnectionStrings:PostgreSQL' (use user-secrets o variables de entorno).");
        _logger = logger;
    }

    public async Task RegistrarAsync(Guid? usuarioId, string? email, string accion, string? entidad = null, string? detalle = null, string? ip = null)
    {
        try
        {
            await using var conn = new NpgsqlConnection(_connectionString);
            await conn.OpenAsync();
            await using var cmd = new NpgsqlCommand(@"
                INSERT INTO audit_log (usuario_id, email, accion, entidad, detalle, ip)
                VALUES (@u, @e, @a, @en, @d, @ip);", conn);
            cmd.Parameters.AddWithValue("u", (object?)usuarioId ?? DBNull.Value);
            cmd.Parameters.AddWithValue("e", (object?)email ?? DBNull.Value);
            cmd.Parameters.AddWithValue("a", accion);
            cmd.Parameters.AddWithValue("en", (object?)entidad ?? DBNull.Value);
            cmd.Parameters.AddWithValue("d", (object?)detalle ?? DBNull.Value);
            cmd.Parameters.AddWithValue("ip", (object?)ip ?? DBNull.Value);
            await cmd.ExecuteNonQueryAsync();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "No se pudo escribir en audit_log ({Accion})", accion);
        }
    }
}
