-- =============================================================================
-- OpenICE/MDPNP TimescaleDB Schema
-- =============================================================================
-- Este archivo define el esquema de base de datos para almacenar datos de
-- dispositivos médicos (tanto simulados como reales) en PostgreSQL utilizando
-- la extensión de serie temporal TimescaleDB.
--
-- Se utiliza un modelo unificado en lugar de una tabla por dispositivo, lo cual
-- es la mejor práctica recomendada en TimescaleDB. Esto evita la fragmentación de
-- esquemas, optimiza el almacenamiento en disco y permite consultas unificadas.
-- =============================================================================

-- 1. Habilitar la extensión de TimescaleDB
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- =============================================================================
-- TABLAS DE DIMENSIONES (Estructuras Relacionales Estándar)
-- =============================================================================

-- Tabla de registro de dispositivos (simulados y reales)
CREATE TABLE devices (
    device_id VARCHAR(64) PRIMARY KEY,        -- Representa el Unique Device Identifier (UDI)
    manufacturer VARCHAR(128) NOT NULL,       -- Fabricante (ej. 'ICE', 'Philips', 'Dräger')
    model VARCHAR(128) NOT NULL,              -- Modelo (ej. 'Multiparameter', 'EvitaXL')
    serial_number VARCHAR(128),               -- Número de serie
    connection_type VARCHAR(32) NOT NULL,     -- 'Simulated', 'Serial', 'Network'
    build_info VARCHAR(128),                  -- Información de compilación/firmware
    operating_system VARCHAR(128),            -- Sistema operativo del adaptador
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Tabla de pacientes (Medical Record Number - MRN)
CREATE TABLE patients (
    patient_id VARCHAR(64) PRIMARY KEY,       -- ID del paciente (MRN)
    first_name VARCHAR(128) NOT NULL,
    last_name VARCHAR(128) NOT NULL,
    gender CHAR(1) CHECK (gender IN ('M', 'F', 'O')),
    dob DATE NOT NULL,                        -- Fecha de nacimiento
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Sesiones de asociación entre paciente y dispositivo
CREATE TABLE patient_device_sessions (
    session_id SERIAL PRIMARY KEY,
    device_id VARCHAR(64) NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    patient_id VARCHAR(64) NOT NULL REFERENCES patients(patient_id) ON DELETE CASCADE,
    start_time TIMESTAMP WITH TIME ZONE NOT NULL,
    end_time TIMESTAMP WITH TIME ZONE,
    CONSTRAINT check_session_dates CHECK (end_time IS NULL OR end_time >= start_time)
);

-- Catálogo de métricas clínicas bajo la nomenclatura ISO/IEEE 11073-10101 / Rosetta
CREATE TABLE metric_types (
    metric_id VARCHAR(64) PRIMARY KEY,        -- ID oficial (ej. MDC_SPO2, MDC_ECG_HEART_RATE)
    display_name VARCHAR(128) NOT NULL,       -- Nombre descriptivo (ej. 'Saturación de Oxígeno')
    default_unit VARCHAR(32) NOT NULL,        -- Unidad por defecto (ej. '%', 'bpm', 'mmHg')
    loinc_code VARCHAR(32),                   -- Código LOINC correspondiente para interoperabilidad (ej. '8867-4')
    ucum_code VARCHAR(32)                     -- Unidad estandarizada en formato UCUM (ej. '/min', 'mm[Hg]', 'Cel')
);


-- =============================================================================
-- TABLAS DE SERIES TEMPORALES (Hypertables de TimescaleDB)
-- =============================================================================

-- A. Registros de conectividad de los dispositivos
CREATE TABLE device_connectivity_events (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,               -- 'Initial', 'Connecting', 'Negotiating', 'Connected', 'Terminal'
    info TEXT,                                 -- Información adicional o de error
    com_port VARCHAR(32)                       -- Puerto COM (en caso de conexiones seriales)
);

-- B. Valores de Signos Vitales / Telemetría Numérica (Frecuencia de muestreo <= 3Hz)
CREATE TABLE vital_values (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64) NOT NULL,
    instance_id INT NOT NULL DEFAULT 0,       -- En caso de múltiples sensores del mismo tipo
    patient_id VARCHAR(64),                   -- Desnormalizado para consultas directas rápidas
    unit_id VARCHAR(32),
    vital_value DOUBLE PRECISION NOT NULL
);

-- C. Ondas de Alta Frecuencia / Waveforms (Frecuencia de muestreo > 3Hz, ej. ECG, Capnograma)
CREATE TABLE waveform_data (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL, -- Marca de tiempo del final/inicio del segmento de la onda
    device_id VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64) NOT NULL,
    instance_id INT NOT NULL DEFAULT 0,
    patient_id VARCHAR(64),
    frequency_hz INT NOT NULL,                -- Frecuencia de muestreo en Hertz
    unit_id VARCHAR(32),
    values REAL[] NOT NULL                    -- Arreglo de muestras numéricas (evita sobrecarga de filas por muestra)
);

-- D. Alertas Clínicas y Técnicas del Dispositivo
CREATE TABLE device_alerts (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    metric_id VARCHAR(64),                    -- Puede ser nulo si es una alerta técnica del sistema completo
    alert_type VARCHAR(64),                   -- 'Clinical' o 'Technical'
    alert_message TEXT NOT NULL,
    priority VARCHAR(16) CHECK (priority IN ('low', 'medium', 'high'))
);

-- E. Datos específicos para bombas de infusión (Tanto la bomba simulada como las físicas)
CREATE TABLE infusion_pump_status (
    time_tick TIMESTAMP WITH TIME ZONE NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    patient_id VARCHAR(64),
    infusion_active BOOLEAN NOT NULL,
    drug_name VARCHAR(128) NOT NULL,
    drug_mass_mcg INT,                        -- Masa del medicamento en microgramos
    solution_volume_ml INT,                    -- Volumen total de la solución en ml
    volume_to_be_infused_ml INT,               -- Volumen a infundir (VTBI) en ml
    infusion_duration_seconds INT,             -- Duración de infusión programada
    infusion_fraction_complete REAL,           -- Porcentaje completado (0.0 a 100.0)
    interlock_stop BOOLEAN DEFAULT FALSE       -- Si fue detenida por el interbloqueo de seguridad
);


-- =============================================================================
-- CONFIGURACIÓN DE HYPERTABLES (TimescaleDB)
-- =============================================================================

-- Convertir tablas normales a Hypertables divididas por tiempo (segmentos de 1 día por defecto)
SELECT create_hypertable('device_connectivity_events', 'time_tick', chunk_time_interval => INTERVAL '1 day');
SELECT create_hypertable('vital_values', 'time_tick', chunk_time_interval => INTERVAL '1 day');
SELECT create_hypertable('waveform_data', 'time_tick', chunk_time_interval => INTERVAL '1 day');
SELECT create_hypertable('device_alerts', 'time_tick', chunk_time_interval => INTERVAL '1 day');
SELECT create_hypertable('infusion_pump_status', 'time_tick', chunk_time_interval => INTERVAL '1 day');


-- =============================================================================
-- CREACIÓN DE ÍNDICES ADICIONALES (Optimización de Consultas)
-- =============================================================================

-- Índices en vital_values para filtrar rápido por dispositivo, métrica y tiempo
CREATE INDEX idx_vital_device_metric ON vital_values (device_id, metric_id, time_tick DESC);
CREATE INDEX idx_vital_patient ON vital_values (patient_id, time_tick DESC);

-- Índices en waveform_data
CREATE INDEX idx_waveform_device_metric ON waveform_data (device_id, metric_id, time_tick DESC);
CREATE INDEX idx_waveform_patient ON waveform_data (patient_id, time_tick DESC);

-- Índices en infusion_pump_status
CREATE INDEX idx_infusion_device ON infusion_pump_status (device_id, time_tick DESC);


-- =============================================================================
-- POLÍTICAS DE COMPRESIÓN (TimescaleDB)
-- =============================================================================

-- La compresión nativa de TimescaleDB reduce el almacenamiento hasta en un 90%
-- convirtiendo los datos a formato columnar.

-- Configurar compresión para vital_values
ALTER TABLE vital_values SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, metric_id, patient_id',
    timescaledb.compress_orderby = 'time_tick DESC'
);

-- Configurar compresión para waveform_data
ALTER TABLE waveform_data SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, metric_id, patient_id',
    timescaledb.compress_orderby = 'time_tick DESC'
);

-- Configurar compresión para logs de conectividad e infusión
ALTER TABLE device_connectivity_events SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id',
    timescaledb.compress_orderby = 'time_tick DESC'
);

ALTER TABLE infusion_pump_status SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id, drug_name',
    timescaledb.compress_orderby = 'time_tick DESC'
);

-- Programar políticas de compresión automática después de 7 días
SELECT add_compression_policy('vital_values', INTERVAL '7 days');
SELECT add_compression_policy('waveform_data', INTERVAL '7 days');
SELECT add_compression_policy('device_connectivity_events', INTERVAL '7 days');
SELECT add_compression_policy('infusion_pump_status', INTERVAL '7 days');


-- =============================================================================
-- INSERCIÓN DE METADATOS INICIALES (Semillas de Configuración)
-- =============================================================================

-- Semilla de Métricas Estándar Rosetta ISO/IEEE 11073-10101 con mapeo LOINC y unidades UCUM
INSERT INTO metric_types (metric_id, display_name, default_unit, loinc_code, ucum_code) VALUES
('MDC_ECG_HEART_RATE', 'Frecuencia Cardíaca (ECG)', 'bpm', '8867-4', '/min'),
('MDC_SPO2', 'Saturación de Oxígeno (SpO2)', '%', '2708-6', '%'),
('MDC_PULS_OXIM_PULS_RATE', 'Frecuencia de Pulso por Oximetría', 'bpm', '8889-8', '/min'),
('MDC_CO2_RESP_RATE', 'Frecuencia Respiratoria (Capnometría)', 'rpm', '9279-1', '/min'),
('MDC_AWY_CO2', 'Presión Parcial de CO2 en Vía Aérea (EtCO2)', 'mmHg', '19889-5', 'mm[Hg]'),
('MDC_PRESS_BLD_SYS', 'Presión Arterial Sistólica', 'mmHg', '8480-6', 'mm[Hg]'),
('MDC_PRESS_BLD_DIA', 'Presión Arterial Diastólica', 'mmHg', '8462-4', 'mm[Hg]'),
('MDC_PRESS_BLD_MEAN', 'Presión Arterial Media', 'mmHg', '8478-0', 'mm[Hg]'),
('MDC_PRESS_BLD_NONINV_SYS', 'Presión Arterial No Invasiva Sistólica', 'mmHg', '8460-8', 'mm[Hg]'),
('MDC_PRESS_BLD_NONINV_DIA', 'Presión Arterial No Invasiva Diastólica', 'mmHg', '8458-2', 'mm[Hg]'),
('MDC_TEMP_BLD', 'Temperatura Corporal', '°C', '8310-5', 'Cel'),
('MDC_FLOW_FLU_INFUS', 'Flujo de Infusión', 'mL/h', '75685-8', 'mL/h'),
('MDC_VOL_INFUS_DELIV', 'Volumen de Infusión Entregado', 'mL', '37030-4', 'mL'),
('MDC_ECG_LEAD_I', 'Electrocardiograma - Derivación I', 'mV', '8601-7', 'mV'),
('MDC_ECG_LEAD_II', 'Electrocardiograma - Derivación II', 'mV', '8602-5', 'mV'),
('MDC_ECG_LEAD_III', 'Electrocardiograma - Derivación III', 'mV', '8603-3', 'mV');

-- Semilla de Dispositivos Simulados Predefinidos en OpenICE
INSERT INTO devices (device_id, manufacturer, model, serial_number, connection_type, build_info, operating_system) VALUES
('SIM_ECG_001', 'ICE', 'ElectroCardioGram', 'SIM-ECG-12345', 'Simulated', 'OpenICE-Sim-1.0', 'JVM'),
('SIM_PULSEOX_001', 'ICE', 'PO (Pulse Oximeter)', 'SIM-PO-12345', 'Simulated', 'OpenICE-Sim-1.0', 'JVM'),
('SIM_CAPNO_001', 'ICE', 'Capnometer', 'SIM-CO2-12345', 'Simulated', 'OpenICE-Sim-1.0', 'JVM'),
('SIM_NIBP_001', 'ICE', 'Noninvasive Blood Pressure', 'SIM-NIBP-12345', 'Simulated', 'OpenICE-Sim-1.0', 'JVM'),
('SIM_IBP_001', 'ICE', 'Invasive Blood Pressure', 'SIM-IBP-12345', 'Simulated', 'OpenICE-Sim-1.0', 'JVM'),
('SIM_TEMP_001', 'ICE', 'Temperature Probe', 'SIM-TEMP-12345', 'Simulated', 'OpenICE-Sim-1.0', 'JVM'),
('SIM_PUMP_001', 'ICE', 'Controllable Pump', 'SIM-PUMP-12345', 'Simulated', 'OpenICE-Sim-1.0', 'JVM'),
('SIM_MULTI_001', 'ICE', 'Multiparameter Monitor', 'SIM-MULTI-12345', 'Simulated', 'OpenICE-Sim-1.0', 'JVM');

-- Semilla de ejemplo de Dispositivos Físicos/Reales Soportados
INSERT INTO devices (device_id, manufacturer, model, serial_number, connection_type, build_info, operating_system) VALUES
('REAL_NELLCOR_595', 'Nellcor', 'N-595', 'NL-595-8821', 'Serial', 'Firmware-3.2', 'Linux-embedded'),
('REAL_PHILIPS_INTELLIVUE', 'Philips', 'Intellivue (LAN)', 'PH-MX800-9912', 'Network', 'PIP-Rev-J', 'VxWorks'),
('REAL_DRAEGER_V500', 'Dräger', 'V500 Ventilator', 'DR-V500-1123', 'Serial', 'Medibus-X-1.2', 'VxWorks');
