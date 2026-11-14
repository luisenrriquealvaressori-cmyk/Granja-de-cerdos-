using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;
using WEB.Services;

namespace WEB.Pages;

public class ReproduccionModel : PageModel
{
    private readonly AgroPigDataService _dataService;
    private readonly UserRoleService _roleService;

    public ReproduccionModel(AgroPigDataService dataService, UserRoleService roleService)
    {
        _dataService = dataService;
        _roleService = roleService;
    }

    public ReproduccionPageViewModel Data { get; set; } = new();

    [TempData]
    public string? MensajeExito { get; set; }

    [TempData]
    public string? MensajeError { get; set; }

    public async Task OnGetAsync()
    {
        Data = await _dataService.GetReproduccionDataAsync();
    }

    // Inseminación Artificial (IA) / Monta
    [RequierePolitica(Policies.SanidadRegistrar)]
    public async Task<IActionResult> OnPostRegistrarServicioAsync(
        [FromForm] string caravana,
        [FromForm] Guid? verracoId,
        [FromForm] string tecnica,
        [FromForm] string turno,
        [FromForm] DateTime fecha)
    {
        if (string.IsNullOrWhiteSpace(caravana))
        {
            MensajeError = "Debe especificar la caravana de la cerda reproductora.";
            return RedirectToPage();
        }

        var success = await _dataService.RegistrarServicioIaAsync(caravana, verracoId, tecnica, turno, fecha);

        if (success)
        {
            MensajeExito = $"Servicio IA para la cerda '{caravana}' registrado en PostgreSQL. El trigger programó ecografía (+28d), traslado (+107d) y parto (+114d).";
        }
        else
        {
            MensajeError = $"No se encontró la cerda con caravana '{caravana}' en el plantel activo.";
        }

        return RedirectToPage();
    }

    // Registro de Parto (Secuencia Biológica: Cerda gestante a término -> Parto en Maternidad)
    [RequierePolitica(Policies.SanidadRegistrar)]
    public async Task<IActionResult> OnPostRegistrarPartoAsync(
        [FromForm] Guid cerdaId,
        [FromForm] int totalNacidos,
        [FromForm] int nacidosVivos,
        [FromForm] int nacidosMuertos,
        [FromForm] int momias,
        [FromForm] decimal pesoTotalCamadaKg,
        [FromForm] string jaulaMaternidad,
        [FromForm] bool asistenciaObstetrica,
        [FromForm] decimal condicionCorporal,
        [FromForm] int duracionMinutos,
        [FromForm] string observaciones,
        [FromForm] DateTime fechaHora)
    {
        if (cerdaId == Guid.Empty)
        {
            MensajeError = "Debe seleccionar una cerda reproductora válida.";
            return RedirectToPage();
        }

        if (totalNacidos <= 0 && nacidosVivos <= 0)
        {
            MensajeError = "El número de lechones nacidos vivos debe ser superior a cero.";
            return RedirectToPage();
        }

        if (nacidosVivos < 0 || nacidosMuertos < 0 || momias < 0 || pesoTotalCamadaKg < 0)
        {
            MensajeError = "Los valores del parto no pueden ser negativos.";
            return RedirectToPage();
        }

        // El total siempre se deriva de la suma de sus componentes (vivos + muertos + momias)
        totalNacidos = nacidosVivos + nacidosMuertos + momias;

        var success = await _dataService.RegistrarPartoAsync(
            cerdaId,
            totalNacidos,
            nacidosVivos,
            nacidosMuertos,
            momias,
            pesoTotalCamadaKg,
            jaulaMaternidad,
            asistenciaObstetrica,
            condicionCorporal,
            duracionMinutos,
            observaciones,
            fechaHora
        );

        if (success)
        {
            MensajeExito = $"¡Parto registrado con éxito en PostgreSQL! {nacidosVivos} lechones vivos, {nacidosMuertos} mortinatos, {momias} momias. La cerda avanzó a estado 'lactante_maternidad' en la jaula '{jaulaMaternidad}'.";
        }
        else
        {
            MensajeError = "No se pudo registrar el parto en la base de datos.";
        }

        return RedirectToPage();
    }
}
