package org.mdpnp.headless;

import org.apache.commons.cli.*;
import org.mdpnp.devices.DeviceDriverProvider;
import org.mdpnp.rtiapi.data.EventLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.Iterator;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.CountDownLatch;

/**
 * Headless Device Adapter entry point.
 *
 * <p>Starts a single ICE device adapter without any JavaFX/GUI dependency.
 * All GUI code from demo-apps (DeviceAdapterCommand, Main, IceAppsContainer)
 * is deliberately excluded.
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

        int    domainId      = Integer.parseInt(line.getOptionValue("domain"));
        String deviceAlias   = line.getOptionValue("device");
        String address       = line.hasOption("address") ? line.getOptionValue("address") : null;
        String discoveryPeers = line.hasOption("peers")  ? line.getOptionValue("peers")  : "";

        // ── 2. Resolve the device driver ──────────────────────────────────────
        DeviceDriverProvider ddp = resolveDriver(deviceAlias);
        log.info("Starting headless adapter for device: {}", ddp.getDeviceType());

        // ── 3. Build Spring application context (RtConfig.xml + DriverContext.xml)
        //       The context is the same one used by DeviceAdapterCommand.execute()
        //       but loaded here without any JavaFX involvement.
        Properties env = new Properties();
        env.put("mdpnp.domain",      Integer.toString(domainId));
        env.put("dds.discovery.peers", discoveryPeers);
        env.put("mdpnp.fhir.url",    "");
        env.put("mdpnp.emr.url",     "");
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
        if (address != null) {
            adapter.setAddress(address);
        }

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

        // connect() returns true on success (or immediately for simulated devices).
        // For serial/network devices it negotiates the link; false means failure.
        boolean connected = adapter.connect();
        if (!connected) {
            log.error("Device failed to connect. Exiting.");
            context.close();
            System.exit(1);
        }

        log.info("Device adapter running – type: {}. Press Ctrl+C to stop.",
                ddp.getDeviceType());

        // Block main thread until the shutdown hook fires (Ctrl+C / SIGTERM).
        // This keeps the adapter alive regardless of whether the device type
        // is simulated (non-blocking connect) or serial/network.
        stopLatch.await();

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
                .withDescription("Device alias – see devs.md for valid values (e.g. Pump_Simulator, DraegerV500)")
                .create("device"));

        opts.addOption(OptionBuilder.withArgName("address")
                .hasArg().isRequired(false)
                .withDescription("Serial port or IP address (required for Serial/Network devices)")
                .create("address"));

        opts.addOption(OptionBuilder.withArgName("peers")
                .hasArg().isRequired(false)
                .withDescription("Comma-separated DDS discovery peer IPs (e.g. 192.168.1.20). " +
                        "Leave empty for local multicast discovery.")
                .create("peers"));

        opts.addOption("help", false, "Display this help message");
        return opts;
    }

    private static CommandLine parseArgs(String[] args, Options options) throws ParseException {
        CommandLineParser parser = new GnuParser();
        // check for --help first (permissive parse)
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

    private static DeviceDriverProvider resolveDriver(String alias) {
        ServiceLoader<DeviceDriverProvider> loader = ServiceLoader.load(DeviceDriverProvider.class);
        Iterator<DeviceDriverProvider> it = loader.iterator();
        while (it.hasNext()) {
            DeviceDriverProvider ddp = it.next();
            String[] aliases = ddp.getDeviceType().getAliases();
            for (String a : aliases) {
                if (a.equals(alias)) return ddp;
            }
        }
        throw new IllegalArgumentException(
                "Unknown device alias: '" + alias + "'. Run with --help to see available devices.");
    }

    private static void printAvailableDevices() {
        System.out.println("\nAvailable device aliases:");
        ServiceLoader<DeviceDriverProvider> loader = ServiceLoader.load(DeviceDriverProvider.class);
        for (DeviceDriverProvider ddp : loader) {
            DeviceDriverProvider.DeviceType dt = ddp.getDeviceType();
            System.out.printf("  %-40s [%s] %s / %s%n",
                    dt.getAlias(),
                    dt.getConnectionType(),
                    dt.getManufacturer(),
                    dt.getModel());
        }
    }
}
