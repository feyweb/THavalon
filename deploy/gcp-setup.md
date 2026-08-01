# Deploying to Google Cloud (Always Free)

Puts the app on a `e2-micro` Compute Engine instance, which is free forever rather than for a
trial period. Everything here stays inside the Always Free allowance.

**Use Compute Engine, not Cloud Run.** Cloud Run is the obvious-looking choice — scale to zero,
a free HTTPS URL, no domain needed — but it runs *stateless* containers on an ephemeral
filesystem and scales to zero after roughly fifteen minutes idle. This app keeps game snapshots
and audit trails on disk, so a scale-down partway through a game would wipe everyone's role. A
plain VM with a persistent disk avoids the problem entirely.

## What Always Free actually covers

| Resource | Allowance | This deployment |
|---|---|---|
| `e2-micro` instance | 1 per month | 1 |
| Region | **only** `us-west1`, `us-central1`, `us-east1` | `us-west1` |
| Standard persistent disk | 30 GB | 10 GB is plenty |
| Egress from North America | 1 GB/month | a few MB |

Two traps worth knowing before you start:

- **The disk must be *standard* persistent disk (`pd-standard`).** Balanced and SSD disks are not
  in the free tier and the console defaults to Balanced.
- **Only those three US regions qualify.** An `e2-micro` anywhere else is billable.

## 1. Project and billing

1. Create a project at <https://console.cloud.google.com/projectcreate>.
2. Link a billing account. A card is required for verification; a bank-issued *reusable*
   virtual card works, a single-use number does not.
3. Set a budget alert at $1 — Billing → Budgets & alerts → Create budget:
   - **Scope** this project, **amount** `$1`
   - **Thresholds** at **1%**, 50% and 100% of *actual* spend. The 1% one emails you at a single
     cent, which is what turns "unexpected charge" into a same-week notification rather than a
     surprise at the end of the month.
   - Also enable the **forecasted spend** alert — it fires on the projection, before the money
     is actually spent.
   - Under *Manage notifications*, add your own address as a Cloud Monitoring notification
     channel. By default only Billing Account Administrators are emailed, which may not be an
     inbox you read.

   Budget data refreshes a few times a day, so allow up to a day of lag. It is a smoke detector,
   not a real-time tripwire — and it notifies rather than caps, because GCP has no hard spending
   limit.

**Why a budget alert on a free deployment.** The free tier is a discount, not a spending limit:
Google simply does not charge for usage inside the allowance, and bills normally for anything
outside it. There is no hard cap, and a budget alert notifies rather than stops.

The trap is that the $300 trial credits mask mistakes for ninety days. Leave the boot disk on
Balanced and the overage quietly consumes credits instead of alerting you; the trial then ends,
nothing about the setup has changed, and a real bill appears. A $1 alert surfaces that in week
one. Configured as described here the cost is $0.00 indefinitely, so any alert means something
is wrong.

## 2. Create the instance

Console → Compute Engine → **Create instance**:

- **Region** `us-west1`, any zone
- **Machine type** `e2-micro` (under E2, shared-core)
- **Boot disk** → Change → Ubuntu 22.04 LTS, disk type **Standard persistent disk**, 10 GB
- **Firewall** → tick **Allow HTTP traffic** and **Allow HTTPS traffic**

Or from the CLI:

```bash
gcloud compute instances create thavalon \
  --zone=us-west1-b \
  --machine-type=e2-micro \
  --image-family=ubuntu-2204-lts --image-project=ubuntu-os-cloud \
  --boot-disk-type=pd-standard --boot-disk-size=10GB \
  --tags=http-server,https-server
```

Unlike Oracle, there is no second firewall to open — those two tick boxes are all that is needed,
and GCP's Ubuntu images do not ship restrictive local `iptables` rules.

## 3. Reserve a static IP

The instance gets an ephemeral IP by default, which changes whenever it stops. Promote it, or
your DNS breaks the first time the VM restarts:

```bash
gcloud compute addresses create thavalon-ip --region=us-west1
gcloud compute instances delete-access-config thavalon --zone=us-west1-b \
  --access-config-name="External NAT"
gcloud compute instances add-access-config thavalon --zone=us-west1-b \
  --access-config-name="External NAT" --address=thavalon-ip
```

**A static IP is free only while attached to a *running* instance.** Reserved but idle, it costs
roughly $7/month — so stopping the VM to save money does the opposite, and deleting the VM
without releasing the address keeps billing. Either leave the instance running (it is free) or
release the address along with it.

## 4. Point a hostname at it

Caddy needs a hostname for TLS; an IP will not do. Add an `A` record for a domain you own, or
register a free subdomain at <https://www.duckdns.org> and point it at the static IP.

## 5. Install Docker and add swap

```bash
gcloud compute ssh thavalon --zone=us-west1-b
```

`e2-micro` has 1 GB of RAM, which is not enough to run a Maven build — `docker compose up --build`
compiles inside the container and gets OOM-killed partway through without swap:

```bash
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
```

Log out and back in for the group change to apply.

## 6. Cap the heap and deploy

`e2-micro` is 1 GB with a 0.25 vCPU baseline, so give the JVM an explicit ceiling. In
`compose.yaml`, under the `thavalon` service:

```yaml
environment:
  JAVA_OPTS: "-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"
```

Then:

```bash
git clone <your-fork> thavalon && cd thavalon
echo "THAVALON_DOMAIN=something.duckdns.org" > .env
docker compose up -d --build
```

The first build takes several minutes on a shared-core instance. Then:

```bash
curl https://something.duckdns.org/api/health     # {"status":"ok"}
```

## Operating it

```bash
docker compose logs -f thavalon      # application log, audit lines included
docker compose restart thavalon      # games survive; state is on the volume
docker compose up -d --build         # deploy a new version
```

Game state lives on the `thavalon-data` volume and reloads on boot, so restarts and redeploys do
not interrupt a game in progress.

### Backups

```bash
docker run --rm -v thavalon_thavalon-data:/data -v $(pwd):/backup alpine \
  tar czf /backup/thavalon-$(date +%F).tar.gz -C /data .
```

### Keeping it free

- Do not resize the instance or switch the disk to Balanced/SSD.
- Keep the instance in `us-west1`, `us-central1` or `us-east1`.
- The 1 GB/month egress allowance is generous here — page loads are a few kilobytes — but it is
  the one limit a runaway client could burn through, so the budget alert from step 1 is the
  backstop.
