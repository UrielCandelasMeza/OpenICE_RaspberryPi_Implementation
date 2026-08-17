import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DragerAtlanRealtime {

  /* 
   * Comando para ajuste de baudios y paridad para el Atlan
   * stty -F /dev/ttyUSB0 9600 cs8 -cstopb parenb -parodd raw
   *
   */

  private static final String PORT = "/dev/ttyUSB0";
  private static final String LOG_FILE = "atlan_realtime.log";

  // Comandos útiles de MEDIBUS
  private static final String CMD_INIT = "Q";   // Handshake
  private static final String CMD_INFO = "V";   // Device ID
  private static final String CMD_DATA = "R1";  // Datos de medición actuales (CP1)
  private static final String CMD_ALRM = "R2";  // Límites inferiores
  private static final String CMD_TEXT = "T1";  // Textos y mensajes de alarma

  public static void main(String[] args) throws Exception {
    File file = new File(PORT);
    if (!file.exists()) {
      System.err.println("Puerto no encontrado: " + PORT);
      return;
    }

    InputStream in = new FileInputStream(file);
    OutputStream out = new FileOutputStream(file);
    PrintWriter log = new PrintWriter(new FileWriter(LOG_FILE, true), true);

    logToFile(log, "Iniciando comunicación con Atlan A350 XL (8E1)...");

    // 1. Handshake
    sendCommand(out, CMD_INIT, log);
    readResponse(in, log);

    // 2. Loop de Polling (Reemplaza el Keep-Alive NOP)
    while (true) {
      Thread.sleep(1000); // 1 segundo para no saturar y mantener vivo el puerto
      sendCommand(out, CMD_DATA, log);
      readResponse(in, log);
    }
  }

  private static void sendCommand(OutputStream out, String payload, PrintWriter log) throws Exception {
    byte[] pBytes = payload.getBytes("ASCII");
    int sum = 0x1B; // <ESC>
    for (byte b : pBytes) sum += b;

    String hexCs = String.format("%02X", sum & 0xFF);
    byte[] csBytes = hexCs.getBytes("ASCII");

    byte[] frame = new byte[1 + pBytes.length + 2 + 1];
    frame[0] = 0x1B; // ESC
    System.arraycopy(pBytes, 0, frame, 1, pBytes.length);
    System.arraycopy(csBytes, 0, frame, 1 + pBytes.length, 2);
    frame[frame.length - 1] = 0x0D; // CR

    out.write(frame);
    out.flush();
    logToFile(log, "Enviado: " + payload + " | HEX: " + bytesToHex(frame));
  }

  private static void readResponse(InputStream in, PrintWriter log) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int b;
    // Lectura bloqueante hasta encontrar el <CR> (0x0D)
    while ((b = in.read()) != -1) {
      buffer.write(b);
      if (b == 0x0D) break;
    }

    if (buffer.size() > 0) {
      byte[] data = buffer.toByteArray();
      String asciiStr = new String(data)
          .replaceAll("\r", "<CR>")
          .replaceAll("\u0001", "<SOH>")
          .replaceAll("\u001B", "<ESC>")
          .replaceAll("\\p{C}", "."); // Limpia otros caracteres de control en ASCII

      logToFile(log, "Recibido ASCII: " + asciiStr);
      logToFile(log, "Recibido HEX: " + bytesToHex(data));
    }
  }

  private static void logToFile(PrintWriter pw, String msg) {
    String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
    System.out.println("[" + ts + "] " + msg);
    pw.println("[" + ts + "] " + msg);
    pw.flush();
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02X ", b));
    }
    return sb.toString().trim();
  }
}
