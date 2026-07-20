# Análisis Comparativo: MQTT vs Kafka para Streaming en Hospital General de México

**Fecha:** 20 de julio de 2026

**Proyecto:** Implementación de OpenICE para servicios especializados en quirófanos

**Alcance:** Decisión de arquitectura para streaming de datos médicos a múltiples clientes con niveles de acceso

**Versión:** 1.0.0

---

## 1. Contexto

El Hospital General de México requiere una arquitectura que:

1. **Monitoree dispositivos médicos** en quirófanos vía LAN/Serial con comunicación en tiempo real
2. **Agregue datos** de múltiples quirófanos hacia áreas clínicas y el hospital central
3. **Streame datos a múltiples clientes** con niveles de acceso:
   - **Jefes de área:** datos crudos en tiempo real (waveforms, numerics, alerts)
   - **Médicos generales:** datos clave filtrados (numerics, alerts)
   - **Administradores:** datos agregados/estadísticos
4. **Garantice que ningún dato se pierda** ante caídas de red (store-and-forward en supervisor Android con buffer SQLite de 24h)

Jerarquía física del hospital:

```
Hospital General de México
 └── Área Clínica (Cardiología, Neurología, etc.)
      └── Quirófano
           └── Dispositivo(s) médico(s)
```

---

## 2. Arquitecturas comparadas

### Arquitectura 1: DDS + MQTT con store-and-forward

```
┌─ Quirófano ──────────────────────────────────────────┐
│ Device → DDS → Supervisor Android (SQLite 24h)       │
│                        ↓ MQTT                        │
│              Broker MQTT - Quirófano (opcional)       │
└────────────────────────┬─────────────────────────────┘
                         │ bridge
                         ↓
┌─ Hospital ───────────────────────────────────────────┐
│        Broker MQTT Central (EMQX / HiveMQ)           │
│           ↓              ↓              ↓            │
│     WebSocket       Backend        Kafka Connect     │
│     (clientes)    (TimescaleDB)    (analytics)       │
└──────────────────────────────────────────────────────┘
```

### Arquitectura 2: DDS + MQTT + Kafka + Apache Camel

```
┌─ Quirófano ──────────────────────────────────────────┐
│ Device → DDS → Adapter (DDS→MQTT)                    │
│                        ↓ MQTT                        │
│              Broker MQTT - Quirófano                   │
└────────────────────────┬─────────────────────────────┘
                         │ Kafka Connector
                         ↓
┌─ Hospital ───────────────────────────────────────────┐
│     Apache Kafka (cluster distribuido)                │
│           ↓                                          │
│     Apache Camel (transformación + routing)           │
│           ↓              ↓                           │
│     TimescaleDB      Dashboard                       │
│     (escritura)      (lectura)                       │
└──────────────────────────────────────────────────────┘
```

---

## 3. Tabla comparativa

| Aspecto | Arq. 1 (MQTT + store-and-forward) | Arq. 2 (Kafka + Camel) |
|---|---|---|
| **Pérdida de datos** | ✅ No se pierden (SQLite 24h + reenvío) | ✅ No se pierden (Kafka persiste en disco) |
| **Escalabilidad de clientes** | ✅ EMQX: 100M conexiones, 1M+ msg/sec | ✅ Kafka escala horizontalmente |
| **Niveles de acceso** | ✅ ACL por topic + wildcard MQTT | Requiere lógica custom en capa de aplicación |
| **Streaming a clientes web** | ✅ EMQX tiene WebSocket nativo | Requiere servicio WebSocket intermedio |
| **Latencia end-to-end** | ✅ ~1-10ms (MQTT nativo) | ⚠️ ~50-200ms (Kafka batch + Camel) |
| **Complejidad operacional** | ✅ Baja (1-3 nodos EMQX) | ⚠️ Alta (Kafka cluster + Zookeeper/KRaft + Camel) |
| **Costo de infraestructura** | ✅ Bajo | ⚠️ Alto (Kafka necesita ~4GB RAM mínimo por nodo) |
| **Replay histórico** | ⚠️ Limitado (retained messages) | ✅ Kafka retiene y relee desde offset |
| **Fan-out a múltiples consumidores** | ✅ MQTT wildcard subscriptions | ✅ Kafka consumer groups |
| **Integración con analytics/ML** | ⚠️ Requiere puente adicional | ✅ Kafka Connect + ksqlDB nativo |

---

## 4. Dato clave de la investigación

**EMQX Enterprise ya tiene un caso de uso publicado para dispositivos médicos** (mayo 2026):

> *"A leading connected healthcare technology company managing smart medical devices across thousands of clinical sites undertook a strategic platform consolidation to replace legacy open-source MQTT and AMQP brokers with an enterprise-grade messaging infrastructure. Facing scaling requirements from 100,000 current devices to 500,000–1,000,000 over the next 3–5 years."*

| Métrica | Valor |
|---|---|
| Dispositivos actuales | 100,000 |
| Dispositivos proyectados | 500,000 – 1,000,000 |
| Throughput | 10,000s de eventos/segundo |
| QoS | QoS 2 (Exactly Once) para datos críticos |
| Despliegue | On-premise para cumplimiento regulatorio |
| Uptime | 99.99%+ con failover automático |
| Escalabilidad | 100M conexiones en cluster de 23 nodos EMQX |

**Referencia:** https://www.emqx.com/en/blog/enterprise-mqtt-infrastructure-for-connected-medical-device-fleets-in-clinical-environments

Para el Hospital General de México, con ~200 quirófanos × 10 dispositivos × 100 msgs/seg = **20,000 msgs/seg**, se usaría el **2%** de la capacidad de un broker EMQX.

---

## 5. Argumentación: por qué MQTT es la opción correcta

### 5.1 El escala del Hospital General de México no justifica Kafka

El volumen estimado es ~20,000 msgs/seg. EMQX maneja 1,000,000+ msg/sec. Kafka está diseñado para escenarios de **cientos de miles a millones de msgs/seg** con múltiples consumidores independientes que necesitan replay histórico. Para este volumen, MQTT es sobradamente suficiente.

### 5.2 Los niveles de acceso se resuelven nativamente con MQTT

```mqtt
# Jefes de área (ven todo su área en tiempo real)
hospital/cardiologia/#

# Médicos generales (solo datos clave)
hospital/cardiologia/quirofano_3/+/numerics/heart_rate
hospital/cardiologia/quirofano_3/+/alerts

# Administradores (datos agregados, no crudos)
hospital/+/+/+/estadisticas
```

EMQX tiene **ACL por topic** y **shared subscriptions** para load balancing entre clientes. Con Kafka, toda la lógica de acceso y filtrado por roles tendría que construirse en la capa de aplicación.

### 5.3 El store-and-forward en el supervisor ya cubre resiliencia

El supervisor Android almacena datos en SQLite (buffer 24h) y reenvía cuando se restablece la conexión. La persistencia está en el **edge** (más cerca de la fuente), no en el centro. Esto es más resiliente que depender de la disponibilidad de un cluster Kafka central.

### 5.4 WebSocket nativo resuelve el streaming a clientes web

EMQX y HiveMQ tienen soporte WebSocket integrado. Los clientes web se conectan directamente al broker MQTT sin un servicio intermedio. Con Kafka, se necesitaría un servicio WebSocket custom que lea de Kafka y exponga los datos.

### 5.5 Complejidad operacional importa en un hospital

| Componente | MQTT (EMQX) | Kafka |
|---|---|---|
| Nodos mínimos | 1 (3 para HA) | 3 brokers + Zookeeper/KRaft |
| RAM por nodo | ~2-4GB | ~8-16GB |
| Personal requerido | Administrador de red | Ingeniero de plataformas distribuidas |
| Curva de aprendizaje | Baja | Alta |
| Monitoreo | EMQX Dashboard | Kafka Manager + JMX + métricas custom |

---

## 6. Cómo escalar a Kafka en el futuro si se necesita

Si en el futuro se requiere analytics avanzado, replay histórico, o integración con data lakes, **no se necesita cambiar la arquitectura base**. Kafka se añade como un consumer más del broker MQTT:

```
Broker MQTT Central (EMQX)
    │
    ├── WebSocket → Clientes web (jefes, médicos, admins)
    │
    ├── Backend → TimescaleDB (operación normal)
    │
    └── Kafka Connect MQTT Source → Apache Kafka
            │
            ├── Apache Camel → Data Lake / ML / Research
            ├── ksqlDB → Analytics en tiempo real
            └── Schema Registry → Gobernanza de datos
```

Esto permite:
- **Replay histórico** sin cambiar la arquitectura de dispositivos
- **Analytics/ML** sobre datos históricos
- **Múltiples consumidores** independientes (research, quality, compliance)
- **Retención indefinida** en Kafka mientras TimescaleDB aplica retención de 7 días

---

## 7. Decisión final

### Seleccionada: Arquitectura 1 (MQTT + store-and-forward)

**Justificación resumida:**

| Factor | Decisión |
|---|---|
| Volumen | 20,000 msgs/seg — MQTT es sobradamente suficiente |
| Resiliencia | Store-and-forward en SQLite cubre caídas de red |
| Acceso | ACL por topic + wildcard nativo de MQTT |
| Clientes | WebSocket nativo en EMQX/HiveMQ |
| Operaciones | Baja complejidad vs Kafka cluster |
| Costo | Bajo vs alto |
| Futuro | Kafka se añade como consumer sin cambiar arquitectura |

### Próximos pasos para esta arquitectura

1. **Validar EMQX vs HiveMQ vs Mosquitto** para el broker MQTT central (evaluar licenciamiento, soporte WebSocket, ACL)
2. **Definir esquema de topics MQTT** definitivo (nomenclatura de área clínica, quirófano, dispositivo)
3. **Implementar supervisor Android** con Cyclone DDS + MQTT client + SQLite/Room
4. **Definir QoS por topic** (QoS 2 para alerts, QoS 1 para numerics, QoS 0 para waveforms)
5. **Diseñar esquema de TimescaleDB** para el hospital central (retención, compresión, continuous aggregates)
6. **Evaluar integración FHIR** para interoperabilidad con sistemas existentes del hospital

---

*Análisis generado a partir de investigación de mercado (EMQX, HiveMQ, Kafka) y evaluación de arquitecturas para el Hospital General de México.*
