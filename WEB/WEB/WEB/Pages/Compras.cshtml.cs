using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;
using WEB.Services;

namespace WEB.Pages;

public class ComprasModel : PageModel
{
    private readonly AgroPigDataService _dataService;
    private readonly UserRoleService _roleService;
    private readonly ILogger<ComprasModel> _logger;

    public ComprasModel(AgroPigDataService dataService, UserRoleService roleService, ILogger<ComprasModel> logger)
    {
        _dataService = dataService;
        _roleService = roleService;
        _logger = logger;
    }

    public ComprasViewModel Data { get; set; } = new();

    [TempData]
    public string? MensajeExito { get; set; }

    [TempData]
    public string? MensajeError { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? TabActiva { get; set; } = "catalogo";

    [BindProperty(SupportsGet = true)]
    public Guid? FiltroProveedor { get; set; }

    public async Task<IActionResult> OnGetAsync()
    {
        if (_roleService.EsOperario)
        {
            MensajeError = "El módulo de Compras y Proveedores está reservado para Administración y Dirección Técnica Veterinaria. Para entradas de bodega y silos, utiliza el Kardex de Inventario.";
            return RedirectToPage("/Inventario");
        }

        Data = await _dataService.GetComprasDataAsync();
        return Page();
    }

    [RequierePolitica(Policies.ComprasGestionar)]
    public async Task<IActionResult> OnPostEmitirOrdenAsync([FromBody] CrearOrdenCompraInput input)
    {
        if (input == null || input.ProveedorId == Guid.Empty)
        {
            return new JsonResult(new { exito = false, mensaje = "Selecciona un proveedor válido." });
        }

        if (input.Lineas == null || !input.Lineas.Any(l => l.Cantidad > 0))
        {
            return new JsonResult(new { exito = false, mensaje = "Debes ingresar al menos un producto con cantidad mayor a cero." });
        }

        try
        {
            var res = await _dataService.CrearOrdenCompraAsync(input);
            return new JsonResult(new { exito = res.Exito, mensaje = res.Mensaje, ordenId = res.OrdenId });
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al emitir orden de compra");
            return new JsonResult(new { exito = false, mensaje = "Ocurrió un error al procesar la orden." });
        }
    }

    [RequierePolitica(Policies.ComprasGestionar)]
    public async Task<IActionResult> OnPostRecibirKardexAsync(
        [FromForm] Guid ordenId,
        [FromForm] string? loteFabricante,
        [FromForm] DateTime? fechaVencimiento,
        [FromForm] string? bodegaDestino)
    {
        if (ordenId == Guid.Empty)
        {
            MensajeError = "Identificador de orden inválido.";
            return RedirectToPage(new { tab = "ordenes" });
        }

        try
        {
            var usuario = _roleService.CurrentUser.Nombre;
            var res = await _dataService.RecibirOrdenCompraEnKardexAsync(
                ordenId, usuario, loteFabricante, fechaVencimiento, bodegaDestino);
            if (res.Exito)
                MensajeExito = res.Mensaje;
            else
                MensajeError = res.Mensaje;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al recibir orden en Kardex");
            MensajeError = "No se pudo procesar la recepción de la orden en Kardex.";
        }

        return RedirectToPage(new { tab = "ordenes" });
    }

    [RequierePolitica(Policies.ComprasGestionar)]
    public async Task<IActionResult> OnPostGuardarProveedorAsync([FromForm] CrearProveedorInput input)
    {
        if (string.IsNullOrWhiteSpace(input.NombreComercial))
        {
            MensajeError = "El nombre comercial del proveedor es obligatorio.";
            return RedirectToPage(new { tab = "proveedores" });
        }

        try
        {
            var res = await _dataService.CrearProveedorAsync(input);
            if (res.Exito)
                MensajeExito = res.Mensaje;
            else
                MensajeError = res.Mensaje;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al guardar proveedor");
            MensajeError = "No se pudo guardar el proveedor.";
        }

        return RedirectToPage(new { tab = "proveedores" });
    }

    [RequierePolitica(Policies.ComprasGestionar)]
    public async Task<IActionResult> OnPostGuardarProductoAsync([FromForm] GuardarProductoCatalogoInput input)
    {
        if (input.ProveedorId == Guid.Empty || string.IsNullOrWhiteSpace(input.NombreProducto))
        {
            MensajeError = "El proveedor y nombre de producto son obligatorios.";
            return RedirectToPage(new { tab = "catalogo" });
        }

        try
        {
            var res = await _dataService.GuardarProductoCatalogoAsync(input);
            if (res.Exito)
                MensajeExito = res.Mensaje;
            else
                MensajeError = res.Mensaje;
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Error al guardar producto del catálogo");
            MensajeError = "No se pudo guardar el producto.";
        }

        return RedirectToPage(new { tab = "catalogo" });
    }
}
