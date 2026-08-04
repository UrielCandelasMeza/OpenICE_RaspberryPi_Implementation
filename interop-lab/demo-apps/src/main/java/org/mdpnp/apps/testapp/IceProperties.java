package org.mdpnp.apps.testapp;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

public class IceProperties {

  private String value;

  public IceProperties(String property) {
    this.value = _loadProperties(property).orElse(null);
  }

  public IceProperties() {
  }

  public Optional<String> getValue() {
    return Optional.ofNullable(this.value);
  }

  public Optional<String> getProperty(String property) {
    this.value = _loadProperties(property).orElse(null);
    return Optional.ofNullable(this.value);
  }

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
