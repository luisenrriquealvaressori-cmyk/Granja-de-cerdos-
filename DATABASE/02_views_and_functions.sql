-- ============================================================================
-- AGROPIG INTELLIGENCE SUITE - FUNCIONES, DISPARADORES Y VISTAS BI
-- Base de datos: agropig_db
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. FUNCIÓN Y DISPARADOR: CÁLCULO BIOLÓGICO DE GESTACIÓN (+114 DÍAS)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_calcular_hitos_reproductivos()
RETURNS TRIGGER AS $$
BEGIN
    -- 1. Confirmación ecográfica a los 28 días post-servicio
    IF NEW.fecha_ecografia_estimada IS NULL THEN
        NEW.fecha_ecografia_estimada := NEW.fecha_servicio + INTERVAL '28 days';
    END IF;

    -- 2. Traslado a jaulas de maternidad (lavado y desparasitación) a los 107 días
    IF NEW.fecha_traslado_maternidad_estimada IS NULL THEN
        NEW.fecha_traslado_maternidad_estimada := NEW.fecha_servicio + INTERVAL '107 days';
    END IF;

    -- 3. Fecha probable de parto exacto a los 114 días
    IF NEW.fecha_parto_estimada IS NULL THEN
        NEW.fecha_parto_estimada := NEW.fecha_servicio + INTERVAL '114 days';
    END IF;

    -- Actualizar estado de la cerda a 'servida_gestante'
    UPDATE cerdas_reproductoras
    SET estado_fisiologico = 'servida_gestante',
        updated_at = NOW()
    WHERE id = NEW.cerda_id;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_calcular_hitos_reproductivos ON servicios_inseminacion;
CREATE TRIGGER trg_calcular_hitos_reproductivos
BEFORE INSERT OR UPDATE OF fecha_servicio ON servicios_inseminacion
FOR EACH ROW
EXECUTE FUNCTION fn_calcular_hitos_reproductivos();

-- ----------------------------------------------------------------------------
-- 2. FUNCIÓN Y DISPARADOR: ACTUALIZACIÓN AUTOMÁTICA DE KARDEX Y SILOS
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_actualizar_stock_kardex()
RETURNS TRIGGER AS $$
DECLARE
    v_factor INT := 1;
BEGIN
    -- Determinar si la operación suma o resta inventario
    IF NEW.tipo_movimiento LIKE 'salida%' OR NEW.tipo_movimiento = 'merma_perdida' THEN
        v_factor := -1;
    ELSE
        v_factor := 1;
    END IF;

    -- Actualizar el inventario en el catálogo central
    UPDATE insumos_catalogo
    SET stock_actual = stock_actual + (NEW.cantidad * v_factor),
        costo_unitario_promedio_usd = CASE 
            WHEN v_factor = 1 AND (stock_actual + NEW.cantidad) > 0 
            THEN ROUND(((stock_actual * costo_unitario_promedio_usd) + (NEW.cantidad * NEW.costo_unitario_usd)) / (stock_actual + NEW.cantidad), 4)
            ELSE costo_unitario_promedio_usd
        END,
        estado_stock = CASE 
            WHEN (stock_actual + (NEW.cantidad * v_factor)) <= stock_minimo_seguridad THEN 'reabastecer_urgente'
            ELSE 'optimo'
        END
    WHERE id = NEW.insumo_id;

    -- Si el movimiento involucra un silo, actualizar su nivel físico
    IF NEW.silo_id IS NOT NULL THEN
        UPDATE silos_depositos
        SET nivel_actual_kg = GREATEST(0, nivel_actual_kg + (NEW.cantidad * v_factor)),
            ultima_sincronizacion = NOW()
        WHERE id = NEW.silo_id;
    END IF;

    -- Si está asociado a un lote y es salida de alimento/sanidad, imputar al costo del lote
    IF NEW.lote_id IS NOT NULL AND v_factor = -1 THEN
        UPDATE lotes_produccion
        SET costo_acumulado_usd = costo_acumulado_usd + NEW.valor_total_usd,
            updated_at = NOW()
        WHERE id = NEW.lote_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_actualizar_stock_kardex ON movimientos_kardex;
CREATE TRIGGER trg_actualizar_stock_kardex
AFTER INSERT ON movimientos_kardex
FOR EACH ROW
EXECUTE FUNCTION fn_actualizar_stock_kardex();

-- ----------------------------------------------------------------------------
-- 3. FUNCIÓN Y DISPARADOR: DESVIACIONES PRESUPUESTARIAS EN TIEMPO REAL
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_recalcular_desviacion_presupuesto()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.monto_estimado_usd > 0 THEN
        NEW.porcentaje_desviacion := ROUND(((NEW.monto_ejecutado_usd - NEW.monto_estimado_usd) / NEW.monto_estimado_usd) * 100, 2);
    ELSE
        NEW.porcentaje_desviacion := 0;
    END IF;

    -- Clasificación BI: Umbral de desviación del 10%
    IF NEW.porcentaje_desviacion > 10.0 THEN
        NEW.estado_bi := 'desviado_critico';
    ELSIF NEW.porcentaje_desviacion < -10.0 THEN
        NEW.estado_bi := 'subejecutado';
    ELSIF NEW.porcentaje_desviacion < 0.0 THEN
        NEW.estado_bi := 'optimo';
    ELSE
        NEW.estado_bi := 'en_norma';
    END IF;

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_recalcular_desviacion_presupuesto ON presupuestos_lote;
CREATE TRIGGER trg_recalcular_desviacion_presupuesto
BEFORE INSERT OR UPDATE OF monto_ejecutado_usd, monto_estimado_usd ON presupuestos_lote
FOR EACH ROW
EXECUTE FUNCTION fn_recalcular_desviacion_presupuesto();

-- ----------------------------------------------------------------------------
-- 4. VISTA BI: RADAR DE PARTOS INMINENTES (VENTANA 7 DÍAS)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_radar_partos_inminentes AS
SELECT 
    c.id AS cerda_id,
    c.caravana,
    c.rfid,
    c.paridad_acumulada,
    (c.paridad_acumulada = 0) AS es_primeriza,
    c.jaula_numero,
    g.nombre AS galpon_nombre,
    s.id AS servicio_id,
    s.fecha_servicio,
    s.fecha_parto_estimada,
    (CURRENT_DATE - s.fecha_servicio) AS dias_gestacion_actual,
    (s.fecha_parto_estimada - CURRENT_DATE) AS dias_para_parto,
    ROUND(((CURRENT_DATE - s.fecha_servicio)::NUMERIC / 114.0) * 100, 1) AS pct_avance_gestacional,
    CASE 
        WHEN (s.fecha_parto_estimada - CURRENT_DATE) <= 0 THEN 'Parto Inminente / Término Cumplido'
        WHEN (s.fecha_parto_estimada - CURRENT_DATE) = 1 THEN 'Mañana (En 24h)'
        ELSE 'En ' || (s.fecha_parto_estimada - CURRENT_DATE) || ' días'
    END AS aviso_temporal,
    v.nombre AS verraco_utilizado,
    c.estatus_sanitario
FROM servicios_inseminacion s
JOIN cerdas_reproductoras c ON s.cerda_id = c.id
LEFT JOIN galpones_naves g ON c.galpon_id = g.id
LEFT JOIN verracos_semen v ON s.verraco_id = v.id
WHERE s.resultado_concepcion IN ('pendiente', 'confirmada_positiva')
  AND s.fecha_parto_estimada BETWEEN CURRENT_DATE - INTERVAL '2 days' AND CURRENT_DATE + INTERVAL '7 days'
ORDER BY s.fecha_parto_estimada ASC;

-- ----------------------------------------------------------------------------
-- 5. VISTA BI: RESUMEN INTEGRAL DE LOTES ACTIVOS
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_resumen_bi_lotes_activos AS
SELECT 
    l.id AS lote_id,
    l.codigo_lote,
    l.etapa_zootecnica,
    g.nombre AS galpon_asignado,
    l.fecha_inicio,
    (CURRENT_DATE - l.fecha_inicio) AS dias_en_ciclo,
    l.cantidad_inicial_cabezas,
    l.cantidad_actual_cabezas,
    l.bajas_acumuladas,
    ROUND((l.bajas_acumuladas::NUMERIC / NULLIF(l.cantidad_inicial_cabezas, 0)) * 100, 2) AS tasa_mortalidad_pct,
    l.peso_promedio_entrada_kg,
    l.peso_promedio_actual_kg,
    l.gmd_actual_g_dia,
    l.fcr_actual,
    l.presupuesto_asignado_usd,
    COALESCE(SUM(p.monto_ejecutado_usd), l.costo_acumulado_usd) AS costo_ejecutado_total_usd,
    ROUND(
        CASE 
            WHEN l.presupuesto_asignado_usd > 0 
            THEN ((COALESCE(SUM(p.monto_ejecutado_usd), l.costo_acumulado_usd) - l.presupuesto_asignado_usd) / l.presupuesto_asignado_usd) * 100 
            ELSE 0 
        END, 2
    ) AS desviacion_presupuesto_pct,
    l.estado
FROM lotes_produccion l
LEFT JOIN galpones_naves g ON l.galpon_id = g.id
LEFT JOIN presupuestos_lote p ON l.id = p.lote_id
WHERE l.estado = 'activo'
GROUP BY l.id, l.codigo_lote, l.etapa_zootecnica, g.nombre, l.fecha_inicio, 
         l.cantidad_inicial_cabezas, l.cantidad_actual_cabezas, l.bajas_acumuladas, 
         l.peso_promedio_entrada_kg, l.peso_promedio_actual_kg, l.gmd_actual_g_dia, 
         l.fcr_actual, l.presupuesto_asignado_usd, l.costo_acumulado_usd, l.estado;

-- ----------------------------------------------------------------------------
-- 6. VISTA BI: BALANCE DE SILOS Y ALMACÉN CENTRAL
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_balance_kardex_silos AS
SELECT 
    s.id AS silo_id,
    s.codigo AS silo_codigo,
    s.nombre AS silo_nombre,
    s.tipo_insumo_principal,
    s.capacidad_maxima_kg,
    s.nivel_actual_kg,
    ROUND((s.nivel_actual_kg / NULLIF(s.capacidad_maxima_kg, 0)) * 100, 1) AS porcentaje_capacidad,
    CASE 
        WHEN (s.nivel_actual_kg / NULLIF(s.capacidad_maxima_kg, 0)) <= 0.15 THEN 'Crítico / Reabastecer Inmediato'
        WHEN (s.nivel_actual_kg / NULLIF(s.capacidad_maxima_kg, 0)) <= 0.30 THEN 'Alerta Reposición'
        ELSE 'Operación Normal'
    END AS estado_operativo,
    s.estado_sensor,
    s.ultima_sincronizacion
FROM silos_depositos s;

-- ----------------------------------------------------------------------------
-- 7. VISTA BI: CORRELACIÓN BIOLÓGICA & FINANCIERA (COSTO / KG PRODUCIDO)
-- ----------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_analisis_costo_kg_producido AS
SELECT 
    l.id AS lote_id,
    l.codigo_lote,
    l.etapa_zootecnica,
    l.cantidad_actual_cabezas,
    (l.cantidad_actual_cabezas * l.peso_promedio_actual_kg) AS biomasa_total_actual_kg,
    l.costo_acumulado_usd,
    ROUND(
        CASE 
            WHEN (l.cantidad_actual_cabezas * l.peso_promedio_actual_kg) > 0 
            THEN l.costo_acumulado_usd / (l.cantidad_actual_cabezas * l.peso_promedio_actual_kg) 
            ELSE 0 
        END, 2
    ) AS costo_por_kg_producido_usd,
    1.48 AS costo_objetivo_presupuestado_usd,
    ROUND(
        CASE 
            WHEN (l.cantidad_actual_cabezas * l.peso_promedio_actual_kg) > 0 
            THEN ((l.costo_acumulado_usd / (l.cantidad_actual_cabezas * l.peso_promedio_actual_kg)) - 1.48) 
            ELSE 0 
        END, 2
    ) AS variacion_vs_target_usd
FROM lotes_produccion l
WHERE l.estado = 'activo';
