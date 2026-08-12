# Políticas QoS más comunes en DDS

**Fecha:** 12 de agosto de 2026

**Proyecto:** OpenICE / MD PnP (`1.5.0-SNAPSHOT`)

**Sistema:** DDS (RTI Connext / Cyclone DDS) — middleware de datos distribuidos

**Alcance:** Referencia rápida de las políticas QoS del estándar DDS: reliability, durability, history, deadline, liveliness, ownership, partition, latency_budget, time_based_filter, resource_limits, destination_order y lifespan

---

Para cada política: **nombre**, **qué controla** (la dimensión de comportamiento) y **para qué sirve** (el problema real que resuelve), con las opciones disponibles.

---

## 1. RELIABILITY

**Qué controla**: si el sistema garantiza la entrega de cada muestra o si permite pérdidas.

**Para qué sirve**: decidir si te importa más la velocidad/bajo overhead o la garantía de que el dato llegue.

Opciones:
- `BEST_EFFORT`: se envía y no se reintenta. Más rápido, menos overhead de red (sin ACKs).
- `RELIABLE`: el Writer reintenta hasta confirmar que el Reader recibió la muestra (usa ACKs/NACKs internamente).

Ejemplo de uso: video/streaming de sensores de alta frecuencia → `BEST_EFFORT` (un frame perdido no vale la pena reenviarlo). Comandos de control, alarmas, transacciones → `RELIABLE`.

---

## 2. DURABILITY

**Qué controla**: si un Reader que se conecta *después* de que se publicó un dato puede aún recibirlo.

**Para qué sirve**: resolver el problema de "llegué tarde a la fiesta" — sin esto, si te suscribes después de que el dato se publicó, te lo pierdes para siempre.

Opciones (de menor a mayor persistencia):
- `VOLATILE`: el Reader solo recibe datos publicados *después* de haberse conectado.
- `TRANSIENT_LOCAL`: el Writer guarda las últimas muestras en memoria local y las entrega a Readers tardíos, mientras el Writer siga vivo.
- `TRANSIENT`: similar, pero el histórico se guarda en un servicio externo (no depende de que el Writer original siga vivo), aunque no sobrevive a un reinicio del sistema.
- `PERSISTENT`: el histórico se guarda en disco/almacenamiento persistente, sobrevive incluso a reinicios completos del sistema.

Ejemplo de uso: estado de configuración de un dispositivo (`TRANSIENT_LOCAL`, para que un nodo que arranca tarde sepa el último estado). Un log de auditoría crítico (`PERSISTENT`).

---

## 3. HISTORY

**Qué controla**: cuántas muestras pasadas se retienen en el buffer antes de descartar las viejas.

**Para qué sirve**: definir si solo te importa el dato más reciente o necesitas una ventana histórica.

Opciones:
- `KEEP_LAST(n)`: guarda solo las últimas `n` muestras (la más común; `n` suele ser 1).
- `KEEP_ALL`: guarda todas las muestras (limitado por `RESOURCE_LIMITS`).

Ejemplo de uso: posición GPS actual → `KEEP_LAST(1)` (el valor viejo no sirve). Reconstrucción de una trayectoria completa → `KEEP_ALL` o `KEEP_LAST(n)` grande.

> Nota: `HISTORY` y `DURABILITY` trabajan juntas — `HISTORY` define *cuánto* se guarda, `DURABILITY` define *para quién* (tardíos) y *dónde* (memoria/disco) se guarda.

---

## 4. DEADLINE

**Qué controla**: el intervalo máximo de tiempo esperado entre actualizaciones sucesivas de una instancia de dato.

**Para qué sirve**: detectar cuando algo "dejó de actualizarse a tiempo" — útil para monitoreo de salud de sensores/nodos.

Cómo funciona: defines un período (ej. 1 segundo). Si no llega una muestra nueva dentro de ese período, se dispara un evento `on_requested_deadline_missed` (en el Reader) u `on_offered_deadline_missed` (en el Writer).

Ejemplo de uso: sensor de temperatura que debe reportar cada segundo → si pasa 1s sin dato nuevo, tu app se entera de que el sensor podría estar caído o desconectado.

---

## 5. LIVELINESS

**Qué controla**: cómo y cada cuánto se determina que un Writer "sigue vivo" (activo, no colgado ni caído).

**Para qué sirve**: detección de fallos de nodos/procesos, distinto de DEADLINE (que es sobre el *dato*, LIVELINESS es sobre el *participante/writer* en sí).

Opciones:
- `AUTOMATIC`: el propio middleware genera señales de "vida" automáticamente, sin intervención de la app.
- `MANUAL_BY_PARTICIPANT`: la aplicación debe indicar actividad (en cualquier Writer del participante) dentro de cierto plazo.
- `MANUAL_BY_TOPIC`: la aplicación debe indicar actividad específicamente en ese Writer/Topic.

Se combina con un `lease_duration` (tiempo máximo sin señal de vida antes de considerar al Writer "muerto").

Ejemplo de uso: sistemas críticos donde necesitas saber inmediatamente si un nodo de control se cayó, no solo si dejó de mandar datos.

---

## 6. OWNERSHIP (+ OWNERSHIP_STRENGTH)

**Qué controla**: qué pasa cuando **varios Writers** publican a la **misma instancia** de un Topic (mismo key/ID).

**Para qué sirve**: resolver conflictos de "quién manda" cuando hay redundancia de publishers (failover, alta disponibilidad).

Opciones:
- `SHARED`: todos los Writers pueden publicar libremente; el Reader recibe todas las actualizaciones de todos.
- `EXCLUSIVE`: solo el Writer con mayor `OWNERSHIP_STRENGTH` (un número que tú defines) es "dueño" de esa instancia; los demás son ignorados a menos que el dueño actual deje de estar vivo (vía LIVELINESS), en cuyo caso el siguiente con mayor strength toma el control.

Ejemplo de uso: dos controladores redundantes de un mismo actuador, donde solo uno debe tener autoridad a la vez (failover automático).

---

## 7. PARTITION

**Qué controla**: subdivisión lógica del tráfico **dentro del mismo dominio**, mediante nombres de partición (strings, con soporte de wildcards).

**Para qué sirve**: aislar o agrupar comunicación sin necesitar dominios distintos (más flexible/dinámico que cambiar de `domain_id`).

Cómo funciona: un Writer y un Reader solo se "matchean" si comparten al menos un nombre de partición (o coinciden por wildcard, ej. `"Robot*"`).

Ejemplo de uso: simular múltiples instancias del mismo sistema en la misma red (ej. `"RobotA"`, `"RobotB"`) sin duplicar dominios ni topics.

---

## 8. LATENCY_BUDGET

**Qué controla**: una "pista" de cuánta latencia end-to-end es aceptable para ese dato.

**Para qué sirve**: darle al middleware margen para optimizar (ej. agrupar/"batchear" varias muestras antes de enviarlas) sin violar tus expectativas de latencia. No es una garantía dura, es una sugerencia de optimización.

Ejemplo de uso: datos donde 50ms de latencia es aceptable → el middleware puede agrupar varias muestras en un solo paquete de red y ahorrar overhead, en vez de enviar cada una inmediatamente.

---

## 9. TIME_BASED_FILTER

**Qué controla**: la frecuencia mínima con la que el Reader *quiere* recibir actualizaciones, aunque el Writer publique más rápido.

**Para qué sirve**: throttling del lado del Reader — evitar saturar a un suscriptor lento con datos que no necesita a la frecuencia completa del publisher.

Ejemplo de uso: un Writer publica a 1000 Hz, pero un Reader en una app de dashboard solo necesita refrescar cada 200ms → configura `minimum_separation = 200ms` y descarta el resto sin procesarlas.

---

## 10. RESOURCE_LIMITS

**Qué controla**: límites máximos de memoria/buffers — número máximo de instancias, máximo de muestras por instancia, máximo total de muestras.

**Para qué sirve**: evitar consumo de memoria descontrolado, crítico en sistemas embebidos o con recursos acotados.

Ejemplo de uso: en un dispositivo embebido con RAM limitada, defines `max_samples`, `max_instances` y `max_samples_per_instance` para que el sistema nunca exceda un presupuesto de memoria conocido.

---

## 11. DESTINATION_ORDER

**Qué controla**: el criterio de orden con el que se aplican/entregan las muestras cuando llegan de múltiples Writers o fuera de secuencia.

**Para qué sirve**: consistencia en sistemas distribuidos donde el orden importa (ej. no sobreescribir un dato nuevo con uno viejo que llegó tarde por la red).

Opciones:
- `BY_RECEPTION_TIMESTAMP`: se ordena según cuándo llegó al Reader (por defecto).
- `BY_SOURCE_TIMESTAMP`: se ordena según cuándo fue escrito por el Writer original — más robusto ante latencias de red variables.

---

## 12. LIFESPAN

**Qué controla**: cuánto tiempo una muestra sigue siendo válida antes de expirar automáticamente y ser descartada.

**Para qué sirve**: evitar que datos "viejos" (que ya no reflejan la realidad) se entreguen o queden en el sistema como si fueran vigentes.

Ejemplo de uso: una alerta que solo tiene sentido si se procesa en los próximos 500ms; pasado ese tiempo, se descarta automáticamente en vez de entregarse tarde.

---

## Tabla resumen rápida

| Política | Qué controla (en una frase) |
|---|---|
| RELIABILITY | Si se garantiza la entrega o se permite pérdida |
| DURABILITY | Si los suscriptores tardíos reciben datos históricos |
| HISTORY | Cuántas muestras pasadas se retienen |
| DEADLINE | Cada cuánto se espera un dato nuevo (y detecta si no llega) |
| LIVELINESS | Cómo se detecta que un Writer sigue activo |
| OWNERSHIP | Qué pasa si varios Writers publican al mismo dato |
| PARTITION | Segmentación lógica dentro del mismo dominio |
| LATENCY_BUDGET | Margen aceptable de latencia (para optimización) |
| TIME_BASED_FILTER | Frecuencia mínima de entrega deseada por el Reader |
| RESOURCE_LIMITS | Límites de memoria/buffers |
| DESTINATION_ORDER | Criterio de orden de aplicación de las muestras |
| LIFESPAN | Cuánto tiempo antes de que una muestra expire |

---

## Cómo se combinan en la práctica

Ninguna política vive aislada — normalmente combinas varias para lograr un comportamiento completo. Ejemplo típico para un **sensor crítico**:

```xml
<datawriter_qos>
  <reliability><kind>RELIABLE_RELIABILITY_QOS</kind></reliability>
  <durability><kind>TRANSIENT_LOCAL_DURABILITY_QOS</kind></durability>
  <history><kind>KEEP_LAST_HISTORY_QOS</kind><depth>5</depth></history>
  <deadline><period><sec>1</sec><nanosec>0</nanosec></period></deadline>
  <liveliness>
    <kind>AUTOMATIC_LIVELINESS_QOS</kind>
    <lease_duration><sec>3</sec><nanosec>0</nanosec></lease_duration>
  </liveliness>
</datawriter_qos>
```

Esto dice: "garantiza la entrega, guarda las últimas 5 muestras para quien se conecte tarde, espero un dato nuevo cada segundo, y considero al writer muerto si no da señales de vida en 3 segundos."

---

*Guía generada a partir del análisis del estándar OMG DDS y de la configuración QoS del proyecto OpenICE / MD PnP (`ice_library.xml`).*