#!/bin/bash
# device_adapter.sh
# Script para administrar el adaptador headless de OpenICE en una Raspberry Pi

ACTION=$1
DEVICE=$2
DOMAIN=${3:-0}

# Rutas de instalación para la Raspberry Pi
PI_USER="pi"
PI_HOME="/home/$PI_USER"
INSTALL_DIR="$PI_HOME/OpenICE"
DEVICE_THIS="$PI_HOME/device.this"
SERVICE_NAME="headless-adapter"

case "$ACTION" in
    list)
        echo "Obteniendo la lista de devices disponibles..."
        ./gradlew :headless-adapter:run --args="--help"
        ;;
        
    install)
        if [ -z "$DEVICE" ]; then
            echo "Error: Debes especificar el nombre del device."
            echo "Ejemplo: ./device_adapter.sh install Pump_Simulator 15"
            exit 1
        fi
        
        echo "=== 1. Guardando configuración en $DEVICE_THIS ==="
        # Si tienes que agregar peers de red, puedes modificar esta línea
        echo "-domain $DOMAIN -device $DEVICE" > "$DEVICE_THIS"
        cat "$DEVICE_THIS"
        
        echo "=== 2. Compilando el empaquetado headless (distZip) ==="
        # Compila el .zip excluyendo el módulo conflictivo de Java 25
        ./gradlew :headless-adapter:distZip -x :data-types:x73-idl-rti-dds:compileJava
        
        ZIP_FILE=$(ls -rt headless-adapter/build/distributions/OpenICE-headless-*.zip 2>/dev/null | tail -n 1)
        if [ -z "$ZIP_FILE" ]; then
            echo "Error: No se encontró el empaquetado .zip en headless-adapter/build/distributions/"
            exit 1
        fi
        
        echo "=== 3. Instalando en $INSTALL_DIR ==="
        mkdir -p "$INSTALL_DIR"
        
        echo "Descomprimiendo $ZIP_FILE..."
        unzip -o -q "$ZIP_FILE" -d "$INSTALL_DIR"
        
        DIR_NAME=$(basename "$ZIP_FILE" .zip)
        
        echo "Actualizando el symlink OpenICE.current..."
        rm -f "$INSTALL_DIR/OpenICE.current"
        ln -s "$INSTALL_DIR/$DIR_NAME" "$INSTALL_DIR/OpenICE.current"
        
        # Asegurarse de que el script tenga permisos de ejecución
        chmod +x "$INSTALL_DIR/OpenICE.current/bin/OpenICE-headless"
        
        echo "=== 4. Instalando y reiniciando el servicio $SERVICE_NAME ==="
        CURRENT_DIR=$(pwd)
        
        # Copiar y dar permisos al script de servicio
        if [ -f "$CURRENT_DIR/headless-adapter.init" ]; then
            sudo cp "$CURRENT_DIR/headless-adapter.init" "/etc/init.d/$SERVICE_NAME"
            sudo chmod +x "/etc/init.d/$SERVICE_NAME"
            
            # Registrar el servicio para que inicie con el sistema
            if command -v systemctl >/dev/null 2>&1; then
                sudo systemctl daemon-reload
                sudo systemctl enable $SERVICE_NAME >/dev/null 2>&1 || true
                sudo systemctl restart $SERVICE_NAME
            else
                sudo update-rc.d $SERVICE_NAME defaults >/dev/null 2>&1 || true
                sudo service $SERVICE_NAME restart
            fi
        else
            echo "Advertencia: No se encontró 'headless-adapter.init' en $CURRENT_DIR."
            echo "El servicio no se pudo instalar automáticamente."
        fi
        
        echo "¡Instalación completada! El adaptador está corriendo en background."
        ;;
        
    device)
        if [ -f "$DEVICE_THIS" ]; then
            echo "Device actualmente instalado y configurado:"
            cat "$DEVICE_THIS"
            echo ""
            echo "Estado del servicio:"
            sudo service $SERVICE_NAME status || echo "Servicio no encontrado."
        else
            echo "No hay ningún device configurado actualmente (no se encontró $DEVICE_THIS)."
        fi
        ;;
        
    *)
        echo "Uso: ./device_adapter.sh [comando] [argumentos...]"
        echo ""
        echo "Comandos:"
        echo "  list                      Muestra la lista de todos los devices soportados."
        echo "  install DEVICE [DOMAIN]   Compila, instala y levanta el servicio para el device indicado."
        echo "                            (El dominio por defecto es 0 si no se provee)."
        echo "  device                    Muestra el device que se encuentra corriendo actualmente."
        echo ""
        echo "Ejemplos:"
        echo "  ./device_adapter.sh list"
        echo "  ./device_adapter.sh install Pump_Simulator"
        echo "  ./device_adapter.sh install DraegerV500 15"
        exit 1
        ;;
esac
