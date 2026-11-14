using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;
using WEB.Services;

namespace WEB.Pages;

public class InventarioModel : PageModel
{
    private readonly AgroPigDataService _dataService;
    private readonly UserRoleService _roleService;

    public InventarioModel(AgroPigDataService dataService, UserRoleService roleService)
    {
        _dataService = dataService;
        _roleService = roleService;
    }

    public InventarioPageViewModel Data { get; set; } = new();

    [TempData]
    public string? MensajeExito { get; set; }

    [TempData]
    public string? MensajeError { get; set; }

    public async Task OnGetAsync()
    {
        Data = await _dataService.GetInventarioDataAsync();
    }

    // CRUD: Crear nuevo insumo o alimento
    [RequierePolitica(Policies.InventarioGestionar)]
    public async Task<IActionResult> OnPostCrearInsumoAsync(
        [FromForm] string sku,
        [FromForm] string nombre,
        [FromForm] string categoria,
        [FromForm] string unidad,
        [FromForm] decimal stockMinimo,
        [FromForm] decimal costoUnitario,
        [FromForm] string? silo,
        [FromForm] decimal stockInicial,
        [FromForm] string? descripcion)
    {
        if (string.IsNullOrWhiteSpace(sku) || string.IsNullOrWhiteSpace(nombre))
        {
            MensajeError = "El código SKU y el nombre del insumo son obligatorios.";
            return RedirectToPage();
        }

        if (costoUnitario < 0 || stockMinimo < 0)
        {
            MensajeError = "Los valores de costo y stock mínimo deben ser mayores o iguales a cero.";
            return RedirectToPage();
        }

        var success = await _dataService.CrearInsumoAsync(
            sku, nombre, categoria, unidad, stockMinimo, costoUnitario, silo, stockInicial, descripcion);

        if (success)
        {
            MensajeExito = $"Insumo/Alimento '{nombre}' (SKU: {sku}) creado exitosamente en PostgreSQL.";
        }
        else
        {
            MensajeError = "Ocurrió un error al persistir el insumo en PostgreSQL.";
        }

        return RedirectToPage();
    }

    // CRUD: Actualizar insumo existente
    [RequierePolitica(Policies.InventarioGestionar)]
    public async Task<IActionResult> OnPostActualizarInsumoAsync(
        [FromForm] Guid id,
        [FromForm] string nombre,
        [FromForm] string categoria,
        [FromForm] string unidad,
        [FromForm] decimal stockMinimo,
        [FromForm] decimal costoUnitario,
        [FromForm] string? silo,
        [FromForm] decimal stockActual)
    {
        if (id == Guid.Empty || string.IsNullOrWhiteSpace(nombre))
        {
            MensajeError = "Datos de insumo no válidos para actualización.";
            return RedirectToPage();
        }

        var success = await _dataService.ActualizarInsumoAsync(
            id, nombre, categoria, unidad, stockMinimo, costoUnitario, silo, stockActual);

        if (success)
        {
            MensajeExito = $"Insumo '{nombre}' actualizado correctamente en la base de datos.";
        }
        else
        {
            MensajeError = "No se pudo actualizar el insumo.";
        }

        return RedirectToPage();
    }

    // CRUD: Eliminar / Desactivar insumo
    [RequierePolitica(Policies.InventarioGestionar)]
    public async Task<IActionResult> OnPostEliminarInsumoAsync([FromForm] Guid id)
    {
        if (id == Guid.Empty)
        {
            MensajeError = "ID de insumo no válido.";
            return RedirectToPage();
        }

        var success = await _dataService.EliminarInsumoAsync(id);
        if (success)
        {
            MensajeExito = "El insumo ha sido desactivado del catálogo en PostgreSQL.";
        }
        else
        {
            MensajeError = "No se pudo desactivar el insumo.";
        }

        return RedirectToPage();
    }

    // Kardex: Registrar entrada de insumo
    [RequierePolitica(Policies.KardexRegistrar)]
    public async Task<IActionResult> OnPostRegistrarEntradaAsync(
        [FromForm] string sku,
        [FromForm] string nombre,
        [FromForm] string categoria,
        [FromForm] string unidad,
        [FromForm] decimal cantidad,
        [FromForm] decimal costoUnitario,
        [FromForm] string destino)
    {
        if (cantidad <= 0 || costoUnitario <= 0)
        {
            MensajeError = "Cantidad y costo unitario deben ser superiores a cero.";
            return RedirectToPage();
        }

        await _dataService.RegistrarEntradaKardexAsync(sku, nombre, categoria, unidad, cantidad, costoUnitario, destino);

        MensajeExito = $"Entrada de {cantidad:N0} {unidad} de '{nombre}' registrada exitosamente en Kardex PostgreSQL.";
        return RedirectToPage();
    }
}
