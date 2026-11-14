using System.Security.Claims;

namespace WEB.Security;

/// <summary>Nombres canónicos de los roles del sistema.</summary>
public static class AppRoles
{
    public const string Administrador = "Administrador";
    public const string Veterinario = "Veterinario";
    public const string Operario = "Operario";

    /// <summary>
    /// Normaliza el valor guardado en BD (incluye nombres heredados del esquema original)
    /// al vocabulario canónico. Devuelve null si el rol no es reconocido (acceso denegado).
    /// </summary>
    public static string? Normalizar(string? raw)
    {
        return raw?.Trim().ToLowerInvariant() switch
        {
            "administrador" or "administrador_financiero" or "admin" => Administrador,
            "veterinario" or "director_veterinario" or "zootecnista_jefe" => Veterinario,
            "operario" or "operario_galpon" => Operario,
            _ => null
        };
    }
}

/// <summary>Nombres de políticas de autorización (matriz de permisos).</summary>
public static class Policies
{
    public const string PresupuestosGestionar = "Presupuestos.Gestionar";
    public const string InventarioGestionar = "Inventario.Gestionar";
    public const string KardexRegistrar = "Kardex.Registrar";
    public const string SanidadRegistrar = "Sanidad.Registrar";
    public const string AlimentacionRegistrar = "Alimentacion.Registrar";
    public const string CerdosEditar = "Cerdos.Editar";
    public const string CerdosBaja = "Cerdos.Baja";
    public const string SincronizacionMovil = "Sincronizacion.Movil";
    public const string UsuariosGestionar = "Usuarios.Gestionar";
    public const string ComprasGestionar = "Compras.Gestionar";
    public const string LotesGestionar = "Lotes.Gestionar";
    public const string TareasGestionar = "Tareas.Gestionar";
}

public static class CustomClaims
{
    public const string Matricula = "agropig:matricula";
    public const string Cargo = "agropig:cargo";
    public const string GranjaId = "agropig:granja";
    public const string DebeCambiarPassword = "agropig:must_change_pwd";
}

public static class ClaimsExtensions
{
    public static Guid? GetUserId(this ClaimsPrincipal user)
        => Guid.TryParse(user.FindFirstValue(ClaimTypes.NameIdentifier), out var id) ? id : null;
}
