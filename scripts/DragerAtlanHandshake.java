import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;

class Response {

  public List<Integer> dataBytes;
  public boolean isCommand;
  public boolean validFrame;
  public String asciiString;

  public Response() {
  }

  public Response(byte[] bytes, String asciiString) {
    this.asciiString = asciiString;
    this.dataBytes = new ArrayList<>();

    toDataBytes(bytes);

    if (bytes.length > 0 && isValidFirstByte(bytes[0])) {
      this.validFrame = true;
      setIsCommand(bytes[0]);
    }

  }

  public int getOpByte() {
    if (dataBytes.size() < 2) {
      return -1;
    }

    return dataBytes.get(1);
  }

  public boolean hasOpByte() {
    return dataBytes.size() >= 2;
  }

  public boolean isUsable() {
    return validFrame && hasOpByte();
  }

  private boolean isValidFirstByte(int firstByte) {

    int[] allowed = new int[] { 0x01, 0x1B };

    for (var e : allowed) {
      if (e == firstByte)
        return true;
    }

    return false;
  }

  private void toDataBytes(byte[] bytes) {
    for (var b : bytes) {
      dataBytes.add(0xFF & (int) b);
    }
  }

  private void setIsCommand(byte start) {

    // int startInt = (int) start;

    if (!(start == 0x1B)) {
      this.isCommand = false;
      return;
    }

    this.isCommand = true;

  }

}

class RealtimeConfiguration {
  public int dataCode;
  public int interval;
  public int min;
  public int max;
  public int maxBin;

  public RealtimeConfiguration() {
  }

  public RealtimeConfiguration(int dataCode, int interval, int min, int max, int maxBin) {
    this.dataCode = dataCode;
    this.interval = interval;
    this.min = min;
    this.max = max;
    this.maxBin = maxBin;
  }

}

public class DragerAtlanHandshake {

  /*
   * Comando para ajuste de baudios y paridad para el Atlan
   * stty -F /dev/ttyUSB0 9600 cs8 -cstopb parenb -parodd raw
   *
   */

  private static final String PORT = "/dev/ttyUSB0";
  private static final String LOG_FILE_PREFIX = "atlan_realtime_";

  private static final int READ_TIMEOUT_MS = 2000;
  private static final int FAST_READ_MAX_MS = 3000;
  private static final int READ_POLL_MS = 20;
  private static final int MAX_NAK_RETRIES = 5;
  private static final int NAK_BACKOFF_MS = 200;
  private static final long POLL_INTERVAL_MS = 2000;

  // Comandos útiles de MEDIBUS

  // Probar [) <29H>] aver que nos da

  private static final String CMD_INIT = "Q"; // Handshake <51H>
  // Este comando no existe
  // private static final String CMD_ENABLE_DISABLE_DATASTREAM = "Q";
  // private static final String CMD_REQUEST_TREND_DATA = "Q";
  private static final String CMD_REQ_REALTIME_CONF = "S"; // Realtime Configuration <53H>
  private static final String CMD_CONFIGURE_REALTIME_TRANS = "T"; // Configure realtime transmission <54H>
  // private static final String CMD_REALTIME_CONF_CHANGED = "V"; // Realtime
  // configuration changed <56H>
  private static final String CMD_SYNC_BYTE = "Ð"; // Es una D medio rara
  private static final String CMD_END_OF_SYNC = "À";

  private static final String CMD_INFO = "R"; // Device ID <52H>
  // private static final String CMD_DATA = "$"; // <24H>
  // private static final String CMD_ALRM = "R2"; // Límites inferiores (No se usa
  // aun)
  // private static final String CMD_TEXT = "T1"; // Textos y mensajes de alarma
  // (No se usa aun)
  private static final String CMD_NAK = "\u0015";

  // private static final String IDNO = "0161";
  // private static final String ID = "OpenICE";
  // private static final String REV = "00.20:04.01";

  private static final String ID_BYTES = "0161'OpenICE'02.10:06.00";

  /*
   * Para poder recibir datos necesitamos enviar primero el <53H>
   * el cual solicita la configuracion en tiempo real.
   * 
   * Luego configuramos la realtime transmision mediante el <54H>
   * 
   * Posterior habilitamos los datastreams usando una serie de bytes
   * para poder recibir los datos.
   * 
   */

  public static void main(String[] args) throws Exception {
    File file = new File(PORT);
    if (!file.exists()) {
      System.err.println("Puerto no encontrado: " + PORT);
      return;
    }

    PrintWriter log = new PrintWriter(new FileWriter(logFileName(), true), true);

    InputStream in = null;
    OutputStream out = null;

    try {
      in = new FileInputStream(file);
      out = new FileOutputStream(file);

      final InputStream hookIn = in;
      final OutputStream hookOut = out;

      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        closeQuietly(hookIn);
        closeQuietly(hookOut);
        log.flush();
      }));

      logToFile(log, "Iniciando comunicación con Atlan A350 XL (8E1)...");

      Optional<Response> response;

      // 1. ICC
      sendCommand(false, out, CMD_INIT, log);

      response = readResponse(in, log);

      // 2. Loop de Polling (Reemplaza el Keep-Alive NOP)
      while (true) {

        // var lastResponse = response;

        try {

          // Mientras no esta presente ni es valido envia acknowledgment negativo
          response = checkResponse(response, out, in, log);

          if (!isUsable(response)) {
            logToFile(log, "Respuesta ausente o incompleta, se reintenta en el siguiente ciclo");
            Thread.sleep(POLL_INTERVAL_MS);
            continue;
          }

          var res = response.get();

          boolean isCommand = res.isCommand;

          int val = res.getOpByte();

          switch (val) {
            case 51:
              if (isCommand) {
                sendCommand(true, out, CMD_INIT, log);
                response = readResponse(in, log);
              }
              break;
            case 52:
              if (isCommand) {
                sendCommand(true, out, ID_BYTES, log);
                response = readResponse(in, log);
              }
              break;
            default:
              // Aqui van todos los pasos para poder conectarse con el resto
              List<RealtimeConfiguration> config = sendConfig(response, out, in, log);

              int[] rawBytes = readBytes(in, log);

              double[] data = parsePacketData(rawBytes, config);

              for (var d : data) {
                logToFile(log, "Recibido: " + d);
              }
          }

          response = checkResponse(response, out, in, log);

        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          logToFile(log, "Interrumpido, cerrando comunicacion");
          return;
        } catch (Exception e) {
          logToFile(log, "Error en el ciclo de polling: " + e);
        }

        Thread.sleep(POLL_INTERVAL_MS); // 2 segundos de delay segun medibus
      }

    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      logToFile(log, "Interrumpido, cerrando comunicacion");
    } finally {
      closeQuietly(in);
      closeQuietly(out);
      log.flush();
      log.close();
    }

  }

  private static boolean isUsable(Optional<Response> response) {
    return response != null && response.isPresent() && response.get().isUsable();
  }

  private static Optional<Response> checkResponse(Optional<Response> response, OutputStream out, InputStream in,
      PrintWriter log)
      throws Exception {

    int attempts = 0;
    Optional<Response> current = response;

    while (!isUsable(current)) {

      if (attempts >= MAX_NAK_RETRIES) {
        logToFile(log, "Sin trama valida tras " + MAX_NAK_RETRIES + " NAK, se abandona la espera");
        return current;
      }

      attempts++;
      sendCommand(true, out, CMD_NAK, log);
      current = readResponse(in, log);

      if (attempts > 1) {
        Thread.sleep(NAK_BACKOFF_MS);
      }
    }

    return current;
  }

  private static List<RealtimeConfiguration> sendConfig(Optional<Response> response, OutputStream out, InputStream in,
      PrintWriter log)
      throws Exception {

    // Primero solicitamos la realtime configuration <53H>
    sendCommand(false, out, CMD_REQ_REALTIME_CONF, log);
    response = readResponse(in, log);
    response = checkResponse(response, out, in, log);

    // Despues enviamos la configuracion por cada byte <54H>

    if (!isUsable(response)) {
      logToFile(log, "Realtime configuration <53H> no valida, se omite el paso <54H>");
      return new ArrayList<>();
    }

    var res = response.get();

    List<RealtimeConfiguration> config = new ArrayList<>();

    int count = 0;

    RealtimeConfiguration tempConfig = new RealtimeConfiguration();

    for (int i = 0; i < res.dataBytes.size(); i++) {

      if (i < 2 || i > res.dataBytes.size() - 4) {
        continue;
      }

      if (count >= 23) {
        config.add(tempConfig);
        tempConfig = new RealtimeConfiguration();
        count = 0;
      }

      switch (count) {
        case 0:
          // 2 Bytes
          tempConfig.dataCode = nBytesToString(res.dataBytes, 2, 16, i);
          break;
        case 2:
          // 8 Bytes
          tempConfig.interval = nBytesToString(res.dataBytes, 8, 10, i);
          break;
        case 10:
          // 5 Bytes
          tempConfig.min = nBytesToString(res.dataBytes, 5, 10, i);
          break;
        case 15:
          // 5 Bytes
          tempConfig.max = nBytesToString(res.dataBytes, 5, 10, i);
          break;
        case 20:
          // 5 Bytes
          tempConfig.maxBin = nBytesToString(res.dataBytes, 3, 16, i);
          break;
        default:
          break;
      }

      count += 1;
    }

    if (count == 23) {
      config.add(tempConfig);
    }

    if (config.isEmpty()) {
      logToFile(log, "Sin registros de realtime configuration, se omite el paso <54H>");
      return config;
    }

    StringBuilder payloadBuilder = new StringBuilder(CMD_CONFIGURE_REALTIME_TRANS);
    for (var c : config) {
      String value = String.format("%02X", c.dataCode) + "01";
      payloadBuilder.append(value);
    }

    String payload = payloadBuilder.toString();

    sendCommand(false, out, payload, log);
    response = readResponse(in, log);
    response = checkResponse(response, out, in, log);

    int[] table = { 0xC1, 0xC2, 0xC3 };

    int[] masks = { 0xC0, 0xC1, 0xC3, 0xC7, 0xCF };

    StringBuilder toSendBuilder = new StringBuilder(CMD_SYNC_BYTE);
    int numConfigs = config.size();

    for (int i = 0; i < table.length; i++) {

      int startIndex = i * 4;

      if (startIndex >= numConfigs)
        break;

      int elementsInGroup = Math.min(4, numConfigs - startIndex);

      String commandString = String.format("%02X", table[i]);
      String argumentString = String.format("%02X", masks[elementsInGroup]);

      toSendBuilder.append(commandString);
      toSendBuilder.append(argumentString);
    }

    // doble end of sync para avisar
    toSendBuilder.append(CMD_END_OF_SYNC);
    toSendBuilder.append(CMD_END_OF_SYNC);

    String toSend = toSendBuilder.toString();
    sendBytes(out, toSend, log);

    return config;

  }

  private static int nBytesToString(List<Integer> array, int bytes, int base, int i) {
    StringBuilder sb = new StringBuilder();
    for (int j = 0; j < bytes; j++) {
      if (i + j >= array.size()) {
        return 0;
      }
      sb.append((char) array.get(i + j).byteValue());
    }

    String str = sb.toString();

    try {
      return Integer.parseInt(str.replace(" ", ""), base);
    } catch (NumberFormatException nfe) {
      return 0;
    }
  }

  private static void sendCommand(
      boolean isRespond,
      OutputStream out,
      String payload,
      PrintWriter log) throws Exception {

    byte[] pBytes = payload.getBytes("ASCII");
    int sumSend = 0x1B; // <ESC>
    int sumRespond = 0x01; // <SOH>
    int sum;

    byte soh = 0x01;
    byte esc = 0x1B;

    // Validamos si tenemos que responder
    if (isRespond) {
      sum = sumRespond;
    } else {
      sum = sumSend;
    }

    for (byte b : pBytes)
      sum += b;

    String hexCs = String.format("%02X", sum & 0xFF);
    byte[] csBytes = hexCs.getBytes("ASCII");

    byte[] frame = new byte[1 + pBytes.length + 2 + 1];
    frame[0] = isRespond ? soh : esc; // ESC ASCII 27
    System.arraycopy(pBytes, 0, frame, 1, pBytes.length);
    System.arraycopy(csBytes, 0, frame, 1 + pBytes.length, 2);
    frame[frame.length - 1] = 0x0D; // CR

    out.write(frame);
    out.flush();
    logToFile(log, "Enviado: " + payload + " | HEX: " + bytesToHex(frame));
  }

  private static void sendBytes(
      OutputStream out,
      String payload,
      PrintWriter log)
      throws Exception {

    byte[] pBytes = new byte[payload.length() / 2];
    for (int i = 0; i < pBytes.length; i++) {
      pBytes[i] = (byte) Integer.parseInt(payload.substring(2 * i, 2 * i + 2), 16);
    }

    out.write(pBytes);
    out.flush();
    logToFile(log, "Enviado: " + payload + " | HEX: " + payload);
  }

  private static int[] readBytes(InputStream in, PrintWriter log) throws Exception {

    ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    int b;

    long deadline = System.currentTimeMillis() + FAST_READ_MAX_MS;

    while (System.currentTimeMillis() < deadline) {

      if (in.available() <= 0) {
        if (buffer.size() > 0) {
          break;
        }
        Thread.sleep(READ_POLL_MS);
        continue;
      }

      b = in.read();

      if (b == -1) {
        break;
      }

      buffer.write(b);
    }

    byte[] data = buffer.toByteArray();

    if (data.length > 0) {
      logToFile(log, "Datos recibidos (" + data.length + " bytes): " + bytesToHex(data));
    } else {
      logToFile(log, "Sin datos recibidos en " + FAST_READ_MAX_MS + " ms");
    }

    int[] dataInt = new int[data.length];

    for (int i = 0; i < data.length; i++) {
      dataInt[i] = 0xFF & data[i];
    }

    return dataInt;

  }

  public static double[] parsePacketData(int[] rawBytes, List<RealtimeConfiguration> activeConfigs) {

    if (rawBytes == null || activeConfigs == null || activeConfigs.isEmpty()) {
      return new double[0];
    }

    if (rawBytes.length < activeConfigs.size() * 2) {
      return new double[0];
    }

    double[] values = new double[activeConfigs.size()];

    for (int i = 0; i < activeConfigs.size(); i++) {
      int byte1 = rawBytes[i * 2];
      int byte2 = rawBytes[i * 2 + 1];

      int xbin = ((byte2 & 0x3F) << 6) | (byte1 & 0x3F);
      RealtimeConfiguration c = activeConfigs.get(i);

      if (c.maxBin == 0) {
        values[i] = Double.NaN;
        continue;
      }

      values[i] = c.min + (xbin * (c.max - c.min) / (double) c.maxBin);
    }

    return values;
  }

  private static Optional<Response> readResponse(InputStream in, PrintWriter log) throws Exception {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int b;

    long deadline = System.currentTimeMillis() + READ_TIMEOUT_MS;

    // Lectura bloqueante hasta encontrar el <CR> (0x0D)
    while (System.currentTimeMillis() < deadline) {

      if (in.available() <= 0) {
        Thread.sleep(READ_POLL_MS);
        continue;
      }

      b = in.read();

      if (b == -1) {
        break;
      }

      buffer.write(b);
      if (b == 0x0D)
        break;
    }

    if (!(buffer.size() > 0)) {
      return Optional.empty();
    }

    byte[] data = buffer.toByteArray();
    String asciiStr = new String(data)
        .replaceAll("\r", "<CR>")
        .replaceAll("\u0001", "<SOH>")
        .replaceAll("\u001B", "<ESC>");
    // .replaceAll("\\p{C}", "."); // Limpia otros caracteres de control en ASCII

    String bytesHex = bytesToHex(data);

    logToFile(log, "Recibido ASCII: " + asciiStr);
    logToFile(log, "Recibido HEX: " + bytesHex);

    return Optional.of(new Response(data, asciiStr));
  }

  private static void logToFile(PrintWriter pw, String msg) {
    String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
    System.out.println("[" + ts + "] " + msg);
    pw.println("[" + ts + "] " + msg);
    pw.flush();
  }

  private static String logFileName() {
    return LOG_FILE_PREFIX + new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date()) + ".log";
  }

  private static void closeQuietly(Closeable c) {
    if (c != null) {
      try {
        c.close();
      } catch (Exception ignored) {
      }
    }
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02X ", b));
    }
    return sb.toString().trim();
  }

  /*
   * private static String sendID(String IDNO, String ID, String REV) {
   * int checksum = 0;
   * 
   * // checksum += 1;
   * // checksum += 82;
   * checksum += checksumStringToInt(IDNO);
   * checksum += checksumStringToInt(ID);
   * checksum += checksumStringToInt(REV);
   * 
   * String checksumString;
   * char cr = 14;
   * 
   * checksumString = Integer.toHexString(checksum);
   * checksumString = checksumString.toUpperCase();
   * return "" + checksumString.charAt(checksumString.length() - 2) +
   * checksumString.charAt(checksumString.length() - 1)
   * + cr;
   * }
   * 
   * private static int checksumStringToInt(String str) {
   * int checksumInt = 0;
   * for (int x = 0; x < str.length(); x++)
   * checksumInt += (int) str.charAt(x);
   * return checksumInt;
   * }
   * 
   */

}
