import java.io.*;
import java.net.*;

public class EfficiaHL7Listener {

    public static void main(String[] args) {
        // --- CONFIGURACIÓN ---
        boolean useSerial = true; // Cambiar a true para escuchar por RS232 (Linux)

        // Configuración de RED (HL7 vía TCP/IP)
        // En LAN/WLAN el Efficia es el CLIENTE — se conecta a nosotros.
        // Este script es el SERVIDOR; el Efficia (o el simulador) se conecta aquí.
        // El puerto debe coincidir con SimEfficiaMonitor.HL7_PORT (2575) y con
        // el puerto configurado en el monitor real.
        int port = 2575;

        // Configuración Serial (Linux)
        // Configurar baudios primero, ej: stty -F /dev/ttyUSB0 9600 cs8 -cstopb -parenb
        // raw
        String serialPort = "/dev/ttyUSB0";

        String outputFile = "datos_efficia.txt";
        // ---------------------

        if (useSerial) {
            System.out.println("Iniciando modo Serial. Escuchando Efficia en " + serialPort + "...");
            File file = new File(serialPort);
            if (!file.exists()) {
                System.err.println("¡El puerto " + serialPort + " no existe! ¿Conectaste el cable USB/Serie?");
                return;
            }
            try (InputStream in = new FileInputStream(file);
                    OutputStream out = new FileOutputStream(file)) {

                System.out.println("¡Puerto Serie abierto! Esperando datos...");
                // En serial no hay "conexiones entrantes" múltiples, es un solo flujo constante
                handleDataStream(in, out, outputFile);

            } catch (IOException e) {
                System.err.println("Error en conexión serial: " + e.getMessage());
            }
        } else {
            // ── MODO SERVIDOR TCP ─────────────────────────────────────────────────────
            // En LAN/WLAN el Efficia CM es el CLIENTE (según el manual).
            // Abrimos un ServerSocket y esperamos a que el monitor (o el simulador)
            // se conecte a nosotros.
            System.out.println("Servidor HL7 escuchando en el puerto " + port + ". Esperando conexión del Efficia...");
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                // Bucle infinito: acepta una conexión, la procesa, vuelve a esperar
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("¡Efficia conectado desde: " + clientSocket.getInetAddress() + "!");

                    try (InputStream in = clientSocket.getInputStream();
                            OutputStream out = clientSocket.getOutputStream()) {
                        handleDataStream(in, out, outputFile);
                    } catch (IOException e) {
                        System.err.println("La conexión con el Efficia se interrumpió: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("Error al iniciar el servidor TCP: " + e.getMessage());
            }
        }
    }

    private static void handleDataStream(InputStream in, OutputStream out, String outputFile) {
        try (FileWriter fw = new FileWriter(outputFile, true);
                PrintWriter pw = new PrintWriter(fw)) {

            int data;
            StringBuilder hl7Message = new StringBuilder();
            boolean isReceiving = false;

            // Leer flujo de bytes
            while ((data = in.read()) != -1) {
                if (data == 0x0B) {
                    // 0x0B indica el inicio del mensaje (Start of Block)
                    isReceiving = true;
                    hl7Message.setLength(0); // Limpiar el buffer para el nuevo mensaje
                } else if (data == 0x1C) {
                    // 0x1C indica el fin de los datos (End of Block)
                    int nextByte = in.read();
                    if (nextByte == 0x0D) { // 0x0D es el retorno de carro final
                        isReceiving = false;

                        String completeMessage = hl7Message.toString();

                        // 1. Mostrar qué se recibió en la consola
                        System.out.println("\n--- Mensaje HL7 Recibido ---");
                        System.out.println(completeMessage);

                        // 2. Guardar lo que se recibió en el archivo .txt
                        pw.println("--- Nuevo Registro ---");
                        pw.println(completeMessage);
                        pw.flush();

                        // 3. ACK de recibido.
                        // Extraer la primera línea (MSH) y separar por pipes para obtener el MSH-10
                        String[] segments = completeMessage.split("\r");
                        String[] mshFields = segments[0].split("\\|");

                        // En HL7, el MSH-10 está en el índice 9
                        String messageControlId = (mshFields.length > 9) ? mshFields[9] : "UNKNOWN";

                        String ackMessage = "MSH|^~\\&|||||20260703115000||ACK^^ACK ALL|" + messageControlId
                                + "|P|2.4\r" +
                                "MSA|AA|" + messageControlId + "\r";

                        out.write(0x0B);
                        out.write(ackMessage.getBytes());
                        out.write(0x1C);
                        out.write(0x0D);
                        out.flush();
                    }
                } else if (isReceiving) {
                    // Si estamos entre el inicio y el fin del bloque, añadir el carácter al mensaje
                    hl7Message.append((char) data);
                }
            }
        } catch (IOException e) {
            System.err.println("El flujo de datos finalizó o se interrumpió: " + e.getMessage());
        }
    }
}
