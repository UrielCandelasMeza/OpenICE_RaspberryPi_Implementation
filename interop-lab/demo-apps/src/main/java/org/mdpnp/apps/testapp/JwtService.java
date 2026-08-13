package org.mdpnp.apps.testapp;

import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JwtService {

  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Verifica si el token JWT (firmado o no) ya expiró decodificando el payload
   * de forma independiente, sin verificar la firma.
   *
   * @param token JWT en formato compacto (header.payload.signature)
   * @return {@code true} si el token expiró o no puede ser decodificado,
   *         {@code false} si sigue vigente o no posee reclamación {@code exp}.
   */
  public boolean verifyExpired(String token) {

    try {
      String[] parts = token.split("\\.");
      if (parts.length < 2) {
        return true;
      }

      byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
      JsonNode json = mapper.readTree(decoded);

      long exp = json.path("exp").asLong();
      if (exp == 0) {
        return false;
      }

      return exp * 1000L < System.currentTimeMillis();

    } catch (Exception e) {
      return true;
    }

  }
}
