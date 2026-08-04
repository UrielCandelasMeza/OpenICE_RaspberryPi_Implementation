package org.mdpnp.apps.testapp.hl7;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
/**
 *  Este codigo se encarga de pedir un access token (JWT) nuevo cuando no haya
 *  o cuando este token este ya haya expirado.
 */

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mdpnp.apps.testapp.IceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TokenProvider {

  protected static final Logger log = LoggerFactory.getLogger(TokenProvider.class);

  IceProperties props = new IceProperties("mdpnp.fhir.token.url");

  private Optional<String> url = props.getValue();

  private Optional<String> token = Optional.empty();

  public TokenProvider() {
  }

  public String getToken() {

    if (this.token.isEmpty() || this.token == null) {
      this.token = fetchToken();
    }

    return this.token.orElse(null);
  }

  public void setToken() {
    this.token = fetchToken();
  }

  public Optional<String> fetchToken() {

    if (url.isEmpty()) {
      log.error("Hubo un problema al momento de cargar la URL");
      return Optional.empty();
    }

    String user = props.getProperty("mdpnp.fhir.token.user").orElse("testuser");
    String pass = props.getProperty("mdpnp.fhir.token.password").orElse("testpass");
    String clientId = props.getProperty("mdpnp.fhir.token.clientId").orElse("my-fhir-client");

    try {
      String bodyData = "client_id=" + clientId
          + "&username=" + user
          + "&password=" + pass
          + "&grant_type=password";

      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url.get()))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(bodyData))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      if (!(response.statusCode() == 200)) {
        log.error("Envio un estatus: " + response.statusCode());
        return Optional.empty();
      }

      JsonNode json = new ObjectMapper().readTree(response.body());
      String accessToken = json.path("access_token").asText(null);
      if (accessToken == null || accessToken.isEmpty()) {
        log.error("La respuesta del token no contiene access_token: " + response.body());
        return Optional.empty();
      }
      log.info("Token obtenido correctamente");
      return Optional.of(accessToken);

    } catch (Exception e) {
      log.error(e.getMessage());
      return Optional.empty();
    }

  }
}
