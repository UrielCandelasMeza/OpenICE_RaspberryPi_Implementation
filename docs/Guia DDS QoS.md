# Guía DDS: Protocolo, QoS, Configuración e Integración

**Fecha:** 12 de agosto de 2026

**Proyecto:** OpenICE / MD PnP (`1.5.0-SNAPSHOT`)

**Sistema:** DDS (RTI Connext / Cyclone DDS) — middleware de datos distribuidos

**Alcance:** Guía integral de DDS: protocolo RTPS, políticas QoS y su configuración en XML, comparativa Cyclone DDS vs RTI Connext, integración con Python y JavaScript/Node.js, seguridad de datos en multicast y dominios

---

## 1. ¿Qué es DDS?

**DDS (Data Distribution Service)** es un estándar de la OMG para middleware de mensajería **publish/subscribe descentralizado**, orientado a sistemas de tiempo real y distribuidos (robótica, defensa, IoT industrial, automoción, ROS 2, etc.).

Puntos clave:

- **No hay broker central** (a diferencia de MQTT o Kafka). Los participantes se descubren y hablan directamente entre sí.
- El protocolo de red subyacente estandarizado se llama **RTPS (Real-Time Publish-Subscribe Protocol)**, típicamente sobre **UDP** (unicast y/o multicast), aunque también existen transportes TCP y shared-memory.
- El modelo se organiza así:
  - **DomainParticipant**: la "entrada" de una aplicación al bus DDS.
  - **Topic**: un canal de datos tipado (nombre + tipo de dato, definido normalmente en IDL).
  - **DataWriter / Publisher**: quien escribe datos en un Topic.
  - **DataReader / Subscriber**: quien lee datos de un Topic.
  - **Domain**: un espacio lógico de comunicación aislado (ver sección 8).

DDS no "envía mensajes sueltos": administra el **estado de datos** de un Topic entre escritores y lectores, y el comportamiento de esa gestión (qué se guarda, cuánto tarda, qué se garantiza) es exactamente lo que define **QoS**.

---

## 2. ¿Qué es QoS y qué estás realmente configurando?

**QoS (Quality of Service)** es un conjunto de políticas que le dicen al middleware **cómo comportarse** respecto a la entrega, el almacenamiento y la prioridad de los datos. No mueves "más o menos datos" directamente: configuras **contratos de comportamiento** que DEBEN coincidir (ser compatibles) entre el DataWriter y el DataReader para que la comunicación se establezca. Si un Reader pide `RELIABLE` y el Writer solo ofrece `BEST_EFFORT`, **no habrá match** y no se comunicarán (esto se llama *requested vs offered QoS*).

Las políticas más usadas:

| Política | Qué controla |
|---|---|
| **RELIABILITY** | `BEST_EFFORT` (sin garantía, más rápido) vs `RELIABLE` (garantiza entrega, con reintentos/ACK) |
| **DURABILITY** | Si un suscriptor tardío recibe datos históricos: `VOLATILE`, `TRANSIENT_LOCAL`, `TRANSIENT`, `PERSISTENT` |
| **HISTORY** | Cuántas muestras se retienen: `KEEP_LAST(n)` o `KEEP_ALL` |
| **DEADLINE** | Tiempo máximo esperado entre actualizaciones de datos |
| **LIVELINESS** | Cómo se detecta que un writer/reader sigue "vivo" |
| **OWNERSHIP** | `SHARED` (todos los writers publican) vs `EXCLUSIVE` (solo el de mayor prioridad) |
| **PARTITION** | Segmentación lógica dentro de un mismo dominio (como "sub-canales") |
| **LATENCY_BUDGET** | Sugerencia de cuánta latencia es aceptable, para optimizar el envío |
| **RESOURCE_LIMITS** | Límites de memoria/buffers (máx. muestras, instancias, etc.) |
| **TIME_BASED_FILTER** | Frecuencia mínima entre entregas al Reader (throttling) |
| **DESTINATION_ORDER** | Orden de aplicación de las muestras (por timestamp de origen o recepción) |

### ¿Es un archivo XML?

Sí, **normalmente**. Tanto Cyclone DDS como RTI Connext (y la mayoría de implementaciones) permiten definir **perfiles de QoS en XML**, cargados en tiempo de ejecución. Esto es lo más recomendable porque:

- Puedes cambiar el comportamiento **sin recompilar** el código.
- Puedes tener perfiles distintos para dev/staging/producción.
- Es el formato estándar entre implementaciones (aunque cada vendor añade extensiones propias vía namespaces).

También puedes configurar QoS **programáticamente** (en C++, Python, etc.) creando objetos `QosPolicy` directamente en código. El XML es preferido para configuración "declarativa" y reusable; el código es útil cuando necesitas lógica dinámica.

### Ejemplo de un archivo XML de QoS (genérico, estilo OMG/RTI)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<dds xmlns="http://www.omg.org/dds/">
  <qos_library name="MiLibreriaQoS">
    <qos_profile name="SensorPerfil" is_default_qos="true">
      <datawriter_qos>
        <reliability>
          <kind>RELIABLE_RELIABILITY_QOS</kind>
        </reliability>
        <durability>
          <kind>TRANSIENT_LOCAL_DURABILITY_QOS</kind>
        </durability>
        <history>
          <kind>KEEP_LAST_HISTORY_QOS</kind>
          <depth>10</depth>
        </history>
        <deadline>
          <period>
            <sec>1</sec>
            <nanosec>0</nanosec>
          </period>
        </deadline>
      </datawriter_qos>
      <datareader_qos>
        <reliability>
          <kind>RELIABLE_RELIABILITY_QOS</kind>
        </reliability>
        <history>
          <kind>KEEP_LAST_HISTORY_QOS</kind>
          <depth>10</depth>
        </history>
      </datareader_qos>
    </qos_profile>
  </qos_library>
</dds>
```

Para **Cyclone DDS**, la configuración global (no solo QoS de topic, sino red, discovery, seguridad) va en un archivo separado apuntado por la variable de entorno `CYCLONEDDS_URI`, con su propio esquema XML (`CycloneDDS`), y el QoS de entidades específicas normalmente se hace vía código o perfiles XML tipo OMG también soportados parcialmente.

---

## 3. Cómo crear tu propia configuración (pasos prácticos)

1. **Define qué necesitas de verdad**: ¿tolerancia a pérdida de paquetes? ¿necesitas historial para nodos que se conectan tarde? ¿límites de memoria estrictos (embebidos)?
2. **Empieza de un perfil base** (`is_default_qos="true"`) y ve creando perfiles hijos con `base_name` que hereden y sobreescriban solo lo necesario.
3. **Separa QoS de Writer y de Reader** cuando el comportamiento difiera (ej. Writer con `TRANSIENT_LOCAL` pero Reader que solo quiere lo último).
4. **Verifica compatibilidad**: reglas como *RELIABLE reader no puede unirse a BEST_EFFORT writer*, *DURABILITY del reader no puede ser "más exigente" que la del writer*, etc.
5. **Prueba con herramientas de introspección** (RTI Admin Console, `ddsperf`/`cyclonedds-tools` en Cyclone) para confirmar que el *match* ocurre.
6. **Versiona el XML junto a tu código** y cárgalo por variable de entorno o parámetro al crear el `DomainParticipant`.

### Ventajas de tener tu propia configuración QoS

- **Desacoplas comportamiento de código**: cambiar reliability/durability no implica recompilar ni redeployar binarios.
- **Optimizas ancho de banda y CPU**: por ejemplo, `BEST_EFFORT` + `KEEP_LAST(1)` para telemetría de alta frecuencia donde el dato viejo no importa.
- **Garantizas entrega crítica** donde sí importa (comandos, alarmas) usando `RELIABLE` + `TRANSIENT_LOCAL`.
- **Controlas el uso de memoria** en sistemas embebidos con `RESOURCE_LIMITS`.
- **Aíslas lógicamente tráfico** con `PARTITION` sin necesitar dominios distintos.
- **Facilitas late-joiners** (nodos que arrancan después) gracias a `DURABILITY`.

---

## 4. Cyclone DDS vs RTI Connext DDS

| Aspecto | Eclipse Cyclone DDS | RTI Connext DDS |
|---|---|---|
| Licencia | Open source (EPL/EDL), gratuito | Comercial (licencia de pago; existe *Community Edition* limitada) |
| Origen | Eclipse Foundation (antes ADLINK) | RTI (Real-Time Innovations) |
| Lenguaje núcleo | C (con binding C++/Python oficiales) | C++ (con bindings oficiales en C, Java, .NET, Python) |
| Uso en ROS 2 | **Middleware por defecto en muchas distros** (`rmw_cyclonedds`) | Soportado también (`rmw_connext`, menos común hoy) |
| Herramientas | `cyclonedds-tools`, Wireshark dissector, más ligero en tooling | Ecosistema muy completo: Admin Console (GUI), Recording/Replay Service, Routing Service, Prototyper |
| DDS Security | Soportado (spec completa en versiones recientes) | Soportado, muy maduro, ampliamente usado en defensa/aeroespacial |
| Soporte comercial | Vía ZettaScale (empresa detrás de Cyclone) | Soporte oficial de RTI, SLAs empresariales |
| Rendimiento | Excelente, muy liviano, buen footprint en embebidos | Excelente, con muchísimas perillas de tuning fino |
| Curva de adopción | Más simple para empezar, buena documentación comunitaria | Más features, pero más complejidad y curva de aprendizaje |
| Casos típicos | Robótica (ROS 2), proyectos open source, IoT | Defensa, aeroespacial, automoción, sistemas críticos con soporte 24/7 |

**En resumen**: si buscas algo gratuito, ligero y con gran integración a ROS 2 → Cyclone DDS. Si necesitas herramientas empresariales robustas (grabación, ruteo, soporte contractual) y no te importa el costo de licencia → RTI Connext.

---

## 5. Integración con Python

### Cyclone DDS (paquete oficial `cyclonedds`)

```bash
pip install cyclonedds
```

```python
from dataclasses import dataclass
from cyclonedds.domain import DomainParticipant
from cyclonedds.topic import Topic
from cyclonedds.pub import DataWriter
from cyclonedds.sub import DataReader
from cyclonedds.idl import IdlStruct

@dataclass
class Sensor(IdlStruct):
    id: int
    temperatura: float

dp = DomainParticipant(domain_id=0)
topic = Topic(dp, "SensorTopic", Sensor)

writer = DataWriter(dp, topic)
writer.write(Sensor(id=1, temperatura=23.5))

reader = DataReader(dp, topic)
for muestra in reader.take(10):
    print(muestra)
```

El QoS se puede pasar en la creación del Topic/Writer/Reader como objeto `Qos`, o cargarse desde el XML apuntado por `CYCLONEDDS_URI`.

### RTI Connext DDS (paquete oficial `rti.connext`)

```bash
pip install rti.connext
```

```python
import rti.connextdds as dds

participant = dds.DomainParticipant(domain_id=0)
provider = dds.QosProvider("mi_qos.xml")
topic = dds.Topic(participant, "SensorTopic", MiTipo)

writer_qos = provider.datawriter_qos_from_profile("MiLibreriaQoS::SensorPerfil")
writer = dds.DataWriter(participant.implicit_publisher, topic, writer_qos)
```

Ambos bindings son oficiales y de primer nivel (no wrappers de terceros).

---

## 6. Integración con JavaScript / Node.js

Aquí la situación es distinta: **no existen bindings oficiales nativos y maduros de DDS para JS** como sí los hay para Python. Las rutas típicas son:

1. **Gateway/bridge REST o WebSocket**: exponer DDS hacia el navegador/Node vía un servicio intermedio.
   - RTI ofrece **Connext DDS Web Integration Service** (REST sobre DDS).
   - Con Cyclone DDS puedes montar tu propio microservicio (por ejemplo en Python con `cyclonedds` + FastAPI/WebSocket) que reexponga los datos a JS.
   - **Zenoh** (protocolo hermano, interoperable con DDS vía `zenoh-bridge-dds`) tiene bindings de JS/WASM bastante más maduros, y es una alternativa popular cuando se necesita llegar a navegador.
2. **Bindings nativos no oficiales**: existen intentos de wrappers Node (FFI sobre las librerías C de Cyclone DDS), pero no son mantenidos oficialmente ni recomendados para producción.

**Recomendación práctica**: si necesitas JS (especialmente en navegador), monta un puente WebSocket/REST en Python o C++ que hable DDS "de verdad" y traduzca hacia/desde JSON para el lado JS.

---

## 7. Proteger la visibilidad de los datos en conexiones multicast

Multicast por sí solo **no es privado**: cualquier equipo en esa red/VLAN que se una al grupo multicast puede ver el tráfico. Para proteger la visibilidad tienes varias capas, de menor a mayor robustez:

1. **Segmentación de red**: VLANs dedicadas, firewalls, limitar el **TTL del multicast** para que no salga del segmento deseado.
2. **PARTITION QoS**: separa lógicamente el tráfico dentro del mismo dominio (no es cifrado, es solo filtrado a nivel de aplicación DDS).
3. **Usar unicast en vez de multicast** cuando el número de peers es reducido y la confidencialidad importa más que la eficiencia de replicación.
4. **DDS Security (la solución real y estándar)**: la especificación OMG DDS Security añade tres plugins:
   - **Authentication**: verifica identidad de cada `DomainParticipant` con certificados X.509/PKI.
   - **Access Control**: define permisos granulares (qué participante puede publicar/suscribir a qué Topic), vía un `permissions.xml` firmado.
   - **Cryptographic**: cifra el payload (y opcionalmente los submensajes RTPS completos) con AES-GCM, de forma que aunque alguien capture el tráfico multicast, no puede leer el contenido sin las claves.

   Tanto Cyclone DDS como RTI Connext implementan DDS Security. La configuración típica requiere: certificado de la CA, certificado/clave del participante, y archivos de gobernanza (`governance.xml`) y permisos (`permissions.xml`), todos referenciados desde el QoS de seguridad del participante.

En resumen: **multicast + DDS Security** te da difusión eficiente **y** confidencialidad/autenticación real; multicast solo, no.

---

## 8. Dominios: qué son y cuántos puedes crear

Un **Domain** es un espacio de comunicación completamente aislado: dos `DomainParticipant` con distinto `domain_id` **nunca se descubren entre sí**, aunque estén en la misma red física. Es la forma más fuerte de aislar tráfico (más fuerte que `PARTITION`, que solo filtra dentro del mismo dominio).

- El `domain_id` es un entero.
- **Límite práctico**: la fórmula estándar de asignación de puertos UDP del protocolo RTPS (definida en la spec de DDS-RTPS) reserva un rango de puertos por cada `domain_id`, lo que en la práctica limita el rango recomendado a **domain IDs entre 0 y 232** (algunas implementaciones documentan el límite superior en 232 o 233 dependiendo de la fórmula exacta de puertos usada) para evitar colisiones o desbordar el rango de puertos disponibles (0–65535).
- Si necesitas más "dominios" de los que la fórmula por defecto permite, puedes **personalizar el mapeo de puertos** en la configuración de transporte (tanto Cyclone como RTI lo permiten), lo cual técnicamente habilita usar valores fuera del rango por defecto.
- En la práctica casi ningún sistema necesita más de un puñado de dominios (se usan típicamente para separar: simulación vs producción, distintos robots/vehículos, o entornos de test).

---

## 9. ¿Qué tan parecido es a peer-to-peer? ¿Qué es un "peer" en DDS?

Tu intuición es correcta: DDS **es, en su núcleo, peer-to-peer**, no cliente-servidor.

- No hay broker: cada nodo (`DomainParticipant`) se anuncia a sí mismo en la red usando el **Simple Discovery Protocol**:
  - **SPDP** (Simple Participant Discovery Protocol): anuncia la existencia del participante, típicamente vía multicast.
  - **SEDP** (Simple Endpoint Discovery Protocol): una vez los participantes se conocen, intercambian información sobre sus DataWriters/DataReaders (topics, tipos, QoS) directamente entre ellos.
- Tras el descubrimiento, el **intercambio de datos ocurre directamente** entre el Writer y el Reader (unicast o multicast), sin intermediarios.

En este contexto, un **"peer"** en DDS es, esencialmente, **un `DomainParticipant`**: una instancia de aplicación conectada al bus DDS que anuncia su propia existencia y sus endpoints (writers/readers), y que descubre y se comunica directamente con los demás participantes del mismo dominio — igual que en una red P2P clásica los nodos se anuncian y comparten información sin depender de un servidor central. La diferencia respecto a un P2P "puro" de archivos es que aquí la comunicación está **estructurada por Topics tipados y gobernada por contratos de QoS**, en vez de ser un intercambio arbitrario de datos.

---

## Resumen rápido

- **DDS** = pub/sub descentralizado, peer-to-peer real, sobre RTPS/UDP.
- **QoS** = contratos de comportamiento (no de contenido) que deben ser compatibles entre Writer y Reader.
- **La config sí suele ser XML**, cargable en runtime, aunque también programable en código.
- **Cyclone DDS**: gratis, liviano, estándar en ROS 2. **RTI Connext**: comercial, más herramientas y soporte.
- **Python**: bindings oficiales en ambos. **JS**: sin binding nativo maduro, se usa un gateway/bridge (o Zenoh).
- **Multicast no es privado por sí solo**: usa DDS Security (auth + access control + cifrado) para protegerlo de verdad.
- **Dominio** = aislamiento total; rango práctico por defecto ~0–232, ampliable personalizando puertos.
- **Peer** = un `DomainParticipant`; DDS es P2P por diseño, sin broker.

---

*Guía generada a partir del análisis del estándar OMG DDS, la documentación de RTI Connext y Cyclone DDS, y el middleware DDS del proyecto OpenICE / MD PnP.*