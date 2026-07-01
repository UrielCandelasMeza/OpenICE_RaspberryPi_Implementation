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
