# OpenICE Headless Adapter

El **Headless Adapter** es un submódulo especializado del proyecto OpenICE diseñado para ejecutar controladores de dispositivos (Device Adapters) en entornos embebidos y de bajos recursos, como una **Raspberry Pi** o contenedores Docker en servidores sin interfaz gráfica.

## 🎯 ¿Qué hace?

El propósito principal de este submódulo es **independizar la ejecución del hardware médico del Supervisor UI**. 
En la arquitectura original de OpenICE, levantar un driver de dispositivo requería arrancar el entorno gráfico completo (JavaFX y `IceAppsContainer`), lo que resultaba en un alto consumo de memoria RAM y errores de dependencias de renderizado en sistemas Linux reducidos o arquitecturas ARM (aarch64).

El Headless Adapter soluciona esto ejecutando **únicamente** el motor lógico del dispositivo, el ciclo de vida de conexión y la capa de publicación de datos hacia la red DDS, con un consumo de recursos mínimo y sin necesidad de librerías visuales.

## ⚙️ ¿Cómo funciona internamente?

El flujo de ejecución del Headless Adapter se basa en la Inyección de Dependencias (Spring) y el cargador de proveedores de servicios nativo de Java (SPI):

1. **Punto de Entrada (`HeadlessMain.java`):**
   Actúa como el motor de arranque ligero. En lugar de levantar la interfaz gráfica, parsea directamente los argumentos de línea de comandos (ej. `--device DraegerV500 --domain 10`) y configura el dominio de comunicación de RTI DDS.

2. **Carga Dinámica (SPI y `DeviceDriverProvider`):**
   Para saber qué dispositivo debe inicializar (y qué clases de Java instanciar), el sistema lee el archivo `META-INF/services/org.mdpnp.devices.DeviceDriverProvider`. Este archivo contiene una lista de todos los adaptadores físicos y simulados disponibles. 
   El `HeadlessMain` busca un `DeviceType.getAlias()` que coincida con el argumento introducido por el usuario.

3. **Inyección de Dependencias (`DeviceAdapterContext.xml`):**
   Una vez identificado el dispositivo, arranca un contexto de **Spring Framework**. Este contexto inyecta los objetos de infraestructura fundamentales que todo dispositivo necesita:
   - `Publisher` y `Subscriber` de DDS (para la red local).
   - `EventLoop` (para manejar la concurrencia y los hilos de red/serie).

4. **Gestión de Librerías Nativas (RTI DDS):**
   Gracias a las adaptaciones en su `build.gradle`, este módulo es capaz de determinar dinámicamente en qué procesador se está ejecutando (`uname -m`). Automáticamente enlaza las librerías dinámicas (`.so`) correctas: `aarch64` si corre en Raspberry Pi o `x64_64` si corre en un servidor/PC Linux.

5. **Ciclo de Vida Activo:**
   Al arrancar, el `Device` queda corriendo como un demonio en segundo plano (generalmente gestionado por `systemd` y el script `device_adapter.sh`), escuchando el puerto físico (Ethernet o RS-232), traduciendo el protocolo de la máquina médica, y publicando los datos estandarizados a DDS para que cualquier Supervisor en la red pueda graficarlos.

## 🚀 Despliegue

La compilación y despliegue del módulo headless está completamente automatizada mediante el script ubicado en la raíz del proyecto:

```bash
# Ejemplo: Compila el código, arma la distribución headless y la instala como un demonio del sistema para que inicie al encender la placa
./device_adapter.sh install DraegerV500 15
```

Si deseas ejecutarlo temporalmente en modo de desarrollo:
```bash
./gradlew :headless-adapter:run --args="--device DraegerV500 --domain 15"
```

---

## 🔌 Configuración de Puertos Seriales y Ejecución de Simuladores (LAN / Serial)

Esta sección detalla cómo configurar los puertos serie en Linux (como la Raspberry Pi y PCs) y los comandos correspondientes para arrancar los simuladores de **Philips Efficia** y **Dräger Atlan**.

### 🛠️ 1. Configuración de Baudios y Modo Serial en Linux

Antes de arrancar cualquier driver o simulador que utilice el puerto serie, es mandatorio configurar los parámetros del puerto (velocidad, paridad, bits de datos, etc.) desde la terminal.

#### Comandos de configuración (`stty`):
* **Configuración para Medibus (Dräger Atlan / Apollo / Evita) - Modo Original (8E1 - Con Paridad Par):**
  *(Requiere soporte de paridad por hardware, ej. en `/dev/ttyUSB0` o el UART completo `/dev/ttyAMA0` de la Pi).*
  ```bash
  stty -F /dev/ttyUSB0 19200 parenb -parodd cs8 -cstopb raw
  ```

* **Configuración para Medibus (Dräger Atlan) - Modo Sin Paridad (8N1):**
  *(Recomendado para usar en el mini UART `/dev/ttyS0` de la Raspberry Pi, el cual no soporta paridad por hardware).*
  ```bash
  stty -F /dev/ttyS0 19200 -parenb cs8 -cstopb raw
  ```

* **Configuración para Philips Efficia (8N1 - 9600 baudios):**
  ```bash
  stty -F /dev/ttyS0 9600 -parenb cs8 -cstopb raw
  ```

* **Restablecer el puerto a su estado normal (después de usarlo):**
  ```bash
  stty -F /dev/ttyS0 sane
  ```

* **Dar permisos al usuario para acceder al puerto serial:**
  ```bash
  sudo usermod -aG dialout $USER
  # (Requiere cerrar sesión y volver a entrar, o ejecutar `newgrp dialout`)
  ```

---

### 🩺 2. Ejecución del Simulador Philips Efficia (`EfficiaMonitor`)

El simulador genera datos y los transmite por red local (HL7/TCP) y/o emite tramas MLLP por el puerto serie.

#### Modo RED (HL7 vía TCP/IP):
En este modo, el simulador en la Raspberry Pi se conecta a un servidor/listener (como `EfficiaHL7Listener.java`) corriendo en tu PC.

1. **En la PC receptora (iniciar servidor TCP):**
   *(Asegúrate de que `useSerial = false` esté configurado en `EfficiaHL7Listener.java`)*
   ```bash
   javac scripts/EfficiaHL7Listener.java && java -cp scripts EfficiaHL7Listener
   ```
2. **En la Raspberry Pi (iniciar simulador cliente):**
   ```bash
   # Reemplaza 192.168.1.100 con la IP de tu PC receptora
   ./gradlew :headless-adapter:run \
     --args="-domain 0 -device EfficiaMonitor" \
     -Defficia.hl7.host=192.168.1.100
   ```

#### Modo SERIAL (HL7 MLLP vía RS-232):
El simulador transmite de forma continua datos por puerto serie directamente hacia el puerto serial del PC receptor.

1. **En la PC receptora (iniciar listener serial):**
   *(Asegúrate de que `useSerial = true` esté configurado en `EfficiaHL7Listener.java`)*
   ```bash
   stty -F /dev/ttyUSB0 9600 -parenb cs8 -cstopb raw
   java -cp scripts EfficiaHL7Listener
   ```
2. **En la Raspberry Pi (iniciar simulador con salida serial):**
   ```bash
   stty -F /dev/ttyS0 9600 -parenb cs8 -cstopb raw
   # El simulador abrirá automáticamente /dev/ttyS0 debido al parámetro -address
   ./gradlew :headless-adapter:run \
     --args="-domain 0 -device EfficiaMonitor -address /dev/ttyS0"
   ```

---

### 💨 3. Ejecución del Simulador Dräger Atlan (`DraegerAtlan`)

El simulador actúa como un dispositivo Medibus Slave (respondiendo a comandos sobre serial) y/o transmite tramas de tendencias HL7 sobre TCP.

#### Modo RED (HL7 vía TCP/IP):
1. **En la PC receptora (iniciar servidor HL7):**
   ```bash
   python3 scripts/atlan_receiver.py --lan --port 2575
   ```
2. **En la Raspberry Pi (iniciar simulador):**
   ```bash
   # Reemplaza 192.168.1.100 con la IP de tu PC
   ./gradlew :headless-adapter:run \
     --args="-domain 0 -device DraegerAtlan" \
     -Datlan.hl7.host=192.168.1.100
   ```

#### Modo SERIAL (Medibus Slave / Master):
El script en Python de tu PC actúa como Medibus Master (haciendo peticiones) y la Raspberry Pi actúa como Medibus Slave (respondiendo con datos simulados).

1. **En la PC receptora (iniciar cliente master Medibus):**
   ```bash
   # Configura el puerto sin paridad (8N1) para que sea compatible con el ttyS0 de la Pi
   stty -F /dev/ttyUSB0 19200 -parenb cs8 -cstopb raw
   python3 scripts/atlan_receiver.py --serial /dev/ttyUSB0
   ```
2. **En la Raspberry Pi (iniciar simulador slave Medibus):**
   ```bash
   stty -F /dev/ttyS0 19200 -parenb cs8 -cstopb raw
   # El simulador responderá en /dev/ttyS0 gracias al parámetro -address
   ./gradlew :headless-adapter:run \
     --args="-domain 0 -device DraegerAtlan -address /dev/ttyS0"
   ```
