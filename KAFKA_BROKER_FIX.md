# Kafka Broker Auto Topic Creation Configuration

## Enable Auto Topic Creation

SSH into the Kafka broker server and update the server configuration:

```bash
# 1. SSH to broker server
ssh -i ~/Downloads/newDevinaKey.pem ubuntu@13.203.60.173

# 2. Edit Kafka server.properties
sudo nano /opt/kafka/config/server.properties

# 3. Add or update these lines:
auto.create.topics.enable=true
num.partitions=20
default.replication.factor=1
