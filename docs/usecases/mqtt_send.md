# Caso de Uso: MQTT Send (Envío de Datos de Dispositivos vía MQTT)

**ID:** UC-MQTT-001  
**Versión:** 1.0  
**Fecha:** 2026-07-25  
**Actor Principal:** Clínico / Ingeniero Biomédico / Desarrollador  
**Sistema:** OpenICE — MqttSend (Módulo MQTT Send)

---

## 1. Breve Descripción

El MQTT Send permite al usuario enviar datos de dispositivos médicos en tiempo real desde el bus DDS de OpenICE hacia un broker MQTT externo. Los datos se publican como JSON en topics con la estructura `openice/{device_udi}/{metric_id}`, permitiendo que clientes externos (aplicaciones web, móviles, scripts Python, sistemas de monitoreo) reciban telemetría médica en tiempo real sin depender del middleware DDS.

---

## 2. Actores

| Actor | Descripción |
|---|---|
| **Usuario** (Clínico / Ingeniero / Desarrollador) | Opera la interfaz gráfica del MQTT Send: configura el broker, conecta/desconecta, monitorea dispositivos y logs. |
| **Dispositivo Médico ICE** | Cualquier dispositivo (simulado o real) conectado a OpenICE que publique tópicos DDS (Numeric, SampleArray, PatientAlert, TechnicalAlert). |
| **Bus DDS (RTI Connext)** | Middleware de datos en tiempo real que transporta las muestras desde los dispositivos hacia el MQTT Send. |
| **Broker MQTT** (Mosquitto u otro) | Servidor MQTT que recibe los mensajes publicados por MQTT Send y los distribuye a los subscribers conectados. |
| **Subscriber Externo** | Cliente MQTT (app web, Python, Java, CLI) que se suscribe a `openice/#` para recibir los datos de dispositivos. |

---

## 3. Precondiciones

1. El sistema OpenICE debe estar en ejecución con al menos un dispositivo médico (real o simulador) conectado y publicando datos en el bus DDS.
2. El MQTT Send debe estar registrado como `IceApplicationProvider` en el SPI (`org.mdpnp.apps.testapp.mqtt.MqttSendFactory`).
3. La interfaz de usuario del MQTT Send debe haberse cargado correctamente (ventana con configuración de broker, lista de dispositivos, botón Connect/Disconnect, log de publicación).
4. Un broker MQTT debe estar accesible en la URL configurada (default: `tcp://localhost:1883`).
5. Si el broker requiere autenticación, las credenciales deben estar configuradas.

---

## 4. Postcondiciones

### Postcondiciones de Éxito
1. Los datos de los dispositivos conectados se publican como JSON en el broker MQTT bajo topics con estructura `openice/{device_udi}/{metric_id}`.
2. El subscriber externo recibe los mensajes publicados.
3. La interfaz muestra el estado de conexión, el contador de mensajes enviados y un log de las publicaciones.
4. Los datos se reenvían automáticamente cada 5 segundos para garantizar que subscribers tardíos reciban datos frescos.

### Postcondiciones de Fracaso
1. No se publica ningún dato si no se establece la conexión MQTT o si ocurre un error durante la conexión.
2. Si ocurre un error durante la publicación, se registra en el log de la aplicación y se muestra en la interfaz.
3. Los datos que ya estaban en el bus DDS antes de la conexión MQTT se publican inmediatamente al conectar (publishAllExistingData).

---

## 5. Flujo Principal (Happy Path)

```
┌─────────────────────────────────────────────────────────────────┐
│  UC-MQTT-001: Enviar datos de dispositivos vía MQTT             │
├─────────────────────────────────────────────────────────────────┤
│  1. El usuario abre la aplicación MQTT Send desde el menú       │
│     de aplicaciones de OpenICE.                                 │
│  2. El sistema carga la interfaz con:                           │
│     - Panel superior: configuración de broker (URL, usuario,    │
│       contraseña, topic prefix)                                 │
│     - Botones Connect/Disconnect y estado                       │
│     - Panel izquierdo: lista de dispositivos conectados         │
│     - Panel central: log de publicaciones                       │
│  3. El sistema suscribe 4 FxLists al bus DDS:                   │
│     NumericFxList, SampleArrayFxList,                           │
│     PatientAlertFxList, TechnicalAlertFxList                    │
│  4. El sistema muestra los dispositivos conectados en la        │
│     lista izquierda con su estado [ON]/[OFF].                   │
│  5. El usuario configura la URL del broker MQTT                 │
│     (default: tcp://localhost:1883), usuario y contraseña.      │
│  6. El usuario hace clic en el botón "Connect".                 │
│  7. El sistema crea un MqttClient y se conecta al broker.       │
│  8. El broker responde CONNACK (conexión exitosa).              │
│  9. El sistema ejecuta publishAllExistingData():                │
│     9.1 Itera todos los NumericFx en numericList.               │
│     9.2 Itera todos los SampleArrayFx en sampleArrayList.       │
│     9.3 Itera todos los AlertFx en patientAlertList.            │
│     9.4 Itera todos los AlertFx en technicalAlertList.          │
│     9.5 Publica cada elemento como JSON al broker MQTT.         │
│ 10. El sistema inicia el republish periódico (cada 5 seg).      │
│ 11. La interfaz muestra "Connected" y el botón Connect se       │
│     deshabilita.                                                │
│ 12. El sistema comienza a escuchar cambios en los FxLists.      │
│ 13. Por cada cambio en un FxList:                               │
│     13.1 El ListChangeListener se dispara.                      │
│     13.2 Se serializa el dato a JSON.                           │
│     13.3 Se publica al broker MQTT con topic                    │
│          openice/{udi}/{metric_id}.                             │
│     13.4 Se incrementa el contador de mensajes enviados.        │
│ 14. Cada 5 segundos, el sistema reenvía todos los datos         │
│     actuales (republish periódico).                             │
│ 15. El usuario hace clic en el botón "Disconnect".              │
│ 16. El sistema detiene el republish periódico.                  │
│ 17. El sistema desconecta el MqttClient del broker.             │
│ 18. La interfaz muestra "Disconnected" y el botón Connect se    │
│     rehabilita.                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. Flujos Alternos

### FA-01: Broker no disponible
```
1. El usuario hace clic en "Connect".
2. El MqttClient intenta conectarse al broker.
3. El broker no responde o rechaza la conexión.
4. El sistema captura la MqttException.
5. La interfaz muestra "Error: <mensaje>" en el estado.
6. El log muestra "Connect failed: <mensaje>".
7. El botón permanece habilitado para reintentar.
```

### FA-02: Credenciales incorrectas
```
1. El usuario hace clic en "Connect" con usuario/contraseña.
2. El broker rechaza la conexión (CONNACK con código de error).
3. El sistema captura la MqttException.
4. La interfaz muestra "Error: <mensaje>".
5. El usuario corrige las credenciales y reintenta.
```

### FA-03: Conexión perdida durante operación
```
1. La conexión MQTT se pierde (broker caído, red, timeout).
2. El MqttCallback.connectionLost() se dispara.
3. El sistema establece connected = false.
4. La interfaz muestra "Connection lost: <mensaje>".
5. El republish periódico se detiene automáticamente.
6. Los listeners dejan de publicar (connected = false).
7. El usuario puede hacer clic en "Connect" para reconectar.
8. Al reconectar, se ejecuta publishAllExistingData() nuevamente.
```

### FA-04: Sin dispositivos conectados
```
1. El usuario abre el MQTT Send sin dispositivos en la red.
2. La lista de dispositivos se muestra vacía ("0 devices").
3. El usuario puede configurar el broker y conectar.
4. Al conectar, no se publica ningún dato (FxLists vacías).
5. Cuando un dispositivo se conecta y publica datos, estos 
   se publican automáticamente vía MQTT.
```

### FA-05: Desconexión de dispositivo durante operación
```
1. Un dispositivo que se estaba publicando se desconecta de 
   la red DDS.
2. El DeviceListModel detecta el cambio y actualiza la lista.
3. El dispositivo muestra [OFF] en la lista de dispositivos.
4. Los datos de ese dispositivo dejan de llegar a los FxLists.
5. Ya no se publican nuevos datos de ese dispositivo vía MQTT.
6. La operación continúa con los demás dispositivos.
```

### FA-06: Cambio de topic prefix
```
1. El usuario cambia el topic prefix mientras MQTT está conectado.
2. Los mensajes subsiguientes se publican con el nuevo prefix.
3. Los mensajes anteriores ya se publicaron con el prefix anterior.
4. El subscriber debe estar suscrito al nuevo prefix para 
   recibir los mensajes actualizados.
```

### FA-07: Error de serialización JSON
```
1. Un dato DDS contiene campos nulos o inválidos.
2. El sistema Omite el dato (return sin publicar).
3. El dato no se publica al broker MQTT.
4. El log no muestra ningún error (el dato se descarta silenciosamente).
5. La operación continúa con los siguientes datos.
```

---

## 7. Reglas de Negocio

| ID | Regla |
|---|---|
| RN-01 | El topic prefix por defecto es `"openice"` pero es configurable por el usuario. |
| RN-02 | El formato del topic es `{prefix}/{device_udi}/{metric_id}`. Los caracteres `/` y `#` en el UDI se reemplazan por `_`. |
| RN-03 | Se usa **QoS 1** (At Least Once) para todas las publicaciones. Los mensajes pueden duplicarse pero no se pierden. |
| RN-04 | No se usa flag `retain` en los mensajes. Los subscribers tardíos solo reciben datos vía el republish periódico. |
| RN-05 | `cleanSession=true` — no se mantienen sesiones persistentes en el broker. |
| RN-06 | El republish periódico ocurre cada **5 segundos** y reenvía **todos** los datos actuales de los 4 FxLists. |
| RN-07 | La lista de dispositivos muestra `[ON]` si `device.getConnected() == true`, `[OFF]` en caso contrario. |
| RN-08 | El log de publicaciones se mantiene con un máximo de **200 entradas**. Las más antiguas se descartan. |
| RN-09 | Los datos de tipo `Numeric` se publican con campos: device_udi, metric_id, vendor_metric_id, instance_id, value, unit_id, device_time, presentation_time. |
| RN-10 | Los datos de tipo `SampleArray` se publican con campos: device_udi, metric_id, vendor_metric_id, instance_id, frequency, unit_id, values (array), device_time. Se aplica throttling de 1 segundo por key (udi/metric_id). |
| RN-11 | Los datos de tipo `Alert` se publican con campos: device_udi, alert_type, identifier, text. |

---

## 8. Requisitos No Funcionales

| ID | Requisito |
|---|---|
| RNF-01 | La latencia entre la recepción de un sample DDS y su publicación MQTT debe ser inferior a 50 ms. |
| RNF-02 | La interfaz debe permanecer responsiva durante la publicación (operaciones MQTT en hilo separado). |
| RNF-03 | El sistema debe soportar la publicación simultánea de al menos 10 dispositivos. |
| RNF-04 | El republish periódico no debe causar degradación del rendimiento con más de 500 datos activos. |
| RNF-05 | El sistema debe registrar eventos de error en el log de la aplicación (`log4j2-test.xml` → `~/demo-apps.log`). |

---

## 9. Diagrama de Arquitectura (Flujo de Datos)

```
  ┌──────────────┐       ┌──────────────┐      ┌──────────────────┐
  │  Dispositivo │─────▶│  Bus DDS     │────▶│  NumericFxList   │
  │  Médico ICE  │       │  RTI Connext │      │  SampleArrayFx   │
  │  (HW/SW)     │       │  (Domain 10) │      │  AlertFxList     │
  └──────────────┘       └──────────────┘      └────────┬─────────┘
                                                        │
                                                        │ ListChangeListener
                                                        ▼
  ┌────────────────────────────────────────────────────────────────┐
  │                    MqttSend Controller                         │
  │  ┌─────────────────┐  ┌──────────────────┐  ┌───────────────┐  │
  │  │publishNumeric() │  │publishSampleArr()│  │publishAlert() │  │
  │  └────────┬────────┘  └───────┬──────────┘  └──────┬────────┘  │
  └───────────┼───────────────────┼────────────────────┼───────────┘
              │                   │                    │
              ▼                   ▼                    ▼
  ┌──────────────────────────────────────────────────────────────┐
  │              publishAllExistingData()                        │
  │  (al conectar + cada 5 segundos)                             │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │              MqttClient (Eclipse Paho)                       │
  │              QoS=1, cleanSession=true                        │
  └──────────────────────────┬───────────────────────────────────┘
                             │ PUBLISH openice/{udi}/{metric_id}
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │              Mosquitto Broker                                │
  │              (tcp://localhost:1883)                          │
  └──────────────────────────┬───────────────────────────────────┘
                             │
                             ▼
  ┌──────────────────────────────────────────────────────────────┐
  │              Subscriber Externo                              │
  │              (suscripto a openice/#)                         │
  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
  │  │  App Web     │  │  Script      │  │  App Móvil   │        │
  │  │  (JS/React)  │  │  Python      │  │  (Android/   │        │
  │  └──────────────┘  └──────────────┘  │  iOS)        │        │
  │                                      └──────────────┘        │
  └──────────────────────────────────────────────────────────────┘
```

---

## 10. Formato de Mensajes MQTT

### Topic Structure
```
{prefix}/{device_udi}/{metric_id}
```

Ejemplo:
```
openice/2480980189999/1.2.840.113554.1.2.3.4.5.0.38.6
```

### Payload — Numeric
```json
{
  "device_udi": "2480980189999...",
  "metric_id": "1.2.840.113554.1.2.3.4.5.0.38.6",
  "vendor_metric_id": "...",
  "instance_id": 1,
  "value": 72.0,
  "unit_id": "bpm",
  "device_time": "2026-07-25T10:30:00Z",
  "presentation_time": "2026-07-25T10:30:00Z"
}
```

### Payload — SampleArray (Waveforms)
```json
{
  "device_udi": "...",
  "metric_id": "...",
  "vendor_metric_id": "...",
  "instance_id": 1,
  "frequency": 250,
  "unit_id": "mV",
  "values": [0.1, 0.2, -0.3, 0.4, ...],
  "device_time": "2026-07-25T10:30:00Z"
}
```

### Payload — Alert
```json
{
  "device_udi": "...",
  "alert_type": "patient_alert",
  "identifier": "HR_HIGH",
  "text": "Heart rate above threshold"
}
```

---

## 11. Creación de un Subscriber Externo

### Python (paho-mqtt)
```python
import paho.mqtt.client as mqtt
import json

def on_message(client, userdata, msg):
    data = json.loads(msg.payload.decode())
    print(f"[{msg.topic}] device={data.get('device_udi')}, "
          f"metric={data.get('metric_id')}, "
          f"value={data.get('value')}")

client = mqtt.Client(client_id="openice-listener")
client.username_pw_set("openice", "openice")
client.connect("localhost", 1883)
client.subscribe("openice/#")  # wildcard para recibir todo
client.on_message = on_message
client.loop_forever()
```

### Java (Eclipse Paho)
```java
MqttClient client = new MqttClient("tcp://localhost:1883", "my-client");
MqttConnectOptions opts = new MqttConnectOptions();
opts.setUserName("openice");
opts.setPassword("openice".toCharArray());
opts.setCleanSession(true);
client.connect(opts);
client.subscribe("openice/#", 1);
client.setCallback(new MqttCallback() {
    public void messageArrived(String topic, MqttMessage msg) {
        System.out.println(topic + " → " + new String(msg.getPayload()));
    }
    public void connectionLost(Throwable cause) {}
    public void deliveryComplete(IMqttDeliveryToken token) {}
});
```

### CLI (mosquitto_sub)
```bash
mosquitto_sub -h localhost -t "openice/#" -u openice -P openice -v
```

**IMPORTANTE:** Siempre suscribirse a `"openice/#"` (con wildcard `#`), NO a `"openice"` solamente. MQTT Send publica a sub-topics como `openice/{udi}/{metric_id}`.

---

## 12. Notas y Observaciones

- El MQTT Send se registra en el SPI como `org.mdpnp.apps.testapp.mqtt.MqttSendFactory` con el nombre visible `"MQTT Send"` y tag `"NOMQTT"`.
- La interfaz incluye una lista de dispositivos conectados (panel izquierdo) que se actualiza en tiempo real desde `DeviceListModel.getContents()`.
- El `DeviceListModel` se inyecta desde el contexto Spring (`IceAppContainerContext.xml`) vía `parentContext.getBean("deviceListModel")`.
- Los 4 FxLists (Numeric, SampleArray, PatientAlert, TechnicalAlert) se crean internamente y se suscriben independientemente de otras apps.
- El republish periódico usa `ScheduledExecutorService` con `scheduleWithFixedDelay` de 5 segundos.
- El throttling de SampleArray evita publicar el mismo (udi/metric_id) más de una vez por segundo.
- La serialización JSON usa Jakarta JSON API (`jakarta.json`) con Parsson como implementación.
- Las dependencias MQTT están en `build.gradle`: `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`.

---

## 13. Historial de Revisiones

| Versión | Fecha | Autor | Cambio |
|---|---|---|---|
| 1.0 | 2026-07-25 | — | Versión inicial |
