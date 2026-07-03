import java.io.*;
import java.net.*;

public class EfficiaHL7Listener {

  public static void main(String[] args) {
      // El puerto debe coincidir con el configurado en la salida HL7 del Efficia
      int port = 2575; 
      String outputFile = "datos_efficia.txt";

      try (ServerSocket serverSocket = new ServerSocket(port)) {
          System.out.println("Iniciando servidor. Escuchando Efficia en el puerto " + port + "...");

          // Bucle infinito para aceptar conexiones entrantes
          while (true) {
              Socket clientSocket = serverSocket.accept();
              System.out.println("Conexión entrante aceptada desde: " + clientSocket.getInetAddress());

              // Manejar la conexión del monitor
              handleClientConnection(clientSocket, outputFile);
          }
      } catch (IOException e) {
          System.err.println("Error al iniciar el servidor: " + e.getMessage());
      }
  }

  private static void handleClientConnection(Socket clientSocket, String outputFile) {
    try (InputStream in = clientSocket.getInputStream();
          OutputStream out = clientSocket.getOutputStream();
          FileWriter fw = new FileWriter(outputFile, true);
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
          } 
          else if (data == 0x1C) { 
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

                // 3. (Opcional) Enviar un acuse de recibo (ACK).
                // Los protocolos HL7 reales suelen requerir que envíes de vuelta un mensaje 
                // de reconocimiento (ACK) formateado en HL7 y envuelto en MLLP para que 
                // el monitor sepa que el dato fue guardado con éxito.
              }
          } 
          else if (isReceiving) {
              // Si estamos entre el inicio y el fin del bloque, añadir el carácter al mensaje
              hl7Message.append((char) data);
          }
        }
    } catch (IOException e) {
        System.err.println("La conexión con el monitor finalizó o se interrumpió: " + e.getMessage());
    }
  }
}