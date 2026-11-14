SELECT 'lotes_produccion' as tabla, count(*) as total FROM lotes_produccion
UNION ALL
SELECT 'cerdas_reproductoras', count(*) FROM cerdas_reproductoras
UNION ALL
SELECT 'insumos_catalogo', count(*) FROM insumos_catalogo
UNION ALL
SELECT 'presupuestos_lote', count(*) FROM presupuestos_lote
UNION ALL
SELECT 'v_radar_partos_inminentes', count(*) FROM v_radar_partos_inminentes;
