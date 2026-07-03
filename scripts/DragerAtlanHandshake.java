import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Script de prueba (standalone) para realizar el Handshaking MEDIBUS.X 
 * con un Drager Atlan A-350XL.
 * 
 * Modos soportados:
 * 1. Red (TCP/IP) - Si el monitor usa LAN o un conversor Serial-Ethernet.
 * 2. Serial (RS232 en Linux) - Usando descriptores de archivo directos (/dev/ttyUSB0).
 */
public class DragerAtlanHandshake {

    public static void main(String[] args) {
        // --- CONFIGURACIÓN ---
        boolean useSerial = true; // Cambiar a false para usar RED (TCP)
        
        // Configuración de RED (TCP/IP)
        String host = "192.168.1.100";
        int port = 2001; 
        
        // Configuración Serial (Linux)
        // IMPORTANTE: Antes de correr esto, configura los baudios en tu terminal:
        // stty -F /dev/ttyUSB0 9600 cs8 -cstopb -parenb raw
        String serialPort = "/dev/ttyUSB0"; 

        String outputFile = "drager_atlan_logs.txt";
        // ---------------------

        InputStream in = null;
        OutputStream out = null;
        Socket socket = null;

        try {
            if (useSerial) {
                System.out.println("Intentando conectar con Drager Atlan por RS232 en " + serialPort + "...");
                File file = new File(serialPort);
                if (!file.exists()) {
                    System.err.println("¡El puerto " + serialPort + " no existe! ¿Conectaste el cable USB/Serie?");
                    return;
                }
                in = new FileInputStream(file);
                out = new FileOutputStream(file);
                System.out.println("¡Puerto Serie abierto correctamente!");
            } else {
                System.out.println("Intentando conectar con Drager Atlan por RED en " + host + ":" + port + "...");
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000);
                in = socket.getInputStream();
                out = socket.getOutputStream();
                socket.setSoTimeout(5000); 
                System.out.println("¡Conexión TCP establecida!");
            }

            try (FileWriter fw = new FileWriter(outputFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {
                 
                logToFile(pw, "Conectado. Modo Serial: " + useSerial);

                // 1. Comando de Handshake (InitializeComm) en protocolo MEDIBUS
                // [ESC] [0x51] [0x36] [0x43] [CR]
                byte[] initCommand = { 0x1B, 0x51, 0x36, 0x43, 0x0D };
                
                System.out.println("Enviando petición de Inicialización (InitializeComm)...");
                out.write(initCommand);
                out.flush();
                logToFile(pw, "Enviado: InitializeComm (0x1B 0x51 0x36 0x43 0x0D)");

                // 2. Leer la respuesta
                byte[] buffer = new byte[1024];
                System.out.println("Esperando respuesta del Atlan...");
                
                int bytesRead;
                try {
                    while ((bytesRead = in.read(buffer)) != -1) {
                        StringBuilder hexString = new StringBuilder();
                        StringBuilder asciiString = new StringBuilder();
                        
                        for (int i = 0; i < bytesRead; i++) {
                            String hex = Integer.toHexString(0xFF & buffer[i]);
                            if (hex.length() == 1) hexString.append('0');
                            hexString.append(hex).append(" ");
                            
                            if (buffer[i] >= 32 && buffer[i] <= 126) {
                                asciiString.append((char) buffer[i]);
                            } else {
                                asciiString.append(".");
                            }
                            
                            if (buffer[i] == 0x0D) {
                                String logLine = "\nTrama Recibida!\nHEX: " + hexString.toString() + "\nASCII: " + asciiString.toString();
                                System.out.println(logLine);
                                logToFile(pw, logLine);
                                
                                hexString.setLength(0);
                                asciiString.setLength(0);
                            }
                        }
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("Tiempo de espera agotado (red). No se recibieron más datos.");
                }

            }
        } catch (IOException e) {
            System.err.println("Error de conexión: " + e.getMessage());
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
                if (socket != null) socket.close();
            } catch (IOException e) {}
        }
    }

    private static void logToFile(PrintWriter pw, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        pw.println("[" + timestamp + "] " + message);
        pw.flush();
    }
}
