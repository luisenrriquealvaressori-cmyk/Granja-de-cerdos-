using System.Security.Claims;
using WEB.Security;

namespace WEB.Services;

public enum RolUsuario
{
    Ninguno,
    Administrador,
    Veterinario,
    Operario
}

public class UserProfile
{
    public string Nombre { get; set; } = "";
    public string Email { get; set; } = "";
    public string Cargo { get; set; } = "";
    public string Rol { get; set; } = "";
    public string Iniciales { get; set; } = "";
    public string AvatarColor { get; set; } = "";
    public string Icono { get; set; } = "";
    public string BadgeText { get; set; } = "";
    public string Descripcion { get; set; } = "";
    public string Matricula { get; set; } = "";
}

/// <summary>
/// Fachada de lectura de la identidad autenticada. El rol SIEMPRE proviene de los claims firmados
/// de la cookie/JWT; nunca de la URL, de una cookie editable ni de un valor por defecto.
/// Las propiedades Puede* se usan para adaptar la interfaz; la autorización real se aplica
/// en servidor con políticas ([RequierePolitica] / RequireAuthorization).
/// </summary>
public class UserRoleService
{
    private readonly IHttpContextAccessor _httpContextAccessor;

    public UserRoleService(IHttpContextAccessor httpContextAccessor)
    {
        _httpContextAccessor = httpContextAccessor;
    }

    private ClaimsPrincipal? Principal => _httpContextAccessor.HttpContext?.User;

    public bool Autenticado => Principal?.Identity?.IsAuthenticated == true;
    public Guid? UserId => Principal?.GetUserId();

    public string CurrentRoleName
    {
        get
        {
            if (!Autenticado) return nameof(RolUsuario.Ninguno);
            var normalizado = AppRoles.Normalizar(Principal!.FindFirstValue(ClaimTypes.Role));
            return normalizado ?? nameof(RolUsuario.Ninguno);
        }
    }

    public RolUsuario CurrentRole
        => Enum.TryParse<RolUsuario>(CurrentRoleName, true, out var role) ? role : RolUsuario.Ninguno;

    public bool EsAdmin => CurrentRole == RolUsuario.Administrador;
    public bool EsVeterinario => CurrentRole == RolUsuario.Veterinario;
    public bool EsOperario => CurrentRole == RolUsuario.Operario;

    // Matriz de permisos (debe coincidir con las políticas registradas en Program.cs)
    public bool PuedeGestionarPresupuestos => EsAdmin;
    public bool PuedeCrearInsumos => EsAdmin;
    public bool PuedeEditarInsumos => EsAdmin;
    public bool PuedeEliminarInsumos => EsAdmin;
    public bool PuedeRegistrarSanidad => EsAdmin || EsVeterinario;
    public bool PuedeRegistrarPartos => EsAdmin || EsVeterinario;
    public bool PuedeReportarGastoAlimentacion => EsAdmin || EsOperario;
    public bool PuedeReportarGastoSanitario => EsAdmin || EsVeterinario;
    public bool PuedeReportarTratamientos => EsAdmin || EsVeterinario;
    public bool PuedeRegistrarKardex => EsAdmin || EsOperario;
    public bool PuedeDarBajaCerdos => EsAdmin || EsVeterinario;

    public bool DebeCambiarPassword => Principal?.FindFirstValue(CustomClaims.DebeCambiarPassword) == "1";

    public UserProfile CurrentUser
    {
        get
        {
            var perfil = GetProfileForRole(CurrentRole);
            var p = Principal;
            if (p is null || !Autenticado) return perfil;

            var nombre = p.FindFirstValue(ClaimTypes.Name);
            if (!string.IsNullOrWhiteSpace(nombre)) perfil.Nombre = nombre;
            var email = p.FindFirstValue(ClaimTypes.Email);
            if (!string.IsNullOrWhiteSpace(email)) perfil.Email = email;
            var cargo = p.FindFirstValue(CustomClaims.Cargo);
            if (!string.IsNullOrWhiteSpace(cargo)) perfil.Cargo = cargo;
            perfil.Matricula = p.FindFirstValue(CustomClaims.Matricula) ?? string.Empty;
            perfil.Iniciales = CalcularIniciales(perfil.Nombre);
            return perfil;
        }
    }

    /// <summary>Atributos visuales (color, icono, descripción) por rol. No contiene datos personales.</summary>
    public static UserProfile GetProfileForRole(RolUsuario role)
    {
        return role switch
        {
            RolUsuario.Veterinario => new UserProfile
            {
                Rol = "Veterinario",
                Cargo = "Médico Veterinario",
                AvatarColor = "bg-teal-700 text-white",
                Icono = "stethoscope",
                BadgeText = "Sanidad & Reproducción",
                Descripcion = "Control sanitario, inseminación IA, radar de partos y salud animal."
            },
            RolUsuario.Operario => new UserProfile
            {
                Rol = "Operario",
                Cargo = "Operario de Campo & Galpones",
                AvatarColor = "bg-amber-700 text-white",
                Icono = "agriculture",
                BadgeText = "Operaciones de Campo",
                Descripcion = "Recepción de insumos, pesajes de lotes y dispensación en silos."
            },
            RolUsuario.Administrador => new UserProfile
            {
                Rol = "Administrador",
                Cargo = "Administrador General",
                AvatarColor = "bg-emerald-800 text-white",
                Icono = "admin_panel_settings",
                BadgeText = "Administrador General",
                Descripcion = "Control total: finanzas, presupuestos, insumos, lotes y usuarios."
            },
            _ => new UserProfile
            {
                Nombre = "Sin sesión",
                Rol = "Sin acceso",
                Cargo = "—",
                AvatarColor = "bg-slate-500 text-white",
                Icono = "lock",
                BadgeText = "Sin acceso",
                Descripcion = "Inicia sesión para continuar."
            }
        };
    }

    private static string CalcularIniciales(string nombre)
    {
        var partes = nombre.Split(' ', StringSplitOptions.RemoveEmptyEntries)
            .Where(p => !p.EndsWith('.')) // omite títulos: "Ing.", "Dra."
            .ToArray();
        if (partes.Length == 0) return "?";
        if (partes.Length == 1) return partes[0][..1].ToUpperInvariant();
        return (partes[0][..1] + partes[^1][..1]).ToUpperInvariant();
    }
}
