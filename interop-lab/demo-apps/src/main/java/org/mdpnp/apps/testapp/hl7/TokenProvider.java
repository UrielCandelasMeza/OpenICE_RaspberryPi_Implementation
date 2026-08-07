package org.mdpnp.apps.testapp.hl7;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.Preferences;

import org.mdpnp.apps.testapp.IceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <h3>Token Provider</h3>
 * <p>
 * Proveedor de tokens de acceso (OAuth2 JWT) para la comunicación segura con
 * el Gateway FHIR. Solicita 2 tokens nuevos ({@code access_token},
 * {@code refresh_token})
 * usando el flujo {@code password} de Keycloak:
 * - Cuando no se dispone de uno se realiza una peticion mediante url encoded.
 * - Cuando ya se obtubo un token y este expiró este usa un refresh token para
 * realizar la solicitud de otro access_token.
 * </p>
 * 
 * @author Uriel Candelas
 */

public class TokenProvider {

  /**
   * Logger de la clase para reportar eventos de autenticación y obtención de
   * tokens.
   */
  protected static final Logger log = LoggerFactory.getLogger(TokenProvider.class);

  /**
   * Esto permite almacenar tokens del usuario similar a una cookie pero usando
   * {@link Preferences} asi no se pierden los tokens al cerrar sesion.
   */
  private Preferences pref = Preferences.userNodeForPackage(TokenProvider.class);

  /**
   * Propiedades del sistema utilizadas para obtener los parámetros de
   * autenticación.
   */
  IceProperties props = new IceProperties("mdpnp.fhir.token.url");

  /** URL del servidor de autenticación (Keycloak u otro proveedor OAuth2). */
  private Optional<String> url = props.getValue();

  /** Token de acceso actual en caché. */
  private Optional<String> access_token = Optional.empty();

  /** Token de refresco actual en caché. */
  private Optional<String> refresh_token = Optional.empty();

  /**
   * Map en el cual se cargan todos los tokens guardados mediante
   * {@link Preferences}.
   * el primer valor corresponde al {@code access_token} y el segundo para el
   * {@code refresh_token}
   */
  private Map<String, String> tokensMap;

  /**
   * <p>
   * Constructor por defecto para {@code TokenProvider}.
   * </p>
   * <p>
   * Inicializa el {@code tokensMap} y recupera tanto los tokens actuales
   * como los inmediatos anteriores desde {@link Preferences}.
   * </p>
   */
  public TokenProvider() {
    this.tokensMap = new HashMap<>();

    this.access_token = Optional.ofNullable(pref.get("access_token", null));
    this.refresh_token = Optional.ofNullable(pref.get("refresh_token", null));

    if (this.access_token.isPresent() && this.refresh_token.isPresent()) {
      String at = this.access_token.get();
      String rt = this.refresh_token.get();

      this.tokensMap.put(at, rt);
    }

  }

  /**
   * Obtiene el token de acceso actual. Si no se ha recuperado previamente o si
   * está
   * vacío, realiza una solicitud de autenticación para obtener uno nuevo.
   *
   * @return El token de acceso como {@link String}, o {@code null} si falló la
   *         obtención.
   */
  public String getAccessToken() {

    Optional<String[]> tokens = fetchTokenRefresh();

    if (tokens.isEmpty()) {
      log.error("No se pudo obtener los tokens.");
      return null;
    }

    String[] t = tokens.get();

    this.access_token = Optional.of(t[0]);
    this.refresh_token = Optional.of(t[1]);

    this.tokensMap.put(t[0], t[1]);
    this.pref.put("access_token", t[0]);
    this.pref.put("refresh_token", t[1]);

    return this.access_token.orElse(null);
  }

  /**
   * Fuerza la obtención de un nuevo token de acceso (invalida el token en caché
   * y realiza una petición síncrona al endpoint de autenticación).
   */
  public String updateToken() {
    Optional<String[]> tokens = fetchTokenPassword();

    if (tokens.isEmpty()) {
      log.error("No se pudo obtener los tokens.");
      return null;
    }

    String[] t = tokens.get();

    this.access_token = Optional.of(t[0]);
    this.refresh_token = Optional.of(t[1]);

    this.tokensMap.put(t[0], t[1]);
    this.pref.put("access_token", t[0]);
    this.pref.put("refresh_token", t[1]);

    return this.access_token.orElse(null);
  }

  /**
   * Realiza una solicitud HTTP POST al endpoint de autenticación utilizando las
   * credenciales
   * configuradas (usuario, contraseña y client ID) para obtener un token JWT.
   *
   * @return Un {@link Optional} con el token de acceso recuperado, o vacío en
   *         caso de error.
   */
  private Optional<String[]> fetchTokenPassword() {

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
      String refreshToken = json.path("refresh_token").asText(null);

      if (accessToken == null || accessToken.isEmpty()) {
        log.error("La respuesta del access token no contiene access_token: " + response.body());
        return Optional.empty();
      }

      if (refreshToken == null || refreshToken.isEmpty()) {
        log.error("La respuesta del refresh token no contiene refresh_token: " + response.body());
        return Optional.empty();
      }

      log.info("Tokens obtenido correctamente");
      return Optional.of(new String[] { accessToken, refreshToken });

    } catch (Exception e) {
      log.error(e.getMessage());
      return Optional.empty();
    }

  }

  /**
   * <p>
   * Realiza una solicitud HTTP POST al endpoint de autenticación utilizando el
   * refresh_token guardado, si no existe este solo truena ademas del client_id
   * para obtener un token JWT.
   * </p>
   * 
   * - Si el {@code refresh_token} no es valido entonces se realiza una peticion
   * con las credenciales para obtener nuevos tokens.
   *
   * @return Un {@link Optional}<{@link String}[]> con el token de acceso y
   *         refresh
   *         recuperados, o vacío en
   *         caso de error.
   */
  private Optional<String[]> fetchTokenRefresh() {

    if (url.isEmpty()) {
      log.error("Hubo un problema al momento de cargar la URL");
      return Optional.empty();
    }

    String refresh_token = this.refresh_token.orElse(null);
    String clientId = props.getProperty("mdpnp.fhir.token.clientId").orElse("my-fhir-client");

    if (refresh_token == null) {
      log.info("No se pudo obtener el refresh token");
      return fetchTokenPassword();
    }

    try {
      String bodyData = "client_id=" + clientId
          + "&refresh_token=" + refresh_token
          + "&grant_type=refresh_token";

      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url.get()))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .POST(HttpRequest.BodyPublishers.ofString(bodyData))
          .build();

      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      JsonNode json = new ObjectMapper().readTree(response.body());

      // Si el refresh_token fue invalidado, se realiza la peticion con las
      // credenciales
      if (response.statusCode() == 400 && json.path("error").asText(null).equals("invalid_grant")) {
        log.info("Refresh token invalidado, obteniendo nuevos tokens...");
        Optional<String[]> tokens = fetchTokenPassword();

        return tokens;
      }

      if (!(response.statusCode() == 200)) {
        log.error("Envio un estatus: " + response.statusCode());
        return Optional.empty();
      }

      String accessToken = json.path("access_token").asText(null);
      String refreshToken = json.path("refresh_token").asText(null);

      if (accessToken == null || accessToken.isEmpty()) {
        log.error("La respuesta del access token no contiene access_token: " + response.body());
        return Optional.empty();
      }

      if (refreshToken == null || refreshToken.isEmpty()) {
        log.error("La respuesta del refresh token no contiene refresh_token: " + response.body());
        return Optional.empty();
      }

      log.info("Tokens obtenido correctamente");
      return Optional.of(new String[] { accessToken, refreshToken });

    } catch (Exception e) {
      log.error(e.getMessage());
      return Optional.empty();
    }
  }

}
