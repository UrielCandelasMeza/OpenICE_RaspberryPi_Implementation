#!/usr/bin/env bash
# =============================================================================
# build.sh — prepara los artefactos y construye las imágenes Docker de OpenICE
# =============================================================================
# Uso:
#   ./build.sh                → build completo (gradle + imágenes Docker)
#   ./build.sh --skip-gradle  → omite gradle si flat/ ya existe
# =============================================================================

set -e

SKIP_GRADLE=false
for arg in "$@"; do
  [[ "$arg" == "--skip-gradle" ]] && SKIP_GRADLE=true
done

echo "======================================================"
echo " OpenICE — Build de imágenes Docker"
echo "======================================================"

# --- Paso 1: Compilar con Gradle y generar flat/ ---
if [[ "$SKIP_GRADLE" == false ]]; then
  echo ""
  echo "▶ [1/2] Compilando con Gradle y generando flat/..."
  ./gradlew :interop-lab:demo-apps:makeFlatRuntime --no-daemon -x test
  echo "✅  flat/ generado correctamente."
else
  echo ""
  echo "⏭  [1/2] Gradle omitido (--skip-gradle)."
  if [[ ! -d "interop-lab/demo-apps/flat" ]]; then
    echo "❌  ERROR: flat/ no existe. Ejecuta sin --skip-gradle primero."
    exit 1
  fi
fi

# --- Paso 2: Construir las imágenes Docker ---
echo ""
echo "▶ [2/2] Construyendo imágenes Docker..."

# Una sola imagen para todos los modos Java (pump, monitor, supervisor, demo-app)
echo "  → openice:1.0  (Dockerfile)"
sudo docker build -t openice:1.0 .

# Imagen separada para el Web Integration Service (binario nativo RTI)
echo "  → openice-wis:1.0  (wis-docker/Dockerfile)"
sudo docker build -t openice-wis:1.0 wis-docker/

echo ""
echo "======================================================"
echo "✅  Build completado. Ahora puedes ejecutar:"
echo ""
echo "  # Solo el visualizador web (http://localhost:8080):"
echo "  docker compose --profile wis up"
echo ""
echo "  # WIS + bomba de infusión simulada:"
echo "  docker compose --profile wis --profile pump up"
echo ""
echo "  # WIS + monitor multiparámetro:"
echo "  docker compose --profile wis --profile monitor up"
echo ""
echo "  # Stack completo:"
echo "  docker compose --profile wis --profile pump --profile monitor up"
echo ""
echo "  # Ejecución directa (sin compose):"
echo "  docker run --rm --network host openice:1.0 \\"
echo "    java -cp \"./*\" -Djava.awt.headless=true \\"
echo "    org.mdpnp.apps.testapp.Main \\"
echo "    -app ICE_Device_Interface -domain 10 -device Controllable_Pump"
echo "======================================================"
