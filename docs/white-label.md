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
| Panel address | `https://panel.novaco.ir` | Their CubeSaz panel. This is what makes the app theirs. |
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

## Shipping an update to every brand

An app update means rebuilding each brand — the APK is a compiled artifact, so there's no
partial update. Loop over the brand files:

```bash
for b in tools/brand/brands/*.properties; do tools/brand/build-brand.sh "$b"; done
```

Each build takes about four to five minutes. This is the cost that grows with the number of
resellers, and it's the thing worth automating first once there are more than a handful.

---

## What this deliberately doesn't do yet

- **No build service.** Builds are run by hand from this repo. Phase 4 of the plan moves this
  behind a button in the CubeSaz console.
- **No update endpoint per brand.** The in-app updater still points wherever `UPDATE_REPO`
  says. Until that's per-brand, a branded build either has no update channel or shares
  CubeVPN's — set `UPDATE_REPO` deliberately per brand, or leave it blank.
- **No accent colour per brand.** The icon background is branded; the in-app accent is still
  one of the three built-in themes the user picks.
