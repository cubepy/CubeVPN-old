# What the CubeSaz server actually serves today

Written from the server side (`cubepy/CubeSaz`, `main`), so this repo can be
read against what exists rather than against what was planned.

`docs/api-contract.md` and `docs/white-label.md` describe a client that signs
in **through a reseller's own CubeSaz panel** (`API_BASE_URL` → their tenant,
`/api/requestcode.php`, services read from that tenant's database). The server
work that has since landed adds a **second, platform-level** path that does not
need a tenant at all — because a customer may buy only the app.

Both can exist. What cannot exist is the app guessing which one it is talking
to. The differences below are the decision that has to be made once.

---

## 0. Settled (2026-09-02)

Four questions were open in the first version of this file. Three are answered
and the server has been changed to match.

**Sign-in is always the reseller's own bot, through the platform.** Whether the
person signing in is the reseller or one of their customers, and wherever that
bot came from — bought from us or made anywhere else — the code is sent by the
brand's own bot through `/app/requestcode.php` on the platform domain. The
per-tenant `/api/` path is not the app's login route: it cannot serve a
customer who has no shop, and that customer is the reason this exists.

So the client sets its base URL to the **platform**, not to a panel, and sends
`X-Cube-Brand` on every call.

**The error codes are the client's names.** The server was changed, not the
app: `invalid_identifier`, `invalid_code`, `expired_code`, `too_many_attempts`,
`rate_limited`, `unauthorized`. Two additions the client should handle:

- `start_required` — the user has never pressed Start on that bot. The client's
  `identifier_not_found` means the same thing and says less; this one has its
  own screen ("open the bot and press Start").
- `license_closed` — the brand's app subscription has lapsed. Carries
  `"code": "402-A"`.

Every failure now carries a `message` in Persian, which the app shows verbatim.
The server had been sending a bare code, leaving the client with nothing to
put on the screen.

**The invite/referral screen is removed.** `user.invite_code` and
`user.referral_count` are not served and will not be. The screen should come
out of the client.

Still open: whether the shop's own gate ("their panel goes dark") and the app
licence both stay as switches, or only the licence. They are independent today
and show different codes.

## 1. What is on the server now

### Brand record

One row per branded app, in the platform database — **not** in a tenant.
`tenant_id` is nullable and that nullability is the point: owning a CubeSaz bot
is not a precondition for owning an app, and deleting the bot does not delete
the app.

Each brand carries two independent 48-hex keys, both compiled into the APK:

| Key | Where it goes | What it opens |
|---|---|---|
| `brand_key` | `X-Cube-Brand:` request header | the `app/` endpoints below |
| `license_key` | `?key=` query parameter | the licence endpoint below |

They are separate so a leak of one does not hand over the other's surface.

### Login, on the platform domain

Not `/api/` (that path belongs to a tenant) — `/app/`:

```
POST  https://<platform>/app/requestcode.php
POST  https://<platform>/app/verifycode.php
GET   https://<platform>/app/accountme.php
```

Every one of them requires `X-Cube-Brand: <brand_key>`. An unknown key and a
brand whose subscription has lapsed answer **identically** — otherwise the
endpoint is a way to enumerate brands one guess at a time.

**`POST /app/requestcode.php`** — body `{"identifier": "<numeric telegram id>"}`

```json
200  {"ok": true, "expires_in": 300}
400  {"ok": false, "error": "start_required"}
400  {"ok": false, "error": "bad_identifier" | "send_failed" | "no_bot_token"}
429  {"ok": false, "error": "rate_limited", "retry_after": 3600}
402  {"ok": false, "error": "license_closed", "code": "402-A"}
503  {"ok": false, "error": "unavailable"}
```

`start_required` is its own code on purpose. Telegram refuses to let a bot open
a conversation, so a user who has never pressed Start on their reseller's bot
cannot be reached — that is a thing the app tells them to go and do, not an
outage. Flattened into a generic failure it reads as "the app is broken".

The code is sent **through the reseller's own bot**, whether that bot came from
us or from anywhere else. The server holds that token encrypted and calls
exactly two Telegram methods on it, for ever: `getMe` (once, to validate) and
`sendMessage`. Never `setWebhook`, never `getUpdates` — that bot is running a
live business and pointing its webhook at us would take it off the air. A test
scans the tree to keep this true.

**`POST /app/verifycode.php`** — body `{"identifier": "...", "code": "123456"}`

```json
200  {"ok": true, "token": "<64 hex>", "expires_at": "2026-10-01T00:00:00Z"}
400  {"ok": false, "error": "wrong_code" | "expired" | "no_code"
                          | "too_many_attempts" | "bad_identifier"}
```

Six digits, five minutes, stored hashed, compared with `hash_equals`, five
guesses claimed by a conditional `UPDATE` rather than counted in PHP. Session
token is 30 days and is stored hashed too.

**`GET /app/accountme.php`** — `Authorization: Bearer <token>`

```json
{
  "ok": true,
  "user":    { "identifier": "123456789" },
  "app":     { "name": "OG VPN", "package": "com.ogvpn.app" },
  "license": { "status": "active", "expires_at": "2026-10-01T00:00:00Z" },
  "account": { "balance": 120000, "services": 2 }
}
```

`account` appears **only** when the brand is linked to a shop we host, and is
read from that shop's own database. A brand with no shop is the normal case,
not a degraded one.

`401 {"ok": false, "error": "unauthenticated"}` on a bad or expired token.

### Licence, on the platform domain

```
GET https://<platform>/platform_applicense.php?key=<license_key>

{"status": "active", "expires_at": "2026-10-01T00:00:00Z"}
{"status": "expired"}
{"status": "unknown"}
```

Three rules that are not style:

1. **Always HTTP 200, always a JSON body**, even on a fatal — a shutdown
   handler guarantees it. `unknown` is the written-down way to say "we cannot
   answer, carry on".
2. **`expired` is the only answer that closes an app.** Never returned because
   the database was unreachable and never because the key was not recognised.
3. Nothing in the response names the reseller, the brand, or CubeSaz.

Cached for an hour (`Cache-Control: public, max-age=3600`).

### Two customer-facing codes

| Code | Means |
|---|---|
| `402-B` | the reseller's **bot/shop** subscription lapsed (existing gate) |
| `402-A` | the **app** licence lapsed (new) |

Separate so support can tell which one it is from a forwarded screenshot.
Neither names CubeSaz, a subscription, or a payment.

### Brand fields the CubeSaz bot collects today

Collected through **@cubesaz_bot** (the build is ours to run, so the assets
have to reach us), on a screen the reseller reaches from their app's card:

`app_name`, `package_name`, `brand_color` (`#RRGGBB`), icon image, splash
image, `channel_url`, `support_url`, `site_url`, `intro_text`, and the login
bot's token/username.

`buildSpec()` separates what blocks a build from what merely improves it:
**blocking** = app name, package name, icon, brand colour, login bot.
Splash and the links are not blocking.

**There is deliberately no subscription URL among them.** A reseller may run
ten panels and their customers must be free to paste whichever subscription
they were given. The app is branded, not locked.

---

## 2. Where this repo and the server disagree

Each row is a decision, not a bug. Nothing here has been changed unilaterally
on either side.

| # | `docs/api-contract.md` expects | The server serves | Cheapest fix |
|---|---|---|---|
| 1 | `API_BASE_URL` + app appends `/api/<name>.php` | `/app/<name>.php` on the platform domain | **client**: point at the platform, append `/app/` |
| 2 | no brand header | `X-Cube-Brand: <brand_key>` required | **client**: send the key it already compiles in |
| 3 | `identifier` may be a phone number | numeric Telegram id only | open — a phone number needs a shop to resolve against |
| 4 | every error carries `message`, shown verbatim | ✅ **done** — every failure carries Persian `message` | — |
| 5 | `identifier_not_found` | `start_required` | client: rename, same screen |
| 6 | `invalid_identifier` | ✅ **done** | — |
| 7 | `invalid_code` / `expired_code` | ✅ **done** | — |
| 8 | `requestcode` returns `cooldown_seconds` | ✅ **done** — sends `cooldown_seconds` and `expires_in` | — |
| 9 | `verifycode` returns a `user` object | ✅ **done** | — |
| 10 | `accountme` returns `services[]` with `subscription_url` | ✅ **done** — real list when a shop is linked, `[]` when not | client: empty is not an error |
| 11 | `user.invite_code` / `referral_count` | not served, and will not be | **client: remove the invite screen** |
| 12 | gating is "the tenant's panel goes dark" | a second, independent licence (`402-A`) | decide whether both switches exist |

### On row 10 — settled

`accountme` now returns `services[]` in the shape this repo asked for. Where a
brand is linked to a shop we host, the list is built by that shop's own
`ServiceHandler` — the same code its mini app uses — so Marzban, Hiddify, x-ui
and stock configs answer identically. The shop's own subscription gate still
applies to its data, and a blocked customer is refused exactly as the mini app
refuses them.

Where there is no shop, the array is **empty, not absent**, and that is the
normal case rather than a degraded one: an app-only customer pastes their own
subscription link, which is a whole product. **An empty `services[]` must not
render as an error** — for one whole class of brand it is the steady state.

A panel that does not answer drops its own row and the rest of the list still
returns; a shop that is entirely down degrades to an empty list rather than
failing the request, because the licence answer is what the endpoint is really
for.

### On row 12

`docs/white-label.md` says a reseller's app stops working when their CubeSaz
subscription lapses, because their panel stops answering. That is still true
for a reseller who has a panel.

The app licence is a **second** switch, for the customer who has no panel to go
dark. It fails open on every error, so it cannot take down apps during an
outage — but it does mean a brand can be closed for one reason and open for the
other, and the two show different codes.

---

## 3. Brand fields: what each side wants

| `white-label.md` asks the reseller for | Collected by the bot today |
|---|---|
| App name | ✅ `app_name` |
| Short name (ASCII, APK file name) | ❌ |
| Package name | ✅ `package_name` |
| Login bot | ✅ token + username |
| Support account (`@name`) | ⚠️ stored as a URL |
| Channel (`@name`) | ⚠️ stored as a URL |
| Panel address (`API_BASE_URL`) | ❌ — and see row 10 |
| Logo (square PNG ≥432) | ✅ icon |
| Icon background colour | ✅ `brand_color` |
| TON wallet (optional) | ❌ |
| `UPDATE_URL` | ❌ |
| `BRAND_EXPIRES_AT` (trials) | ❌ |

And collected but not used by any build input yet: `splash`, `site_url`,
`intro_text`.

Adding the missing ones to the bot is small and mechanical; the two that need a
decision first are **Panel address** (row 10) and **`UPDATE_URL`**, because
both assume the reseller has a panel.

---

## 4. What has not been decided

1. Does the app sign in against **the platform** (`/app/`, brand header, works
   with no shop) or against **the reseller's panel** (`/api/`, needs a shop)?
   Or both, chosen by whether a panel address was supplied at build time?
2. Do both switches stay (panel-goes-dark **and** the app licence), or only
   one?
3. Do the error codes move to this repo's names or the server's?
4. Is the invite/referral screen staying? Nothing serves it today.

None of these is answered here on purpose. They are single decisions with wide
consequences, and both halves are cheap to change once and expensive to change
twice.

---

*Server side lives in `cubepy/CubeSaz`: `src/Platform/AppBrands.php`,
`app/{requestcode,verifycode,accountme}.php`, `app/lib/bootstrap.php`,
`platform_applicense.php`. Behaviour is pinned by
`tests/app_brand_flow_test.php` and `tests/subscription_kind_test.php`.*
