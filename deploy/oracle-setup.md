# Deploying to Oracle Cloud Always Free

A walkthrough for putting this on an Ampere A1 instance. The app needs very little — 1 GB of RAM
is plenty — so it fits inside the free allowance with room to spare.

Two steps in here account for almost every failed first attempt. They are marked **⚠**.

## 1. Create the instance

In the Oracle Cloud console: **Compute → Instances → Create instance**.

- **Shape**: `VM.Standard.A1.Flex`, 1 OCPU / 6 GB. Since 15 June 2026 the Always Free allowance is
  **2 OCPU and 12 GB** total across all A1 instances (halved from 4/24, with no announcement), so
  this uses half of it.
- **Image**: Ubuntu 22.04 (aarch64).
- **SSH key**: upload your public key.

**Home region matters more than it looks, and cannot be changed.** Always Free resources exist
only in your home region, so this one choice decides forever whether you can get an A1 instance.
Two things to weigh:

- **Prefer a multi-AD region.** Each availability domain is a separate pool capacity can appear
  in, so a three-AD region gives three chances per attempt where a single-AD region gives one.
  San Jose (`us-sanjose-1`) has a single AD and a long trail of users asking to be moved off it.
- **Ignore latency.** This app exchanges a few small JSON payloads; 20 ms versus 150 ms is
  imperceptible. Optimise entirely for capacity.

As of mid-2026, `eu-frankfurt-1` and `ap-singapore-1` provision A1 within minutes while US East
stalls for days. Capacity shifts, so check current community reports before committing.

**⚠ "Out of host capacity" is normal.** Free A1 capacity is heavily contested in popular regions.
If you hit it, try a different availability domain, or retry periodically — it does clear. This
is a capacity limit, not a problem with your account.

### If A1 never frees up

Always Free also includes **2× `VM.Standard.E2.1.Micro`** (AMD, 1 OCPU / 1 GB each). These are
effectively always available — no capacity fight. The app runs fine on one, with two adjustments.

**Add swap.** 1 GB is not enough to run a Maven build, and `docker compose up --build` compiles
on the instance. Without swap the build gets OOM-killed partway through:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

**Cap the heap.** In `compose.yaml`, under the `thavalon` service:

```yaml
environment:
  JAVA_OPTS: "-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
```

Architecture needs no attention either way — the image is built on the instance, so it compiles
natively whether that is ARM or x86.

## 2. Open the ports — in both places

**⚠ This is the step that catches everyone.** Oracle's Ubuntu images ship with restrictive local
`iptables` rules *in addition to* the cloud-level firewall. Opening one and not the other leaves
the port silently unreachable, and `ufw` does not manage these rules.

**a. Cloud level.** VCN → your subnet → Security List → Add Ingress Rules:

| Source | Protocol | Destination port |
|---|---|---|
| `0.0.0.0/0` | TCP | 80 |
| `0.0.0.0/0` | TCP | 443 |

**b. On the instance.**

```bash
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

Verify from your laptop before going further — `nc -vz <instance-ip> 80` should connect.

## 3. Point a hostname at it

Caddy needs a hostname to get a TLS certificate; an IP address will not do.

If you have a domain, add an `A` record to the instance's public IP. If you do not,
[DuckDNS](https://www.duckdns.org) gives you a free subdomain in about a minute — register
`something.duckdns.org` and point it at the IP. Caddy treats it like any other hostname.

## 4. Install Docker

```bash
sudo apt-get update && sudo apt-get install -y ca-certificates curl
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
```

Log out and back in so the group membership applies.

## 5. Deploy

```bash
git clone <your-fork> thavalon && cd thavalon
echo "THAVALON_DOMAIN=something.duckdns.org" > .env
docker compose up -d --build
```

The first build takes a few minutes on one OCPU — it compiles inside the container. Then:

```bash
curl https://something.duckdns.org/api/health     # {"status":"ok"}
docker compose logs -f
```

If TLS does not come up, it is almost always step 2b.

## Operating it

```bash
docker compose logs -f thavalon      # application log
docker compose restart thavalon      # games survive; state is on the volume
docker compose up -d --build         # deploy a new version
```

Game state lives on the `thavalon-data` volume and is reloaded on boot, so restarts and redeploys
do not interrupt a game in progress.

### Backups

The volume is small enough to copy wholesale:

```bash
docker run --rm -v thavalon_thavalon-data:/data -v $(pwd):/backup alpine \
  tar czf /backup/thavalon-$(date +%F).tar.gz -C /data .
```

### Memory

The container is capped at 70% of available RAM and uses SerialGC, which suits a single small
instance. On a 1 GB shape, set an explicit ceiling in `compose.yaml` instead:

```yaml
environment:
  JAVA_OPTS: "-Xmx256m -XX:+UseSerialGC"
```
