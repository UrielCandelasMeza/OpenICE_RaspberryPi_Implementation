# Reporte: Análisis de Esquemas DB y Plan de Implementación v1.1

**Fecha:** 20 de julio de 2026

**Proyecto:** OpenICE / MD PnP — Backend Central para Hospital General de México

**Alcance:** Síntesis de esquemas existentes (v1.0 DBML, db.sql, JdbcPersister) y propuesta unificada v1.1

**Versión:** 1.0.0

---

## 1. Contexto

El sistema requiere una base de datos central para el Hospital General de México que soporte:

- **Telemetría en tiempo real** de dispositivos médicos (numéricos, waveforms, alertas, bombas)
- **Contexto operacional del hospital** (quirófanos, áreas clínicas, cirugías, supervisores)
- **Asociación paciente-dispositivo** con sesiones temporales
- **Streaming a múltiples clientes** con niveles de acceso (jefes de área, médicos, admins)

Se analizaron 3 fuentes existentes:

| Fuente | Archivo | Propósito |
|---|---|---|
| **v1.0 DBML** | `docs/diagrams/relational/v1.0/mdpnp v1.0.dbml` | Primera propuesta relacional con contexto hospitalario |
| **db.sql** | `db.sql` | Esquema productivo de telemetría TimescaleDB |
| **setup_local_timescale.sql** | `setup_local_timescale.sql` | Esquema legacy para dispositivos edge (deprecado) |
| **JdbcPersister** | `JdbcPersister.java` | Exportador del Data Recorder (demo-apps) |

---

## 2. Análisis de Esquemas Existentes

### 2.1 v1.0 DBML — Capa Operacional

El esquema v1.0 modela el **contexto organizacional del hospital**:

```mermaid
cat_supervisor → det_supervisor_dispositivos → cat_dispositivo
cat_quirofanos → det_paciente_cirujia → paciente
cat_area_medica → cat_cirujia → det_paciente_cirujia
hipertabla_valores → cat_dispositivo, cat_unidades
hipertabla_alertas → cat_dispositivo, cat_alerta
```

**Fortalezas:**
- Modela quirófanos, áreas clínicas y cirugías
- Asocia supervisores con dispositivos
- Programa cirugías con paciente + fecha + quirófano
- Enums tipados (`tipo_lectura`, `severidad_alarma`)

**Debilidades:**
- Sin tabla de waveforms (aplana todo a `hipertabla_valores`)
- Sin tabla de bombas de infusión
- Sin eventos de conectividad de dispositivos
- Sin composición de dispositivos compuestos
- `patient_device_sessions` no existe (usa `det_supervisor_dispositivos` sin paciente)
- PKs autoincrement en vez de UDI real
- Sin seeds de métricas Rosetta/LOINC

### 2.2 db.sql — Capa de Telemetría

El esquema db.sql modela la **telemetría médica** con TimescaleDB:

```mermaid
devices → vital_values, waveform_data, device_alerts, infusion_pump_status, device_connectivity_events
device_types → devices
metric_types → vital_values, waveform_data
patients → patient_device_sessions → devices
device_composition → devices (self-reference)
```

**Fortalezas:**
- Tablas separadas por tipo de dato (numérico, waveform, alerta, infusión, conectividad)
- Waveforms como `REAL[]` con `frequency_hz`
- Seeds de 16 métricas Rosetta con LOINC/UCUM
- Seeds de 9 device types y 11 dispositivos
- `TIMESTAMP WITH TIME ZONE` en todas las tablas
- Índices optimizados para consultas por device+metric+time

**Debilidades:**
- Sin contexto operacional del hospital (no hay quirófanos, áreas, cirugías)
- Sin registro de supervisores
- Sin programa de cirugías
- Retención no definida

### 2.3 setup_local_timescale.sql — Legacy (DEPRECADO)

**Estado:** El usuario indicó que ya no se usará tal cual. Solo se conserva como referencia de tablas legacy (`allnumerics`, `allsamples`, `patientdevice`).

### 2.4 JdbcPersister — Exportador

**Estado:** Esquema minimalista para exportación puntual. Sirve como inspiración pero no como base.

| Columna JdbcPersister | Equivalente db.sql | Equivalente v1.0 |
|---|---|---|
| `DEVICE_ID VARCHAR(25)` | `device_id VARCHAR(64)` | `cat_dispositivo.codigo` |
| `METRIC_ID VARCHAR(25)` | `metric_id VARCHAR(64)` | `cat_unidades` (sin string ID) |
| `INSTANCE_ID INTEGER` | `instance_id INT` | ❌ No existe |
| `TIME_TICK TIMESTAMP` | `time_tick TIMESTAMPTZ` | `hipertabla_valores.tiempo` |
| `PATIENT_ID VARCHAR(25)` | `patient_id VARCHAR(64)` | ❌ No en hypertable |
| `VITAL_VALUE DOUBLE` | `vital_value DOUBLE PRECISION` | `hipertabla_valores.valor_num` |

**JdbcPersister NO exporta:** waveforms, alertas completas, infusión, conectividad, composición de dispositivos, sesiones paciente-dispositivo.

---

## 3. Tabla Comparativa Final

| Capa | v1.0 DBML | db.sql | v1.1 (Unificado) |
|---|---|---|---|
| **Supervisores** | ✅ `cat_supervisor` | ❌ | ✅ De v1.0 |
| **Quirófanos** | ✅ `cat_quirofanos` | ❌ | ✅ De v1.0 |
| **Áreas clínicas** | ✅ `cat_area_medica` | ❌ | ✅ De v1.0 |
| **Cirugías** | ✅ `cat_cirujia` | ❌ | ✅ De v1.0 |
| **Programación surgeries** | ✅ `det_paciente_cirujia` | ❌ | ✅ De v1.0 |
| **Supervisor↔Dispositivos** | ✅ `det_supervisor_dispositivos` | ❌ | ✅ De v1.0 |
| **Dispositivos** | `cat_dispositivo` (id int) | `devices` (UDI VARCHAR(64)) | ✅ db.sql (UDI) |
| **Tipos de dispositivo** | ❌ | ✅ `device_types` | ✅ db.sql |
| **Métricas (LOINC/UCUM)** | `cat_unidades` (parcial) | `metric_types` (completo) | ✅ db.sql |
| **Paciente** | `paciente` (id, nombre, apellido) | `patients` (MRN, nombre, gender, dob) | ✅ db.sql (MRN) |
| **Sesiones paciente-device** | ❌ | ✅ `patient_device_sessions` | ✅ db.sql |
| **Composición dispositivos** | ❌ | ✅ `device_composition` | ✅ db.sql |
| **Valores numéricos** | `hipertabla_valores` (unificado) | `vital_values` (tipado) | ✅ db.sql (tipado) |
| **Waveforms** | ❌ (aplana a valores) | ✅ `waveform_data` (REAL[]) | ✅ db.sql |
| **Alertas** | `hipertabla_alertas` (simple) | `device_alerts` (completo) | ✅ db.sql |
| **Bombas de infusión** | ❌ | ✅ `infusion_pump_status` | ✅ db.sql |
| **Conectividad** | ❌ | ✅ `device_connectivity_events` | ✅ db.sql |
| **Seeds métricas** | ❌ | ✅ 16 Rosetta | ✅ db.sql |
| **Seeds dispositivos** | ❌ | ✅ 11 dispositivos | ✅ db.sql |

---

## 4. Propuesta v1.1 — Esquema Unificado

### 4.1 Arquitectura de tablas

```
┌────────────────────────────────────────────────────────────────────┐
│                    CAPA OPERACIONAL (de v1.0)                      │
│                                                                    │
│  cat_supervisor ──┐                                                │
│  cat_quirofanos ──┤                                                │
│  cat_area_medica ─┼─ det_paciente_cirujia ── paciente              │
│  cat_cirujia ─────┤                                                │
│                   └─ det_supervisor_dispositivos                   │
├────────────────────────────────────────────────────────────────────┤
│                    CAPA DE TELEMETRÍA (de db.sql)                  │
│                                                                    │
│  device_types ── devices ──┬── vital_values (Hypertable)           │
│  metric_types ─────────────┼── waveform_data (Hypertable)          │
│  patients ── patient_device_sessions                               │
│  device_composition (self-ref)     ├── device_alerts (Hypertable)  │
│                                    ├── infusion_pump_status (Hyp)  │
│                                    └── device_connectivity (Hyp)   │
└────────────────────────────────────────────────────────────────────┘
```

---

## 5. Cambios Clave de v1.0 → v1.1

| Cambio | Justificación |
|---|---|
| `paciente` usa `mrn` como identificador clínico (no solo `id int`) | MRN es el estándar en sistemas hospitalarios |
| `patient_device_sessions` en vez de `det_supervisor_dispositivos` solo | Permite vincular paciente-dispositivo sin depender del supervisor |
| `devices.quirofano_id` | Vincula cada dispositivo a su quirófano físico |
| `vital_values.paciente_id` como INT (FK a `paciente.id`) | Vínculo directo paciente-valores para consultas |
| `device_alerts.quirofano_id` denormalizado | Consultas rápidas de alertas por quirófano sin JOIN |
| `device_alerts.priority` incluye `'critical'` | v1.0 solo tenía baja/media/alta; se añade crítica |
| `waveform_data` como tabla separada | v1.0 aplana waveforms; db.sql las guarda como `REAL[]` |
| `infusion_pump_status` como tabla separada | v1.0 no maneja bombas de infusión |
| `device_connectivity_events` | v1.0 no monitorea conexiones/desconexiones |
| Retención 90 días (vital), 30 días (waveform), 180 días (alertas/connectivity) | Hospital central retiene más que el edge (3 días) |
| Chunk interval 1 día | Más eficiente para consultas diarias en hospital |

---

## 6. Diferencias con `setup_local_timescale.sql` (DEPRECADO)

| Aspecto | setup_local_timescale.sql (legacy) | v1.1 (nuevo) |
|---|---|---|
| Chunk interval | 6 horas | 1 día |
| Compresión | 12 horas | 7 días |
| Retención | 3 días (SD card) | 90/30/180 días (hospital) |
| Tablas legacy | `allnumerics`, `allsamples`, `patientdevice` | ❌ Eliminadas |
| `paciente_id` | VARCHAR(64) 'OFFLINE_PATIENT' | INT (FK a `paciente.id`) |
| Capa operacional | ❌ | ✅ Quirófanos, áreas, cirugías, supervisores |
| Device → Quirófano | ❌ | ✅ `devices.quirofano_id` |

---

*Reporte generado a partir del análisis cruzado de v1.0 DBML, db.sql, setup_local_timescale.sql y JdbcPersister.java.*
