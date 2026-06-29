package org.mdpnp.headless;

import org.apache.commons.cli.*;
import org.mdpnp.devices.DeviceDriverProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * Headless Device Adapter entry point.
 *
 * <p>Starts a single ICE device adapter without any JavaFX/GUI dependency.
 *
 * <p>Usage:
 * <pre>
 *   ./gradlew :headless-adapter:run --args="-domain 0 -device Pump_Simulator"
 *   ./gradlew :headless-adapter:run --args="-domain 0 -device DraegerV500 -address /dev/ttyUSB0"
 *   ./gradlew :headless-adapter:run --args="-domain 0 -device IntellivueEthernet -address 192.168.1.100"
 * </pre>
 */
@SuppressWarnings("deprecation")
public class HeadlessMain {

    private static final Logger log = LoggerFactory.getLogger(HeadlessMain.class);

    public static void main(String[] args) throws Exception {

        // ── 1. Parse CLI arguments ─────────────────────────────────────────────
        Options options = buildOptions();
        CommandLine line = parseArgs(args, options);
        if (line == null) return; // --help was printed

        int    domainId       = Integer.parseInt(line.getOptionValue("domain"));
        String deviceAlias    = line.getOptionValue("device");
        String address        = line.hasOption("address") ? line.getOptionValue("address") : null;
        String discoveryPeers = line.hasOption("peers")   ? line.getOptionValue("peers")   : "";

        // ── 2. Resolve the device driver ──────────────────────────────────────
        DeviceDriverProvider ddp = resolveDriver(deviceAlias);
        log.info("Starting headless adapter for device: {}", ddp.getDeviceType());

        // ── 3. Build Spring application context (RtConfig.xml + DriverContext.xml)
        Properties env = new Properties();
        env.put("mdpnp.domain",        Integer.toString(domainId));
        env.put("dds.discovery.peers", discoveryPeers);
        env.put("mdpnp.fhir.url",      "");
        env.put("mdpnp.emr.url",       "");
        env.put("mdpnp.partition.file", "device.partition");

        ClassPathXmlApplicationContext context =
                new ClassPathXmlApplicationContext(new String[]{"DeviceAdapterContext.xml"}, false);
        PropertyPlaceholderConfigurer ppc = new PropertyPlaceholderConfigurer();
        ppc.setIgnoreUnresolvablePlaceholders(true);
        ppc.setProperties(env);
        ppc.setOrder(0);
        context.addBeanFactoryPostProcessor(ppc);
        context.refresh();

        // ── 4. Start the device ───────────────────────────────────────────────
        DeviceDriverProvider.DeviceAdapter adapter = ddp.create(context);
        if (address != null) adapter.setAddress(address);

        // Shutdown hook: graceful stop on Ctrl+C / SIGTERM
        final CountDownLatch stopLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received – stopping adapter...");
            try {
                adapter.disconnect();
                adapter.stop();
            } catch (Exception ex) {
                log.error("Error during shutdown", ex);
            } finally {
                stopLatch.countDown();
            }
        }));

        // connect() returns true immediately for simulated devices,
        // or after link negotiation for serial/network devices.
        if (!adapter.connect()) {
            log.error("Device failed to connect. Exiting.");
            context.close();
            System.exit(1);
        }

        log.info("Device adapter running – type: {}. Press Ctrl+C to stop.", ddp.getDeviceType());
        stopLatch.await();  // block until Ctrl+C

        context.close();
        log.info("Headless adapter stopped cleanly.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("static-access")
    private static Options buildOptions() {
        Options opts = new Options();
        opts.addOption(OptionBuilder.withArgName("domain")
                .hasArg().isRequired(true)
                .withDescription("DDS domain ID (e.g. 0)")
                .create("domain"));
        opts.addOption(OptionBuilder.withArgName("device")
                .hasArg().isRequired(true)
                .withDescription("Device alias (e.g. Pump_Simulator, DraegerV500). Run --help to list all.")
                .create("device"));
        opts.addOption(OptionBuilder.withArgName("address")
                .hasArg().isRequired(false)
                .withDescription("Serial port or IP (required for Serial/Network devices).")
                .create("address"));
        opts.addOption(OptionBuilder.withArgName("peers")
                .hasArg().isRequired(false)
                .withDescription("Comma-separated DDS discovery peer IPs. Empty = local multicast.")
                .create("peers"));
        opts.addOption("help", false, "Display this help message");
        return opts;
    }

    private static CommandLine parseArgs(String[] args, Options options) throws Exception {
        CommandLineParser parser = new GnuParser();
        Options helpOnly = new Options();
        helpOnly.addOption("help", false, "help");
        CommandLine helpLine = parser.parse(helpOnly, args, true);
        if (helpLine.hasOption("help")) {
            new HelpFormatter().printHelp("OpenICE-headless", options);
            printAvailableDevices();
            return null;
        }
        return parser.parse(options, args);
    }

    /**
     * Loads all {@link DeviceDriverProvider} entries from every
     * {@code META-INF/services/org.mdpnp.devices.DeviceDriverProvider} file
     * found on the classpath (this module + demo-devices), silently skipping
     * any entry whose class is not available (e.g. {@code DeviceFactory$*}
     * entries that require demo-apps/JavaFX).
     *
     * <p>To register a new provider, simply add its fully-qualified class name
     * to {@code headless-adapter/src/main/resources/META-INF/services/org.mdpnp.devices.DeviceDriverProvider}.
     * No code change here is needed.
     */
    private static List<DeviceDriverProvider> loadProviders() throws Exception {
        List<DeviceDriverProvider> providers = new ArrayList<>();
        String spiResource = "META-INF/services/" + DeviceDriverProvider.class.getName();

        // getResources() returns ALL matching files across the entire classpath
        // (from demo-devices AND from this module).
        Enumeration<URL> urls =
                HeadlessMain.class.getClassLoader().getResources(spiResource);

        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            log.debug("Reading SPI file: {}", url);
            try (BufferedReader br =
                         new BufferedReader(new InputStreamReader(url.openStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    try {
                        Class<?> cls = Class.forName(line);
                        providers.add(
                            (DeviceDriverProvider) cls.getDeclaredConstructor().newInstance());
                        log.debug("Loaded provider: {}", line);
                    } catch (ClassNotFoundException e) {
                        // Expected for DeviceFactory$* entries when demo-apps
                        // (JavaFX) is not on the classpath. Skip silently.
                        log.debug("Skipping unavailable provider: {}", line);
                    }
                }
            }
        }

        if (providers.isEmpty()) {
            log.warn("No DeviceDriverProviders found. Check SPI file in resources.");
        }
        return providers;
    }

    private static DeviceDriverProvider resolveDriver(String alias) throws Exception {
        for (DeviceDriverProvider ddp : loadProviders()) {
            for (String a : ddp.getDeviceType().getAliases()) {
                if (a.equals(alias)) return ddp;
            }
        }
        throw new IllegalArgumentException(
                "Unknown device alias: '" + alias + "'. Run with --help to see available devices.");
    }

    private static void printAvailableDevices() throws Exception {
        System.out.println("\nAvailable device aliases:");
        for (DeviceDriverProvider ddp : loadProviders()) {
            DeviceDriverProvider.DeviceType dt = ddp.getDeviceType();
            System.out.printf("  %-40s [%s] %s / %s%n",
                    dt.getAlias(),
                    dt.getConnectionType(),
                    dt.getManufacturer(),
                    dt.getModel());
        }
    }
}
