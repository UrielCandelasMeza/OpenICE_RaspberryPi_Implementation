package org.chat.mqtt;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MqttService implements MqttCallback {

  private final int QOS = 2;
  private String username;
  private String password;

  private String topic;
  private String broker;
  private String clientId;

  private MqttClient client;
  private MemoryPersistence persistance;
  private MqttConnectOptions connOpts;

  public MqttService() {
  }

  public MqttService(String clientId, String broker, String topic, String username, String password) {
    this.clientId = clientId;
    this.broker = broker;
    this.topic = topic;
    this.username = username;
    this.password = password;

    persistance = new MemoryPersistence();
    connOpts = new MqttConnectOptions();
    connOpts.setUserName(username);
    connOpts.setPassword(password.toCharArray());

  }

  public void connect() {

    if (this.topic == null || this.topic.isEmpty()) {
      System.out.println("Topic is not defined");
      return;
    }
    try {
      client = new MqttClient(broker, clientId, persistance);
      client.setCallback(this);
      connOpts.setCleanSession(true);
      System.out.println("Connecting to broker: " + broker);
      client.connect(connOpts);
      System.out.println("Connected with client id: " + this.clientId);
      client.subscribe(this.topic);
    } catch (MqttException e) {
      printMqttException(e);
    }
  }

  public void sendMessage(String content) {

    if (this.topic == null) {
      System.out.println("Topic is not defined");
      return;
    }

    if (content == null || content.isEmpty()) {
      System.out.println("Content can not be empty");
      return;
    }

    try {
      MqttMessage message = new MqttMessage(content.getBytes());
      message.setQos(QOS);
      client.publish(this.topic, message);
      System.out.println("[ENVIADO]");
    } catch (MqttException e) {
      printMqttException(e);
    }
  }

  public void disconnect() {
    try {
      client.disconnect();
    } catch (MqttException e) {
      printMqttException(e);
    }
  }

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    String content = new String(message.getPayload());
    System.out.println("\n[RECIBIDO] " + content);

  }

  @Override
  public void connectionLost(Throwable cause) {
    System.out.println("Conexión perdida: " + cause.getMessage());
  }

  @Override
  public void deliveryComplete(IMqttDeliveryToken token) {
    // Confirmación de que tu mensaje fue entregado (vacío está bien)
  }

  private void printMqttException(MqttException e) {
    System.out.println("reason " + e.getReasonCode());
    System.out.println("msg " + e.getMessage());
    System.out.println("loc " + e.getLocalizedMessage());
    System.out.println("cause " + e.getCause());
    System.out.println("excep " + e);
    e.printStackTrace();
  }

}
