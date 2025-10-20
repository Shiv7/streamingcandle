#!/usr/bin/env python3
"""
Test script to verify MongoDB integration for derivative grouping
"""

import json
import time
from kafka import KafkaProducer

def create_test_messages():
    """Create test messages for different instrument types"""
    
    # Test equity message
    equity_message = {
        "Exch": "NSE",
        "ExchType": "C",  # Cash/Equity
        "Token": "12345",
        "ScripCode": "RELIANCE",
        "LastRate": 2500.50,
        "TotalQty": 1000,
        "TickDt": "2025-01-20T10:30:00.000Z",
        "companyName": "RELIANCE INDUSTRIES LTD",
        "receivedTimestamp": int(time.time() * 1000)
    }
    
    # Test option message (derivative)
    option_message = {
        "Exch": "NSE",
        "ExchType": "D",  # Derivative
        "Token": "67890",
        "ScripCode": "RELIANCE25JAN2500CE",
        "LastRate": 45.25,
        "TotalQty": 500,
        "TickDt": "2025-01-20T10:30:00.000Z",
        "companyName": "RELIANCE 25 JAN 2025 CE 2500.00",
        "receivedTimestamp": int(time.time() * 1000)
    }
    
    # Test future message (derivative)
    future_message = {
        "Exch": "NSE",
        "ExchType": "D",  # Derivative
        "Token": "54321",
        "ScripCode": "RELIANCE25JANFUT",
        "LastRate": 2510.75,
        "TotalQty": 750,
        "TickDt": "2025-01-20T10:30:00.000Z",
        "companyName": "RELIANCE 25 JAN 2025 FUT",
        "receivedTimestamp": int(time.time() * 1000)
    }
    
    return [equity_message, option_message, future_message]

def send_test_messages():
    """Send test messages to Kafka topics"""
    
    producer = KafkaProducer(
        bootstrap_servers=['13.203.60.173:9094'],
        value_serializer=lambda x: json.dumps(x).encode('utf-8'),
        key_serializer=lambda x: x.encode('utf-8') if x else None
    )
    
    messages = create_test_messages()
    
    try:
        for i, message in enumerate(messages):
            # Send to forwardtesting-data topic
            producer.send('forwardtesting-data', 
                         key=message['ScripCode'], 
                         value=message)
            print(f"✅ Sent message {i+1} to forwardtesting-data: {message['ScripCode']}")
            
            time.sleep(1)  # Small delay between messages
            
        producer.flush()
        print("✅ All test messages sent successfully!")
        
    except Exception as e:
        print(f"❌ Error sending messages: {e}")
    finally:
        producer.close()

if __name__ == "__main__":
    print("🚀 Testing MongoDB integration for derivative grouping...")
    print("📊 Sending test messages with different instrument types:")
    print("   - Equity: RELIANCE (ExchType=C)")
    print("   - Option: RELIANCE25JAN2500CE (ExchType=D)")
    print("   - Future: RELIANCE25JANFUT (ExchType=D)")
    print()
    
    send_test_messages()
    
    print()
    print("🔍 Check the enriched-market-data topic to verify:")
    print("   - Equity shows correct company name")
    print("   - Derivatives are grouped under the correct underlying equity family")
    print("   - No more 'Unknown' company names for derivatives")
