using Npgsql;

namespace WEB.Security;

/// <summary>
/// Resuelve la cadena de conexión a PostgreSQL desde user-secrets, configuración o DATABASE_URL,
/// convirtiendo automáticamente URIs de proveedores cloud (Neon, Render, Railway) al formato clave-valor requerido por Npgsql.
/// </summary>
public static class PostgresConnectionResolver
{
    public static string? GetRawValue(IConfiguration configuration)
    {
        string?[] candidates =
        {
            configuration.GetConnectionString("PostgreSQL"),
            configuration["ConnectionStrings_PostgreSQL"],
            configuration["DATABASE_URL"],
            Environment.GetEnvironmentVariable("DATABASE_URL"),
            Environment.GetEnvironmentVariable("POSTGRES_URL"),
            Environment.GetEnvironmentVariable("NEON_DATABASE_URL")
        };

        foreach (var candidate in candidates)
        {
            if (string.IsNullOrWhiteSpace(candidate)) continue;
            var cleaned = candidate.Trim();
            if (cleaned.StartsWith("psql ", StringComparison.OrdinalIgnoreCase))
            {
                cleaned = cleaned[5..].Trim();
            }
            cleaned = cleaned.Trim('\'', '"', ' ');
            if (!string.IsNullOrWhiteSpace(cleaned))
                return cleaned;
        }

        return null;
    }

    public static bool IsConfigured(IConfiguration configuration) => GetRawValue(configuration) is not null;

    public static string Resolve(IConfiguration configuration)
    {
        var raw = GetRawValue(configuration);
        if (string.IsNullOrWhiteSpace(raw))
        {
            throw new InvalidOperationException(
                "Falta 'ConnectionStrings:PostgreSQL' o 'DATABASE_URL' en user-secrets o variables de entorno.");
        }

        if (!raw.StartsWith("postgres://", StringComparison.OrdinalIgnoreCase) &&
            !raw.StartsWith("postgresql://", StringComparison.OrdinalIgnoreCase))
        {
            return raw;
        }

        var uri = new Uri(raw);
        var csb = new NpgsqlConnectionStringBuilder
        {
            Host = uri.Host,
            Port = uri.Port > 0 ? uri.Port : 5432,
            Database = Uri.UnescapeDataString(uri.AbsolutePath.Trim('/')),
            SslMode = SslMode.Require
        };

        if (!string.IsNullOrEmpty(uri.UserInfo))
        {
            var sep = uri.UserInfo.IndexOf(':');
            if (sep >= 0)
            {
                csb.Username = Uri.UnescapeDataString(uri.UserInfo[..sep]);
                csb.Password = Uri.UnescapeDataString(uri.UserInfo[(sep + 1)..]);
            }
            else
            {
                csb.Username = Uri.UnescapeDataString(uri.UserInfo);
            }
        }

        var query = uri.Query.TrimStart('?');
        if (!string.IsNullOrWhiteSpace(query))
        {
            foreach (var pair in query.Split('&', StringSplitOptions.RemoveEmptyEntries))
            {
                var eq = pair.IndexOf('=');
                if (eq <= 0) continue;
                var key = Uri.UnescapeDataString(pair[..eq]).Trim().ToLowerInvariant();
                var val = Uri.UnescapeDataString(pair[(eq + 1)..]).Trim().ToLowerInvariant();

                switch (key)
                {
                    case "sslmode":
                        csb.SslMode = val switch
                        {
                            "disable" => SslMode.Disable,
                            "allow" => SslMode.Allow,
                            "prefer" => SslMode.Prefer,
                            "require" => SslMode.Require,
                            "verify-ca" or "verifyca" => SslMode.VerifyCA,
                            "verify-full" or "verifyfull" => SslMode.VerifyFull,
                            _ => SslMode.Require
                        };
                        break;
                    case "channel_binding":
                        csb.ChannelBinding = val switch
                        {
                            "disable" => ChannelBinding.Disable,
                            "prefer" or "require" => ChannelBinding.Prefer,
                            _ => csb.ChannelBinding
                        };
                        break;
                }
            }
        }

        return csb.ConnectionString;
    }
}
