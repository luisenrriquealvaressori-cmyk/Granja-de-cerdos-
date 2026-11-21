-- ============================================================================
-- AGROPIG INTELLIGENCE SUITE - LIMPIEZA TRANSACCIONAL 07
-- Limpia inventario, catálogo de compras/proveedores, niveles de silos a 0 kg
-- y elimina todos los usuarios excepto la cuenta única 'admin'.
-- Conserva categorías, galpones, jaulas, lotes, cerdos, cerdas y tareas.
-- ============================================================================

BEGIN;

-- 1. Limpiar compras, proveedores, movimientos Kardex y catálogo de insumos
DELETE FROM ordenes_compra_detalles;
DELETE FROM ordenes_compra;
DELETE FROM catalogo_proveedores_productos;
DELETE FROM proveedores;
DELETE FROM movimientos_kardex;
DELETE FROM registro_tratamientos_clinicos;
DELETE FROM aplicaciones_sanitarias_lote;
DELETE FROM insumos_catalogo;

-- 2. Reiniciar los silos físicos a 0 kg manteniendo sus ubicaciones para nuevos ingresos
UPDATE silos_depositos
SET nivel_actual_kg = 0,
    tipo_insumo_principal = 'Sin asignar';

-- 3. Reasignar referencias de usuarios hacia la cuenta única 'admin'
UPDATE alertas_operativas_desviaciones
SET resuelto_por_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE resuelto_por_id IS NOT NULL
  AND resuelto_por_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE gastos_operativos_transacciones
SET autorizado_por_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE autorizado_por_id IS NOT NULL
  AND autorizado_por_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE gastos_operativos_transacciones
SET registrado_por_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE registrado_por_id IS NOT NULL
  AND registrado_por_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE lotes_produccion
SET responsable_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE responsable_id IS NOT NULL
  AND responsable_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE mortalidad_bajas
SET reportado_por_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE reportado_por_id IS NOT NULL
  AND reportado_por_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE partos
SET responsable_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE responsable_id IS NOT NULL
  AND responsable_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE partos
SET atendido_por_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE atendido_por_id IS NOT NULL
  AND atendido_por_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE perdidas_perinatales
SET responsable_registro_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE responsable_registro_id IS NOT NULL
  AND responsable_registro_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE pesajes_monitoreo
SET registrado_por_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE registrado_por_id IS NOT NULL
  AND registrado_por_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE servicios_inseminacion
SET operario_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE operario_id IS NOT NULL
  AND operario_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE sync_outbox_logs
SET operario_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE operario_id IS NOT NULL
  AND operario_id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE transiciones_etapa_lote
SET aprobado_por_id = 'd0000000-0000-0000-0000-0000000000a1'
WHERE aprobado_por_id IS NOT NULL
  AND aprobado_por_id <> 'd0000000-0000-0000-0000-0000000000a1';

-- 4. Eliminar todas las cuentas excepto 'admin' y asegurar su estado activo
DELETE FROM usuarios_operadores
WHERE id <> 'd0000000-0000-0000-0000-0000000000a1';

UPDATE usuarios_operadores
SET activo = TRUE
WHERE id = 'd0000000-0000-0000-0000-0000000000a1';

COMMIT;
