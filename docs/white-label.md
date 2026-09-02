# White-label builds

How a reseller gets their own branded copy of this app, and how you produce it.

The app is one codebase; the brand is a build input. Nothing is forked, and nothing about a
reseller's build diverges from `main` — they get whatever the current release is, wearing
their name.

---

## What the reseller sends you

Ask for exactly this. Anything missing blocks the build, so it's worth collecting in one go.

| Field | Example | Notes |
|---|---|---|
| App name | `نوا وی‌پی‌ان` | Any script. This is the name under the icon and inside the app. |
| Short name | `NovaVPN` | ASCII, no spaces. Used for the APK file name only. |
| Package name | `ir.novaco.vpn` | Lowercase reverse-domain. **Permanent** — see below. |
| Login bot | `@nova_login_bot` | Must be *their* bot: their customers' accounts live in it. |
| Support account | `@nova_support` | |
| Channel | `@nova_channel` | |
| Panel address | `https://panel.novaco.ir` | Their CubeSaz panel. Blank for a reseller on their own system — see below. |
| Logo | `logo.png` | Square PNG, ≥432×432, transparent background preferred. |
| Icon background | `#0E7490` | The colour behind the logo on the launcher icon. |
| TON wallet | *(optional)* | Leave blank and the donation card disappears. |

Two of these can never change afterwards, so confirm them explicitly before the first build:

- **The package name.** Android identifies an app by it. Changing it later produces a *different*
  app that cannot update the old one — every customer would have to uninstall and reinstall.
- **The login bot.** It's tied to the accounts their customers sign in with.

---

## Producing the build

Put the answers in a brand file (copy `tools/brand/brand.example.properties`), drop the logo
next to it, and run one command:

```bash
cp tools/brand/brand.example.properties tools/brand/brands/novavpn.properties
# edit it, and put the reseller's logo.png in tools/brand/brands/
tools/brand/build-brand.sh tools/brand/brands/novavpn.properties
```

That generates the signing key on first use, renders the launcher icons from the logo, builds
signed release APKs, prints the signing fingerprint, and leaves everything in `dist/NovaVPN/`:

```
NovaVPN-v1.4.3-arm64-v8a-release.apk     59M   most phones from ~2017 on
NovaVPN-v1.4.3-armeabi-v7a-release.apk   56M   older 32-bit phones
NovaVPN-v1.4.3-universal-release.apk    163M   works on both, one file
```

Icons are written into `app/src/main/res` for the build and reverted immediately after, so one
brand's icon can never leak into the next build.

### The signing key

Generated once per brand into `tools/brand/keystores/<slug>.jks` and reused for every build of
that brand forever after. Android only accepts an update signed with the same key as the
installed version.

**Back that folder up somewhere off the build machine.** If a brand's key is lost, that brand
can never ship an update again — the only path forward is every customer uninstalling and
reinstalling, losing their configs. It is the one failure in this whole system that cannot be
repaired. The folder is gitignored and must stay that way; it holds keys and their passwords.

---

## Handing it over

Send the reseller **the universal APK** unless they've asked otherwise. It's the largest file
but the only one that works on every phone, which removes an entire category of support
question. Send the two per-ABI files instead only to a reseller who understands the difference
and wants the smaller download.

Give them this along with the file, in their own words, for their customers:

> Install is from the file, not from Google Play. Android will warn that the file comes from an
> unknown source — that's expected for any app installed outside the store. Allow it once for
> the browser or file manager you're installing from, and the install proceeds normally.

Also tell the reseller three things about running it:

1. **Updates come from inside the app.** When you publish a new version they don't redistribute
   anything by hand — their customers get a prompt in the app.
2. **Their customers sign in with their bot**, and their purchased services import automatically.
   Someone who isn't a customer can still use the app by adding a config manually.
3. **The app stops working for their customers if their CubeSaz subscription lapses** — their
   panel stops answering and the app shows a neutral code, with no mention of CubeSaz or of
   payment. Their brand stays intact in front of their customers.

---

## Updates: the file is handed over once

You send a reseller their APK **once**. After that, their customers update from inside the
app, and the reseller never redistributes a file again.

Set `UPDATE_URL` in the brand file to their panel's feed:

```
UPDATE_URL=https://panel.novaco.ir/api/appupdate.php
```

When you publish a new build for that brand, the build service writes one file on the panel:

```
storage/app-updates/<tenant id>.json
{"version":"1.4.4",
 "url":"https://…/Nova-v1.4.4-universal-release.apk",
 "abis":{"arm64-v8a":"https://…-arm64-v8a-release.apk",
         "armeabi-v7a":"https://…-armeabi-v7a-release.apk"}}
```

Every install of that brand picks it up on its next check, downloads the APK matching its own
CPU, and installs over itself — the signing key is the same, so nothing is lost.

Two things fall out of serving this from the panel rather than from a link you own:

- **It's already gated.** A reseller whose CubeSaz subscription lapses stops answering here
  too, so their app stops being offered updates at the same moment it stops signing anyone in.
  There's no second switch to maintain.
- **The APKs stay on a CDN.** The panel only says which version is current; the file itself is
  served from the release host, so a popular brand's update doesn't run through the panel.

Leave `UPDATE_URL` blank and the app falls back to the GitHub release feed in `UPDATE_REPO`,
which is how CubeVPN's own builds work.

## Shipping an update to every brand

An app update means rebuilding each brand — the APK is a compiled artifact, so there's no
partial update. Loop over the brand files:

```bash
for b in tools/brand/brands/*.properties; do tools/brand/build-brand.sh "$b"; done
```

Each build takes about four to five minutes. This is the cost that grows with the number of
resellers, and it's the thing worth automating first once there are more than a handful.

---

## A reseller who isn't on our panel

`API_BASE_URL` is what connects the app to a CubeSaz tenant: sign-in codes are sent by that
tenant's bot and purchased services are read from its database. A reseller who sells through
their own system has neither, so leave the field blank. The app then hides sign-in, "my
services" and the referral card, and what they get is a branded client their customers paste
their own subscription link into — any panel, since the fetcher reads plain, base64, Clash and
JSON subscriptions alike.

Two things follow. Their app can't be switched off by the CubeSaz subscription gate, because
none of its traffic passes through us. And `BRAND_BOT` becomes decoration: it still points the
app's Telegram buttons at their bot, but nothing signs in through it.

## Trial builds

`BRAND_EXPIRES_AT=2026-09-09` produces a build that works through that day and then stops —
the app shows a single notice, and the VPN service refuses to start even from the widget or
always-on VPN, so there is no way around it from outside the app.

The date is compiled in rather than checked against a server. For a trial that is the right
trade: nothing to run, nothing to keep online, and no way for our own downtime to break a
build we handed someone. It also means extending a trial is a new APK, which is why this is
for trials and never for billing.

Leave it blank for every paying brand.

## Building on your own server

Every brand is a separate build, and on a private repository GitHub's runners are billed by
the minute. `tools/brand/setup-runner.sh` moves the work onto a machine you already own, where
the minutes are free:

```bash
sudo tools/brand/setup-runner.sh --repo cubepy/CubeVPN --token <registration token>
```

The token comes from Settings → Actions → Runners → New self-hosted runner, and expires in an
hour. The script installs a JDK, the Android SDK, a dedicated unprivileged account, and the
runner as a systemd service.

Brands live on that machine, not in this repository:

```
/var/lib/cubevpn-brands/
    ogvpn.properties        one per reseller, plus their logo
    keystores/              signing keys, generated on first build
    publish.sh              where finished APKs go; you fill this in
```

That split is the point. The signing keys never become GitHub secrets — they sit in a
directory the runner reads directly, so a brand's key never leaves the machine that made it.

Then Actions → **Build brand** → Run workflow, and give it a slug, or `all` to rebuild every
brand for a new app version. Finished builds are handed to `publish.sh`; the workflow uploads
no artifacts at all, because three APKs are about 280 MB and the free artifact allowance is
500 MB in total.

### Sharing the machine with something else

A build is about five minutes of every core it can reach, and the obvious server to put it on
is one already answering Telegram webhooks. The setup script writes a systemd drop-in so that
never becomes a choice between the two:

```
CPUWeight=20     under contention the scheduler prefers everything else
CPUQuota=…       half the cores by default; --cpu-quota to change it
MemoryMax=6G     --memory-max to change it
IOWeight=20
```

`MemoryMax` is the one that matters most. Gradle and the Kotlin compiler together can reach
for six or seven gigabytes, and without a cgroup limit the kernel's OOM killer chooses its
victim by its own accounting — which on a database server is usually MySQL rather than the
build. With the limit, a build that overruns is the process that dies.

Between builds the runner costs almost nothing: one .NET process holding a long poll to
GitHub, a couple of hundred megabytes of RAM and no measurable CPU. The load is only while a
build runs, and a build only runs when you start one.

One rule that comes with a self-hosted runner: never enable one on a public repository.
Anyone who opens a pull request would be running their own code on your server.

## What this deliberately doesn't do yet

- **No build service.** Builds are run by hand from this repo. Phase 4 of the plan moves this
  behind a button in the CubeSaz console.
- **No accent colour per brand.** The icon background is branded; the in-app accent is still
  one of the three built-in themes the user picks.
