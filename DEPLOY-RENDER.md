# Deploy the relay to Render — step by step

This deploys the Phone Tunnel signaling server to **Render's free tier**. No
credit card needed, no server to maintain, free forever (with the catch that
the free service sleeps after 15 minutes of inactivity — first connection
takes ~30 s to wake it up; subsequent ones are instant).

Total time: about 10 minutes once you have the prerequisites.

## Prerequisites

- A Render account → https://render.com/register (sign up with GitHub for speed)
- A GitHub account
- Git installed locally (or you can use GitHub's web UI to upload files)
- Your Cloudflare TURN credentials handy: **App ID** and **API Token**
  (the ones you regenerated earlier)

## Step 1 — Get the relay code into GitHub

The `PhoneTunnel-Relay` folder needs to be in a GitHub repo so Render can
build it. Two ways — pick whichever is easier for you.

### Option A (web UI, no git command line)

1. Go to https://github.com/new
2. **Repository name:** `phonetunnel-relay`
3. **Visibility:** Private is fine (Render still builds it). Public is also fine.
4. Don't add a README/license/gitignore — leave it empty
5. Click **Create repository**
6. On the next page, click **uploading an existing file**
7. Drag the **contents of** `C:\Users\manin\OneDrive\Desktop\app idea\PhoneTunnel-Relay`
   into the browser (the contents — not the folder itself). You need:
   - `Dockerfile`
   - `render.yaml`
   - `build.gradle.kts`
   - `settings.gradle.kts`
   - `gradle.properties`
   - `gradle/` folder
   - `src/` folder
8. Scroll down, click **Commit changes**

### Option B (git command line)

```powershell
cd "C:\Users\manin\OneDrive\Desktop\app idea\PhoneTunnel-Relay"
git init
git add .
git commit -m "phonetunnel relay"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/phonetunnel-relay.git
git push -u origin main
```

(Create the empty repo first via GitHub web UI as in Option A steps 1-5.)

## Step 2 — Create the Render service

1. Go to https://dashboard.render.com
2. Click **New +** (top right) → **Web Service**
3. Click **Connect** next to GitHub if it's not already linked. Authorise
   Render to read your repos (Render's GitHub app)
4. Find `phonetunnel-relay` in the repo list — click **Connect**
5. On the configuration page:
   - **Name:** `phonetunnel-relay` (you'll get a URL like `phonetunnel-relay.onrender.com`)
   - **Region:** Pick whichever is closest to you (Singapore for India, Frankfurt for EU, Oregon/Ohio for US)
   - **Branch:** `main`
   - **Runtime:** Should auto-detect **Docker** (because of the Dockerfile). If not, select Docker.
   - **Instance type:** **Free**
6. Scroll down to **Environment Variables**. Click **Add Environment Variable** twice:
   - Key: `CF_APP_ID`  Value: `cc3c9f8241f98f8f8b02185632f3549c`
   - Key: `CF_API_TOKEN`  Value: *(paste your regenerated Cloudflare API Token)*
7. Click **Create Web Service** at the bottom

Render now builds your relay. The first build takes 5-8 minutes (downloading
Gradle, dependencies, then compiling). Watch the logs — you'll see Gradle
downloading then `BUILD SUCCESSFUL`.

## Step 3 — Get your relay URL

After the build finishes, the service status flips to **Live**. At the top
of the service page you'll see your URL:

```
https://phonetunnel-relay.onrender.com
```

(Or whatever name you chose.)

Smoke-test it from any browser:

```
https://phonetunnel-relay.onrender.com/health
```

Should respond `ok`. If the service is sleeping it takes ~30 s to wake — that
is normal on Render free tier. After that it's responsive.

## Step 4 — Configure the Android app

On **both** phones:

1. Open Phone Tunnel → tap the gear icon (top right) → Settings
2. Paste your URL into the **Signaling URL** field — exactly as Render shows it:
   ```
   https://phonetunnel-relay.onrender.com
   ```
3. Tap **Save** — banner should say *"Saved — cross-network mode ON"*
4. Back to Home → status reads *"Cross-network (relay set)"*

## Step 5 — Pair across networks

- Server phone → Share my internet → Start sharing → 6-digit code
- Client phone → Use a friend's internet → enter code → Connect → OK on VPN dialog

Within 5–10 s (first pairing of the session, longer if Render needs to wake)
both phones flip to **Connected**. Data now flows through WebRTC — directly
between the phones if their NATs allow, otherwise through Cloudflare TURN.

## What you should see in Render logs

When pairing succeeds, Render's logs show:

```
INFO relay - HELLO role=SERVER code=NNNNNN
INFO relay - HELLO role=CLIENT code=NNNNNN
INFO relay - PAIRED code=NNNNNN role=SERVER
INFO relay - Refreshed Cloudflare TURN credentials
INFO relay - PAIRED code=NNNNNN role=CLIENT
INFO relay - CLOSED code=NNNNNN role=SERVER   (after WebRTC takes over)
INFO relay - CLOSED code=NNNNNN role=CLIENT
```

The `CLOSED` lines are normal — the signaling channel is no longer needed
once the WebRTC data channel is up. The data flows directly or via
Cloudflare TURN from that point on.

## What you'll see in Cloudflare TURN dashboard

Go back to Cloudflare → Realtime → TURN → your app. Within a couple of
minutes of a successful tunnel, you'll see:

- **Active sessions**: 1
- **Bytes used today**: increasing as you browse
- **Total bandwidth**: tracked toward the 1 TB/month free quota

If "active sessions" stays at 0 even when phones say Connected, the data
path is **direct P2P** (didn't need TURN) — that's the best outcome.

## Cold start workaround (free tier)

Render free tier sleeps services after 15 min of no requests. First request
after a sleep takes 30-60 seconds to wake. To avoid this:

- **Easiest:** Use it. Tap something on the phone every 10 minutes.
- **Better:** Set up a free uptime monitor like [UptimeRobot](https://uptimerobot.com/)
  to ping `https://phonetunnel-relay.onrender.com/health` every 5 minutes.
  Free, no signup catch.
- **Paid:** Upgrade Render service to Starter ($7/mo) — always on, no sleep.

Once paired, the phones don't talk to the signaling server at all (data
flows directly via WebRTC), so even if Render sleeps mid-call, your tunnel
keeps working.

## Updating the relay

If you change the relay code, push to GitHub. Render auto-rebuilds and
redeploys on every push to the main branch. Old sessions are killed during
the deploy (~30 s downtime).

## Troubleshooting

| What you see | What it means / what to do |
|---|---|
| Build fails with "shadowJar not found" | Likely a stale `build.gradle.kts` upload. Push the latest from your local folder again. |
| Build succeeds but service crash-loops | Check **Logs** tab. Most common cause: env var typo. Verify both `CF_APP_ID` and `CF_API_TOKEN` are set under Environment. |
| Phone says "WebRTC did not connect within 45 s" but signaling logs show HELLO + PAIRED | Cloudflare TURN credentials invalid → check **API Token** isn't rotated/expired in Cloudflare dashboard. |
| Logs show "Cloudflare TURN API HTTP 401" | Wrong `CF_API_TOKEN`. Regenerate in Cloudflare, update Render env var, redeploy. |
| Logs show "Cloudflare TURN: OFF — STUN only" at startup | Env vars not loaded. Set them under Environment tab, then trigger Manual Deploy → Clear cache & deploy. |
| Long pairing delay every time | Free tier cold start. Set up UptimeRobot ping (see above). |

Anything weirder, paste the Render log output and I'll dig in.
