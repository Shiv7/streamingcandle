#!/bin/bash

echo "=========================================="
echo "Verifying Test Data Producer"
echo "=========================================="
echo ""

# Check if producer is running
echo "1. Checking if producer is running..."
if ps aux | grep -q "[t]est_data_producer.py"; then
    echo "✅ Producer is RUNNING"
    PID=$(ps aux | grep "[t]est_data_producer.py" | awk '{print $2}')
    echo "   PID: $PID"
else
    echo "❌ Producer is NOT running"
    exit 1
fi
echo ""

# Test Kafka connection
echo "2. Testing Kafka connection..."
if nc -z -w 5 13.203.60.173 9094 2>/dev/null; then
    echo "✅ Kafka broker is reachable"
else
    echo "⚠️  Cannot reach Kafka broker (may be firewall/network issue)"
fi
echo ""

# Check topics (if kafka-topics is available)
echo "3. Topics to verify:"
echo "   - forwardtesting-data"
echo "   - Orderbook"
echo "   - OpenInterest"
echo ""

echo "To manually verify data is being sent, run:"
echo ""
echo "kafka-console-consumer --bootstrap-server 13.203.60.173:9094 \\"
echo "  --topic forwardtesting-data --max-messages 3"
echo ""

echo "=========================================="
echo "Producer Verification Complete"
echo "=========================================="

