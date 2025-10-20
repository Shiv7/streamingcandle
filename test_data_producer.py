#!/usr/bin/env python3
"""
StreamingCandle Test Data Producer

Sends dummy market data to Kafka for testing the streamingcandle module.

Topics:
- forwardtesting-data (TickData)
- Orderbook (OrderBookSnapshot)
- OpenInterest (Open Interest data)

Usage:
    pip install kafka-python
    python test_data_producer.py

Author: Test Script for StreamingCandle Module
Date: October 20, 2025
"""

import json
import time
import random
from datetime import datetime
from kafka import KafkaProducer
from typing import Dict, List

# Configuration
KAFKA_BROKER = "13.203.60.173:9094"
TOPICS = {
    'ticks': 'forwardtesting-data',
    'orderbook': 'Orderbook',
    'openinterest': 'OpenInterest'
}

# Stock symbols to simulate
STOCKS = [
    {"token": 26000, "scripCode": "NIFTY", "name": "NIFTY 50", "basePrice": 19500.0},
    {"token": 26009, "scripCode": "BANKNIFTY", "name": "BANK NIFTY", "basePrice": 44500.0},
    {"token": 2885, "scripCode": "RELIANCE", "name": "RELIANCE INDUSTRIES", "basePrice": 2450.0},
    {"token": 1330, "scripCode": "HDFC", "name": "HDFC BANK", "basePrice": 1650.0},
    {"token": 3045, "scripCode": "SBIN", "name": "STATE BANK OF INDIA", "basePrice": 580.0},
]


class MarketDataGenerator:
    """Generate realistic market data"""
    
    def __init__(self):
        # Initialize price tracking for each stock
        self.current_prices = {}
        self.total_quantities = {}  # Cumulative volumes
        self.open_interests = {}
        
        for stock in STOCKS:
            token = stock["token"]
            self.current_prices[token] = stock["basePrice"]
            self.total_quantities[token] = random.randint(1000000, 5000000)
            self.open_interests[token] = random.randint(10000000, 50000000)
    
    def generate_tick_data(self, stock: Dict) -> Dict:
        """
        Generate TickData matching exact schema from TickData.java
        
        IMPORTANT: Uses exact @JsonProperty names from Java model:
        - Exch, ExchType, Token, ScripCode, LastRate, etc.
        """
        token = stock["token"]
        
        # Simulate price movement (+/- 0.5%)
        price_change = self.current_prices[token] * random.uniform(-0.005, 0.005)
        self.current_prices[token] += price_change
        current_price = round(self.current_prices[token], 2)
        
        # Simulate volume increase
        last_qty = random.randint(100, 5000)
        self.total_quantities[token] += last_qty
        
        # Generate timestamp in /Date(milliseconds)/ format
        timestamp_ms = int(time.time() * 1000)
        tick_dt = f"/Date({timestamp_ms})/"
        
        tick_data = {
            "Exch": "N",  # N = NSE
            "ExchType": "C",  # C = Cash
            "Token": token,
            "ScripCode": stock["scripCode"],
            "LastRate": current_price,
            "LastQty": last_qty,
            "TotalQty": self.total_quantities[token],
            "High": round(current_price * 1.01, 2),
            "Low": round(current_price * 0.99, 2),
            "OpenRate": round(stock["basePrice"], 2),
            "PClose": round(stock["basePrice"] * 0.998, 2),
            "AvgRate": round(current_price * 0.999, 2),
            "Time": timestamp_ms,
            "BidQty": random.randint(500, 2000),
            "BidRate": round(current_price - 0.05, 2),
            "OffQty": random.randint(500, 2000),
            "OffRate": round(current_price + 0.05, 2),
            "TBidQ": random.randint(10000, 50000),
            "TOffQ": random.randint(10000, 50000),
            "TickDt": tick_dt,
            "ChgPcnt": round((current_price - stock["basePrice"]) / stock["basePrice"] * 100, 2),
            "companyName": stock["name"]
        }
        
        return tick_data
    
    def generate_orderbook(self, stock: Dict) -> Dict:
        """
        Generate OrderBookSnapshot matching exact schema from OrderBookSnapshot.java
        """
        token = stock["token"]
        current_price = self.current_prices[token]
        
        # Generate 5 levels of bids (descending prices)
        bid_rates = []
        bid_qtys = []
        for i in range(5):
            bid_rates.append(round(current_price - (i + 1) * 0.05, 2))
            bid_qtys.append(random.randint(100, 2000))
        
        # Generate 5 levels of asks (ascending prices)
        off_rates = []
        off_qtys = []
        for i in range(5):
            off_rates.append(round(current_price + (i + 1) * 0.05, 2))
            off_qtys.append(random.randint(100, 2000))
        
        orderbook = {
            "exchange": "N",
            "exchangeType": "C",
            "token": str(token),  # String in OrderBookSnapshot
            "bidRate": bid_rates,
            "bidQty": bid_qtys,
            "offRate": off_rates,
            "offQty": off_qtys,
            "totalBidQty": sum(bid_qtys),
            "totalOffQty": sum(off_qtys),
            "companyName": stock["name"],
            "receivedTimestamp": int(time.time() * 1000)
        }
        
        return orderbook
    
    def generate_open_interest(self, stock: Dict) -> Dict:
        """
        Generate OpenInterest matching exact schema from OpenInterest.java
        """
        token = stock["token"]
        
        # Simulate OI change
        oi_change = random.randint(-10000, 10000)
        self.open_interests[token] += oi_change
        current_oi = self.open_interests[token]
        
        open_interest = {
            "exchange": "N",
            "exchangeType": "C",
            "token": str(token),  # String in OpenInterest
            "openInterest": current_oi,
            "oiChange": oi_change,
            "oiChangePercent": round((oi_change / current_oi * 100) if current_oi > 0 else 0.0, 2),
            "lastRate": self.current_prices[token],
            "volume": random.randint(100000, 500000),
            "companyName": stock["name"],
            "receivedTimestamp": int(time.time() * 1000)
        }
        
        return open_interest


def create_producer() -> KafkaProducer:
    """Create Kafka producer with JSON serialization"""
    return KafkaProducer(
        bootstrap_servers=[KAFKA_BROKER],
        value_serializer=lambda v: json.dumps(v).encode('utf-8'),
        key_serializer=lambda k: k.encode('utf-8') if k else None,
        compression_type='gzip',
        acks='all',
        retries=3
    )


def send_message(producer: KafkaProducer, topic: str, key: str, data: Dict) -> None:
    """Send message to Kafka topic"""
    try:
        future = producer.send(topic, key=key, value=data)
        record_metadata = future.get(timeout=10)
        print(f"✅ Sent to {topic} | Partition: {record_metadata.partition} | Offset: {record_metadata.offset}")
    except Exception as e:
        print(f"❌ Failed to send to {topic}: {e}")


def main():
    """Main producer loop"""
    
    print("=" * 80)
    print("StreamingCandle Test Data Producer")
    print("=" * 80)
    print(f"Kafka Broker: {KAFKA_BROKER}")
    print(f"Topics: {', '.join(TOPICS.values())}")
    print(f"Stocks: {len(STOCKS)} instruments")
    print("=" * 80)
    print()
    
    # Create producer
    print("🔧 Creating Kafka producer...")
    producer = create_producer()
    print("✅ Producer created successfully")
    print()
    
    # Create data generator
    generator = MarketDataGenerator()
    
    print("🚀 Starting data generation... (Press Ctrl+C to stop)")
    print()
    
    message_count = 0
    
    try:
        while True:
            # Generate data for each stock
            for stock in STOCKS:
                token = stock["token"]
                scrip_code = stock["scripCode"]
                
                # 1. Send Tick Data (most frequent - every iteration)
                tick_data = generator.generate_tick_data(stock)
                send_message(producer, TOPICS['ticks'], scrip_code, tick_data)
                print(f"📊 Tick: {scrip_code} @ ₹{tick_data['LastRate']:.2f} | Vol: {tick_data['TotalQty']:,}")
                
                # 2. Send OrderBook (every 2 iterations)
                if message_count % 2 == 0:
                    orderbook = generator.generate_orderbook(stock)
                    send_message(producer, TOPICS['orderbook'], scrip_code, orderbook)
                    print(f"📖 OrderBook: {scrip_code} | Bid: ₹{orderbook['bidRate'][0]:.2f} | Ask: ₹{orderbook['offRate'][0]:.2f}")
                
                # 3. Send Open Interest (every 5 iterations, for derivatives)
                if message_count % 5 == 0 and token in [26000, 26009]:  # Only for NIFTY and BANKNIFTY
                    oi_data = generator.generate_open_interest(stock)
                    send_message(producer, TOPICS['openinterest'], scrip_code, oi_data)
                    print(f"📈 OI: {scrip_code} | OI: {oi_data['openInterest']:,} | Change: {oi_data['oiChange']:+,}")
                
                print()
            
            message_count += 1
            
            # Progress indicator
            print(f"{'='*80}")
            print(f"Messages sent: {message_count * len(STOCKS)} | Iteration: {message_count}")
            print(f"{'='*80}")
            print()
            
            # Sleep between iterations (simulate real market data rate)
            time.sleep(2)  # 2 seconds between iterations
            
    except KeyboardInterrupt:
        print()
        print("🛑 Stopping producer...")
    
    finally:
        # Flush and close producer
        producer.flush()
        producer.close()
        print("✅ Producer closed successfully")
        print()
        print("=" * 80)
        print(f"Total iterations: {message_count}")
        print(f"Total messages: ~{message_count * len(STOCKS) * 2}")
        print("=" * 80)


if __name__ == "__main__":
    main()

