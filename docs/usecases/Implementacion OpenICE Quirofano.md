# Caso de Uso: Implementación OpenICE Quirófano (Supervisor y Flujo Clínico)

**ID:** UC-QUI-001
**Versión:** 1.1
**Fecha:** 2026-08-27
**Actor Principal:** Anestesista (operación) con soporte de Biomédica
**Sistema:** Supervisor OpenICE (formato Android) + nodo Raspberry Pi
**Alcance:** Conexión del Supervisor con los dispositivos del quirófano, monitoreo, control de unidades y envío de datos clínicos (FHIR).

---

## 1. Breve Descripción

La implementación del quirófano de OpenICE con una Raspberry Pi se divide en tres secciones: el **Supervisor**, el **perfil** y el **usuario**.

- **Supervisor:** aplicación OpenICE en formato **Android**, con acceso a internet y al compilado para Android. Permite al usuario entrar al quirófano, ver los dispositivos conectados y sus métricas.
- **Perfil:** entidad lógica que define con qué dispositivos (headless adapters) se realiza la conexión en cada quirófano. Es gestionado por **Biomédica** desde el nodo Raspberry Pi.
- **Usuario:** el **Anestesista** opera en sala; **Biomédica** apoya, administra los perfiles y ejecuta el protocolo de cambio de raspberry en caso necesario.

---

## 2. Actores y Niveles de Privilegio

| Rol | Sección / Acción |
|---|---|
| **Anestesista** | Entra al quirófano, conecta los medical devices, prueba la conexión, escanea el QR, ve gráficas y métricas, inicia la cirugía. No administra perfiles ni mueve dispositivos. |
| **Biomédica** | Soporte ante problemas de OpenICE. Administra/cambia perfiles y mueve dispositivos. Dispara y valida el failover de raspberry (protocolo definido en el documento *Interfaz Raspberry Pi*). |

---

## 3. Flujo Principal (Happy Path)

```
UC-QUI-001: Operar el quirófano con el Supervisor OpenICE
  1. El usuario (Anestesista) ingresa al quirófano con OpenICE en
     formato Android (acceso a internet y al compilado para Android).
  2. Se conectan los medical devices al paciente.
  3. Se enciende la Raspberry Pi y se conectan los medical devices a
     la raspberry.
  4. Se realizan los test de conexión con los medical devices en la
     raspberry (1 minuto por headless, secuencial).
  5. Una vez exitosos, se "trae" el Supervisor:
     5.1 En la raspberry se genera un **QR de conexión** (por Biomédica).
     5.2 El Supervisor lo **escanea** para realizar la conexión.
     5.3 Si a la tablet no le funciona la cámara, se ingresa el
         **domain manualmente**, visible en la pantalla del QR o en la
         pantalla principal de la raspberry.
  6. Una vez escaneado el QR / ingresado el domain, el Supervisor se
     conecta y muestra en pantalla los **3 dispositivos médicos**.
  7. El usuario selecciona un dispositivo y ve **gráficas
     (valor-tiempo)** de cada métrica medida.
  8. El usuario puede **cambiar las unidades** entre UCUM y las otras
     unidades definidas.
  9. El usuario marca el botón **"inicio de cirugía"**.
 10. El inicio de cirugía activa el envío de datos mediante **FHIR
     (HL7 Exporter)** por detrás.
 11. Durante la cirugía, el Anestesista **no puede dejar de enviar
     datos**: OpenICE solo activa las apps una vez iniciada la cirugía.
```

---

## 4. QR de Conexión y Domain

- **Contenido del QR:** `domain` (asignado por la API), headless activos y datos del paciente (**desde el SQLite local del nodo**, no de la API).
- **Generación/validación:** Biomédica (con PIN), bajo demanda y con caducidad.
- **Escaneo:** lo realiza el Anestesista para abrir el Supervisor.
- **Fallback sin cámara:** el `domain` se ingresa manualmente; se muestra en la pantalla del QR y en la pantalla principal de la raspberry.

Ver detalles completos en el documento **Interfaz Raspberry Pi** (UC-RPI).

---

## 5. Verificación de Paciente en Cirugía

Antes de habilitar el perfil y operar, el nodo confirma que el paciente está en **cirugía activa** (API del hospital + caché local). Si no está en cirugía, se muestra aviso y se pide confirmación explícita. Esto impide operar "por si las dudas" sin corroborar el estado clínico.

---

## 6. Gráficas y Cambio de Unidades

- El Supervisor muestra, por cada dispositivo seleccionado, las **gráficas valor-tiempo** de cada métrica medida.
- Permite **cambiar las unidades** entre **UCUM** y las otras unidades ya definidas.

---

## 7. Inicio de Cirugía y Envío FHIR

- Un botón marca el **inicio de la cirugía**.
- A partir de ese momento los datos se envían mediante **FHIR (HL7 Exporter)** por detrás.
- El **Anestesista no puede dejar de enviar datos**.
- Para que OpenICE envie los datos, **solo activa las apps una vez iniciada la cirugía**.

---

## 8. Soporte de Biomédica y Cambio de Raspberry

- Si hay algún problema con OpenICE, se debe contactar **inmediatamente con el área de Biomédica**, quienes brindan apoyo al Anestesista (usuario) para un mejor uso del sistema.
- Cuando es necesario, **Biomédica** podrá **cambiar la raspberry** siguiendo el **protocolo de failover** definido en el documento *Interfaz Raspberry Pi* (UC-RPI, sección de failover), donde el dispositivo/raspi de reemplazo se reaprovisiona desde la base de datos.

---

## 9. Reglas de Negocio

| ID | Regla |
|---|---|
| RN-01 | La conexión del Supervisor se realiza escaneando el **QR** o ingresando el **domain** manualmente si la cámara falla. |
| RN-02 | La creación/cambio de perfiles y el movimiento de dispositivos es **exclusivo de Biomédica**. |
| RN-03 | El **inicio de cirugía** es condición necesaria para que OpenICE **active las apps** y **envíe datos mediante FHIR (HL7 Exporter)**. |
| RN-04 | Durante la cirugía, el **Anestesista no puede dejar de enviar datos**. |
| RN-05 | El **QR** lleva `domain` (API) + headless activos + datos del paciente (**desde SQLite**); Biomédica lo genera, el Anestesista lo escanea. |
| RN-06 | El **cambio de raspberry** solo lo ejecuta **Biomédica** mediante el protocolo de failover (referencia cruzada a UC-RPI). |

---

## 10. Flujos Alternos

### FA-01: Cámara del Supervisor no funciona
1. El usuario no puede escanear el QR.
2. Se **ingresa el domain manualmente** (visible en pantalla del QR / pantalla principal de la raspi).
3. La conexión continúa igual.

### FA-02: Test de conexión de medical devices falla
1. Algún headless no logra conexión ni envío de datos dentro de 1 minuto.
2. Se registra el fallo en los **logs** del nodo.
3. Se comunica a **Biomédica** para diagnóstico/soporte.

### FA-03: Problema con OpenICE
1. Ocurre un problema con el sistema.
2. Se contacta **inmediatamente** con **Biomédica**.
3. Biomédica brinda apoyo al Anestesista y, de ser necesario, **cambia la raspberry** mediante el protocolo de failover.

---

## 11. Notas y Observaciones

- Este caso de uso se complementa con **`Interfaz RaspberryPi.md`** (UC-RPI), que describe el nodo Raspberry Pi, su modo offline (SQLite), daemons, seguridad y failover.
- La **fuente de verdad** (perfiles, dispositivos, ubicación y estado del lapso) reside en la **API propia / DB**; ni la raspberry ni el supervisor la adjudican por su cuenta.
- Los **datos del paciente** se tratan como PHI: protección en tránsito (TLS), en reposo (cifrado) y sin exposición innecesaria.

---

## 12. Historial de Revisiones

| Versión | Fecha | Autor | Cambio |
|---|---|---|---|
| 1.0 | — | — | Versión inicial (borrador de requisitos). |
| 1.1 | 2026-08-27 | — | Especificación formal: roles (Anestesista/Biomédica), QR/domain, verificación de paciente en cirugía, inicio de cirugía y envío FHIR, soporte de Biomédica y failover de raspberry. |

---

*Caso de uso generado a partir de la revisión del plan de implementación del quirófano y su reconciliación con el caso de uso de la interfaz Raspberry Pi.*
