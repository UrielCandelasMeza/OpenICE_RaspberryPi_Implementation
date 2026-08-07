package org.mdpnp.apps.testapp.mqtt;

import java.io.IOException;
import java.net.URL;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import org.mdpnp.apps.testapp.DeviceListModel;
import org.mdpnp.apps.testapp.IceApplicationProvider;
import org.mdpnp.rtiapi.data.EventLoop;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import com.rti.dds.subscription.Subscriber;

public class MqttSendFactory implements IceApplicationProvider {
    // private static final Logger log =
    // LoggerFactory.getLogger(MqttSendFactory.class);

    private final IceApplicationProvider.AppType appType = new IceApplicationProvider.AppType(
            "MQTT Send", "NOMQTT",
            (URL) MqttSendFactory.class.getResource("mqtt-send.png"), 0.75, false);

    @Override
    public IceApplicationProvider.AppType getAppType() {
        return appType;
    }

    @Override
    public IceApplicationProvider.IceApp create(ApplicationContext parentContext) throws IOException {
        final Subscriber subscriber = (Subscriber) parentContext.getBean("subscriber");
        final EventLoop eventLoop = (EventLoop) parentContext.getBean("eventLoop");
        final DeviceListModel deviceListModel = parentContext.getBean("deviceListModel", DeviceListModel.class);

        FXMLLoader loader = new FXMLLoader(MqttSend.class.getResource("MqttSend.fxml"));
        final Parent ui = loader.load();
        final MqttSend controller = loader.getController();

        controller.start(eventLoop, subscriber, deviceListModel);

        return new IceApplicationProvider.IceApp() {
            @Override
            public IceApplicationProvider.AppType getDescriptor() {
                return appType;
            }

            @Override
            public Parent getUI() {
                return ui;
            }

            @Override
            public void activate(ApplicationContext context) {
            }

            @Override
            public void stop() {
            }

            @Override
            public void destroy() throws Exception {
                controller.stop();
            }
        };
    }
}
