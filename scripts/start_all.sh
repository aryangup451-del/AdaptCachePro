#!/bin/bash
set -e
cd /workspaces/AdaptCachePro

pkill -9 -f com.adaptivecache.Main 2>/dev/null || true
pkill -9 -f cloudflared 2>/dev/null || true
sleep 1

CP=out/production/AdaptiveCache
JAVA=/usr/lib/jvm/java-21-openjdk/bin/java

mkdir -p docs/demo-output

echo "Starting origin on :9000..."
setsid $JAVA -cp $CP com.adaptivecache.Main origin > docs/demo-output/cluster-origin.log 2>&1 < /dev/null &
sleep 1

echo "Starting Proxy A on :8081..."
NODE_ID=node-a PEER_PROXIES=http://127.0.0.1:8082 PROXY_PORT=8081 setsid $JAVA -cp $CP com.adaptivecache.Main proxy > docs/demo-output/cluster-a.log 2>&1 < /dev/null &
sleep 1

echo "Starting Proxy B on :8082..."
NODE_ID=node-b PEER_PROXIES=http://127.0.0.1:8081 PROXY_PORT=8082 setsid $JAVA -cp $CP com.adaptivecache.Main proxy > docs/demo-output/cluster-b.log 2>&1 < /dev/null &
sleep 2

# Verify local health
echo "Verifying local Proxy A..."
curl -sI http://127.0.0.1:8081/_health | head -n 1

# Start Cloudflare Tunnel
echo "Starting Cloudflare tunnel..."
setsid /tmp/cloudflared tunnel --url http://127.0.0.1:8081 > /tmp/tunnel.log 2>&1 < /dev/null &
sleep 6

PUBLIC_URL=$(grep -o 'https://[-a-z0-9]*\.trycloudflare\.com' /tmp/tunnel.log | head -n 1)
echo "PUBLIC_URL: $PUBLIC_URL"
