package org.mdpnp.headless;

import org.mdpnp.devices.AbstractDevice;
import org.mdpnp.devices.DeviceDriverProvider;
import org.mdpnp.devices.DeviceDriverProvider.SpringLoadedDriver;
import org.mdpnp.devices.coleparmer.TB800Balance;
import org.mdpnp.devices.cpc.bernoulli.DemoBernoulli;
import org.mdpnp.devices.denver.mseries.MSeriesScale;
import org.mdpnp.devices.draeger.medibus.*;
import org.mdpnp.devices.fluke.prosim68.DemoProsim68;
import org.mdpnp.devices.ge.serial.DemoGESerial;
import org.mdpnp.devices.hospira.symbiq.DemoSymbiq;
import org.mdpnp.devices.ivy._450c.DemoIvy450C;
import org.mdpnp.devices.masimo.radical.DemoRadical7;
import org.mdpnp.devices.nellcor.pulseox.DemoN595;
import org.mdpnp.devices.nihon.koden.NKV550;
import org.mdpnp.devices.nonin.pulseox.DemoNoninPulseOx;
import org.mdpnp.devices.oridion.capnostream.DemoCapnostream20;
import org.mdpnp.devices.philips.intellivue.DemoEthernetIntellivue;
import org.mdpnp.devices.philips.intellivue.DemoSerialIntellivue;
import org.mdpnp.devices.simulation.co2.SimCapnometer;
import org.mdpnp.devices.simulation.ecg.SimElectroCardioGram;
import org.mdpnp.devices.simulation.ibp.SimInvasivePressure;
import org.mdpnp.devices.simulation.multi.SimMultiparameter;
import org.mdpnp.devices.simulation.nibp.DemoSimulatedBloodPressure;
import org.mdpnp.devices.simulation.pulseox.*;
import org.mdpnp.devices.simulation.pump.SimControllablePump;
import org.mdpnp.devices.simulation.clcbp.SimControllableBPMonitor;
import org.mdpnp.devices.simulation.pump.SimInfusionPump;
import org.mdpnp.devices.simulation.temp.SimThermometer;
import org.mdpnp.devices.zephyr.biopatch.DemoBioPatch;
import org.mdpnp.devices.baxter.AS50;
import org.mdpnp.devices.alaris.Asena;
import org.mdpnp.devices.puritanbennett._840.PB840Provider;
import org.mdpnp.devices.puritanbennett._840.PB980Provider;
import org.mdpnp.rtiapi.data.EventLoop;
import org.springframework.context.support.AbstractApplicationContext;

import com.rti.dds.publication.Publisher;
import com.rti.dds.subscription.Subscriber;

/**
 * Device provider implementations for the headless adapter module.
 *
 * <p>This mirrors {@code org.mdpnp.apps.testapp.DeviceFactory} from demo-apps but
 * has <strong>zero JavaFX dependency</strong>. All inner classes are registered via
 * {@code META-INF/services/org.mdpnp.devices.DeviceDriverProvider} in this module
 * so that the ServiceLoader finds only these (headless-safe) implementations.
 */
public class HeadlessDeviceFactory {

    // ── Simulated devices ─────────────────────────────────────────────────────

    public static class PO_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "PO (Legacy)", "PO_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new SimPulseOximeter(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class FourSecFullyFixedAvePO_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "PO (4s Fixed Average)", "4S_FF_AVG_PO_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new FourSecFixedAvgSimPulseOximeter(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class FourSecFullyFixedNoSoftAvePO_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "PO (4s Fixed Average No Soft Avg)", "4S_FF_NO_SOFT_AVG_PO_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new FourSecNoSoftAvgSimPulseOximeter(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class EightSecFullyFixedAvePO_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "PO (8s Fixed Average)", "8S_FF_AVG_PO_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new EightSecFixedAvgSimPulseOximeter(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class EightSecIceSettableFixedAvePO_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "PO (8s ICE Settable Average)", "8S_IS_AVG_PO_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new InitialEightSecIceSettableAvgSimPulseOximeter(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class EightSecOperSettableFixedAvePO_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "PO (8s Oper Settable Average)", "8S_OS_AVG_PO_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new InitialEightSecOperSettableAvgSimPulseOximeter(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class NIBP_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "Noninvasive Blood Pressure", "NIBP_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoSimulatedBloodPressure(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class IBP_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "Invasive Blood Pressure", "IBP_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new SimInvasivePressure(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class ECG_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "ElectroCardioGram", "ECG_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new SimElectroCardioGram(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class CO2_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "Capnometer", "CO2_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new SimCapnometer(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class Temp_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "Temperature Probe", "Temp_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new SimThermometer(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class Pump_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "Infusion Pump", "Pump_Simulator", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new SimInfusionPump(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class ControllablePump_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "Controllable Pump", "Controllable_Pump", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new SimControllablePump(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class ControllableBPMonitor_SimulatorProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "Controllable BP Monitor", "Controllable_BPMonitor", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new SimControllableBPMonitor(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class MultiparameterProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "ICE", "Multiparameter Monitor", "Multiparameter", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new SimMultiparameter(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class SymbiqProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Simulated, "Hospira", "Symbiq", "Symbiq", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoSymbiq(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    // ── Serial devices ────────────────────────────────────────────────────────

    public static class NoninProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Nonin", "Bluetooth Pulse Oximeter", "Nonin", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoNoninPulseOx(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class NellcorN595Provider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Nellcor", "N-595", "NellcorN595", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoN595(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class MasimoRadical7Provider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Masimo", "Radical-7", "MasimoRadical7", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoRadical7(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class IntellivueSerialProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Philips", "Intellivue (MIB/RS232)", "IntellivueSerial", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoSerialIntellivue(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class DraegerApolloProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Dr\u00E4ger", "Apollo", new String[]{"DraegerApollo", "Dr\u00E4gerApollo"}, 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoApollo(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class DraegerEvitaXLProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Dr\u00E4ger", "EvitaXL", new String[]{"DraegerEvitaXL", "Dr\u00E4gerEvitaXL"}, 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoEvitaXL(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class DraegerV500Provider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Dr\u00E4ger", "V500", new String[]{"DraegerV500", "Dr\u00E4gerV500"}, 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoV500(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class DraegerV500_38400Provider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Dr\u00E4ger", "V500 (38400)", new String[]{"DraegerV500_38400", "Dr\u00E4gerV500_38400"}, 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoV500_38400(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class DraegerEvita4Provider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Dr\u00E4ger", "Evita4", new String[]{"DraegerEvita4", "Dr\u00E4gerEvita4"}, 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoEvita4(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class Capnostream20Provider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Oridion", "Capnostream20", "Capnostream20", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoCapnostream20(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class FlukeProsim68Provider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Fluke", "Prosim 6/8", "FlukeProsim68", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoProsim68(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class Ivy450CProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Ivy", "450C Monitor", "Ivy450C", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoIvy450C(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class BioPatchProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Zephyr", "BioPatch", "BioPatch", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoBioPatch(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class GESerialProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "GE", "Dash", "GESerial", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoGESerial(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class MSeriesScaleProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Denver", "MSeries", "MSeries", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new MSeriesScale(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class AlarisSerialProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Alaris", "Asena", "AlarisPump", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new Asena(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class AS50SerialProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Baxter", "AS50", "AS50", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new AS50(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class TB800Provider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Serial, "Cole-Parmer", "TB800", "TB800", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new TB800Balance(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    // PB840 / PB980 already have their own standalone providers in demo-devices.
    // They are re-exported here for completeness via the headless services file.
    public static class PB840HeadlessProvider extends PB840Provider {}
    public static class PB980HeadlessProvider extends PB980Provider {}

    // ── Network devices ───────────────────────────────────────────────────────

    public static class IntellivueEthernetProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Network, "Philips", "Intellivue (LAN)", "IntellivueEthernet", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoEthernetIntellivue(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class BernoulliProvider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Network, "CardioPulmonaryCorp", "Bernoulli", "Bernoulli", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new DemoBernoulli(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }

    public static class NKV550Provider extends SpringLoadedDriver {
        @Override public DeviceType getDeviceType() {
            return new DeviceType(ice.ConnectionType.Network, "Nihon Koden", "NKV550", "NKV550", 1);
        }
        @Override public AbstractDevice newInstance(AbstractApplicationContext ctx) throws Exception {
            return new NKV550(ctx.getBean("subscriber", Subscriber.class),
                    ctx.getBean("publisher", Publisher.class),
                    ctx.getBean("eventLoop", EventLoop.class));
        }
    }
}
