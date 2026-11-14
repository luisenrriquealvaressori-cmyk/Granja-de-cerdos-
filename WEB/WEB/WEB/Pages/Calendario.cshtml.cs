using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;
using WEB.Services;

namespace WEB.Pages;

public class CalendarioModel : PageModel
{
    private readonly AgroPigDataService _dataService;
    private readonly UserRoleService _roleService;

    public CalendarioModel(AgroPigDataService dataService, UserRoleService roleService)
    {
        _dataService = dataService;
        _roleService = roleService;
    }

    public CalendarioPageViewModel Data { get; set; } = new();

    [BindProperty(SupportsGet = true)]
    public DateTime? Fecha { get; set; }

    [BindProperty(SupportsGet = true)]
    public int? Mes { get; set; }

    [BindProperty(SupportsGet = true)]
    public int? Anio { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? Tipo { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? Estado { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? Rol { get; set; }

    public async Task OnGetAsync()
    {
        Data = await _dataService.GetCalendarioPageDataAsync(Fecha, Mes, Anio, Tipo, Estado, Rol);
    }

    [RequierePolitica(Policies.TareasGestionar)]
    public async Task<IActionResult> OnPostGuardarTareaAsync([FromForm] TareaActividadInputModel input)
    {
        if (string.IsNullOrWhiteSpace(input.Titulo))
        {
            TempData["Toast"] = "error: El título de la actividad es obligatorio.";
            return RedirectToPage(new { Fecha, Mes, Anio, Tipo, Estado, Rol });
        }

        var ok = await _dataService.GuardarTareaAsync(input);
        TempData["Toast"] = ok ? "success: Actividad programada en el cronograma exitosamente." : "error: No se pudo programar la actividad.";
        return RedirectToPage(new { Fecha = input.FechaProgramada.ToString("yyyy-MM-dd"), Mes, Anio, Tipo, Estado, Rol });
    }

    public async Task<IActionResult> OnPostCompletarTareaAsync([FromForm] Guid id, [FromForm] string? observaciones)
    {
        var operario = _roleService.CurrentUser.Nombre;
        var ok = await _dataService.ActualizarEstadoTareaAsync(id, "completada", observaciones, operario);
        TempData["Toast"] = ok ? "success: Tarea marcada como completada." : "error: No se pudo actualizar la tarea.";
        return RedirectToPage(new { Fecha, Mes, Anio, Tipo, Estado, Rol });
    }

    public async Task<IActionResult> OnPostIniciarTareaAsync([FromForm] Guid id)
    {
        var operario = _roleService.CurrentUser.Nombre;
        var ok = await _dataService.ActualizarEstadoTareaAsync(id, "en_progreso", null, operario);
        TempData["Toast"] = ok ? "info: Tarea marcada en progreso." : "error: No se pudo actualizar la tarea.";
        return RedirectToPage(new { Fecha, Mes, Anio, Tipo, Estado, Rol });
    }

    [RequierePolitica(Policies.TareasGestionar)]
    public async Task<IActionResult> OnPostEliminarTareaAsync([FromForm] Guid id)
    {
        var ok = await _dataService.EliminarTareaAsync(id);
        TempData["Toast"] = ok ? "success: Actividad removida del cronograma." : "error: No se pudo eliminar la actividad.";
        return RedirectToPage(new { Fecha, Mes, Anio, Tipo, Estado, Rol });
    }

    public async Task<IActionResult> OnPostResolverNotificacionAsync([FromForm] Guid notifId)
    {
        await _dataService.ResolverNotificacionAsync(notifId);
        TempData["Toast"] = "success: Notificación resuelta.";
        return RedirectToPage(new { Fecha, Mes, Anio, Tipo, Estado, Rol });
    }
}
