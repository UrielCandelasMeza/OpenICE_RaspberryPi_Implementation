FROM eclipse-temurin:25-jre
COPY interop-lab/demo-apps/flat /opt/mdpnp/flat
WORKDIR /opt/mdpnp/flat

ENV LD_LIBRARY_PATH=/opt/mdpnp/flat
ENV RTI_LICENSE_FILE=/opt/mdpnp/flat/OpenICE_license.dat
# Imagen base para los dispositivos. Extender con CMD, por ejemplo:
# CMD java -cp "./*" -Djava.awt.headless=true org.mdpnp.apps.testapp.Main \
#          -app ICE_Device_Interface -domain 10 -device Controllable_Pump
