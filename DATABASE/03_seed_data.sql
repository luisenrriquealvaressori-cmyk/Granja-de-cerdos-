-- ============================================================================
-- AGROPIG INTELLIGENCE SUITE - DATOS MAESTROS Y REGISTROS INICIALES
-- Base de datos: agropig_db
-- ============================================================================

-- 1. Granja Principal
INSERT INTO granjas (id, codigo, nombre, ubicacion, superficie_hectareas, capacidad_total_cabezas)
VALUES (
    'a0000000-0000-0000-0000-000000000001',
    'GRANJA-SJ-01',
    'Granja San José (Principal)',
    'Valle Central Porcino, Km 42 Carretera Panamericana',
    28.5,
    3200
) ON CONFLICT (codigo) DO NOTHING;

-- 2. Galpones y Naves
INSERT INTO galpones_naves (id, granja_id, codigo, nombre, tipo_sistema, capacidad_cabezas, temperatura_ambiente_celsius, humedad_relativa_pct)
VALUES 
(
    'b0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'GALP-01',
    'Galpón 1 - Ventilación Natural',
    'ventilacion_natural',
    450,
    23.8,
    58.0
),
(
    'b0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000001',
    'GALP-02',
    'Galpón 2 - Presión Negativa',
    'presion_negativa',
    550,
    22.5,
    60.0
),
(
    'b0000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000001',
    'GALP-03',
    'Galpón 3 - Climatizado Automatizado',
    'climatizado_automatizado',
    600,
    24.2,
    62.0
),
(
    'b0000000-0000-0000-0000-000000000004',
    'a0000000-0000-0000-0000-000000000001',
    'GALP-04',
    'Galpón 4 - Túnel de Viento (Engorde Intensivo)',
    'tunel_viento',
    700,
    23.4,
    62.0
),
(
    'b0000000-0000-0000-0000-000000000005',
    'a0000000-0000-0000-0000-000000000001',
    'MAT-02',
    'Sala de Maternidad M-02 (40 Jaulas Parición)',
    'climatizado_automatizado',
    40,
    22.5,
    55.0
) ON CONFLICT DO NOTHING;

-- 3. Silos y Depósitos
INSERT INTO silos_depositos (id, granja_id, codigo, nombre, capacidad_maxima_kg, nivel_actual_kg, tipo_insumo_principal)
VALUES
(
    'c0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'SILO-A1',
    'Silo Nodriza A-1 (Alimento Engorde)',
    48000.0,
    32640.0, -- 68%
    'Concentrado Engorde Terminado 18% Proteína'
),
(
    'c0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000001',
    'SILO-04',
    'Silo 4 (Gestación Sur)',
    35000.0,
    25200.0, -- 72%
    'Ración Gestación Mantenimiento'
),
(
    'c0000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000001',
    'BOD-CENTRAL',
    'Bodega Central de Insumos & Farmacia',
    15000.0,
    6200.0,
    'Medicamentos, Preiniciadores y Aditivos'
) ON CONFLICT DO NOTHING;

-- 4. Usuarios Operadores
INSERT INTO usuarios_operadores (id, granja_id, nombre_usuario, nombre, email, password_hash, rol, matricula_profesional, telefono)
VALUES
(
    'd0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'vet_juan',
    'Dr. Juan Rivas',
    'juan.rivas@agropig.com',
    crypt('1234', gen_salt('bf')),
    'director_veterinario',
    'MV-7821',
    '+52 55 1234 5678'
),
(
    'd0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000001',
    'zoo_carlos',
    'Dr. Carlos Meneses',
    'carlos.meneses@agropig.com',
    crypt('1234', gen_salt('bf')),
    'zootecnista_jefe',
    'MV-9482',
    '+52 55 9876 5432'
),
(
    'd0000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000001',
    'admin_carlos',
    'Ing. Carlos Ramírez',
    'carlos.ramirez@agropig.com',
    crypt('1234', gen_salt('bf')),
    'administrador_financiero',
    'CPA-1102',
    '+52 55 5555 4444'
) ON CONFLICT (id) DO NOTHING;

-- 5. Líneas Genéticas y Verracos
INSERT INTO lineas_geneticas (id, nombre, casa_genetica, potencial_gmd_g_dia, fcr_teorico_objetivo, prolificidad_objetivo_nac_vivos)
VALUES
(
    'e0000000-0000-0000-0000-000000000001',
    'Topigs Norsvin (TN70 x DanBred)',
    'Topigs Norsvin',
    980,
    2.42,
    14.5
),
(
    'e0000000-0000-0000-0000-000000000002',
    'PIC Camborough x PIC 337',
    'PIC',
    960,
    2.45,
    14.2
) ON CONFLICT (nombre) DO NOTHING;

INSERT INTO verracos_semen (id, granja_id, codigo, nombre, linea_genetica_id, tipo_reproductor, viabilidad_espermatica_pct, concentracion_billones)
VALUES
(
    'f0000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'VERR-P88',
    'Pietrain Master P-88 (Lote #2024-SM)',
    'e0000000-0000-0000-0000-000000000001',
    'dosis_semen_refrigerado',
    84.0,
    1.50
),
(
    'f0000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000001',
    'VERR-D12',
    'Duroc Magnus D-12',
    'e0000000-0000-0000-0000-000000000002',
    'verraco_en_pie',
    88.5,
    1.80
) ON CONFLICT (codigo) DO NOTHING;

-- 6. Cerdas Reproductoras
INSERT INTO cerdas_reproductoras (id, granja_id, caravana, rfid, linea_genetica_id, galpon_id, jaula_numero, paridad_acumulada, estado_fisiologico, estatus_sanitario, prolificidad_historica_media)
VALUES
(
    '10000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'CRD-9402',
    '982-00016041',
    'e0000000-0000-0000-0000-000000000001',
    'b0000000-0000-0000-0000-000000000005',
    'M-02',
    3,
    'celo_activo',
    'PLE + E. Coli Vigente',
    14.3
),
(
    '10000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000001',
    'CRD-3810',
    '982-000184712',
    'e0000000-0000-0000-0000-000000000002',
    'b0000000-0000-0000-0000-000000000005',
    'M-04',
    4,
    'servida_gestante',
    'Pre-parto Completo',
    15.0
),
(
    '10000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000001',
    'CRD-4012',
    '982-000318455',
    'e0000000-0000-0000-0000-000000000001',
    'b0000000-0000-0000-0000-000000000005',
    'M-08',
    2,
    'servida_gestante',
    'Hierro + Vacunas Listas',
    13.8
) ON CONFLICT (caravana) DO NOTHING;

-- 7. Servicios de Inseminación
INSERT INTO servicios_inseminacion (id, cerda_id, verraco_id, fecha_servicio, turno, tecnica_ia, resultado_concepcion, fecha_parto_estimada)
VALUES
(
    '20000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000002', -- CRD-3810 (Día 114 de gestación)
    'f0000000-0000-0000-0000-000000000002',
    CURRENT_DATE - INTERVAL '113 days',
    '1_dosis_manana',
    'inseminacion_post_cervical',
    'confirmada_positiva',
    CURRENT_DATE + INTERVAL '1 day' -- Mañana
),
(
    '20000000-0000-0000-0000-000000000002',
    '10000000-0000-0000-0000-000000000003', -- CRD-4012 (Día 112)
    'f0000000-0000-0000-0000-000000000001',
    CURRENT_DATE - INTERVAL '112 days',
    '1_dosis_manana',
    'inseminacion_post_cervical',
    'confirmada_positiva',
    CURRENT_DATE + INTERVAL '2 days'
) ON CONFLICT DO NOTHING;

-- 8. Lotes de Producción
INSERT INTO lotes_produccion (
    id, granja_id, galpon_id, codigo_lote, etapa_zootecnica, 
    fecha_inicio, fecha_cierre_estimada, cantidad_inicial_cabezas, 
    cantidad_actual_cabezas, bajas_acumuladas, peso_promedio_entrada_kg, 
    peso_promedio_actual_kg, gmd_actual_g_dia, fcr_actual, 
    presupuesto_asignado_usd, costo_acumulado_usd, estado
)
VALUES
(
    '30000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    'b0000000-0000-0000-0000-000000000004', -- Galpón 4
    'LOT-2024-F204',
    'engorde_intensivo',
    CURRENT_DATE - INTERVAL '48 days',
    CURRENT_DATE + INTERVAL '68 days',
    450,
    445,
    5,
    28.5,
    88.4,
    790.0,
    2.68,
    48000.0,
    54820.0,
    'activo'
),
(
    '30000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000001',
    'b0000000-0000-0000-0000-000000000003', -- Galpón 3
    'LOT-2024-P088',
    'precebo',
    CURRENT_DATE - INTERVAL '25 days',
    CURRENT_DATE + INTERVAL '20 days',
    520,
    511,
    9,
    6.8,
    18.5,
    485.0,
    1.42,
    18450.0,
    17100.0,
    'activo'
) ON CONFLICT (codigo_lote) DO NOTHING;

-- 9. Categorías e Insumos de Inventario
INSERT INTO categorias_insumos (id, codigo, nombre)
VALUES
('40000000-0000-0000-0000-000000000001', 'ALIM', 'Alimento Balanceado'),
('40000000-0000-0000-0000-000000000002', 'MED', 'Medicamentos & Vacunas'),
('40000000-0000-0000-0000-000000000003', 'PRE', 'Preiniciadores & Aditivos')
ON CONFLICT (codigo) DO NOTHING;

INSERT INTO insumos_catalogo (
    id, granja_id, categoria_id, codigo_sku, nombre, unidad_medida, 
    stock_actual, stock_minimo_seguridad, costo_unitario_promedio_usd, 
    silo_deposito_id, estado_stock
)
VALUES
(
    '50000000-0000-0000-0000-000000000001',
    'a0000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000001',
    'ALM-MZ-001',
    'Maíz Grano Partido Especial (88% MS)',
    'Kg',
    32400.0,
    15000.0,
    0.2800,
    'c0000000-0000-0000-0000-000000000001',
    'optimo'
),
(
    '50000000-0000-0000-0000-000000000002',
    'a0000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000003',
    'ALM-PRE-014',
    'Concentrado Precebo Preiniciador I (21% Prot)',
    'Kg',
    850.0,
    1200.0,
    1.4500,
    'c0000000-0000-0000-0000-000000000003',
    'reabastecer_urgente'
),
(
    '50000000-0000-0000-0000-000000000003',
    'a0000000-0000-0000-0000-000000000001',
    '40000000-0000-0000-0000-000000000002',
    'SAN-VAC-008',
    'Vacuna Circovirus PCV2 + Mycoplasma (50 Dosis)',
    'Frascos',
    15.0,
    25.0,
    42.0000,
    'c0000000-0000-0000-0000-000000000003',
    'reabastecer_urgente'
) ON CONFLICT (codigo_sku) DO NOTHING;

-- 10. Centros de Costo y Presupuestos
INSERT INTO centros_costo (id, granja_id, codigo, nombre, porcentaje_peso_presupuestal)
VALUES
('60000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000001', 'CC-ALIM', 'Alimento Balanceado Fase Engorde', 73.5),
('60000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000001', 'CC-SAN', 'Plan Vacunal & Sanidad Preventiva', 9.2),
('60000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000001', 'CC-NOM', 'Nómina & Operarios Granja', 17.3)
ON CONFLICT (codigo) DO NOTHING;

INSERT INTO presupuestos_lote (id, lote_id, centro_costo_id, rubro_nombre, monto_estimado_usd, monto_ejecutado_usd, porcentaje_desviacion, estado_bi)
VALUES
(
    '70000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000001', -- LOT-2024-F204
    '60000000-0000-0000-0000-000000000001',
    'Alimento Balanceado Fase Engorde',
    48000.0,
    54820.0,
    14.2,
    'desviado_critico'
),
(
    '70000000-0000-0000-0000-000000000002',
    '30000000-0000-0000-0000-000000000001', -- LOT-2024-F204
    '60000000-0000-0000-0000-000000000002',
    'Plan Vacunal & Sanidad Preventiva',
    12500.0,
    11100.0,
    -11.2,
    'optimo'
) ON CONFLICT DO NOTHING;

-- 11. Alertas Operativas en Vivo
INSERT INTO alertas_operativas_desviaciones (
    granja_id, lote_id, tipo_alerta, nivel_severidad, 
    descripcion, valor_detectado, umbral_esperado, estado
)
VALUES
(
    'a0000000-0000-0000-0000-000000000001',
    '30000000-0000-0000-0000-000000000001',
    'desviacion_financiera',
    'critica',
    'Alimento Engorde Lote LOT-2024-F204: Sobreconsumo detectado en Tolva 04',
    '+14.2% sobrecosto',
    'Max ±10%',
    'abierta'
),
(
    'a0000000-0000-0000-0000-000000000001',
    NULL,
    'protocolo_sanitario',
    'preventiva',
    'Lote Maternidad M-102: Inicio de plan de vacunación contra Mycoplasma hyopneumoniae programado',
    'Programado 14:00 hrs',
    'Día 18 Lactancia',
    'abierta'
);
