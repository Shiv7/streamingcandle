#!/bin/bash

# Production Deployment Script for Streaming Candle Service
# This script ensures proper setup of directories and permissions

set -e  # Exit on any error

echo "🚀 Starting Production Deployment for Streaming Candle Service..."

# Configuration
APP_NAME="streamingcandle"
STATE_DIR="/var/lib/kafka-streams/${APP_NAME}"
LOG_DIR="/var/log/${APP_NAME}"
SERVICE_USER="kotsin"  # Change to your service user

# Create necessary directories
echo "📁 Creating state and log directories..."
sudo mkdir -p "${STATE_DIR}"
sudo mkdir -p "${LOG_DIR}"

# Set proper ownership and permissions
echo "🔐 Setting directory permissions..."
sudo chown -R ${SERVICE_USER}:${SERVICE_USER} "${STATE_DIR}"
sudo chown -R ${SERVICE_USER}:${SERVICE_USER} "${LOG_DIR}"
sudo chmod -R 755 "${STATE_DIR}"
sudo chmod -R 755 "${LOG_DIR}"

# Clean up any existing state from previous failed runs
echo "🧹 Cleaning up existing state directories..."
sudo find "${STATE_DIR}" -name "*.lock" -delete 2>/dev/null || true
sudo find "${STATE_DIR}" -name "*-StreamThread-*" -type d -exec rm -rf {} + 2>/dev/null || true

# Verify Kafka connectivity
echo "📡 Verifying Kafka connectivity..."
timeout 10 bash -c "cat < /dev/null > /dev/tcp/172.31.0.121/9092" || {
    echo "❌ ERROR: Cannot connect to Kafka broker at 172.31.0.121:9092"
    echo "Please ensure Kafka is running and accessible"
    exit 1
}

echo "✅ Kafka connectivity verified"

# Build the application
echo "🔨 Building application..."
mvn clean package -DskipTests

# Check if JAR was built successfully
JAR_FILE="target/demo-0.0.1-SNAPSHOT.jar"
if [ ! -f "${JAR_FILE}" ]; then
    echo "❌ ERROR: JAR file not found at ${JAR_FILE}"
    exit 1
fi

echo "✅ Build completed successfully"

# Create systemd service file (optional)
cat > /tmp/${APP_NAME}.service << EOF
[Unit]
Description=Streaming Candle Kafka Processor
After=network.target kafka.service

[Service]
Type=simple
User=${SERVICE_USER}
WorkingDirectory=$(pwd)
ExecStart=/usr/bin/java -jar -Xmx2g -Xms1g ${JAR_FILE}
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal
Environment=JAVA_OPTS="-Djava.io.tmpdir=/tmp/${APP_NAME}"

[Install]
WantedBy=multi-user.target
EOF

echo "📋 Systemd service file created at /tmp/${APP_NAME}.service"
echo "To install as a system service, run:"
echo "  sudo cp /tmp/${APP_NAME}.service /etc/systemd/system/"
echo "  sudo systemctl daemon-reload"
echo "  sudo systemctl enable ${APP_NAME}"
echo "  sudo systemctl start ${APP_NAME}"

echo ""
echo "🎯 PRODUCTION CHECKLIST:"
echo "✅ State directories created with proper permissions"
echo "✅ Kafka connectivity verified"
echo "✅ Application built successfully"
echo "✅ Directory conflicts resolved"
echo ""
echo "🚀 Ready to start the streaming candle service!"
echo ""
echo "To run manually:"
echo "  java -jar ${JAR_FILE}"
echo ""
echo "To monitor logs:"
echo "  tail -f ${LOG_DIR}/application.log"

echo ""
echo "⚠️  IMPORTANT PRODUCTION NOTES:"
echo "1. Ensure /var/lib/kafka-streams has sufficient disk space (>10GB recommended)"
echo "2. Monitor memory usage - each timeframe uses ~100MB"
echo "3. Set up log rotation for ${LOG_DIR}"
echo "4. Configure monitoring for Kafka consumer lag"
echo "5. Test failover scenarios in staging first" 