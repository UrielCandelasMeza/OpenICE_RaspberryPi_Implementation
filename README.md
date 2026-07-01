# OpenICE (Medical Device Plug-and-Play)

[![Join the chat at https://gitter.im/mdpnp/mdpnp](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/mdpnp/mdpnp?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Latest Release](https://img.shields.io/github/release/mdpnp/mdpnp.svg)](https://github.com/mdpnp/mdpnp/releases/latest)

## 🏥 ¿Qué puede hacer este sistema?

OpenICE es una plataforma open-source orientada a la interoperabilidad de equipos médicos. Utilizando el middleware DDS (Data Distribution Service) como columna vertebral, OpenICE permite:

1. **Conectar hardware médico físico:** Extraer telemetría (frecuencias cardíacas, respiratorias, presiones) y curvas en tiempo real de monitores comerciales (Draeger, Philips, Nellcor, Nonin) sin depender de sistemas cerrados.
2. **Visualización y Supervisión Centralizada (Supervisor):** Provee una interfaz gráfica rica (JavaFX) donde los médicos e ingenieros pueden visualizar el estatus, alarmas y signos vitales de múltiples pacientes simultáneamente a través de la red local.
3. **Simulación de Dispositivos:** Capacidad de instanciar bombas de infusión simuladas, monitores multiparamétricos y sensores de ECG de prueba para validar lógicas de software sin hardware real.
4. **Despliegue Distribuido:** Mediante el módulo `headless-adapter`, un controlador de hardware físico puede correr silenciosamente en un dispositivo embebido (como Raspberry Pi) y transmitir datos por red al Supervisor ubicado en otra computadora.

---

## 🛠️ Comandos de Gradle

El proyecto utiliza **Gradle Wrapper** para asegurar reproducibilidad. No necesitas instalar Gradle en tu máquina, simplemente usa `./gradlew` (Linux/Mac) o `gradlew.bat` (Windows) en la raíz del proyecto.

### Ejecución
- `./gradlew :interop-lab:demo-apps:run` : Compila e inicia el **Supervisor Principal (App con Interfaz Gráfica)**. Desde aquí puedes arrancar dispositivos y simular escenarios.
- `./gradlew :headless-adapter:run --args="--device <DEVICE> --domain <DOMAIN>"` : Arranca un **Controlador de Dispositivo sin interfaz gráfica** (ideal para la Raspberry Pi).

### Compilación y Empaquetado
- `./gradlew build` : Compila el proyecto completo, ejecuta pruebas unitarias y genera los binarios.
- `./gradlew clean` : Elimina todos los archivos compilados (`/build`) para forzar una compilación en limpio.
- `./gradlew assemble` : Compila el código fuente y genera los `.jar` sin correr las pruebas unitarias.
- `./gradlew distZip` o `./gradlew distTar` : Crea una carpeta empaquetada en un `.zip` o `.tar` (en `build/distributions`) con todas las librerías nativas y binarios ejecutables listos para ser distribuidos a un servidor de producción.

### Generación y Diagnóstico
- `./gradlew rtiddsgenExplodeResources` : Descomprime y prepara las librerías nativas del core de RTI DDS antes de compilar.
- `./gradlew tasks` : Lista todas las tareas de compilación disponibles en el proyecto.
- `./gradlew dependencies` : Imprime un árbol con todas las dependencias y librerías externas que usa el proyecto.

---

## 📂 Estructura del Proyecto (Carpetas)

El código fuente está dividido en submódulos especializados para separar la lógica de la interfaz y la infraestructura:

### 1. `interop-lab/` (Aplicaciones de Laboratorio / UI)
Contiene la arquitectura gráfica y la lógica principal del panel de usuario:
- **`demo-apps/`**: La aplicación del **Supervisor**. Contiene los paneles visuales, tablas de signos vitales, gestión de alarmas y el punto de entrada principal gráfico (`Main.java`).
- **`demo-devices/`**: Implementaciones del "IceAppsContainer" para los dispositivos, adaptándolos para ser creados dinámicamente en el entorno visual.
- **`demo-guis/`** y **`demo-guis-javafx/`**: Componentes visuales reutilizables (ej. widgets de gráficas dinámicas, controles de bombas, paneles de ECG).

### 2. `devices/` (Protocolos Base y Controladores Físicos)
El "Cerebro de bajo nivel" traductor del proyecto. Aquí se encuentra la lógica pura (sin interfaz) para conectarse con el hardware médico:
- **`devices/common/`**: Lógica central para conectarse por puerto serie o sockets de red (incluyendo las Máquinas de Estados y Watchdogs).
- **`devices/draeger/`**: Controladores que implementan y entienden el protocolo en bytes Medibus.
- **`devices/philips/`**: Controladores TCP/UDP que entienden el protocolo Intellivue por red.
- **`devices/nellcor/`, `devices/nonin/`, etc.**: Implementaciones específicas para distintas marcas de oxímetros o capnógrafos.

### 3. `headless-adapter/` (Servicios en Segundo Plano)
Módulo ligero diseñado específicamente para sistemas embebidos. Utiliza el Service Provider Interface (SPI) y Spring para iniciar dispositivos de la carpeta `devices/` físicos e inyectarlos en la red DDS sin dependencias gráficas pesadas.

### 4. `data-types/` (Modelado de Datos IDL)
Define las estructuras estandarizadas de datos que viajan por la red DDS.
- **`x73-idl/`**: Contiene los esquemas `.idl` (Interface Definition Language) basados en los estándares médicos IEEE 11073 (Device Identity, Numeric, SampleArray, AlarmLimit).
- **`x73-idl-rti-dds/`**: Módulo que compila los `.idl` usando el generador nativo de RTI y crea las clases/objetos de Java finales que viajan por la red.

### 5. Archivos Raíz y Configuración
- **`docs/`**: Diagramas de arquitectura (PlantUML), guías de instalación y manuales para el Headless Adapter.
- **`gradle/`**: Contiene el Wrapper de Gradle que descarga automáticamente las dependencias del compilador.
- **`device_adapter.sh`**: Script para instalar como servicio el adaptador en plataformas Linux/Raspberry Pi.
- **`settings.gradle` / `build.gradle`**: Definen la jerarquía de compilación y las librerías a importar.
