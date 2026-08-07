package org.mdpnp.apps.testapp;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Clase que facilita la lectura y gestión de propiedades de configuración
 * del sistema OpenICE, principalmente desde archivos {@code ice.properties}
 * ubicados en el classpath o en el directorio de trabajo actual (CWD).
 * 
 * Permite acceder a los valores configurados de forma segura mediante
 * {@link Optional}.
 * 
 * @author Uriel Candelas
 */
public class IceProperties {

  /** El valor de la última propiedad leída o consultada. */
  private String value;

  /**
   * Crea una instancia de {@code IceProperties} cargando de inmediato
   * el valor de la propiedad especificada.
   *
   * @param property Nombre de la propiedad a buscar (ej. "mdpnp.fhir.url").
   */
  public IceProperties(String property) {
    this.value = _loadProperties(property).orElse(null);
  }

  /**
   * Constructor por defecto. Crea una instancia de {@code IceProperties}
   * sin inicializar ninguna propiedad de forma predeterminada.
   */
  public IceProperties() {
  }

  /**
   * Obtiene el valor almacenado actualmente en la instancia.
   *
   * @return Un {@link Optional} que contiene el valor si existe, o vacío en caso
   *         contrario.
   */
  public Optional<String> getValue() {
    return Optional.ofNullable(this.value);
  }

  /**
   * Consulta, carga y actualiza el valor de la propiedad especificada.
   *
   * @param property Nombre de la propiedad que se desea buscar.
   * @return Un {@link Optional} con el valor de la propiedad si fue encontrada.
   */
  public Optional<String> getProperty(String property) {
    this.value = _loadProperties(property).orElse(null);
    return Optional.ofNullable(this.value);
  }

  /**
   * Carga los archivos de propiedades {@code ice.properties} tanto del classpath
   * como del
   * sistema de archivos local (CWD) y busca el valor correspondiente a la clave
   * proporcionada.
   * Las propiedades locales en el archivo del CWD sobrescriben a las del
   * classpath.
   *
   * @param property Clave de la propiedad a buscar.
   * @return Un {@link Optional} con el valor obtenido, o vacío si no se
   *         encuentra.
   */
  private Optional<String> _loadProperties(String property) {

    Properties props = new Properties();

    try (InputStream is = IceProperties.class.getResourceAsStream("/ice.properties")) {
      if (is != null) {
        props.load(is);
      }
    } catch (IOException ioe) {
      ioe.printStackTrace();
    }

    try (FileInputStream fis = new FileInputStream("ice.properties")) {
      props.load(fis);
    } catch (IOException ioe) {
      // optional user override, missing file is not an error
    }

    return Optional.ofNullable(props.getProperty(property));
  }
}
