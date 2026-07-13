package org.mdpnp.devices.hl7;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.app.Application;
import ca.uhn.hl7v2.app.SimpleServer;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v24.message.ACK;
import ca.uhn.hl7v2.model.v24.message.ORU_R01;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.parser.EncodingNotSupportedException;
import ca.uhn.hl7v2.parser.Parser;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

/**
 * Servicio general para manejo de HL7 v2.4 usando HAPI.
 *
 * <p>
 * Provee infraestructura MLLP/HL7 reutilizable:
 * <ul>
 * <li>Parseo de mensajes HL7 raw a objetos HAPI</li>
 * <li>Creación de servidores MLLP (SimpleServer)</li>
 * <li>Generación de ACK</li>
 * <li>Casting a tipos específicos (ORU_R01)</li>
 * </ul>
 *
 * <p>
 * Este servicio es un Spring {@code @Component} singleton. Se inyecta
 * en los drivers que necesiten comunicarse vía HL7.
 */
@Component
public class Hl7Service {

    private static final Logger log = LoggerFactory.getLogger(Hl7Service.class);

    private HapiContext context;

    @PostConstruct
    public void init() {
        context = new DefaultHapiContext();
        Parser parser = context.getPipeParser();
        parser.getParserConfiguration().setValidating(false);
        log.info("Hl7Service initialized (HL7 v2.4)");
    }

    /**
     * Crea un servidor MLLP en el puerto especificado.
     *
     * @param port    puerto TCP para escuchar conexiones MLLP
     * @param handler aplicación HAPI que procesará los mensajes recibidos
     * @return el servidor MLLP listo para iniciar
     */
    public SimpleServer createServer(int port, Application handler) throws HL7Exception {
        SimpleServer server = new SimpleServer(context, port, false);
        server.registerApplication("*", "*", handler);
        return server;
    }

    /**
     * Parsea un mensaje HL7 crudo (sin framing MLLP) a un objeto Message de HAPI.
     *
     * @param rawMessage cadena HL7 delimitada por pipes
     * @return objeto Message parseado
     */
    public Message parse(String rawMessage) throws HL7Exception, EncodingNotSupportedException {
        return context.getPipeParser().parse(rawMessage);
    }

    /**
     * Codifica un objeto Message HAPI a una cadena HL7 delimitada por pipes.
     *
     * @param msg objeto Message a codificar
     * @return cadena HL7 resultante
     */
    public String encode(Message msg) throws HL7Exception {
        return context.getPipeParser().encode(msg);
    }

    /**
     * Cast genérico de Message a ORU_R01.
     *
     * @param msg mensaje HAPI ya parseado
     * @return ORU_R01
     * @throws ClassCastException si el mensaje no es ORU_R01
     */
    public ORU_R01 castToORU(Message msg) {
        return (ORU_R01) msg;
    }

    /**
     * Genera un ACK positivo (AA) para el mensaje recibido.
     *
     * @param msg mensaje original al que responder
     * @return Message ACK
     */
    public Message generateACK(Message msg) throws HL7Exception {
        try {
            ACK ack = (ACK) msg.generateACK();
            // Set fields that might be missing when generated from parsed message
            if (msg.getVersion() != null) {
                ack.getMSH().getVersionID().getVersionID().setValue(msg.getVersion());
            }
            return ack;
        } catch (java.io.IOException e) {
            throw new HL7Exception(e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (context != null) {
            try {
                context.close();
            } catch (java.io.IOException e) {
                log.warn("Error closing HapiContext", e);
            }
            log.info("Hl7Service shut down");
        }
    }
}
