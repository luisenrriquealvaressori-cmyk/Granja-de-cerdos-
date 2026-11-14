using Npgsql;

namespace WEB.Security;

public record AuthUser(Guid Id, string Nombre, string Email, string Rol, string? Matricula, string? Cargo, Guid? GranjaId, bool DebeCambiarPassword);

public enum LoginEstado { Ok, Invalido, Bloqueado, Error }

public record LoginResultado(LoginEstado Estado, AuthUser? Usuario = null, TimeSpan? Espera = null);

/// <summary>
/// Autenticación contra usuarios_operadores (hash bcrypt vía pgcrypto) con bloqueo temporal
/// por intentos fallidos persistido en BD y registro de auditoría.
/// </summary>
public class AuthService
{
    private const int MaxIntentos = 5;
    private static readonly TimeSpan DuracionBloqueo = TimeSpan.FromMinutes(10);
    private static readonly string[] PasswordsDebiles = { "1234", "12345678", "password", "contraseña", "agropig", "agropig123", "admin1234" };

    private static readonly SemaphoreSlim SchemaLock = new(1, 1);
    private static volatile bool _schemaLista;

    private readonly string _connectionString;
    private readonly AuditService _audit;
    private readonly ILogger<AuthService> _logger;

    public AuthService(IConfiguration configuration, AuditService audit, ILogger<AuthService> logger)
    {
        _connectionString = PostgresConnectionResolver.Resolve(configuration);
        _audit = audit;
        _logger = logger;
    }

    private NpgsqlConnection Crear() => new(_connectionString);

    /// <summary>Columnas de seguridad y tabla de auditoría (idempotente, una sola vez por proceso).</summary>
    public async Task EnsureSchemaAsync()
    {
        if (_schemaLista) return;
        await SchemaLock.WaitAsync();
        try
        {
            if (_schemaLista) return;
            await using var conn = Crear();
            await conn.OpenAsync();
            const string ddl = @"
                CREATE EXTENSION IF NOT EXISTS pgcrypto;
                ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS nombre_usuario VARCHAR(60) UNIQUE;
                ALTER TABLE usuarios_operadores ALTER COLUMN email DROP NOT NULL;
                ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS debe_cambiar_password BOOLEAN NOT NULL DEFAULT FALSE;
                ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS ultimo_login TIMESTAMPTZ;
                ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS intentos_fallidos INT NOT NULL DEFAULT 0;
                ALTER TABLE usuarios_operadores ADD COLUMN IF NOT EXISTS bloqueado_hasta TIMESTAMPTZ;
                CREATE TABLE IF NOT EXISTS audit_log (
                    id BIGSERIAL PRIMARY KEY,
                    fecha TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    usuario_id UUID,
                    email VARCHAR(150),
                    accion VARCHAR(80) NOT NULL,
                    entidad VARCHAR(80),
                    detalle TEXT,
                    ip VARCHAR(64)
                );
                CREATE INDEX IF NOT EXISTS idx_audit_fecha ON audit_log(fecha DESC);
                CREATE INDEX IF NOT EXISTS idx_audit_usuario ON audit_log(usuario_id, fecha DESC);

                -- Valores heredados guardados en texto plano o con otro formato: se re-hashean con bcrypt y se obliga a cambiarlos
                UPDATE usuarios_operadores
                SET password_hash = crypt(password_hash, gen_salt('bf', 10)), debe_cambiar_password = TRUE
                WHERE password_hash !~ '^\$2[abxy]\$';

                -- Contraseña predeterminada conocida: debe rotarse en el siguiente ingreso
                UPDATE usuarios_operadores
                SET debe_cambiar_password = TRUE
                WHERE debe_cambiar_password = FALSE
                  AND password_hash ~ '^\$2[abxy]\$'
                  AND password_hash = crypt('1234', password_hash);";
            await using var cmd = new NpgsqlCommand(ddl, conn);
            await cmd.ExecuteNonQueryAsync();
            _schemaLista = true;
        }
        finally
        {
            SchemaLock.Release();
        }
    }

    public async Task<LoginResultado> ValidarAsync(string email, string password, string? ip)
    {
        email = (email ?? string.Empty).Trim();
        try
        {
            await EnsureSchemaAsync();
            await using var conn = Crear();
            await conn.OpenAsync();

            Guid id; string nombre, rolRaw; string? matricula, cargo; Guid? granja;
            bool activo, debeCambiar, passOk; DateTimeOffset? bloqueadoHasta; int intentos;

            await using (var cmd = new NpgsqlCommand(@"
                SELECT id, nombre, rol, matricula_profesional, granja_id, activo, debe_cambiar_password,
                       bloqueado_hasta, intentos_fallidos,
                       (password_hash = crypt(@p, password_hash)) AS ok
                FROM usuarios_operadores
                WHERE lower(COALESCE(nombre_usuario, '')) = lower(@e)
                   OR lower(nombre) = lower(@e)
                   OR lower(COALESCE(email, '')) = lower(@e)
                   OR split_part(lower(COALESCE(email, '')), '@', 1) = lower(@e)
                LIMIT 1;", conn))
            {
                cmd.Parameters.AddWithValue("e", email);
                cmd.Parameters.AddWithValue("p", password ?? string.Empty);
                await using var r = await cmd.ExecuteReaderAsync();
                if (!await r.ReadAsync())
                {
                    await DemoraAnteFalloAsync();
                    await _audit.RegistrarAsync(null, email, "LOGIN_FALLIDO", "usuario", "Usuario inexistente", ip);
                    return new LoginResultado(LoginEstado.Invalido);
                }
                id = r.GetGuid(0);
                nombre = r.GetString(1);
                rolRaw = r.GetString(2);
                matricula = r.IsDBNull(3) ? null : r.GetString(3);
                cargo = null;
                granja = r.IsDBNull(4) ? null : r.GetGuid(4);
                activo = r.GetBoolean(5);
                debeCambiar = r.GetBoolean(6);
                bloqueadoHasta = r.IsDBNull(7) ? null : r.GetFieldValue<DateTimeOffset>(7);
                intentos = r.GetInt32(8);
                passOk = !r.IsDBNull(9) && r.GetBoolean(9);
            }

            if (bloqueadoHasta.HasValue && bloqueadoHasta.Value > DateTimeOffset.UtcNow)
            {
                await _audit.RegistrarAsync(id, email, "LOGIN_BLOQUEADO", "usuario", null, ip);
                return new LoginResultado(LoginEstado.Bloqueado, null, bloqueadoHasta.Value - DateTimeOffset.UtcNow);
            }

            var rol = AppRoles.Normalizar(rolRaw);
            if (!activo || rol is null)
            {
                await DemoraAnteFalloAsync();
                await _audit.RegistrarAsync(id, email, "LOGIN_FALLIDO", "usuario", activo ? $"Rol no reconocido: {rolRaw}" : "Usuario inactivo", ip);
                return new LoginResultado(LoginEstado.Invalido);
            }

            if (!passOk)
            {
                var nuevos = intentos + 1;
                var bloquear = nuevos >= MaxIntentos;
                await using (var upd = new NpgsqlCommand(@"
                    UPDATE usuarios_operadores
                    SET intentos_fallidos = @n,
                        bloqueado_hasta = CASE WHEN @b::boolean THEN NOW() + @dur::interval ELSE bloqueado_hasta END
                    WHERE id = @id;", conn))
                {
                    upd.Parameters.AddWithValue("n", bloquear ? 0 : nuevos);
                    upd.Parameters.AddWithValue("b", bloquear);
                    upd.Parameters.AddWithValue("dur", DuracionBloqueo);
                    upd.Parameters.AddWithValue("id", id);
                    await upd.ExecuteNonQueryAsync();
                }
                await DemoraAnteFalloAsync();
                await _audit.RegistrarAsync(id, email, bloquear ? "LOGIN_BLOQUEO_ACTIVADO" : "LOGIN_FALLIDO", "usuario", $"Intento {nuevos}/{MaxIntentos}", ip);
                return bloquear
                    ? new LoginResultado(LoginEstado.Bloqueado, null, DuracionBloqueo)
                    : new LoginResultado(LoginEstado.Invalido);
            }

            await using (var ok = new NpgsqlCommand(@"
                UPDATE usuarios_operadores
                SET intentos_fallidos = 0, bloqueado_hasta = NULL, ultimo_login = NOW()
                WHERE id = @id;", conn))
            {
                ok.Parameters.AddWithValue("id", id);
                await ok.ExecuteNonQueryAsync();
            }

            await _audit.RegistrarAsync(id, email, "LOGIN_OK", "usuario", null, ip);
            return new LoginResultado(LoginEstado.Ok, new AuthUser(id, nombre, email, rol, matricula, cargo ?? CargoPorDefecto(rol), granja, debeCambiar));
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error validando credenciales");
            return new LoginResultado(LoginEstado.Error);
        }
    }

    public async Task<(bool Ok, string? Error)> CambiarPasswordAsync(Guid userId, string actual, string nueva, string? ip)
    {
        var errorPolitica = ValidarPoliticaPassword(nueva, actual);
        if (errorPolitica is not null) return (false, errorPolitica);

        try
        {
            await EnsureSchemaAsync();
            await using var conn = Crear();
            await conn.OpenAsync();

            bool correcto;
            string? email;
            await using (var chk = new NpgsqlCommand(
                "SELECT (password_hash = crypt(@p, password_hash)), email FROM usuarios_operadores WHERE id = @id AND activo = TRUE;", conn))
            {
                chk.Parameters.AddWithValue("p", actual ?? string.Empty);
                chk.Parameters.AddWithValue("id", userId);
                await using var r = await chk.ExecuteReaderAsync();
                if (!await r.ReadAsync()) return (false, "Usuario no encontrado.");
                correcto = !r.IsDBNull(0) && r.GetBoolean(0);
                email = r.IsDBNull(1) ? null : r.GetString(1);
            }
            if (!correcto)
            {
                await _audit.RegistrarAsync(userId, email, "PASSWORD_CAMBIO_FALLIDO", "usuario", "Contraseña actual incorrecta", ip);
                return (false, "La contraseña actual no es correcta.");
            }

            await using (var upd = new NpgsqlCommand(@"
                UPDATE usuarios_operadores
                SET password_hash = crypt(@n, gen_salt('bf', 10)), debe_cambiar_password = FALSE
                WHERE id = @id;", conn))
            {
                upd.Parameters.AddWithValue("n", nueva);
                upd.Parameters.AddWithValue("id", userId);
                await upd.ExecuteNonQueryAsync();
            }
            await _audit.RegistrarAsync(userId, email, "PASSWORD_CAMBIADO", "usuario", null, ip);
            return (true, null);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error cambiando contraseña");
            return (false, "No se pudo actualizar la contraseña. Inténtalo de nuevo.");
        }
    }

    public static string? ValidarPoliticaPassword(string nueva, string? actual = null)
    {
        if (string.IsNullOrWhiteSpace(nueva) || nueva.Length < 8)
            return "La nueva contraseña debe tener al menos 8 caracteres.";
        if (!nueva.Any(char.IsLetter) || !nueva.Any(char.IsDigit))
            return "Debe combinar letras y números.";
        if (PasswordsDebiles.Contains(nueva, StringComparer.OrdinalIgnoreCase))
            return "Esa contraseña es demasiado común. Elige otra.";
        if (actual is not null && nueva == actual)
            return "La nueva contraseña debe ser distinta de la actual.";
        return null;
    }

    private static string CargoPorDefecto(string rol) => rol switch
    {
        AppRoles.Veterinario => "Médico Veterinario",
        AppRoles.Operario => "Operario de Campo & Galpones",
        _ => "Administrador General"
    };

    // Respuesta con latencia aleatoria para dificultar la enumeración de usuarios por tiempo de respuesta
    private static Task DemoraAnteFalloAsync() => Task.Delay(Random.Shared.Next(150, 350));
}
