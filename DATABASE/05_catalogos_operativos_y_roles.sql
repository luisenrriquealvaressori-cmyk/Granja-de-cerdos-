-- ============================================================================
-- AGROPIG INTELLIGENCE SUITE - MIGRACIÓN IDEMPOTENTE 05
-- 1. Catálogos maestros en BD (Labores, Horarios/Turnos, Clínico/Sanitario)
-- 2. Tabla de Personalización UI por Usuario/Rol (preferencias_ui_usuario)
-- 3. Remediación de Categorías (KIT vs MED), Alimentos en Silos, Jaulas y Genética
-- 4. Semilla operacional condicional (Lotes, Plantel, Cerdos, Tareas) si está vacía
-- ============================================================================

-- 1. Asegurar columnas auxiliares faltantes en tablas existentes
ALTER TABLE presupuestos_lote
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 2. Categoría dedicada para Instrumental & Kit Clínico (separada de fármacos MED)
INSERT INTO categorias_insumos (id, codigo, nombre, descripcion)
VALUES (
    'e0000000-0000-0000-0000-000000000006',
    'KIT',
    'Instrumental & Kit Clínico',
    'Jeringas descartables, agujas hipodérmicas, antisépticos tópicos, torundas y guantes'
)
ON CONFLICT (codigo) DO UPDATE
SET nombre = EXCLUDED.nombre,
    descripcion = EXCLUDED.descripcion;

-- Reasignar los 4 insumos descartables del kit clínico desde MED hacia KIT (manteniendo su código SKU)
UPDATE insumos_catalogo
SET categoria_id = (SELECT id FROM categorias_insumos WHERE codigo = 'KIT' LIMIT 1),
    updated_at = NOW()
WHERE codigo_sku IN ('MED-JER-05ML', 'MED-AGU-16G', 'MED-YOD-10', 'MED-TOR-ALG');

-- 3. Alimentos Balanceados y Preiniciadores vinculados a Silos + Fármacos/Vacunas/Bioseguridad/Kit
WITH granja_ref AS (
    SELECT COALESCE(
        (SELECT id FROM granjas WHERE codigo = 'GRANJA-SJ-01' LIMIT 1),
        (SELECT id FROM granjas LIMIT 1)
    ) AS id
)
INSERT INTO insumos_catalogo (
    granja_id, categoria_id, codigo_sku, nombre, unidad_medida,
    stock_actual, stock_minimo_seguridad, costo_unitario_promedio_usd, silo_deposito_id, estado_stock, activo
)
SELECT
    g.id,
    c.id,
    d.sku,
    d.nombre,
    d.unidad,
    d.stock,
    d.stock_min,
    d.costo,
    (SELECT s.id FROM silos_depositos s WHERE s.codigo = d.silo_codigo LIMIT 1),
    'optimo',
    TRUE
FROM granja_ref g
CROSS JOIN (VALUES
    -- Alimentos vinculados a SILO-A1, SILO-A2 y SILO-B1 (en kg para sincronizar nivel_actual_kg)
    ('ALIM', 'ALIM-ENG-F2', 'Concentrado Engorde Fase 2 Pelleteado', 'kg', 16000.00, 3000.00, 0.7200, 'SILO-A1'),
    ('ALIM', 'ALIM-CREC-F1', 'Concentrado Crecimiento Fase 1 Granulado', 'kg', 9600.00, 2000.00, 0.7800, 'SILO-A1'),
    ('PRE',  'PRE-INIC-F1', 'Preiniciador Fase 1 Micro-pellet Lechones', 'kg', 8200.00, 1200.00, 1.0600, 'SILO-A2'),
    ('PRE',  'PRE-INIC-F2', 'Preiniciador II Extruido', 'kg', 9800.00, 1500.00, 0.9400, 'SILO-A2'),
    ('ALIM', 'ALIM-LACT-AD', 'Concentrado Lactancia Alta Densidad', 'kg', 8500.00, 1500.00, 0.8100, 'SILO-B1'),
    ('ALIM', 'ALIM-GEST-FIB', 'Alimento Gestación Multigrano Fibra Activa', 'kg', 6500.00, 1200.00, 0.6600, 'SILO-B1'),
    -- Medicamentos y biológicos adicionales (MED)
    ('MED',  'MED-TIL-100', 'Tilmicosina 300mg/ml Frasco 100ml', 'ml', 500.00, 60.00, 0.2400, NULL),
    ('MED',  'MED-VIT-100', 'Complejo B + Aminoácidos Frasco 100ml', 'ml', 900.00, 100.00, 0.0700, NULL),
    ('MED',  'MED-OXIT-50', 'Oxitocina Sintética 10 UI/ml Frasco 50ml', 'ml', 400.00, 50.00, 0.1000, NULL),
    ('MED',  'MED-PCV2-50', 'Vacuna Circovirus PCV2 + Mycoplasma 50 Dosis', 'dosis', 250.00, 40.00, 0.9500, NULL),
    ('MED',  'MED-IVER-100', 'Ivermectina 1% Porcina Frasco 100ml', 'ml', 650.00, 80.00, 0.0900, NULL),
    -- Kit clínico & Bioseguridad
    ('KIT',  'KIT-GUA-NIT', 'Guantes de Nitrilo Veterinarios (Par)', 'ud', 400.00, 50.00, 0.1800, NULL),
    ('BIO',  'BIO-AMON-01', 'Amonio Cuaternario 5ta Gen. Desinfectante Galón', 'ml', 12000.00, 2000.00, 0.0120, NULL),
    ('BIO',  'BIO-CAL-25',  'Cal Hidratada Desinfección Pediluvios Saco 25kg', 'kg', 500.00, 100.00, 0.2800, NULL)
) AS d(cat_codigo, sku, nombre, unidad, stock, stock_min, costo, silo_codigo)
JOIN categorias_insumos c ON c.codigo = d.cat_codigo
ON CONFLICT (codigo_sku) DO UPDATE
SET categoria_id = EXCLUDED.categoria_id,
    silo_deposito_id = COALESCE(EXCLUDED.silo_deposito_id, insumos_catalogo.silo_deposito_id),
    unidad_medida = EXCLUDED.unidad_medida,
    costo_unitario_promedio_usd = EXCLUDED.costo_unitario_promedio_usd,
    updated_at = NOW();

-- Sincronizar nivel actual de silos si se encuentran en 0 Kg tras instalación limpia
UPDATE silos_depositos s
SET nivel_actual_kg = COALESCE((
        SELECT SUM(i.stock_actual)
        FROM insumos_catalogo i
        WHERE i.silo_deposito_id = s.id AND i.activo = TRUE
    ), 0),
    ultima_sincronizacion = NOW()
WHERE s.nivel_actual_kg = 0;

-- Vincular catálogo de proveedores con insumos existentes cuando falte el insumo_id
UPDATE catalogo_proveedores_productos cpp
SET insumo_id = CASE cpp.codigo_producto
    WHEN 'ALIM-ENG-01'  THEN (SELECT id FROM insumos_catalogo WHERE codigo_sku = 'ALIM-ENG-F2' LIMIT 1)
    WHEN 'ALIM-PREC-01' THEN (SELECT id FROM insumos_catalogo WHERE codigo_sku = 'PRE-INIC-F1' LIMIT 1)
    WHEN 'ALIM-GEST-01' THEN (SELECT id FROM insumos_catalogo WHERE codigo_sku = 'ALIM-GEST-FIB' LIMIT 1)
    WHEN 'MED-HIE-100'  THEN (SELECT id FROM insumos_catalogo WHERE codigo_sku = 'MED-HIE-100' LIMIT 1)
    WHEN 'MED-OXI-100'  THEN (SELECT id FROM insumos_catalogo WHERE codigo_sku = 'MED-OXI-100' LIMIT 1)
    WHEN 'MED-JER-05'   THEN (SELECT id FROM insumos_catalogo WHERE codigo_sku = 'MED-JER-05ML' LIMIT 1)
    ELSE cpp.insumo_id
END
WHERE cpp.insumo_id IS NULL;

-- 4. Reparar relación galpon_id y etapa_zootecnica en jaulas_corrales
UPDATE jaulas_corrales j
SET galpon_id = CASE
    WHEN j.codigo LIKE 'J-MAT-%' OR j.codigo LIKE 'G1-J%' THEN (SELECT id FROM galpones_naves WHERE codigo = 'GALP-01' LIMIT 1)
    WHEN j.codigo LIKE '%GEST%'                          THEN (SELECT id FROM galpones_naves WHERE codigo = 'GALP-02' LIMIT 1)
    WHEN j.codigo LIKE 'C-PREC-%'                        THEN (SELECT id FROM galpones_naves WHERE codigo = 'GALP-03' LIMIT 1)
    WHEN j.codigo LIKE 'C-CREC-%' OR j.codigo LIKE 'C-ENG-%' THEN (SELECT id FROM galpones_naves WHERE codigo = 'GALP-04' LIMIT 1)
    ELSE j.galpon_id
END,
etapa_zootecnica = CASE
    WHEN j.codigo LIKE 'G1-J%' THEN 'Maternidad'
    ELSE j.etapa_zootecnica
END
WHERE j.galpon_id IS NULL OR (j.codigo LIKE 'G1-J%' AND j.etapa_zootecnica = 'Engorde Intensivo');

-- 5. Completar líneas genéticas estándar
INSERT INTO lineas_geneticas (nombre, casa_genetica, potencial_gmd_g_dia, fcr_teorico_objetivo, prolificidad_objetivo_nac_vivos, descripcion)
VALUES
    ('Topigs Norsvin TN70', 'Topigs Norsvin', 980, 2.38, 15.5, 'Línea materna hiperprolífica con alta capacidad lechera y docilidad'),
    ('PIC Camborough', 'PIC', 960, 2.42, 14.8, 'Eficiencia en costo de lechón destetado y rusticidad ambiental'),
    ('DanBred Hybrid', 'DanBred', 1010, 2.32, 16.5, 'Máxima prolificidad mundial con alto índice de magro en canal'),
    ('Pietrain Belga', 'Pietrain Seleccionado', 1040, 2.28, 11.5, 'Línea terminal macho de máximo rendimiento magro en canal'),
    ('Duroc Talent', 'Duroc Internacional', 990, 2.36, 12.0, 'Semental rústico con excelente marmoleo, sabor y vigor al nacer'),
    ('Landrace DanBred', 'DanBred', 950, 2.40, 15.8, 'Madre con sobresaliente instinto materno y producción lechera')
ON CONFLICT (nombre) DO NOTHING;

-- 6. Tabla Maestra: Catálogo de Labores de Campo y Conceptos de Gasto Operativo
CREATE TABLE IF NOT EXISTS catalogo_labores_campo (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    codigo VARCHAR(40) NOT NULL UNIQUE,
    nombre VARCHAR(120) NOT NULL,
    tipo_registro VARCHAR(30) NOT NULL, -- 'LABOR_CAMPO' o 'GASTO_OPERATIVO'
    categoria VARCHAR(50) NOT NULL,
    etapa_sugerida VARCHAR(60),
    galpon_sugerido VARCHAR(30),
    duracion_sugerida VARCHAR(40),
    tarifa_referencia_nio NUMERIC(12,2) NOT NULL DEFAULT 0,
    centro_costo_codigo VARCHAR(20),
    rol_sugerido VARCHAR(40),
    requiere_insumo BOOLEAN NOT NULL DEFAULT FALSE,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    orden INT NOT NULL DEFAULT 100,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

WITH granja_ref AS (
    SELECT COALESCE(
        (SELECT id FROM granjas WHERE codigo = 'GRANJA-SJ-01' LIMIT 1),
        (SELECT id FROM granjas LIMIT 1)
    ) AS id
)
INSERT INTO catalogo_labores_campo (
    granja_id, codigo, nombre, tipo_registro, categoria, etapa_sugerida,
    galpon_sugerido, duracion_sugerida, tarifa_referencia_nio, centro_costo_codigo,
    rol_sugerido, requiere_insumo, orden
)
SELECT
    g.id, d.codigo, d.nombre, d.tipo_registro, d.categoria, d.etapa_sugerida,
    d.galpon_sugerido, d.duracion_sugerida, d.tarifa_nio, d.cc_codigo,
    d.rol_sugerido, d.req_insumo, d.orden
FROM granja_ref g
CROSS JOIN (VALUES
    -- Labores de campo estándar (LABOR_CAMPO)
    ('LAB-LIMP-GALP',   'Limpieza y lavado a presión de corrales',        'LABOR_CAMPO',     'Bioseguridad',     'Todas',       'GALP-04', 'Medio día (4h)',   160.00, 'CC-NOM',  'Operario',    FALSE, 10),
    ('LAB-DESINF-PED',  'Desinfección, calado y recarga de pediluvios',   'LABOR_CAMPO',     'Bioseguridad',     'Todas',       'GALP-01', '2 horas (0.25j)',  80.00,  'CC-SAN',  'Operario',    TRUE,  20),
    ('LAB-ALIM-MAN',    'Distribución y calibración de tolvas de comida', 'LABOR_CAMPO',     'Alimentación',     'Precebo',     'GALP-03', '2 horas (0.25j)',  80.00,  'CC-ALIM', 'Operario',    FALSE, 30),
    ('LAB-PESAJE-LOT',  'Pesaje muestral y control GMD de lote',          'LABOR_CAMPO',     'Manejo',           'Crecimiento', 'GALP-04', 'Medio día (4h)',   160.00, 'CC-NOM',  'Operario',    FALSE, 40),
    ('LAB-ASIST-PARTO', 'Vigilancia de partos y secado de lechones',      'LABOR_CAMPO',     'Maternidad',       'Maternidad',  'GALP-01', 'Jornal completo',  320.00, 'CC-NOM',  'Veterinario', FALSE, 50),
    ('LAB-DETEC-CELO',  'Detección de celo y recela con verraco',         'LABOR_CAMPO',     'Reproducción',     'Gestación',   'GALP-02', '2 horas (0.25j)',  80.00,  'CC-NOM',  'Veterinario', FALSE, 60),
    ('LAB-VAC-LOTE',    'Jornada de vacunación y desparasitación grupal', 'LABOR_CAMPO',     'Sanidad',          'Precebo',     'GALP-03', 'Medio día (4h)',   160.00, 'CC-SAN',  'Veterinario', TRUE,  70),
    ('LAB-MANEJO-LECH', 'Descolmillado, corte de cola y muesqueo',        'LABOR_CAMPO',     'Maternidad',       'Maternidad',  'GALP-01', '2 horas (0.25j)',  80.00,  'CC-NOM',  'Veterinario', TRUE,  80),
    ('LAB-MANT-HIDR',   'Revisión de chupones, bebederos y cortinas',     'LABOR_CAMPO',     'Mantenimiento',    'Todas',       'GALP-04', '2 horas (0.25j)',  80.00,  'CC-MANT', 'Operario',    FALSE, 90),
    ('LAB-MOV-CORRAL',  'Traslado y reagrupación de cerdos entre corrales','LABOR_CAMPO',    'Manejo',           'Crecimiento', 'GALP-03', 'Medio día (4h)',   160.00, 'CC-NOM',  'Operario',    FALSE, 100),
    -- Conceptos estandarizados de gasto operativo (GASTO_OPERATIVO)
    ('GAS-JORNAL-EXT',  'Pago de jornal temporal adicional de campo',      'GASTO_OPERATIVO', 'Mano de Obra',     'Todas',       'GALP-04', 'Jornal completo',  320.00, 'CC-NOM',  'Administrador', FALSE, 110),
    ('GAS-CAL-DESINF',  'Compra local de cal viva, cloro o jabón yodado', 'GASTO_OPERATIVO', 'Sanidad/Limpieza', 'Todas',       'GALP-01', NULL,               450.00, 'CC-SAN',  'Administrador', FALSE, 120),
    ('GAS-REP-CHUPETE', 'Repuestos menores: chupones inox, manguera, llaves','GASTO_OPERATIVO','Mantenimiento',   'Todas',       'GALP-03', NULL,               380.00, 'CC-MANT', 'Administrador', FALSE, 130),
    ('GAS-BOMB-TERM',   'Bombillos infrarrojos / calefacción maternidad', 'GASTO_OPERATIVO', 'Mantenimiento',    'Maternidad',  'GALP-01', NULL,               550.00, 'CC-MANT', 'Administrador', FALSE, 140),
    ('GAS-COMBUST-GEN', 'Combustible motobomba lavado y planta eléctrica','GASTO_OPERATIVO', 'Combustible/Agua', 'Todas',       'GALP-04', NULL,               750.00, 'CC-SERV', 'Administrador', FALSE, 150),
    ('GAS-FLETE-INS',   'Flete o acarreo de sacos de alimento / insumos', 'GASTO_OPERATIVO', 'Transporte',       'Todas',       'GALP-04', NULL,               600.00, 'CC-ALIM', 'Administrador', FALSE, 160),
    ('GAS-ENERG-AGUA',  'Recibo energía eléctrica / bombeo de agua',      'GASTO_OPERATIVO', 'Servicios',        'Todas',       'GALP-01', NULL,               1850.00,'CC-SERV', 'Administrador', FALSE, 170),
    ('GAS-HONOR-VET',   'Asistencia clínica / diagnóstico laboratorio ext.','GASTO_OPERATIVO','Otros',           'Todas',       'GALP-01', NULL,               1200.00,'CC-VET',  'Administrador', FALSE, 180)
) AS d(codigo, nombre, tipo_registro, categoria, etapa_sugerida, galpon_sugerido, duracion_sugerida, tarifa_nio, cc_codigo, rol_sugerido, req_insumo, orden)
ON CONFLICT (codigo) DO UPDATE
SET nombre = EXCLUDED.nombre,
    tipo_registro = EXCLUDED.tipo_registro,
    categoria = EXCLUDED.categoria,
    etapa_sugerida = EXCLUDED.etapa_sugerida,
    galpon_sugerido = EXCLUDED.galpon_sugerido,
    duracion_sugerida = EXCLUDED.duracion_sugerida,
    tarifa_referencia_nio = EXCLUDED.tarifa_referencia_nio,
    centro_costo_codigo = EXCLUDED.centro_costo_codigo,
    rol_sugerido = EXCLUDED.rol_sugerido,
    requiere_insumo = EXCLUDED.requiere_insumo,
    orden = EXCLUDED.orden,
    activo = TRUE;

-- 7. Tabla Maestra: Catálogo de Horarios, Turnos, Tomas de Alimento, Dosis IA y Plantillas de Agenda
CREATE TABLE IF NOT EXISTS catalogo_horarios_turnos (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    codigo VARCHAR(40) NOT NULL UNIQUE,
    nombre VARCHAR(120) NOT NULL,
    tipo_horario VARCHAR(30) NOT NULL, -- 'TURNO_JORNADA' | 'TOMA_ALIMENTO' | 'PLANTILLA_AGENDA' | 'DOSIS_IA'
    hora_inicio VARCHAR(10) NOT NULL,
    hora_fin VARCHAR(10),
    categoria_actividad VARCHAR(40),
    etapa_aplicable VARCHAR(60),
    rol_responsable VARCHAR(40),
    prioridad_default VARCHAR(20) NOT NULL DEFAULT 'Media',
    descripcion VARCHAR(250),
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    orden INT NOT NULL DEFAULT 100
);

WITH granja_ref AS (
    SELECT COALESCE(
        (SELECT id FROM granjas WHERE codigo = 'GRANJA-SJ-01' LIMIT 1),
        (SELECT id FROM granjas LIMIT 1)
    ) AS id
)
INSERT INTO catalogo_horarios_turnos (
    granja_id, codigo, nombre, tipo_horario, hora_inicio, hora_fin,
    categoria_actividad, etapa_aplicable, rol_responsable, prioridad_default, descripcion, orden
)
SELECT
    g.id, d.codigo, d.nombre, d.tipo_horario, d.h_ini, d.h_fin,
    d.cat, d.etapa, d.rol, d.prio, d.descr, d.orden
FROM granja_ref g
CROSS JOIN (VALUES
    -- Turnos de jornada laboral
    ('TUR-MAN',       'Turno Mañana (06:00 - 14:00)',                'TURNO_JORNADA',    '06:00', '14:00', 'Manejo',       'Todas',       'Operario',    'Media',   'Primera jornada operativa: ración AM, aseo y rondas sanitarias.', 10),
    ('TUR-TAR',       'Turno Tarde (14:00 - 20:00)',                 'TURNO_JORNADA',    '14:00', '20:00', 'Manejo',       'Todas',       'Operario',    'Media',   'Segunda jornada operativa: ración PM, ventilación y cierre.', 20),
    ('TUR-NOC',       'Turno Guardia Nocturna (20:00 - 06:00)',      'TURNO_JORNADA',    '20:00', '06:00', 'Reproduccion', 'Maternidad',  'Operario',    'Alta',    'Vigilancia de partos inminentes, calefacción y tomas nocturnas.', 30),
    ('TUR-COMP',      'Jornada Técnica Diurna (07:00 - 16:00)',      'TURNO_JORNADA',    '07:00', '16:00', 'Sanidad',      'Todas',       'Veterinario', 'Media',   'Horario administrativo y dirección veterinaria.', 40),
    -- Horarios de toma de alimento
    ('ALIM-0630',     'Toma 1 · Mañana Temprano (06:30)',            'TOMA_ALIMENTO',    '06:30', '07:30', 'Alimentacion', 'Todas',       'Operario',    'Alta',    'Primera ración del día tras verificación de agua y comederos.', 50),
    ('ALIM-1030',     'Toma 2 · Media Mañana Lechones (10:30)',      'TOMA_ALIMENTO',    '10:30', '11:00', 'Alimentacion', 'Precebo',     'Operario',    'Media',   'Refuerzo de preiniciador fresco para destetados.', 60),
    ('ALIM-1430',     'Toma 3 · Tarde Lechones / Nodrizas (14:30)',  'TOMA_ALIMENTO',    '14:30', '15:00', 'Alimentacion', 'Maternidad',  'Operario',    'Media',   'Tercera ración para cerdas lactantes y recría.', 70),
    ('ALIM-1630',     'Toma 4 · Cierre Vespertino (16:30)',          'TOMA_ALIMENTO',    '16:30', '17:30', 'Alimentacion', 'Engorde',     'Operario',    'Alta',    'Segunda ración principal de crecimiento, engorde y gestación.', 80),
    ('ALIM-1900',     'Toma 5 · Nocturna Maternidad (19:00)',        'TOMA_ALIMENTO',    '19:00', '19:30', 'Alimentacion', 'Maternidad',  'Operario',    'Media',   'Estímulo nocturno de consumo en sala de lactancia.', 90),
    -- Turnos/Dosis IA
    ('IA-1-MAN',      '1ra Dosis Inseminación · Mañana (07:00)',     'DOSIS_IA',         '07:00', '09:00', 'Reproduccion', 'Gestación',   'Veterinario', 'Alta',    'Primera aplicación seminal al detectar reflejo de inmovilidad.', 100),
    ('IA-2-TAR',      '2da Dosis Inseminación · Tarde +12h (17:00)', 'DOSIS_IA',         '17:00', '19:00', 'Reproduccion', 'Gestación',   'Veterinario', 'Alta',    'Segunda dosis de refuerzo ovulatorio.', 110),
    ('IA-3-REF',      '3ra Dosis Refuerzo · Mañana Día +1 (07:00)',  'DOSIS_IA',         '07:00', '08:30', 'Reproduccion', 'Gestación',   'Veterinario', 'Media',   'Refuerzo tardío si persiste celo franco.', 120),
    -- Plantillas estandarizadas de Agenda (para evitar escribir títulos manuales)
    ('AGD-RACION-AM', 'Suministro Ración AM y Chequeo Bebederos',    'PLANTILLA_AGENDA', '06:30', '08:00', 'Alimentacion', 'Todas',       'Operario',    'Alta',    'Dispensar alimento según curva de cada lote y verificar flujo en chupones.', 130),
    ('AGD-RONDA-MAT', 'Ronda de Maternidad, Calostro y Neonatos',    'PLANTILLA_AGENDA', '07:00', '08:30', 'Reproduccion', 'Maternidad',  'Veterinario', 'Urgente', 'Control de temperatura de lechoneras, vitalidad y consumo de nodrizas.', 140),
    ('AGD-VAC-HIERRO','Aplicación de Hierro Dextrano + Toltrazuril', 'PLANTILLA_AGENDA', '08:30', '10:00', 'Sanidad',      'Maternidad',  'Veterinario', 'Alta',    'Protocolo Día 3 de vida para camadas recién nacidas.', 150),
    ('AGD-ASEO-NAVE', 'Lavado, Desinfección y Recarga de Pediluvios','PLANTILLA_AGENDA', '09:30', '11:30', 'Bioseguridad', 'Todas',       'Operario',    'Media',   'Higienización de pasillos, corrales y control de amonio cuaternario.', 160),
    ('AGD-ECO-28D',   'Ecografía de Diagnóstico de Gestación (D+28)','PLANTILLA_AGENDA', '10:30', '12:00', 'Reproduccion', 'Gestación',   'Veterinario', 'Alta',    'Confirmación ultrasónica de hembras servidas y detección de repetición.', 170),
    ('AGD-PESAJE-SEM','Pesaje Muestral Semanal y Calibración GMD',   'PLANTILLA_AGENDA', '13:30', '15:30', 'Manejo',       'Engorde',     'Operario',    'Media',   'Muestreo de peso por corral para ajuste de ración diaria.', 180),
    ('AGD-RACION-PM', 'Suministro Ración PM y Control Ventilación',  'PLANTILLA_AGENDA', '16:30', '17:30', 'Alimentacion', 'Todas',       'Operario',    'Alta',    'Segunda entrega diaria de balanceado y ajuste de cortinas/extractores.', 190),
    ('AGD-AUD-SILOS', 'Lectura de Nivel de Silos y Stock Kardex',    'PLANTILLA_AGENDA', '17:30', '18:30', 'Mantenimiento','Todas',       'Administrador','Media',  'Conciliación diaria de kilos dispensados vs existencia física.', 200)
) AS d(codigo, nombre, tipo_horario, h_ini, h_fin, cat, etapa, rol, prio, descr, orden)
ON CONFLICT (codigo) DO UPDATE
SET nombre = EXCLUDED.nombre,
    tipo_horario = EXCLUDED.tipo_horario,
    hora_inicio = EXCLUDED.hora_inicio,
    hora_fin = EXCLUDED.hora_fin,
    categoria_actividad = EXCLUDED.categoria_actividad,
    etapa_aplicable = EXCLUDED.etapa_aplicable,
    rol_responsable = EXCLUDED.rol_responsable,
    prioridad_default = EXCLUDED.prioridad_default,
    descripcion = EXCLUDED.descripcion,
    orden = EXCLUDED.orden,
    activo = TRUE;

-- 8. Tabla Maestra: Catálogos Clínicos, Sanitarios y Zootécnicos
CREATE TABLE IF NOT EXISTS catalogo_clinico_sanitario (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    granja_id UUID NOT NULL REFERENCES granjas(id) ON DELETE CASCADE,
    codigo VARCHAR(40) NOT NULL UNIQUE,
    tipo_catalogo VARCHAR(35) NOT NULL, -- 'CAUSA_MORTALIDAD' | 'DIAGNOSTICO_CLINICO' | 'VIA_ADMINISTRACION' | 'TECNICA_IA' | 'ETAPA_ZOOTECNICA'
    nombre VARCHAR(120) NOT NULL,
    subcategoria VARCHAR(60),
    dias_retiro_referencia INT NOT NULL DEFAULT 0,
    activo BOOLEAN NOT NULL DEFAULT TRUE,
    orden INT NOT NULL DEFAULT 100
);

WITH granja_ref AS (
    SELECT COALESCE(
        (SELECT id FROM granjas WHERE codigo = 'GRANJA-SJ-01' LIMIT 1),
        (SELECT id FROM granjas LIMIT 1)
    ) AS id
)
INSERT INTO catalogo_clinico_sanitario (
    granja_id, codigo, tipo_catalogo, nombre, subcategoria, dias_retiro_referencia, orden
)
SELECT
    g.id, d.codigo, d.tipo, d.nombre, d.subcat, d.retiro, d.orden
FROM granja_ref g
CROSS JOIN (VALUES
    -- Causas de mortalidad (CAUSA_MORTALIDAD)
    ('MORT-APLAST',  'CAUSA_MORTALIDAD',    'Aplastamiento perinatal por madre',      'Maternidad', 0, 10),
    ('MORT-INANIC',  'CAUSA_MORTALIDAD',    'Inanición / Bajo peso al nacer (<0.9kg)','Maternidad', 0, 20),
    ('MORT-NEUMON',  'CAUSA_MORTALIDAD',    'Complejo respiratorio / Neumonía aguda', 'Precebo',    0, 30),
    ('MORT-DIARR',   'CAUSA_MORTALIDAD',    'Colibacilosis / Diarrea severa neonatal','Maternidad', 0, 40),
    ('MORT-INFEC',   'CAUSA_MORTALIDAD',    'Septicemia / Infección estreptocócica',  'Todas',      0, 50),
    ('MORT-PROLAP',  'CAUSA_MORTALIDAD',    'Prolapso o torsión gástrica aguda',      'Engorde',    0, 60),
    ('MORT-CARDIO',  'CAUSA_MORTALIDAD',    'Muerte súbita / Estrés cardiovascular',  'Engorde',    0, 70),
    ('MORT-OTRA',    'CAUSA_MORTALIDAD',    'Accidente traumático / Sacrificio hum.', 'Todas',      0, 80),
    -- Diagnósticos clínicos frecuentes (DIAGNOSTICO_CLINICO)
    ('DIAG-ANEMIA',  'DIAGNOSTICO_CLINICO', 'Profilaxis férrica / Anemia neonatal',   'Preventivo', 0, 10),
    ('DIAG-RESP',    'DIAGNOSTICO_CLINICO', 'Infección respiratoria (Mycoplasma/APP)','Respiratorio',14, 20),
    ('DIAG-DIGEST',  'DIAGNOSTICO_CLINICO', 'Enteritis bacteriana / Diarrea post-dest.','Digestivo',10, 30),
    ('DIAG-MASTIT',  'DIAGNOSTICO_CLINICO', 'Síndrome MMA (Metritis-Mastitis-Agalactia)','Maternidad',7,40),
    ('DIAG-PODAL',   'DIAGNOSTICO_CLINICO', 'Cojera / Inflamación articular y podal', 'Locomotor',  5, 50),
    ('DIAG-PARASIT', 'DIAGNOSTICO_CLINICO', 'Control de endoparásitos y sarna',       'Preventivo', 18,60),
    ('DIAG-VACUNA',  'DIAGNOSTICO_CLINICO', 'Inmunización programada calendario oficial','Biológico',0, 70),
    ('DIAG-PARTO',   'DIAGNOSTICO_CLINICO', 'Asistencia obstétrica / Inducción oxitócica','Maternidad',0,80),
    -- Vías de administración (VIA_ADMINISTRACION)
    ('VIA-IM',       'VIA_ADMINISTRACION',  'Intramuscular profunda (IM)',             'Parenteral', 0, 10),
    ('VIA-SC',       'VIA_ADMINISTRACION',  'Subcutánea (SC retroauricular)',         'Parenteral', 0, 20),
    ('VIA-VO',       'VIA_ADMINISTRACION',  'Vía Oral (VO / Agua de bebida)',         'Oral',       0, 30),
    ('VIA-TOP',      'VIA_ADMINISTRACION',  'Tópica / Desinfección cutánea',          'Externa',    0, 40),
    ('VIA-IV',       'VIA_ADMINISTRACION',  'Intravenosa auricular (IV)',             'Parenteral', 0, 50),
    -- Técnicas IA (TECNICA_IA)
    ('IA-PCAI',      'TECNICA_IA',          'Post-Cervical (PCAI)',                   'inseminacion_post_cervical', 0, 10),
    ('IA-TRAD',      'TECNICA_IA',          'Cervical Clásica Tradicional',           'inseminacion_cervical',      0, 20),
    ('IA-MONTA',     'TECNICA_IA',          'Monta Natural Dirigida',                 'monta_natural',              0, 30),
    -- Etapas zootécnicas oficiales (ETAPA_ZOOTECNICA)
    ('ETP-MAT',      'ETAPA_ZOOTECNICA',    'Maternidad',                             '0 - 21 días',    0, 10),
    ('ETP-PREC',     'ETAPA_ZOOTECNICA',    'Precebo',                                '22 - 70 días',   0, 20),
    ('ETP-CREC',     'ETAPA_ZOOTECNICA',    'Crecimiento',                            '71 - 115 días',  0, 30),
    ('ETP-ENG',      'ETAPA_ZOOTECNICA',    'Engorde',                                '116 - 165 días', 0, 40),
    ('ETP-GEST',     'ETAPA_ZOOTECNICA',    'Gestación',                              'Reproductoras',  0, 50),
    ('ETP-REEMP',    'ETAPA_ZOOTECNICA',    'Reemplazo',                              'Primerizas',     0, 60)
) AS d(codigo, tipo, nombre, subcat, retiro, orden)
ON CONFLICT (codigo) DO UPDATE
SET nombre = EXCLUDED.nombre,
    tipo_catalogo = EXCLUDED.tipo_catalogo,
    subcategoria = EXCLUDED.subcategoria,
    dias_retiro_referencia = EXCLUDED.dias_retiro_referencia,
    orden = EXCLUDED.orden,
    activo = TRUE;

-- 9. Tabla de Preferencias y Personalización UI por Usuario / Rol
CREATE TABLE IF NOT EXISTS preferencias_ui_usuario (
    usuario_id UUID PRIMARY KEY REFERENCES usuarios_operadores(id) ON DELETE CASCADE,
    rol_base VARCHAR(40) NOT NULL,
    modulos_visibles_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    acciones_rapidas_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    nav_inferior_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    galpon_predeterminado VARCHAR(30),
    turno_predeterminado VARCHAR(40),
    moneda_preferida VARCHAR(10) NOT NULL DEFAULT 'NIO',
    modo_compacto BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 10. Semilla operacional condicional (SOLO si la tabla está vacía) para habilitar todos los selectores reales
INSERT INTO lotes_produccion (
    granja_id, codigo_lote, galpon_id, etapa_zootecnica, fase_actual,
    fecha_inicio, fecha_ingreso_fase, fecha_cierre_estimada,
    cantidad_inicial_cabezas, cantidad_inicial, cantidad_actual_cabezas, cantidad_actual,
    peso_promedio_entrada_kg, peso_promedio_actual_kg, peso_ingreso_total_kg, peso_actual_total_kg,
    peso_objetivo_salida_kg, linea_genetica_id, fcr_actual, gmd_actual_g_dia,
    presupuesto_asignado_usd, estado
)
SELECT
    'a0000000-0000-0000-0000-000000000001'::uuid,
    d.codigo,
    (SELECT id FROM galpones_naves WHERE codigo = d.galpon_cod LIMIT 1),
    d.etapa,
    d.fase,
    CURRENT_DATE - d.dias,
    CURRENT_DATE - d.dias,
    CURRENT_DATE + (150 - d.dias),
    d.cab, d.cab, d.cab, d.cab,
    d.peso_ini, d.peso_act, ROUND(d.cab * d.peso_ini, 2), ROUND(d.cab * d.peso_act, 2),
    d.peso_obj,
    (SELECT id FROM lineas_geneticas WHERE nombre = 'Topigs Norsvin TN70' LIMIT 1),
    d.fcr, d.gmd, d.presup, 'activo'
FROM (VALUES
    ('LOT-MAT-01',  'GALP-01', 'Maternidad',        'maternidad',        14, 48, 1.45, 4.20,  6.50,  1.25, 290, 950.00),
    ('LOT-PREC-01', 'GALP-03', 'Precebo',           'precebo',           35, 90, 6.20, 14.50, 28.00, 1.68, 440, 2400.00),
    ('LOT-CREC-01', 'GALP-04', 'Crecimiento',       'crecimiento',       72, 75, 28.0, 52.40, 65.00, 2.15, 760, 4800.00),
    ('LOT-ENG-01',  'GALP-04', 'Engorde Intensivo', 'engorde_intensivo', 110,60, 65.0, 91.20, 115.0, 2.48, 920, 7200.00)
) AS d(codigo, galpon_cod, etapa, fase, dias, cab, peso_ini, peso_act, peso_obj, fcr, gmd, presup)
WHERE NOT EXISTS (SELECT 1 FROM lotes_produccion)
ON CONFLICT (codigo_lote) DO NOTHING;

INSERT INTO verracos_semen (
    granja_id, codigo, nombre, linea_genetica_id, tipo_reproductor,
    viabilidad_espermatica_pct, concentracion_billones, lote_dosis, activo
)
SELECT
    'a0000000-0000-0000-0000-000000000001'::uuid,
    d.codigo, d.nombre,
    (SELECT id FROM lineas_geneticas WHERE nombre = d.gen LIMIT 1),
    d.tipo, d.viab, d.conc, d.lote, TRUE
FROM (VALUES
    ('VER-DUROC-01', 'Duroc Talent #01',  'Duroc Talent',  'Dosis Refrigerada IA', 89.5, 2.2, 'SEM-2026-D01'),
    ('VER-PIET-02',  'Pietrain Magro #02','Pietrain Belga','Dosis Refrigerada IA', 91.0, 2.5, 'SEM-2026-P02'),
    ('VER-LAND-03',  'Landrace Abuelo #03','Landrace DanBred','Monta / IA Mixta', 87.0, 2.0, 'SEM-2026-L03')
) AS d(codigo, nombre, gen, tipo, viab, conc, lote)
WHERE NOT EXISTS (SELECT 1 FROM verracos_semen)
ON CONFLICT (codigo) DO NOTHING;

INSERT INTO cerdas_reproductoras (
    granja_id, caravana, rfid, linea_genetica_id, galpon_id, jaula_numero,
    fecha_nacimiento, paridad_acumulada, estado_fisiologico, estatus_sanitario, prolificidad_historica_media, activa
)
SELECT
    'a0000000-0000-0000-0000-000000000001'::uuid,
    d.caravana, d.rfid,
    (SELECT id FROM lineas_geneticas WHERE nombre = 'Topigs Norsvin TN70' LIMIT 1),
    (SELECT id FROM galpones_naves WHERE codigo = d.galpon_cod LIMIT 1),
    d.jaula,
    CURRENT_DATE - 420,
    d.paridad, d.estado, 'optimo', d.prolif, TRUE
FROM (VALUES
    ('CR-101', 'RFID-CR101', 'GALP-01', 'J-MAT-01',  3, 'lactante',           14.2),
    ('CR-102', 'RFID-CR102', 'GALP-01', 'J-MAT-02',  2, 'lactante',           13.8),
    ('CR-103', 'RFID-CR103', 'GALP-02', 'J-GEST-01', 4, 'gestante_confirmada',14.5),
    ('CR-104', 'RFID-CR104', 'GALP-02', 'J-GEST-02', 2, 'gestante_confirmada',13.5),
    ('CR-105', 'RFID-CR105', 'GALP-02', 'C-GEST-01', 1, 'vacia_espera_celo',  12.6),
    ('CR-106', 'RFID-CR106', 'GALP-02', 'C-GEST-01', 0, 'primeriza_reemplazo', 0.0)
) AS d(caravana, rfid, galpon_cod, jaula, paridad, estado, prolif)
WHERE NOT EXISTS (SELECT 1 FROM cerdas_reproductoras)
ON CONFLICT (caravana) DO NOTHING;

INSERT INTO cerdos_individuales (
    granja_id, crotal, rfid, sexo, fecha_nacimiento, peso_actual_kg,
    etapa_actual, jaula_corral_id, galpon_id, lote_id, linea_genetica, estatus_sanitario, activo
)
SELECT
    'a0000000-0000-0000-0000-000000000001'::uuid,
    d.crotal, d.rfid, d.sexo, CURRENT_DATE - d.edad_dias, d.peso,
    d.etapa,
    (SELECT id FROM jaulas_corrales WHERE codigo = d.jaula_cod LIMIT 1),
    (SELECT id FROM galpones_naves WHERE codigo = d.galpon_cod LIMIT 1),
    (SELECT id FROM lotes_produccion WHERE codigo_lote = d.lote_cod LIMIT 1),
    d.genetica, d.sanitario, TRUE
FROM (VALUES
    ('PC-201', 'RFID-201', 'Hembra',   14,  4.40,  'Maternidad',  'J-MAT-01',  'GALP-01', 'LOT-MAT-01',  'Topigs Norsvin TN70', 'Saludable'),
    ('PC-202', 'RFID-202', 'Castrado', 14,  4.10,  'Maternidad',  'J-MAT-02',  'GALP-01', 'LOT-MAT-01',  'Topigs Norsvin TN70', 'Saludable'),
    ('PC-203', 'RFID-203', 'Hembra',   36,  12.80, 'Precebo',     'C-PREC-01', 'GALP-03', 'LOT-PREC-01', 'PIC Camborough',      'Saludable'),
    ('PC-204', 'RFID-204', 'Castrado', 38,  13.50, 'Precebo',     'C-PREC-01', 'GALP-03', 'LOT-PREC-01', 'PIC Camborough',      'Observación'),
    ('PC-205', 'RFID-205', 'Hembra',   74,  39.00, 'Crecimiento', 'C-CREC-01', 'GALP-04', 'LOT-CREC-01', 'DanBred Hybrid',      'Saludable'),
    ('PC-206', 'RFID-206', 'Castrado', 78,  41.50, 'Crecimiento', 'C-CREC-02', 'GALP-04', 'LOT-CREC-01', 'Duroc Talent',        'Saludable'),
    ('PC-207', 'RFID-207', 'Hembra',   122, 88.40, 'Engorde',     'C-ENG-01',  'GALP-04', 'LOT-ENG-01',  'Pietrain Belga',      'Saludable'),
    ('PC-208', 'RFID-208', 'Castrado', 126, 94.20, 'Engorde',     'C-ENG-01',  'GALP-04', 'LOT-ENG-01',  'Pietrain Belga',      'En Tratamiento')
) AS d(crotal, rfid, sexo, edad_dias, peso, etapa, jaula_cod, galpon_cod, lote_cod, genetica, sanitario)
WHERE NOT EXISTS (SELECT 1 FROM cerdos_individuales)
ON CONFLICT (crotal) DO NOTHING;

-- Recalcular ocupación real de jaulas según padrón de cerdos_individuales
UPDATE jaulas_corrales j
SET ocupacion_actual = COALESCE((
    SELECT COUNT(*) FROM cerdos_individuales c WHERE c.jaula_corral_id = j.id AND c.activo = TRUE
), 0)
WHERE EXISTS (SELECT 1 FROM cerdos_individuales c WHERE c.jaula_corral_id = j.id AND c.activo = TRUE);

INSERT INTO protocolos_sanitarios (codigo_plan, nombre, etapa_aplicacion, dia_vida_aplicacion, tipo_biologico, dosis_recomendada, via_administracion)
SELECT d.cod, d.nom, d.etapa, d.dia, d.bio, d.dosis, d.via
FROM (VALUES
    ('PROT-D03', 'Hierro Dextrano + Anticoccidial Neonatal', 'Maternidad', 3,  'Hierro Dextrano 200mg + Toltrazuril', '2.0 ml + 1.0 ml', 'Intramuscular / Oral'),
    ('PROT-D07', 'Mycoplasma hyopneumoniae 1ra Dosis',       'Maternidad', 7,  'Bacterina Mycoplasma',                '2.0 ml',          'Intramuscular'),
    ('PROT-D21', 'Circovirus PCV2 + Mycoplasma Refuerzo',    'Precebo',    21, 'Vacuna Bivalente PCV2+M.hyo',         '2.0 ml',          'Intramuscular'),
    ('PROT-D35', 'Inmunización Peste Porcina Clásica (PPC)', 'Precebo',    35, 'Vacuna PPC Cepa China',               '2.0 ml',          'Intramuscular profunda'),
    ('PROT-D70', 'Desparasitación Interna y Externa',        'Crecimiento',70, 'Ivermectina 1% Porcina',              '1.0 ml / 33 kg',  'Subcutánea')
) AS d(cod, nom, etapa, dia, bio, dosis, via)
WHERE NOT EXISTS (SELECT 1 FROM protocolos_sanitarios)
ON CONFLICT (codigo_plan) DO NOTHING;

INSERT INTO tareas_actividades_granja (
    granja_id, titulo, descripcion, tipo_actividad, prioridad, estado,
    fecha_programada, hora_programada, galpon_nombre, lote_codigo, asignado_a_nombre, asignado_a_rol
)
SELECT
    'a0000000-0000-0000-0000-000000000001'::uuid,
    d.titulo, d.descr, d.tipo, d.prio, 'pendiente',
    CURRENT_DATE, d.hora, d.galpon, d.lote, d.asig, d.rol
FROM (VALUES
    ('Suministro Ración AM y Chequeo Bebederos',     'Dispensar primera toma de Preiniciador II y Crecimiento desde silos.', 'Alimentacion', 'Alta',    '06:30', 'Galpón 3 - Precebo / Destete',     'LOT-PREC-01', 'Jorge Jr. (Operario de Campo)', 'Operario'),
    ('Ronda de Maternidad, Calostro y Neonatos',     'Control térmico de lechoneras e inspección de cerdas CR-101 y CR-102.', 'Reproduccion', 'Urgente', '07:00', 'Galpón 1 - Maternidad & Lactancia', 'LOT-MAT-01',  'Junior MVZ',                    'Veterinario'),
    ('Aplicación de Hierro Dextrano + Toltrazuril',  'Protocolo profiláctico neonatal en jaulas J-MAT-01 y J-MAT-02.',      'Sanidad',      'Alta',    '08:30', 'Galpón 1 - Maternidad & Lactancia', 'LOT-MAT-01',  'Junior MVZ',                    'Veterinario'),
    ('Lavado, Desinfección y Recarga de Pediluvios', 'Limpieza de pasillos y recarga de amonio cuaternario en Galpón 4.',   'Bioseguridad', 'Media',   '10:00', 'Galpón 4 - Crecimiento y Engorde',  'LOT-ENG-01',  'Encargado de Galpones',         'Operario')
) AS d(titulo, descr, tipo, prio, hora, galpon, lote, asig, rol)
WHERE NOT EXISTS (SELECT 1 FROM tareas_actividades_granja WHERE fecha_programada = CURRENT_DATE);
