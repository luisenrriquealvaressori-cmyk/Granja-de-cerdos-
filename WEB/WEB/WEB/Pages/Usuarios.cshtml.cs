using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;
using WEB.Services;

namespace WEB.Pages;

[Authorize(Policy = Policies.UsuariosGestionar)]
public class UsuariosModel : PageModel
{
    private readonly AgroPigDataService _dataService;
    private readonly UserRoleService _roleService;
    private readonly ILogger<UsuariosModel> _logger;

    public UsuariosModel(AgroPigDataService dataService, UserRoleService roleService, ILogger<UsuariosModel> logger)
    {
        _dataService = dataService;
        _roleService = roleService;
        _logger = logger;
    }

    public UsuariosPageViewModel Data { get; set; } = new();

    [TempData]
    public string? MensajeExito { get; set; }

    [TempData]
    public string? MensajeError { get; set; }

    public async Task<IActionResult> OnGetAsync()
    {
        // Doble verificación: solo administradores tienen acceso
        if (!_roleService.EsAdmin)
        {
            return Forbid();
        }

        Data = await _dataService.GetUsuariosAsync();
        return Page();
    }

    public async Task<IActionResult> OnPostCrearUsuarioAsync([FromForm] CrearUsuarioInput input)
    {
        if (!_roleService.EsAdmin)
        {
            return Forbid();
        }

        if (input == null || string.IsNullOrWhiteSpace(input.NombreUsuario) || string.IsNullOrWhiteSpace(input.Nombre))
        {
            MensajeError = "El nombre de usuario y el nombre completo son obligatorios.";
            return RedirectToPage();
        }

        try
        {
            var res = await _dataService.CrearUsuarioAsync(input);
            if (res.Exito)
                MensajeExito = res.Mensaje;
            else
                MensajeError = res.Mensaje;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al crear usuario en el panel administrativo");
            MensajeError = "Error al crear el usuario: " + ex.Message;
        }

        return RedirectToPage();
    }

    public async Task<IActionResult> OnPostToggleActivoAsync([FromForm] Guid usuarioId)
    {
        if (!_roleService.EsAdmin)
        {
            return Forbid();
        }

        if (usuarioId == Guid.Empty)
        {
            MensajeError = "Identificador de usuario inválido.";
            return RedirectToPage();
        }

        try
        {
            var ok = await _dataService.ToggleUsuarioActivoAsync(usuarioId);
            if (ok)
                MensajeExito = "Estado de acceso del usuario actualizado correctamente.";
            else
                MensajeError = "No se pudo actualizar el estado del usuario.";
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al cambiar estado activo del usuario");
            MensajeError = "Error al modificar usuario: " + ex.Message;
        }

        return RedirectToPage();
    }
}
