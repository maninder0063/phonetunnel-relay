# Phone Tunnel relay (MVP2)

A tiny WebSocket relay that pairs two Phone Tunnel phones by a 6-digit code and
forwards their encrypted tunnel frames. Use this when the two phones are on
different Wi-Fi networks or one is on mobile data.

The relay **never decrypts** your traffic — it just shuttles AES-GCM ciphertext
between the two phones. The encryption key is derived from your 6-digit code on
both phones via PBKDF2, never sent over the network.

## Build

```powershell
# from inside the PhoneTunnel-Relay folder
.\gradlew.bat shadowJar          # if you have a wrapper, or use the existing system Gradle
```

Output: `build\libs\phonetunnel-relay.jar` (about 8 MB, includes Ktor + Netty).

## Run locally

```powershell
java -jar build\libs\phonetunnel-relay.jar
# logs:
# 11:30:11.103 INFO  relay - Phone Tunnel relay starting on 0.0.0.0:8080
```

Smoke-test it:

```powershell
curl http://localhost:8080/health      # -> ok
curl http://localhost:8080/stats       # -> active=0 total=0 bytes=0 pending=0
```

## Expose it to the internet

You have three realistic options.

### Option A — ngrok (zero infrastructure, free, perfect for testing)

```powershell
# in a second terminal, after the relay is running on :8080
ngrok http 8080
# prints something like:
#   Forwarding   https://abc123.ngrok-free.app -> http://localhost:8080
```

Use **`wss://abc123.ngrok-free.app`** as the relay URL inside the Phone Tunnel
app on both phones. ngrok terminates TLS for free and gives you a stable
hostname for the session.

Caveats:
- Free ngrok URLs change each restart. Re-enter on both phones if you restart.
- Free tier limits connections — fine for two phones.
- Latency: every byte goes US East (ngrok default) and back. Add 50-150 ms RTT.

### Option B — small cloud VM (the production-ish path)

Any Linux VM with port 8080 (or 443 behind a reverse proxy) works. Cheapest
serviceable options as of 2026: Hetzner CX11 (~$5/mo), Oracle Cloud always-free
ARM instance, Fly.io (paid since the free tier ended).

Minimum setup on the VM:

```bash
sudo apt install -y openjdk-17-jre
scp phonetunnel-relay.jar user@vm:~/
ssh user@vm
nohup java -jar phonetunnel-relay.jar > relay.log 2>&1 &
sudo ufw allow 8080
```

Put nginx / Caddy in front for TLS termination:

```caddy
# /etc/caddy/Caddyfile
relay.example.com {
    reverse_proxy localhost:8080
}
```

Use **`wss://relay.example.com`** in the app.

### Option C — Docker

```dockerfile
# Dockerfile (in this directory)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY build/libs/phonetunnel-relay.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

```powershell
docker build -t phonetunnel-relay .
docker run -d -p 8080:8080 --name relay phonetunnel-relay
```

Then point a reverse proxy at it, same as Option B.

## Operational notes

- Memory: ~80 MB resident under one active session.
- Throughput: ~100-200 Mbps per active tunnel on a 1 vCPU VM (WebSocket overhead
  is the bottleneck, not crypto — crypto is on the phones).
- Bandwidth cost: every byte the client browses is sent *up* from the server
  phone to the relay and *down* from the relay to the client phone. **You pay
  for 2x your tunneled traffic** on a metered cloud host.
- No persistence, no auth, no user accounts. Anyone who knows the relay URL +
  6-digit code can pair. Keep your relay URL semi-private if you don't want
  random people sharing your relay's bandwidth.
- Pairing TTL: 5 minutes. Sessions themselves are unlimited.

## What gets pushed through (wire protocol)

```
client (browser tab on phone B)
  -> kernel routes via tun
  -> VpnService hands packet to TunnelEngine
  -> AES-GCM encrypted with key = PBKDF2(code, salt)
  -> binary WebSocket frame to relay
  -> relay forwards binary frame to paired peer
  -> server phone decrypts
  -> userspace NAT opens a real Socket() from the server phone
  -> server phone forwards bytes via its real network
  -> response comes back, wrapped, encrypted, sent back through relay
```
