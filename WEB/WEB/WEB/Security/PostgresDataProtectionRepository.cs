using System.Xml.Linq;
using Microsoft.AspNetCore.DataProtection.Repositories;
using Npgsql;

namespace WEB.Security;

/// <summary>
/// Persiste las claves de Data Protection en PostgreSQL (Neon) para que las cookies de sesión
/// y los tokens Antiforgery sobrevivan a reinicios y despliegues de contenedores en Render.
/// </summary>
public sealed class PostgresDataProtectionRepository : IXmlRepository
{
    private readonly IConfiguration _configuration;
    private readonly ILogger<PostgresDataProtectionRepository> _logger;

    public PostgresDataProtectionRepository(IConfiguration configuration, ILogger<PostgresDataProtectionRepository> logger)
    {
        _configuration = configuration;
        _logger = logger;
    }

    private static void EnsureTable(NpgsqlConnection conn)
    {
        using var cmd = new NpgsqlCommand(@"
            CREATE TABLE IF NOT EXISTS data_protection_keys (
                friendly_name VARCHAR(255) PRIMARY KEY,
                xml_data TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );", conn);
        cmd.ExecuteNonQuery();
    }

    public IReadOnlyCollection<XElement> GetAllElements()
    {
        if (!PostgresConnectionResolver.IsConfigured(_configuration))
            return Array.Empty<XElement>();

        try
        {
            using var conn = new NpgsqlConnection(PostgresConnectionResolver.Resolve(_configuration));
            conn.Open();
            EnsureTable(conn);

            using var cmd = new NpgsqlCommand("SELECT xml_data FROM data_protection_keys ORDER BY created_at;", conn);
            using var reader = cmd.ExecuteReader();
            var list = new List<XElement>();
            while (reader.Read())
            {
                list.Add(XElement.Parse(reader.GetString(0)));
            }
            return list;
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "No se pudieron leer claves DataProtection desde PostgreSQL; se usará almacén temporal.");
            return Array.Empty<XElement>();
        }
    }

    public void StoreElement(XElement element, string friendlyName)
    {
        if (!PostgresConnectionResolver.IsConfigured(_configuration))
            return;

        try
        {
            using var conn = new NpgsqlConnection(PostgresConnectionResolver.Resolve(_configuration));
            conn.Open();
            EnsureTable(conn);

            using var cmd = new NpgsqlCommand(@"
                INSERT INTO data_protection_keys (friendly_name, xml_data)
                VALUES (@name, @xml)
                ON CONFLICT (friendly_name) DO UPDATE SET xml_data = EXCLUDED.xml_data;", conn);
            cmd.Parameters.AddWithValue("name", friendlyName);
            cmd.Parameters.AddWithValue("xml", element.ToString(SaveOptions.DisableFormatting));
            cmd.ExecuteNonQuery();
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "No se pudo guardar clave DataProtection '{FriendlyName}' en PostgreSQL.", friendlyName);
        }
    }
}
