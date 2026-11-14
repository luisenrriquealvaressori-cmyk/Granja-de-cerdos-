using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;
using WEB.Services;

namespace WEB.Pages;

public class LotesModel : PageModel
{
    private readonly AgroPigDataService _dataService;

    public LotesModel(AgroPigDataService dataService)
    {
        _dataService = dataService;
    }

    public LotesPageViewModel Data { get; set; } = new();

    [TempData]
    public string? MensajeExito { get; set; }

    public async Task OnGetAsync()
    {
        Data = await _dataService.GetLotesPlanningDataAsync();
    }

    [RequierePolitica(Policies.LotesGestionar)]
    public async Task<IActionResult> OnPostCrearLoteAsync(
        [FromForm] string etapa,
        [FromForm] Guid galponId,
        [FromForm] int cantidad,
        [FromForm] decimal pesoEntrada,
        [FromForm] DateTime fechaIngreso,
        [FromForm] Guid lineaGeneticaId,
        [FromForm] string procedencia)
    {
        if (cantidad <= 0 || pesoEntrada <= 0)
        {
            ModelState.AddModelError("", "Cantidad y peso deben ser mayores a cero.");
            await OnGetAsync();
            return Page();
        }

        var codigo = await _dataService.CrearLoteProduccionAsync(
            etapa, galponId, cantidad, pesoEntrada, fechaIngreso, lineaGeneticaId, procedencia);

        MensajeExito = $"¡Lote {codigo} aperturado y persistido en PostgreSQL exitosamente con matriz presupuestaria automática!";
        return RedirectToPage();
    }
}
