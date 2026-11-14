-- ============================================================================
-- AGROPIG INTELLIGENCE SUITE - SEED ADICIONAL PARA COBERTURA COMPLETA
-- ============================================================================

-- Más Cerdas Reproductoras
INSERT INTO cerdas_reproductoras (id, granja_id, caravana, rfid, linea_genetica_id, galpon_id, jaula_numero, paridad_acumulada, estado_fisiologico, estatus_sanitario, prolificidad_historica_media)
VALUES
(
    '10000000-0000-0000-0000-000000000004',
    'a0000000-0000-0000-0000-000000000001',
    'CRD-2911',
    '982-000145220',
    'e0000000-0000-0000-0000-000000000001',
    'b0000000-0000-0000-0000-000000000005',
    'M-12',
    5,
    'lactante_maternidad',
    'Protocolo Calostro OK',
    14.6
),
(
    '10000000-0000-0000-0000-000000000005',
    'a0000000-0000-0000-0000-000000000001',
    'CRD-4155',
    '982-000219803',
    'e0000000-0000-0000-0000-000000000002',
    'b0000000-0000-0000-0000-000000000005',
    'M-11',
    0,
    'servida_gestante',
    'Pre-parto Primeriza OK',
    0.0
),
(
    '10000000-0000-0000-0000-000000000006',
    'a0000000-0000-0000-0000-000000000001',
    'CRD-5021',
    '982-000441290',
    'e0000000-0000-0000-0000-000000000001',
    'b0000000-0000-0000-0000-000000000005',
    'M-15',
    1,
    'vacia_descanso',
    'Sanidad Al Día',
    13.2
) ON CONFLICT (caravana) DO NOTHING;

-- Servicio IA para la cerda CRD-4155 para que aparezca en el Radar de Partos (Día 112)
INSERT INTO servicios_inseminacion (id, cerda_id, verraco_id, fecha_servicio, turno, tecnica_ia, resultado_concepcion, fecha_parto_estimada)
VALUES
(
    '20000000-0000-0000-0000-000000000003',
    '10000000-0000-0000-0000-000000000005', -- CRD-4155
    'f0000000-0000-0000-0000-000000000001',
    CURRENT_DATE - INTERVAL '111 days',
    '1_dosis_manana',
    'inseminacion_post_cervical',
    'confirmada_positiva',
    CURRENT_DATE + INTERVAL '3 days'
) ON CONFLICT DO NOTHING;

-- Más Insumos de Inventario
INSERT INTO insumos_catalogo (
    id, granja_id, categoria_id, codigo_sku, nombre, unidad_medida, 
    stock_actual, stock_minimo_seguridad, costo_unitario_promedio_usd, 
    silo_deposito_id, estado_stock
)
VALUES
(
    '50000000-0000-0000-0000-000000000004',
    'a0000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000002',
    'SAN-FAR-022',
    'Ivermectina 1% Inyectable (Frasco 500ml)',
    'Frascos',
    18.0,
    6.0,
    18.5000,
    'c0000000-0000-0000-0000-000000000003',
    'optimo'
),
(
    '50000000-0000-0000-0000-000000000005',
    'a0000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000003',
    'ALM-AD-005',
    'Suplemento Electrolítico Lechón Soluble',
    'Kg',
    45.0,
    20.0,
    6.2000,
    'c0000000-0000-0000-0000-000000000003',
    'optimo'
),
(
    '50000000-0000-0000-0000-000000000006',
    'a0000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000002',
    'SAN-VIT-003',
    'Complejo Vitamínico ADE Fuerte Inyectable',
    'Frascos',
    4.0,
    10.0,
    14.2000,
    'c0000000-0000-0000-0000-000000000003',
    'reabastecer_urgente'
) ON CONFLICT (codigo_sku) DO NOTHING;

-- Más Presupuestos por Rubro
INSERT INTO presupuestos_lote (id, lote_id, centro_costo_id, rubro_nombre, monto_estimado_usd, monto_ejecutado_usd, porcentaje_desviacion, estado_bi)
VALUES
(
    '70000000-0000-0000-0000-000000000003',
    '30000000-0000-0000-0000-000000000001', -- LOT-2024-F204
    '60000000-0000-0000-0000-000000000003',
    'Nómina & Operarios Granja',
    24000.0,
    23800.0,
    -0.8,
    'optimo'
),
(
    '70000000-0000-0000-0000-000000000004',
    '30000000-0000-0000-0000-000000000002', -- LOT-2024-P088
    '60000000-0000-0000-0000-000000000001',
    'Alimento Preiniciador I & II',
    14500.0,
    13900.0,
    -4.1,
    'optimo'
),
(
    '70000000-0000-0000-0000-000000000005',
    '30000000-0000-0000-0000-000000000002', -- LOT-2024-P088
    '60000000-0000-0000-0000-000000000002',
    'Plan Vacunal Destete (PCV2 + Myco)',
    3950.0,
    3200.0,
    -19.0,
    'optimo'
) ON CONFLICT DO NOTHING;

-- Movimientos Kardex iniciales
INSERT INTO movimientos_kardex (granja_id, insumo_id, silo_id, tipo_movimiento, fecha_hora, cantidad, costo_unitario_usd, documento_referencia, proveedor)
VALUES
(
    'a0000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000001',
    'c0000000-0000-0000-0000-000000000001',
    'entrada_compra',
    NOW() - INTERVAL '2 days',
    18000.0,
    0.2800,
    'Guía Despacho Granel #8921',
    'BioNutrición Porcina S.A.'
),
(
    'a0000000-0000-0000-0000-000000000001',
    '50000000-0000-0000-0000-000000000003',
    'c0000000-0000-0000-0000-000000000003',
    'salida_consumo',
    NOW() - INTERVAL '1 day',
    2.0,
    42.0000,
    'Protocolo Paridad M-14',
    'Dispensario Sanitario'
) ON CONFLICT DO NOTHING;
