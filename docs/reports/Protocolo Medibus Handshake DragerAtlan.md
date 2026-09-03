# Reporte Técnico: Preguntas y Exploración del Protocolo MEDIBUS vía Script DragerAtlanHandshake

**Fecha:** 2 de septiembre de 2026  
**Proyecto:** OpenICE / MD PnP (`1.5.1-SNAPSHOT`)  
**Sistema:** Driver Draeger Atlan A350-XL (MEDIBUS serial)  
**Alcance:** Documentación de preguntas, proceso y resultados del script `scripts/DragerAtlanHandshake.java`

---

## 1. Contexto

El script `scripts/DragerAtlanHandshake.java` establece comunicación **serial** con el ventilador/sistema de anestesia **Dräger Atlan A350-XL** mediante tramas del protocolo **MEDIBUS**. La configuración de puerto empleada es:

```
stty -F /dev/ttyUSB0 9600 cs8 -cstopb parenb -parodd raw
```

i.e. **8E1** (8 bits de datos, paridad par, 1 bit de parada, sin control de flujo). El script actúa como **maestro** (master) enviando comandos y leyendo las respuestas del Atlan (esclavo).

Este reporte **no resuelve** el protocolo: únicamente recopila las preguntas planteadas, describe qué hace el script y documenta los resultados observados en los logs.

---

## 2. Preguntas planteadas

Las siguientes preguntas fueron formuladas durante el trabajo de exploración. Se transcriben de forma literal y **sin responder**:

1. ¿Cómo funciona el envío de datos por **MEDIBUS-X**? ¿Es igual que en MEDIBUS?
2. Si es así, ¿cómo se envían los datos **específicamente para el Atlan**? ¿Cuántos **bytes** se envían?
3. Se revisó que **a veces son 6** bytes. Si son 6, ¿qué bytes se deben enviar? Se identificaron como candidatos: el **SOH**, el **command**, el **argument**, el **checksum** y el **CR**.
4. ¿Estos envían **Key codes** después del argument? Y si es así, ¿el **checksum** cubre **todos los bytes de la petición**?
5. Con relación a lo anterior: **¿qué se puede solicitar y cómo?** Estas preguntas se basan en un protocolo MEDIBUS documentado para el **Evita 2**.
6. En general, **¿realmente se usan 6 bytes?**

---

## 3. Proceso (qué hace el script)

### 3.1 Construcción de la trama de envío

El método `sendCommand(...)` (`scripts/DragerAtlanHandshake.java:92-109`) arma la trama de la siguiente forma:

- **Byte inicial:** `0x1B` (`<ESC>`).
- **Payload:** los bytes ASCII del comando (1 o más caracteres).
- **Checksum:** suma módulo 256 de todos los bytes **incluyendo** el `0x1B` inicial, expresado como **2 caracteres ASCII en hexadecimal en mayúsculas**.
- **Byte final:** `0x0D` (`CR`).

```java
frame[0] = 0x1B; // ESC
System.arraycopy(pBytes, 0, frame, 1, pBytes.length);
System.arraycopy(csBytes, 0, frame, 1 + pBytes.length, 2); // checksum
frame[frame.length - 1] = 0x0D; // CR
```

### 3.2 Flujo principal

1. **Handshake:** envío de `Q` (inicialización de comunicación).
2. **Solicitud de identificación del dispositivo:** envío de `R1` (device ID) y del bloque de identidad `6666'Hola'00.20:04.01`.
3. **Bucle de sondeo (*polling*):** envío periódico de distintos comandos: `$`, `%`, `&`, `+`, `,`, `-`, `*`, entre otros.

### 3.3 Barrido por fuerza bruta

Se probaron variaciones y sobrescrituras del script enviando códigos como `24`, `25`, `29`, etc., y se llegó a ejecutar un **bucle `for` recorriendo el byte de solicitud de 1 a 256** para probar todos los códigos de comando posibles.

---

## 4. Resultados observados

Resultados verificados en los logs de sesión:

- `scripts/atlan_realtime.txt`
- `scripts/atlan_realtime.log`
- `scripts/atlan_realtime2.txt`
- `scripts/drager_atlan_logs.txt`

### 4.1 Tramas de 5 bytes funcionan a veces

Se probó enviando **SOH [sería ESC], el byte de solicitud, el checksum y el CR (5 bytes en total)**. Con esta trama de 5 bytes **sí resultó** al solicitar **`52H` (R1/device ID), `53H`** y **`3FH`**, logrando obtener la **fecha y hora**.

Ejemplos de tramas de envío de 5 bytes verificadas:

| Comando | Trama HEX |
|---|---|
| `Q` (0x51) | `1B 51 36 43 0D` |
| `R1` (0x52 + argumento) | `1B 52 31 39 45 0D` |
| `$` (0x24) | `1B 24 33 46 0D` |

### 4.2 Respuestas con contenido real

- **`0x28` (fecha y hora):** devolvió contenido real en formato `HH:MM:SS DD-MMM-YY`:
  ```
  Recibido HEX: 01 28 31 35 3A 32 37 3A 31 32 32 31 2D 41 55 47 2D 32 36 44 31 0D
  ```
  (ASCII: `01 28 15:27:12 21-AUG-26 D1 0D`)

- **`0x53`:** devolvió una página extensa con campos etiquetados `FFF`:
  ```
  Recibido HEX: 01 53 30 30 20 20 20 32 30 30 30 30 2D 20 20 32 31 ... FFF ...
  ```

- **`0x52` (device ID):** devolvió la identificación del dispositivo:
  ```
  Recibido HEX: 01 52 35 35 35 35 27 41 33 58 58 27 30 32 2E 31 30 3A 30 36 2E 30 30 42 38 0D
  ```
  (ASCII: `01 52 5555'A3XX'02.10:06.00 B8 0D`)

### 4.3 Comandos que NO devolvieron datos

- **`0x24` (24H) y `0x25`:** devolvieron **solo un eco de cabecera sin contenido** de medición:
  ```
  Recibido HEX: 01 24 32 35 0D
  ```
  i.e. se recibe la cabecera de respuesta pero **sin** datos de medición.

- **No se logró** obtener datos de medición mediante `24H` ni alarma por esta vía de momento.

---

## 5. Pregunta abierta pendiente

A pesar de haberse confirmado la obtención de **fecha/hora** (`0x28`), **device ID** (`0x52`) y una **página de configuración** (`0x53`), sigue pendiente:

- ¿Cómo **solicitar y recibir datos de medición** (i.e. mediante `24H` / comando equivalente)?
- ¿Cómo **solicitar y recibir alarmas**?
- ¿Cuál es el conteo exacto y la estructura definitiva de bytes (¿5 o 6?) que requiere el Atlan para estas solicitudes?

---

## 6. Conclusión

- La trama de **5 bytes** (`ESC + comando + checksum(2) + CR`) **sí fue validada** para los comandos `52H`, `53H`, `3FH`, y permitió obtener **fecha y hora**, **device ID** y **página de configuración**.
- El comando `24H` (solicitud de datos de medición actuales) devolvió **solo la cabecera de respuesta sin datos**, por lo que el mecanismo para obtener mediciones y alarmas **no se ha logrado aún** y queda documentado como **pendiente**.
- Este reporte es **solo de documentación**: las preguntas del punto 2 permanecen sin respuesta técnica en este documento.

---

*Reporte generado a partir del análisis del script `scripts/DragerAtlanHandshake.java` y de los logs de sesión `scripts/atlan_realtime.txt`, `scripts/atlan_realtime.log`, `scripts/atlan_realtime2.txt` y `scripts/drager_atlan_logs.txt`.*
