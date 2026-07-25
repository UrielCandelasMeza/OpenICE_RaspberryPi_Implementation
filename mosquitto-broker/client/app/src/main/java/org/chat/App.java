/*
 * Sistema de mensajeria tipo chat en vivo usando MQTT
 * usando el broker de mosquitto.
 */
package org.chat;

import java.util.Scanner;

import org.chat.mqtt.MqttService;

public class App {

  public static void main(String[] args) {

    String broker = "tcp://localhost:1883";
    String topic = "openice/#";

    String clientId = args[0];
    String username = args[1];
    String password = args[2];

    MqttService client = new MqttService(clientId, broker, topic, username, password);

    Scanner sc = new Scanner(System.in);

    client.connect();

    // Handle CTRL + C and exits
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("Desconectando...");
      client.disconnect();
      sc.close();
    }));

    System.out.println("Para salir ejecuta CTRL+C");
    while (true) {
      try {
        String mensaje = sc.nextLine();
        client.sendMessage(mensaje);
      } catch (Exception e) {
        break;
      }
    }

  }
}
