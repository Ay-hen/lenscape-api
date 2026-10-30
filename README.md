# Lenscape — Spring Boot backend

Java 17 · Spring Boot 3.2 · Spring Security (stateless JWT) · MongoDB · optional Kafka · STOMP WebSocket.
Serves the Angular app in `../frontend`: marketplace catalogue, availability engine, bookings, reviews, favourites, messaging, notifications,
photographer workspace, administration and analytics.

## Run locally
```bash
docker compose up -d mongo          

# Quickest start (development only): temporary random JWT key, no Kafka
SPRING_PROFILES_ACTIVE=dev APP_ADMIN_USERNAME=admin APP_ADMIN_PASSWORD='change-me-please-123' APP_ADMIN_EMAIL=admin@example.com ./mvnw spring-boot:run

# Realistic start: real secret from the environment
export JWT_SECRET="$(openssl rand -base64 48)"
export KAFKA_ENABLED=false
./mvnw spring-boot:run               
```
All settings are environment variables — see **`.env.example`** (copy it, fill it in, never commit it).

## Secrets and configuration
| Variable | Purpose |
|---|---|
| `JWT_SECRET` | **Required** (except with profile `dev`). Base64, ≥ 32 bytes: `openssl rand -base64 48`. The server refuses to start without it, with a too-short key, and with the key that used to be committed to this repository (it is public — consider every token ever signed with it compromised). |
| `OTP_HMAC_SECRET` | **Required** (except profile `dev`). ≥ 32 characters: `openssl rand -base64 48`. Keys the hash of the e-mailed codes. |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | SMTP account that sends the verification code (default host `smtp.gmail.com:587`; override with `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM`). Gmail needs an **App password** (Google account → Security → 2-Step Verification → App passwords), not the normal password. |
| `OTP_TTL`, `OTP_RESEND_COOLDOWN`, `OTP_MAX_SENDS_PER_HOUR`, `OTP_MAX_FAILED_ATTEMPTS` | optional: code lifetime (`PT10M`), resend cooldown (`PT1M`), sends per hour (5), wrong tries per code (5) |
| `MONGODB_URI`, `MONGODB_DATABASE` | database connection (default `mongodb://localhost:27017`, db `photography`) |
| `CORS_ALLOWED_ORIGINS` | frontend origin(s), comma separated |
| `APP_ADMIN_USERNAME/PASSWORD/EMAIL/NAME` | creates the first administrator at start-up if that username does not exist (password 12–72 chars). Nobody can self-register as admin. |
| `APP_TIMEZONE` | zone for booking wall-clock times (default `Africa/Casablanca`) |
| `APP_PUBLIC_BASE_URL`, `UPLOAD_DIR` | uploaded images: public URL of this API, and the folder they are stored in |
| `KAFKA_ENABLED`, `KAFKA_BOOTSTRAP_SERVERS` | notification pipeline (in-process when disabled) |

No third-party API key is needed by this backend. (Online payments would need a provider such as Stripe or CMI — not implemented, see limitations.)

## E-mail verification (sign-up)
Design adapted from the Nexus backend. Registration is two steps and **no account exists until the address is proven**:
1. `POST /user/auth/register` validates the form, BCrypt-hashes the password and stores the details in `pending_registrations` together with an HMAC-SHA256 hash of a random 6-digit code (the code itself is never stored or logged), then e-mails the code.
2. `POST /auth/otp/verify` `{email, otp}` checks the code and, only then, creates the user (`emailVerified=true`), provisions the photographer profile if needed, and returns the same `{token, role, user}` as login.

Protections: every state change is one atomic MongoDB update — max 5 wrong tries per code (the 5th wrong try kills the code), code lifetime 10 min and rejected at the exact expiry instant, a code can be consumed once, new code replaces (invalidates) the old one, 60 s resend cooldown and 5 sends/hour per address (429 + `Retry-After`), the resend endpoint answers identically whether or not a registration is pending, pending records disappear after 24 h (TTL index), failed SMTP delivery drops the code and releases the cooldown (503). Codes are bound to the e-mail address by the HMAC.
Existing accounts (created before this feature) have no `emailVerified` flag and are treated as verified; the bootstrap admin is created verified. The e-mail address of a verified account cannot be changed through `PUT /customers/me` (an e-mail-change flow with its own code is not built).
**Local development without an SMTP account:** `SPRING_PROFILES_ACTIVE=dev` prints the code in the server log (`DEV ONLY (app.mail.log-only) ... code is 123456`) instead of sending mail. Set `DEV_LOG_CODES=false` to send real mail in dev. Never enable log-only in production.

## Database
MongoDB — unchanged technology. New collections are created on demand and indexes are created automatically (`auto-index-creation=true`):
`pending_registrations` (TTL), `photographer_profiles`, `services`, `packages`, `availability`, `bookings` (unique `reference`), `reviews` (unique `bookingId`), `favorites`, `promo_codes`,
`customer_profiles`, `automation`, `applications`, `reports`, `audit`, `settings`, `view_stats`, `counters`; plus the existing `user`, `token` (now with a TTL index),
`reservation`, `conversation`, `notification`.
**Existing data is never deleted or overwritten.** At start-up (idempotent): reference data (categories, FAQ, templates) is created once;
users without `createdAt` get "now"; every photographer account receives a profile, and profiles that were still draft/pending are set to **approved** — photographers go live from sign-up, there is no review step. Services and packages are published by default.
The primary key of a user is still the username; `id` in the API equals the username, and a photographer's profile id equals the username too.

## API overview
Full list with the Angular feature that uses each route: [`docs/ENDPOINTS.md`](../docs/ENDPOINTS.md).
Errors always look like `{ "code": "VALIDATION|NOT_FOUND|CONFLICT|SLOT_UNAVAILABLE|PRICE_CHANGED|UNAUTHENTICATED|FORBIDDEN|RATE_LIMITED|SERVER", "message": "...", "errors": { "field": "message" } }`.

## Security model
* BCrypt password hashes; login by username or e-mail; constant-time-ish failure path; 5 failures / 15 min per login name → 429.
* JWT (HS256) is verified on every request **and** must still exist in the token store (logout revokes it; password change revokes all other sessions; Mongo TTL cleans up).
  Roles and suspension are read from the database on each request, so suspending an account takes effect immediately.
* Route rules in `SecurityConfiguration` + ownership checks inside the services (a customer only ever sees their own bookings; a photographer only their own workspace; analytics owner-or-admin).
* Registration accepts only `client` and `photographer`. Server-controlled fields (approval, identity badge, rating, counters, location, moderation) cannot be set through profile/service endpoints.
* Prices are always computed server-side; the client's expected total is only used to detect a changed price (`PRICE_CHANGED`). Customers never receive the platform commission.
* Uploads: type decided from magic bytes (JPEG/PNG/WebP only), random file names, served with a fixed content type + `nosniff`.
* The legacy `/user/**` endpoints (original API) are kept but now check who is calling; admin-only ones are restricted; no raw `User` entities are returned.

## Tests
```bash
./mvnw test        # domain engines, booking/auth/automation services (mocked repositories), upload safety, existing chat/notification tests
```
`PhotographyApplicationTests` (full context) runs only when `MONGODB_URI` is set. The OTP service tests mock MongoDB: the atomic-update guarantees themselves need an integration test against a real database.

## Limitations (read before production)
* **Slot locking is per server instance** (`KeyedLock`). Run a single instance, or add a Mongo transaction / unique slot document before scaling out.
* Search/aggregations load the relevant collections and filter in memory — fine for thousands of profiles, not millions.
* Uploads use local disk (use a persistent volume or swap `UploadService` for object storage).
* Automation: auto-accept, nudges, auto-expiry, reminders, thank-you and review requests run (in-app notifications, scheduler every 5 min). E-mail/SMS delivery, automatic confirmation chat messages, repeat-customer offers and gallery tracking are stored preferences that are **not executed yet**.
* Payments are not implemented; `paymentStatus` stays `unpaid` and refund recording is rejected until a booking is `paid`.
* Profile view counts are not de-duplicated (anonymous visitors can inflate them).
* Rate limiting of login is in memory (per instance). The OTP limits are stored in MongoDB and hold across instances.
* Real SMTP delivery has not been exercised by the author (only the logic around it): send yourself a test code first. Gmail may block sign-ins from new servers; an App password is required, and bulk sending needs a proper mail provider (Brevo, Mailgun, SES …) with a verified sender domain.
