import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class DragerAtlanHandshake {

  /* 
   * Comando para ajuste de baudios y paridad para el Atlan
   * stty -F /dev/ttyUSB0 9600 cs8 -cstopb parenb -parodd raw
   *
   */

  private static final String PORT = "/dev/ttyUSB0";
  private static final String LOG_FILE = "atlan_realtime.log";

  // Comandos útiles de MEDIBUS
  private static final String CMD_INIT = "Q";   // Handshake <51H>
  private static final String CMD_INFO = "R";   // Device ID <52H>
  private static final String CMD_DATA = "*";  // Datos de prueba aver si da algo
  private static final String CMD_ALRM = "R2";  // Límites inferiores (No se usa aun)
  private static final String CMD_TEXT = "T1";  // Textos y mensajes de alarma (No se usa aun)
  
  private static final String IDNO = "6666";
  private static final String ID = "MBUS (C)F. TOEMBOEL";
  private static final String REV = "00.20:04.01";

  private static final String ID_BYTES = "6666'Hola'00.20:04.01";

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

    String response = "";

    // 1. ICC
    sendCommand(false, out, CMD_INIT, log);
    response = readResponse(in, log);

    // 2. Loop de Polling (Reemplaza el Keep-Alive NOP)
    while (true) {

      Thread.sleep(2000); // 2 segundos de delay segun medibus
      
      if(response.equals("51")) { 
        sendCommand(true, out, CMD_INIT, log);
        Thread.sleep(2000);

      }
      if(response.equals("52")) {
        // Pide un 
        sendCommand(true, out, ID_BYTES, log);
        Thread.sleep(2000);
      }
      if(response.equals("15")) {
        // Recibio un mal acknowledgment gracias a que la checsum estaba mal
        sendCommand(true, out, CMD_INIT, log);
        Thread.sleep(2000);
      }

      sendCommand(false, out, "-", log);
      response = readResponse(in, log);

    }
  }

  private static void sendCommand(
    boolean isRespond, 
    OutputStream out, 
    String payload, 
    PrintWriter log
  ) throws Exception {

    byte[] pBytes = payload.getBytes("ASCII");
    int sumSend = 0x1B; // <ESC>
    int sumRespond = 0x01 // <SOH>
    int sum;

    // Validamos si tenemos que responder
    if(isRespond) {
      sum = sumRespond;
    } else {
      sum = sumSend;
    }
    
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

  private static String readResponse(InputStream in, PrintWriter log) throws Exception {
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
      //return "";
      return Integer.toHexString((int) data[1]);
    }
    return "";
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

  private static String sendID(String IDNO, String ID, String REV) {
    int checksum = 0;

    // checksum += 1;
    // checksum += 82;
    checksum += checksumStringToInt(IDNO);
    checksum += checksumStringToInt(ID);
    checksum += checksumStringToInt(REV);

    String checksumString;
    char cr = 14;

    checksumString = Integer.toHexString(checksum);
    checksumString = checksumString.toUpperCase();
    return "" + checksumString.charAt(checksumString.length() - 2) + checksumString.charAt(checksumString.length() - 1) + cr;
  }

  private static int checksumStringToInt(String str) {
    int checksumInt = 0;
    for (int x = 0; x < str.length(); x++) checksumInt += (int) str.charAt(x);
    return checksumInt;
  }
}

