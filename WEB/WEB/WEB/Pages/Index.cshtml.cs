using System.Security.Claims;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;
using WEB.Services;

namespace WEB.Pages;

public class IndexModel : PageModel
{
    private readonly AgroPigDataService _dataService;
    private readonly UserRoleService _roleService;

    public IndexModel(AgroPigDataService dataService, UserRoleService roleService)
    {
        _dataService = dataService;
        _roleService = roleService;
    }

    public DashboardBiViewModel Data { get; set; } = new();
    public PreferenciasUiUsuarioDto PreferenciasUi { get; set; } = new();
    public List<CatalogoGalponMovilDto> GalponesSelector { get; set; } = new();
    public List<CatalogoHorarioMovilDto> TurnosSelector { get; set; } = new();

    [BindProperty(SupportsGet = true)]
    public string? GalponFiltro { get; set; }

    public string GalponFiltroActivo { get; set; } = "";

    public async Task OnGetAsync()
    {
        var rol = User.FindFirstValue(ClaimTypes.Role)
            ?? (_roleService.EsAdmin ? AppRoles.Administrador : _roleService.EsVeterinario ? AppRoles.Veterinario : AppRoles.Operario);

        PreferenciasUi = await _dataService.ObtenerPreferenciasUiUsuarioAsync(User.GetUserId(), rol);

        GalponFiltroActivo = GalponFiltro == null
            ? (PreferenciasUi.GalponPredeterminado ?? "")
            : (string.Equals(GalponFiltro.Trim(), "ALL", StringComparison.OrdinalIgnoreCase) ? "" : GalponFiltro.Trim());

        var sel = await _dataService.GetPreferenciasSelectorCatalogAsync();
        GalponesSelector = sel.Galpones;
        TurnosSelector = sel.Turnos;

        Data = await _dataService.GetDashboardBiDataAsync(
            rol,
            _roleService.CurrentUser.Nombre,
            GalponFiltroActivo,
            PreferenciasUi.ModulosVisibles);
    }

    public async Task<IActionResult> OnPostCompletarTareaRapidaAsync([FromForm] Guid id, [FromForm] string? galponFiltro)
    {
        if (id != Guid.Empty)
        {
            var ok = await _dataService.ActualizarEstadoTareaAsync(id, "completada", null, _roleService.CurrentUser.Nombre);
            TempData["Toast"] = ok
                ? "success: Tarea marcada como completada."
                : "error: No se pudo actualizar la tarea.";
        }
        return RedirectToPage(new { GalponFiltro = string.IsNullOrWhiteSpace(galponFiltro) ? "ALL" : galponFiltro });
    }
}
