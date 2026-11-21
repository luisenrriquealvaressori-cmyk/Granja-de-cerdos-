using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;
using WEB.Services;

namespace WEB.Pages;

public class CerdosModel : PageModel
{
    private readonly AgroPigDataService _dataService;
    private readonly UserRoleService _roleService;

    private readonly ILogger<CerdosModel> _logger;

    public CerdosModel(AgroPigDataService dataService, UserRoleService roleService, ILogger<CerdosModel> logger)
    {
        _logger = logger;
        _dataService = dataService;
        _roleService = roleService;
    }

    public CerdosPageViewModel Data { get; set; } = new();
    public CatalogosFormularioWebViewModel Catalogos { get; set; } = new();

    [BindProperty(SupportsGet = true)]
    public string? EtapaFiltro { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? Busqueda { get; set; }

    [TempData]
    public string? MensajeExito { get; set; }

    [TempData]
    public string? MensajeError { get; set; }

    public async Task OnGetAsync()
    {
        Data = await _dataService.GetCerdosPageDataAsync(EtapaFiltro, Busqueda);
        Catalogos = await _dataService.GetCatalogosFormularioWebAsync();
    }

    [RequierePolitica(Policies.CerdosEditar)]
    public async Task<IActionResult> OnPostGuardarCerdoAsync([FromForm] CerdoInputModel input)
    {
        if (input == null || string.IsNullOrWhiteSpace(input.Crotal))
        {
            MensajeError = "El número de crotal o arete del cerdo es obligatorio.";
            return RedirectToPage(new { etapa = EtapaFiltro, busqueda = Busqueda });
        }

        if (input.FechaNacimiento > DateTime.Today)
        {
            MensajeError = "La fecha de nacimiento no puede ser futura.";
            return RedirectToPage(new { etapa = EtapaFiltro, busqueda = Busqueda });
        }

        if (input.PesoActualKg <= 0)
        {
            MensajeError = "El peso actual debe ser mayor a 0 kg.";
            return RedirectToPage(new { etapa = EtapaFiltro, busqueda = Busqueda });
        }

        try
        {
            var ok = await _dataService.GuardarCerdoAsync(input);
            if (ok)
            {
                var calc = AgroPigDataService.CalcularPlanZootecnico(input.FechaNacimiento, input.PesoActualKg);
                MensajeExito = $"Cerdo '{input.Crotal.Trim().ToUpperInvariant()}' guardado correctamente. Clasificado en: {calc.EtapaClasificada} ({calc.DiasVida} días de vida, ración: {calc.ConsumoDiarioKg:N2} kg/día en {calc.TomasAlDia} tomas de {calc.CantidadPorTomaGramos} g).";
            }
            else
            {
                MensajeError = "No se pudo guardar la información del cerdo.";
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al guardar cerdo");
            MensajeError = "Error al guardar el cerdo. Verifica los datos e inténtalo de nuevo.";
        }

        return RedirectToPage(new { etapa = EtapaFiltro, busqueda = Busqueda });
    }

    [RequierePolitica(Policies.CerdosBaja)]
    public async Task<IActionResult> OnPostEliminarCerdoAsync([FromForm] Guid id, [FromForm] string crotal)
    {
        if (id == Guid.Empty)
        {
            MensajeError = "Identificador de cerdo inválido.";
            return RedirectToPage();
        }

        try
        {
            var ok = await _dataService.EliminarCerdoAsync(id);
            if (ok)
            {
                MensajeExito = $"Cerdo '{crotal}' dado de baja del plantel activo y liberado el cupo en su jaula.";
            }
            else
            {
                MensajeError = "No se pudo dar de baja el cerdo.";
            }
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al dar de baja cerdo {Id}", id);
            MensajeError = "Error al dar de baja el cerdo. Inténtalo de nuevo.";
        }

        return RedirectToPage(new { etapa = EtapaFiltro, busqueda = Busqueda });
    }
}
