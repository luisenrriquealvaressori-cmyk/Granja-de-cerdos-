using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using WEB.Security;
using WEB.Services;

namespace WEB.Pages;

public class PresupuestosModel : PageModel
{
    private readonly AgroPigDataService _dataService;
    private readonly UserRoleService _roleService;

    public PresupuestosModel(AgroPigDataService dataService, UserRoleService roleService)
    {
        _dataService = dataService;
        _roleService = roleService;
    }

    public PresupuestosViewModel Data { get; set; } = new();
    public CatalogosFormularioWebViewModel Catalogos { get; set; } = new();

    [BindProperty(SupportsGet = true)]
    public string? LoteFiltro { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? RubroDefault { get; set; }

    [TempData]
    public string? MensajeExito { get; set; }

    [TempData]
    public string? MensajeError { get; set; }

    public async Task<IActionResult> OnGetAsync()
    {
        if (!_roleService.EsAdmin)
        {
            return RedirectToPage("/AccesoDenegado");
        }

        Data = await _dataService.GetPresupuestosDataAsync(LoteFiltro);
        Catalogos = await _dataService.GetCatalogosFormularioWebAsync();
        return Page();
    }

    // CRUD: Crear o Presupuestar Rubro (Alimento, Mano de Obra, Servicios, etc.)
    [RequierePolitica(Policies.PresupuestosGestionar)]
    public async Task<IActionResult> OnPostCrearRubroPresupuestarioAsync(
        [FromForm] Guid loteId,
        [FromForm] Guid centroCostoId,
        [FromForm] string rubroNombre,
        [FromForm] decimal montoEstimado)
    {
        if (loteId == Guid.Empty || centroCostoId == Guid.Empty || string.IsNullOrWhiteSpace(rubroNombre))
        {
            MensajeError = "Debes seleccionar un lote, centro de costo y especificar el nombre del rubro presupuestario.";
            return RedirectToPage(new { LoteFiltro });
        }

        if (montoEstimado <= 0)
        {
            MensajeError = "El monto presupuestado estimado debe ser superior a cero.";
            return RedirectToPage(new { LoteFiltro });
        }

        var success = await _dataService.CrearPresupuestoRubroAsync(loteId, centroCostoId, rubroNombre, montoEstimado);
        if (success)
        {
            MensajeExito = $"Rubro presupuestario '{rubroNombre}' proyectado exitosamente por ${montoEstimado:N2} USD en PostgreSQL.";
        }
        else
        {
            MensajeError = "No se pudo guardar la línea presupuestaria.";
        }

        return RedirectToPage(new { LoteFiltro });
    }

    // CRUD: Actualizar Rubro Presupuestario
    [RequierePolitica(Policies.PresupuestosGestionar)]
    public async Task<IActionResult> OnPostActualizarRubroPresupuestarioAsync(
        [FromForm] Guid id,
        [FromForm] string rubroNombre,
        [FromForm] decimal montoEstimado)
    {
        if (id == Guid.Empty || string.IsNullOrWhiteSpace(rubroNombre) || montoEstimado <= 0)
        {
            MensajeError = "Datos de rubro no válidos para actualización presupuestal.";
            return RedirectToPage(new { LoteFiltro });
        }

        var success = await _dataService.ActualizarPresupuestoRubroAsync(id, rubroNombre, montoEstimado);
        if (success)
        {
            MensajeExito = $"Presupuesto de '{rubroNombre}' ajustado a ${montoEstimado:N2} USD.";
        }
        else
        {
            MensajeError = "No se pudo actualizar el rubro presupuestario.";
        }

        return RedirectToPage(new { LoteFiltro });
    }

    // CRUD: Eliminar Rubro Presupuestario
    [RequierePolitica(Policies.PresupuestosGestionar)]
    public async Task<IActionResult> OnPostEliminarRubroPresupuestarioAsync([FromForm] Guid id)
    {
        if (id == Guid.Empty)
        {
            MensajeError = "ID de rubro presupuestario no válido.";
            return RedirectToPage(new { LoteFiltro });
        }

        var success = await _dataService.EliminarPresupuestoRubroAsync(id);
        if (success)
        {
            MensajeExito = "Rubro presupuestario eliminado de PostgreSQL con éxito.";
        }
        else
        {
            MensajeError = "No se pudo eliminar el rubro presupuestario.";
        }

        return RedirectToPage(new { LoteFiltro });
    }

    // CRUD: Registrar Gasto Operativo Real (Mano de Obra, Servicios, Alimentación, etc.)
    [RequierePolitica(Policies.PresupuestosGestionar)]
    public async Task<IActionResult> OnPostRegistrarGastoAsync(
        [FromForm] Guid loteId,
        [FromForm] Guid centroCostoId,
        [FromForm] string tipoMovimiento,
        [FromForm] decimal monto,
        [FromForm] string concepto,
        [FromForm] string proveedor,
        [FromForm] DateTime fecha)
    {
        if (monto <= 0)
        {
            MensajeError = "El monto del gasto debe ser superior a cero.";
            return RedirectToPage(new { LoteFiltro });
        }

        if (loteId == Guid.Empty || centroCostoId == Guid.Empty)
        {
            MensajeError = "Debes seleccionar un lote y un centro de costo para imputar el gasto.";
            return RedirectToPage(new { LoteFiltro });
        }

        var success = await _dataService.RegistrarGastoOperativoAsync(
            loteId, centroCostoId, tipoMovimiento, monto, concepto, proveedor, fecha);

        if (success)
        {
            MensajeExito = $"Gasto de ${monto:N2} USD registrado en PostgreSQL. Desviaciones y costos recalculados automáticamente.";
        }
        else
        {
            MensajeError = "Error al asentar la transacción en PostgreSQL.";
        }

        return RedirectToPage(new { LoteFiltro });
    }

    // CRUD: Eliminar o Anular Gasto Operativo Real
    [RequierePolitica(Policies.PresupuestosGestionar)]
    public async Task<IActionResult> OnPostEliminarGastoAsync([FromForm] Guid transaccionId)
    {
        if (transaccionId == Guid.Empty)
        {
            MensajeError = "ID de transacción no válido.";
            return RedirectToPage(new { LoteFiltro });
        }

        var success = await _dataService.EliminarGastoOperativoAsync(transaccionId);
        if (success)
        {
            MensajeExito = "Gasto operativo anulado y revertido correctamente del presupuesto y lote.";
        }
        else
        {
            MensajeError = "No se pudo anular el gasto en la base de datos.";
        }

        return RedirectToPage(new { LoteFiltro });
    }

    // Operario / Campo: Reportar consumo de alimento (gasto en CC-ALIM y salida de kardex)
    [RequierePolitica(Policies.AlimentacionRegistrar)]
    public async Task<IActionResult> OnPostReportarConsumoAlimentoAsync(
        [FromForm] Guid loteId,
        [FromForm] Guid insumoId,
        [FromForm] decimal cantidad,
        [FromForm] string unidadMedida,
        [FromForm] DateTime fecha,
        [FromForm] string observaciones)
    {
        if (loteId == Guid.Empty || insumoId == Guid.Empty || cantidad <= 0)
        {
            MensajeError = "Debe seleccionar un lote, el tipo de alimento balanceado y una cantidad mayor a cero.";
            return RedirectToPage(new { LoteFiltro });
        }

        bool success;
        try
        {
            success = await _dataService.RegistrarConsumoAlimentoGastoAsync(
                loteId, insumoId, cantidad, unidadMedida, fecha, observaciones);
        }
        catch (InvalidOperationException ex)
        {
            MensajeError = ex.Message;
            return RedirectToPage(new { LoteFiltro });
        }

        if (success)
        {
            MensajeExito = $"Consumo de alimento ({cantidad} {unidadMedida}) asentado correctamente en PostgreSQL: descontado de Kardex/Silo e imputado al presupuesto de alimentación (CC-ALIM).";
        }
        else
        {
            MensajeError = "No se pudo procesar el consumo de alimento en la base de datos.";
        }

        return RedirectToPage(new { LoteFiltro });
    }

    // Veterinario / Clínica: Aplicar medicamento con kit completo descartable (jeringa, aguja, yodo, torunda)
    // Descuenta dosis de frasco (ej. 100ml -> 95ml), descuenta insumos de Kardex y asienta auditoría
    [RequierePolitica(Policies.SanidadRegistrar)]
    public async Task<IActionResult> OnPostAplicarMedicamentoAsync(
        [FromForm] Guid? loteId,
        [FromForm] string? crotalCerda,
        [FromForm] string? galponJaula,
        [FromForm] Guid insumoId,
        [FromForm] decimal dosis,
        [FromForm] string unidadMedida,
        [FromForm] decimal volumenFrascoInicial,
        [FromForm] DateTime fecha,
        [FromForm] string diagnostico,
        [FromForm] string viaAdministracion,
        [FromForm] bool incluyeJeringa,
        [FromForm] string? tipoJeringa,
        [FromForm] bool incluyeAguja,
        [FromForm] string? tipoAguja,
        [FromForm] bool incluyeYodo,
        [FromForm] decimal? volumenYodoMl,
        [FromForm] bool incluyeTorunda,
        [FromForm] string? veterinarioNombre,
        [FromForm] string? matriculaVeterinario)
    {
        if (insumoId == Guid.Empty || dosis <= 0)
        {
            MensajeError = "Debe seleccionar el medicamento o vacuna y una dosis superior a cero.";
            return RedirectToPage(new { LoteFiltro });
        }

        // El animal debe identificarse explícitamente: ya no se asume un crotal ni una ubicación por defecto
        if (string.IsNullOrWhiteSpace(crotalCerda) && !loteId.HasValue)
        {
            MensajeError = "Indica el crotal del animal tratado o selecciona el lote al que pertenece.";
            return RedirectToPage(new { LoteFiltro });
        }

        string crotal = string.IsNullOrWhiteSpace(crotalCerda) ? "LOTE" : crotalCerda.Trim().ToUpper();
        string ubicacion = string.IsNullOrWhiteSpace(galponJaula) ? "Sin especificar" : galponJaula.Trim();
        string jeringa = string.IsNullOrWhiteSpace(tipoJeringa) ? "5 ml Descartable" : tipoJeringa.Trim();
        string aguja = string.IsNullOrWhiteSpace(tipoAguja) ? "16G x 1.5\"" : tipoAguja.Trim();
        decimal yodoMl = volumenYodoMl ?? 5.0m;

        // Responsable clínico: siempre la persona autenticada (el formulario ya no puede suplantar a otro veterinario)
        var perfil = _roleService.CurrentUser;
        string vet = perfil.Nombre;
        string mat = string.IsNullOrWhiteSpace(perfil.Matricula) ? "S/N" : perfil.Matricula;
        decimal volInicial = volumenFrascoInicial > 0 ? volumenFrascoInicial : 100.0m;
        decimal volRestante = Math.Max(0m, volInicial - dosis);

        bool success;
        try
        {
            success = await _dataService.RegistrarAplicacionMedicaGastoAsync(
                loteId,
                crotalCerda: crotal,
                galponJaula: ubicacion,
                insumoId: insumoId,
                dosis: dosis,
                unidadMedida: unidadMedida,
                volumenFrascoInicial: volInicial,
                fecha: fecha,
                diagnostico: diagnostico,
                viaAdministracion: viaAdministracion,
                incluyeJeringa: incluyeJeringa,
                tipoJeringa: jeringa,
                incluyeAguja: incluyeAguja,
                tipoAguja: aguja,
                incluyeYodo: incluyeYodo,
                volumenYodoMl: yodoMl,
                incluyeTorunda: incluyeTorunda,
                veterinarioResponsable: vet,
                matriculaVeterinario: mat);
        }
        catch (InvalidOperationException ex)
        {
            MensajeError = ex.Message;
            return RedirectToPage(new { LoteFiltro });
        }

        if (success)
        {
            MensajeExito = $"Tratamiento registrado con éxito en [{crotal}]: dosis de {dosis:0.#} {unidadMedida} descontada del frasco ({volInicial:0} ml -> {volRestante:0} ml restantes). Insumos clínicos descontados del inventario Kardex y gasto imputado a CC-SAN. Responsable: {vet}.";
        }
        else
        {
            MensajeError = "No se pudo procesar la aplicación médica en la base de datos.";
        }

        return RedirectToPage(new { LoteFiltro });
    }
}
