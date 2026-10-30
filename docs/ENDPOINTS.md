# Angular feature → Spring Boot endpoint mapping

Auth column: **public** = no token · **any** = signed in · **client / photographer / admin** = that role (enforced in `SecurityConfiguration` and again in the service).
Every Angular call goes through `src/app/core/http/*` (repository named in brackets). A script cross-checked all 80 frontend calls against the controllers (see TEST-RESULTS.md).

## Authentication & account  [AuthRepository, CustomerRepository]
| Angular feature | Method & route | Auth | Notes |
|---|---|---|---|
| Login page | `POST /user/auth/login` | public | body `{username (or e-mail), password}` → `{token, role, user}`; 401 wrong credentials, 403 suspended, 429 throttled |
| Register (customer / photographer) — step 1 | `POST /user/auth/register` | public | validates, parks the details, e-mails a 6-digit code → `{message,email,verificationRequired,otpExpiresInSeconds}`. **No account and no token yet.** 409 with `errors.username/email`; 429 + `Retry-After` when sending too often; 503 if the e-mail cannot be sent |
| Verify e-mail — step 2 (`/verify-email` page) | `POST /auth/otp/verify` | public | `{email, otp}` → creates the account (photographers also get a profile that is live immediately — there is no approval step) and returns `{token, role, user}` like login. 400 `INVALID_CODE` (wrong/expired/used), 429 after 5 wrong tries, 409 if the username was taken meanwhile |
| Verify e-mail — “send a new code” | `POST /auth/otp/resend` | public | `{email}`; always the same generic answer; 60 s cooldown, max 5 per hour, 429 + `retryAfterSeconds` |
| Sign out | `POST /user/auth/logout` | token | revokes the bearer token |
| Session check (guards, app start) | `GET /user/me` | any | role/status from the database |
| Account settings → change password | `POST /auth/password` | any | `{current,next}`; ends other sessions |
| Account settings → profile, saved locations, notification prefs | `GET/PUT /customers/me` | client | the e-mail address is a verified identity: sending a *different* address is rejected (400 `errors.email`); the unchanged address is accepted |

## Marketplace  [CatalogRepository]
| Angular feature | Route | Auth |
|---|---|---|
| Home categories, discover filters | `GET /categories` | public |
| Discover (search, filters, sort, pagination, map) | `GET /photographers?q&city&lat&lng&radiusKm&categoryId&minPrice&maxPrice&availableOn&minRating&minExperience&tier&verifiedOnly&sort&page&pageSize` | public |
| Photographer profile | `GET /photographers/{id}` `/services` `/packages` `/reviews` `/similar` | public (owner/admin also see unpublished) |
| Profile view counter | `POST /photographers/{id}/view` | public |
| Favourites page / cards | `GET /photographers/summaries?ids=` | public |
| Home "best value" | `GET /packages/best-value?limit=` | public |
| Home banner / FAQ | `GET /content/banner`, `GET /content/faq` | public |
| Home customer stories | `GET /reviews/featured?limit=` | public (real reviews only) |
| Images | `POST /uploads` (multipart `file`) → `{url}`; `GET /uploads/{name}` | photographer/admin · public |
| Profile picture | `POST /uploads/avatar` (multipart `file`) → `{url}`; then `PUT /customers/me` or `PUT /photographers/me` with `avatarUrl` (`""` = default picture) | any signed-in account |

## Availability & booking  [AvailabilityRepository, BookingRepository, PaymentRepository, ReviewRepository, FavoriteRepository]
| Angular feature | Route | Auth |
|---|---|---|
| Booking wizard calendar | `GET /photographers/{id}/open-days?dates&duration`, `GET /photographers/{id}/slots?date&duration` | public |
| Studio → availability / calendar | `GET /photographers/{id}/availability`, `PUT /photographers/me/availability` | public read · photographer |
| Booking wizard price | `POST /bookings/quote` | any |
| Booking wizard submit | `POST /bookings` (`idempotencyKey`, `expectedTotal`) | client |
| Bookings lists (customer/studio/admin) | `GET /bookings?status&q&from&to&photographerId&customerId&page&pageSize` | any (scoped by role) |
| Booking detail | `GET /bookings/{id}` | participant/admin |
| Confirm / reject / complete | `PATCH /bookings/{id}/status` | photographer (admin) |
| Cancel | `POST /bookings/{id}/cancel` | participant/admin |
| Reschedule request / answer | `POST /bookings/{id}/reschedule`, `POST /bookings/{id}/reschedule/respond` | participant (other side answers) |
| Delivery status | `PATCH /bookings/{id}/delivery` | photographer/admin |
| Dispute open / resolve | `POST /bookings/{id}/dispute`, `POST /bookings/{id}/dispute/resolve` | participant · admin |
| Record refund | `POST /bookings/{id}/refund` | admin (only for `paid` bookings) |
| Leave review / reply | `POST /bookings/{id}/review`, `POST /reviews/{id}/reply` | client · photographer |
| Favourite toggle / list | `POST /favorites/{photographerId}/toggle`, `GET /favorites` | client |
| Pay online | — (not implemented: `PaymentRepository.available()` is `false`) | |

## Photographer studio  [PhotographerWorkspaceRepository, AutomationRepository, AnalyticsRepository]
| Angular feature | Route | Auth |
|---|---|---|
| Studio profile / onboarding | `GET/PUT /photographers/me`, `POST /photographers/me/submit` (legacy no-op: profiles are already live), `PUT /photographers/me/location` | photographer |
| Services, wizard | `POST /photographers/me/services`, `PATCH /photographers/me/services/{id}/status` | photographer |
| Packages | `POST /photographers/me/packages`, `PATCH …/packages/{id}/status`, `DELETE …/packages/{id}` | photographer |
| Portfolio | `PUT /photographers/me/portfolio` | photographer |
| Promo codes | `GET/PUT /photographers/me/promo-codes` | photographer |
| Automation | `GET/PUT /photographers/me/automation` | photographer |
| Dashboard / finance charts | `GET /analytics/photographers/{id}?from&to` | owner or admin |

## Messaging & notifications  [MessagingRepository, NotificationRepository] (existing API, unchanged)
| Angular feature | Route | Auth |
|---|---|---|
| Messages inbox/thread | `GET /messages/conversations`, `GET /messages/conversations/{id}`, `POST /messages/conversations`, `POST /messages/conversations/{id}/messages`, `POST /messages/conversations/{id}/read` | any (participants) |
| Notification bell/page | `GET /notifications`, `POST /notifications/{id}/read`, `POST /notifications/read-all` | any |
| (available, not used by the UI) | `GET /messages/contacts`, `GET /notifications/unread-count`, STOMP `/ws` | any |

## Administration  [AdminRepository, AnalyticsRepository]  — all `admin`
`GET /admin/overview` · `GET /admin/users` · `PATCH /admin/users/{id}/status` · `GET /admin/applications` · `POST /admin/applications/{photographerId}/decision` ·
`GET /admin/services` · `GET /admin/packages` · `PATCH /admin/services/{id}/moderation` · `GET /admin/reports` · `PATCH /admin/reports/{id}` · `GET /admin/audit` ·
`GET/PUT /admin/settings` · `GET /analytics/platform?from&to`. Any signed-in user: `POST /reports` (report content).

## Legacy endpoints kept for compatibility (not used by the Angular app)
`/user/get-user/{u}`, `/user/photographers/{city}`, `/user/city/{city}`, `/user/reservations/{u}`, `/user/create/reservation`, `/user/edit/photographer`, `/user/status`,
`/user/delete/reservation/{id}`, and admin-only `/user/clients/all`, `/user/photographers/all`, `/user/suspended/all`, `/user/reservations/all`, `/user/suspension/{u}`, `/user/unsuspension/{u}`, `/user/delete/user/{u}`.
All now check ownership/role. The new booking module (`/bookings`) replaces the old reservation flow.
