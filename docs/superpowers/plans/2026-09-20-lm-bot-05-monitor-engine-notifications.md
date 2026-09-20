# Plan 5 — Monitor Engine and Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Monitors actually run. Each active monitor checks Luxmed on a jittered
interval, records what it finds in an append-only event log, notifies the owner
over Telegram (or visibly degrades when Telegram is not linked), and moves
through the `active` / `paused` / `completed` / `failed` state machine with the
failure policy from spec §5.5.

**Architecture:** A Gears structured-concurrency engine started by
`BackendApplication`. One reconcile fiber discovers the active set from
Postgres and starts one cancellable fiber per monitor; each fiber loops
`check → dedup via monitor_events → notify → sleep(jittered interval)`. One
Luxmed client — and therefore one `AccountGate` — is shared by every monitor of
an account and by the dictionary service, so the per-account rate limiter is
finally held across requests (issue #35). Notifications sit behind a
`NotificationChannel` boundary; Telegram is the one implementation, linked
through a one-time `/start <code>` deep link handled by a long-polling fiber.
Pure policy — slot filtering, dedup identity, backoff, jitter, state
transitions — lives in `shared` or in pure backend objects and is unit-tested;
all engine I/O is behind `SlotSearch`, `NotificationService`, and repository
boundaries.

**Tech Stack:** Scala 3.9.0, JVM 25, Scala.js 1.22.0 Wasm + JSPI, Gears 0.3.1,
Tapir 1.13.31, sttp 3.11.0, jsoniter-scala 2.41.0, Laminar 17.2.1, Magnum
1.3.1, Flyway 13.7.0, PostgreSQL 18, MUnit 1.3.6.

## Global Constraints

- Work in the flake devShell; Node 26+ and Temurin 25 are required.
- Follow strict red-green-refactor. Run `testFull`, never bare `test`.
- The approved PRD is authoritative. The spec amendments this plan needs are
  already committed with the plan (see below); if implementation exposes a
  further design error, amend and commit the spec before writing code against
  the correction.
- Gears is the only async vocabulary. `scala.concurrent.Future` and JavaScript
  `Promise` stay inside `frontend/.../bridge/`.
- Expected failures are values. Do not use exceptions for control flow. The one
  deliberate exception boundary is the engine's per-check guard, which converts
  a crashing check into a counted failure (spec §5.5 "check crashes beyond the
  retry budget") and rethrows cancellation.
- Plain constructor wiring only: no DI framework and no reflection.
- Browser-facing endpoints use `setCookieOpt`, never `setCookie`.
- Pin actual JSON bytes for every new shared wire type; round-trip-only tests
  are insufficient.
- Every resource operation is scoped to the authenticated owner in a service.
- Luxmed-facing dates and times have `Europe/Warsaw` semantics. Slot datetimes
  are stored and compared as Warsaw-local values, never server-local.
- Secrets never enter events, notifications, logs, or API values. Event
  `detail` strings come from `SafeDiagnostic` or from fixed text.
- Plan 5 does not book. `autoBook` monitors notify exactly like notify-only
  monitors; Plan 6 inserts the lock → validate → confirm-or-release step behind
  the same check result. The `booking_*` event kinds are defined now so the log
  and the detail view do not change when Plan 6 starts writing them.
- Format Scala with `sbt scalafmtAll` and finish with `sbt testFull`,
  `sbt frontend/fastLinkJS`, `nix flake check`, the async-vocabulary gate, and
  `git diff --check`.

---

## Spec amendments committed with this plan

These are decisions the spec did not contain and that the plan depends on.
They were committed to
`docs/superpowers/specs/2026-07-27-lm-bot-prd-design.md` before this plan:

1. **`monitors` gains last-check columns** (`last_check_at`,
   `last_check_summary`). §3.3 already requires a last-check summary in the
   monitor list; §5.3's table had no column that could produce one.
2. **`monitor_events` is fully specified** (§5.3): kind, nullable slot identity
   and slot details, detail text, created-at, with a partial unique index that
   makes per-slot dedup an insert-on-conflict decision rather than a query.
3. **`users` gains Telegram link-code storage**: an Argon2id hash plus expiry,
   single-use and valid for 15 minutes.
4. **Per-slot dedup is permanent for the lifetime of the monitor**, keyed by
   slot identity in `monitor_events` — replacing "within a lookback window".
   A window shorter than the monitor's date range would re-notify every window
   for a slot that is still available, which is exactly the noise dedup exists
   to prevent.
5. **`failed` monitors can be resumed manually**; `completed` is terminal. §5.5
   already said "resuming it is a manual action in the UI", but Plan 4's
   `MonitorService` only allowed `active ⇄ paused`.
6. **Engine cadence and retry numbers** (§5.5): reconciliation every 15 s, first
   check immediately, waits between checks ±20 % jittered, transient backoff
   1 min × 2ⁿ capped at 30 min, rate-limited backoff 5 min × 2ⁿ capped at 1 h,
   3 consecutive persistent failures → `failed`, version rejection notified to
   every linked admin once per engine lifetime and retried with the capped
   backoff.
7. **Telegram configuration** (§9): `TELEGRAM_BOT_TOKEN`,
   `TELEGRAM_BOT_USERNAME`, and a `TELEGRAM_API_BASE` override for
   development/tests.

---

## Planned File Structure

```text
shared/src/main/scala/lmbot/shared/
├── api/
│   ├── Codecs.scala                    (modify)
│   ├── MonitorEndpoints.scala          (modify)
│   ├── SettingsEndpoints.scala         (new)
│   └── SettingsPayloads.scala          (new)
└── domain/
    ├── Monitor.scala                   (modify: last-check fields)
    ├── MonitorEvent.scala              (new)
    └── Slot.scala                      (new: FoundSlot, SlotCriteria, SlotFilter)

backend/src/main/scala/lmbot/backend/
├── BackgroundWorker.scala              (new)
├── BackendApplication.scala            (modify)
├── account/
│   ├── AccountClientRegistry.scala     (new)
│   └── DictionaryService.scala         (modify)
├── config/Config.scala                 (modify)
├── db/
│   ├── AccountRepo.scala               (modify)
│   ├── MonitorEventRepo.scala          (new)
│   ├── MonitorRepo.scala               (modify)
│   ├── Rows.scala                      (modify)
│   └── UserRepo.scala                  (modify)
├── http/
│   ├── MonitorRoutes.scala             (modify)
│   └── SettingsRoutes.scala            (new)
├── luxmed/
│   ├── AccountGate.scala               (modify: Sleeper moves out)
│   └── model/TermsModels.scala         (modify: id lists)
├── monitor/
│   ├── CheckFailure.scala              (new)
│   ├── LuxmedSlotSearch.scala          (new)
│   ├── MonitorCheck.scala              (new)
│   ├── MonitorEngine.scala             (new)
│   ├── MonitorService.scala            (modify)
│   └── SlotSearch.scala                (new)
├── notify/
│   ├── NotificationChannel.scala       (new)
│   ├── NotificationService.scala       (new)
│   ├── TelegramBot.scala               (new)
│   ├── TelegramLinkPoller.scala        (new)
│   └── TelegramLinkService.scala       (new)
└── support/Sleeper.scala               (moved from luxmed/AccountGate.scala)

backend/src/main/resources/db/migration/
└── V3__monitor_events_telegram.sql     (new)

frontend/src/main/scala/lmbot/frontend/
├── AppState.scala                      (modify)
├── Msg.scala                           (modify)
├── Update.scala                        (modify)
├── api/ApiClient.scala                 (modify)
└── view/
    ├── AppView.scala                   (modify)
    ├── MonitorView.scala               (modify)
    └── SettingsView.scala              (new)
```

Tests mirror those responsibilities. Do not fold engine policy, notification
delivery, and Telegram transport into one class: the check decision, the
notification boundary, and the Telegram wire client have different failure
modes and are independently testable.

---

### Task 1: Pin the shared monitor-engine contract

**Files:**

- Create: `shared/src/main/scala/lmbot/shared/domain/Slot.scala`
- Create: `shared/src/main/scala/lmbot/shared/domain/MonitorEvent.scala`
- Create: `shared/src/main/scala/lmbot/shared/api/SettingsPayloads.scala`
- Create: `shared/src/main/scala/lmbot/shared/api/SettingsEndpoints.scala`
- Modify: `shared/src/main/scala/lmbot/shared/domain/Monitor.scala`
- Modify: `shared/src/main/scala/lmbot/shared/api/MonitorEndpoints.scala`
- Modify: `shared/src/main/scala/lmbot/shared/api/Codecs.scala`
- Modify: `shared/src/test/scala/lmbot/shared/CodecRoundTripTest.scala`

**Interfaces:**

- Produces `FoundSlot`, `SlotCriteria`, and `SlotFilter.matches` — the pure
  filtering rules from spec §8, in `shared` so the backend and its tests share
  one implementation.
- Produces `MonitorEventId`, `MonitorEventKind` (ten wire-named cases), and
  `MonitorEventView`.
- Produces `TelegramSettingsView` and `TelegramLinkCodeView`.
- Extends `MonitorView` with `lastCheckAt: Option[Instant]` and
  `lastCheckSummary: Option[String]`.
- Extends `MonitorEndpoints` with `events`; adds `SettingsEndpoints` for
  status, link-code, and unlink.

- [ ] **Step 1: Write literal wire-format tests**

Add to `CodecRoundTripTest` literal values for the new types and pin their
exact bytes. At minimum:

```scala
val slotJson =
  """{"key":"400:2026-08-10T09:00","clinicId":100,"clinicName":"Mock Clinic","doctorId":200,"doctorName":"dr Mock Doctor","from":"2026-08-10T09:00","to":"2026-08-10T09:15","telemedicine":false}"""

val eventJson =
  """{"id":7,"monitorId":3,"kind":"slot_found","slot":""" + slotJson + ""","detail":null,"createdAt":"2026-08-10T07:01:02Z"}"""
```

Pin every `MonitorEventKind` wire name as a bare JSON string
(`"slot_found"`, `"notification_sent"`, `"notification_failed"`,
`"booking_attempted"`, `"booking_succeeded"`, `"booking_failed"`,
`"monitor_paused"`, `"monitor_completed"`, `"monitor_failed"`, `"error"`), and
pin `MonitorView` bytes including the two new nullable fields. Also pin:

```scala
assertEquals(
  writeToString(TelegramSettingsView(available = true, linked = false, botUsername = Some("lm_bot"))),
  """{"available":true,"linked":false,"botUsername":"lm_bot"}"""
)
```

- [ ] **Step 2: Run the shared suites and verify red**

```bash
sbt "sharedJVM/testFull; sharedJS/testFull"
```

- [ ] **Step 3: Implement `Slot.scala` and its pure filter tests**

```scala
final case class FoundSlot(
    key: String,
    clinicId: Long,
    clinicName: Option[String],
    doctorId: Long,
    doctorName: String,
    from: LocalDateTime,
    to: LocalDateTime,
    telemedicine: Boolean
)

final case class SlotCriteria(
    facilityIds: List[Long],
    doctorIds: List[Long],
    dateFrom: LocalDate,
    dateTo: LocalDate,
    timeFrom: LocalTime,
    timeTo: LocalTime,
    daysOfWeek: List[DayOfWeek]
)

object SlotFilter:
  /** Warsaw-local: the slot starts inside [timeFrom, timeTo) on a selected
    * weekday inside [dateFrom, dateTo], and belongs to a selected
    * clinic/doctor when any are selected. Empty provider lists mean "any".
    */
  def matches(slot: FoundSlot, criteria: SlotCriteria): Boolean
```

Write the filter tests first, in `shared/src/test/scala/lmbot/shared/SlotFilterTest.scala`:
inside/outside the date range on both boundaries, start exactly at
`timeFrom` (accepted) and exactly at `timeTo` (rejected), a slot on a
non-selected weekday, a clinic/doctor outside the selection, and the empty
selection meaning any. No clock, no zone lookup: the inputs are already
Warsaw-local.

- [ ] **Step 4: Implement the event and settings types, codecs, schemas, endpoints**

`MonitorEventKind.wireName` / `fromWire` follow the `MonitorState` pattern.
`MonitorEventView.slot` is `Option[FoundSlot]`; `detail` is
`Option[String]`. `MonitorEventId` is an opaque `Long` with a JSON number codec.

`MonitorEndpoints.events`:

```scala
val events: Endpoint[
  Option[String],
  (MonitorId, Int),
  ApiError,
  List[MonitorEventView],
  Any
] =
  securedIdBase.get
    .in("events")
    .in(query[Int]("limit").default(50))
    .out(jsonBody[List[MonitorEventView]])
```

`SettingsEndpoints` (path `api/settings/telegram`) adds `status` (GET),
`linkCode` (`POST .../link-code`), and `unlink` (DELETE), all secured and all
returning `ApiError` on failure. Register the new codecs and schemas in
`Codecs.scala` — including an explicit `JsonValueCodec[List[MonitorEventView]]`,
following the existing list-codec convention.

- [ ] **Step 5: Run the shared suites and verify green**

```bash
sbt "sharedJVM/testFull; sharedJS/testFull"
sbt scalafmtAll
```

---

### Task 2: Add the monitor-event schema and focused repositories

**Files:**

- Create: `backend/src/main/resources/db/migration/V3__monitor_events_telegram.sql`
- Create: `backend/src/main/scala/lmbot/backend/db/MonitorEventRepo.scala`
- Modify: `backend/src/main/scala/lmbot/backend/db/Rows.scala`
- Modify: `backend/src/main/scala/lmbot/backend/db/MonitorRepo.scala`
- Modify: `backend/src/main/scala/lmbot/backend/db/AccountRepo.scala`
- Modify: `backend/src/main/scala/lmbot/backend/db/UserRepo.scala`
- Modify: `backend/src/main/scala/lmbot/backend/monitor/MonitorService.scala`
- Create: `backend/src/test/scala/lmbot/backend/MonitorEventRepoTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/MonitorRepoTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/AccountRepoTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/UserRepoTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/MonitorServiceTest.scala`

**Interfaces:**

- Produces `MonitorEventRow` and `MonitorEventRepo` with an
  insert-on-conflict dedup primitive:
  `recordSlotFound(monitorId, slot, at): Boolean` — `true` only when this slot
  had never been recorded for the monitor.
- Produces `MonitorRepo.findById`, `listActive`, `recordCheck`, `complete`,
  `fail`, and `pauseAllForAccount`.
- Produces `AccountRepo.findById` and
  `markAuthFailed(id, reason, at): Boolean` — `true` only when the status
  actually changed, which is the engine's once-per-episode notification guard.
- Produces `UserRepo` Telegram methods: `setTelegramLinkCode`,
  `clearTelegramLinkCode`, `listLinkableUsers`, `setTelegramChatId`,
  `clearTelegramChatId`, `listAdmins`.
- Extends `MonitorService` with `events(ownerId, monitorId, limit)` and allows
  `failed → active` on resume.
- `MonitorView` mapping now carries `lastCheckAt` and `lastCheckSummary`.

- [ ] **Step 1: Write the migration**

```sql
alter table users
    add column telegram_link_code_hash text,
    add column telegram_link_code_expires_at timestamptz;

create sequence monitor_event_id_seq;

create table monitor_events (
    id                 bigint primary key default nextval('monitor_event_id_seq'),
    monitor_id         bigint not null references monitors(id) on delete cascade,
    kind               text not null
                       -- keep in sync with lmbot.shared.domain.MonitorEventKind.wireName
                       check (kind in ('slot_found','notification_sent',
                                       'notification_failed','booking_attempted',
                                       'booking_succeeded','booking_failed',
                                       'monitor_paused','monitor_completed',
                                       'monitor_failed','error')),
    slot_key           text,
    slot_clinic_id     bigint,
    slot_clinic_name   text,
    slot_doctor_id     bigint,
    slot_doctor_name   text,
    slot_from          timestamp,
    slot_to            timestamp,
    slot_telemedicine  boolean,
    detail             text,
    created_at         timestamptz not null default now(),
    constraint slot_details_all_or_none check (
        (slot_key is null and slot_clinic_id is null and slot_doctor_id is null
         and slot_from is null and slot_to is null and slot_telemedicine is null)
        or
        (slot_key is not null and slot_clinic_id is not null
         and slot_doctor_id is not null and slot_from is not null
         and slot_to is not null and slot_telemedicine is not null)
    )
);

create index idx_monitor_events_monitor_created
    on monitor_events (monitor_id, created_at desc);

-- Per-slot dedup: one slot_found row per monitor, enforced by the database.
create unique index idx_monitor_events_slot_found
    on monitor_events (monitor_id, slot_key) where kind = 'slot_found';

alter table monitors
    add column last_check_at timestamptz,
    add column last_check_summary text;
```

`slot_from` / `slot_to` are `timestamp` without time zone on purpose: they are
Warsaw-local wall-clock values, matching how `monitors.date_from`/`time_from`
already store Luxmed-facing times.

- [ ] **Step 2: Write the repository tests and verify red**

`MonitorEventRepoTest` (extends `PostgresSuite`) covers:

- `recordSlotFound` returns `true` once and `false` on the identical second
  call, including after a second repo instance (the database, not memory,
  enforces dedup).
- `append` records each non-slot kind with a null slot and returns an id.
- `listRecent` returns newest first and honours the limit.
- Deleting the monitor cascades its events.

`MonitorRepoTest` adds: `listActive` returns only active rows across owners;
`recordCheck` writes both last-check fields without touching `updated_at`;
`complete` and `fail` transition only from `active`; `pauseAllForAccount`
transitions exactly that account's active monitors.

`AccountRepoTest` adds: `markAuthFailed` flips `active → auth_failed` with the
reason and returns `true`, returns `false` on the second call, and never
overwrites a `disabled` account.

`UserRepoTest` adds: link-code set/list/clear and chat-id set/clear.

- [ ] **Step 3: Implement the repositories until green**

`MonitorEventRepo.recordSlotFound` is one statement:

```scala
sql"""insert into monitor_events
      (monitor_id, kind, slot_key, slot_clinic_id, slot_clinic_name,
       slot_doctor_id, slot_doctor_name, slot_from, slot_to,
       slot_telemedicine, detail, created_at)
      values (${monitorId.value}, 'slot_found', ${slot.key},
              ${slot.clinicId}, ${slot.clinicName}, ${slot.doctorId},
              ${slot.doctorName}, ${slot.from}, ${slot.to},
              ${slot.telemedicine}, null, $at)
      on conflict (monitor_id, slot_key) where kind = 'slot_found' do nothing"""
  .update.run() > 0
```

`MonitorRepo.recordCheck` deliberately does not touch `updated_at`: that column
means "the user last changed the definition".

- [ ] **Step 4: Extend `MonitorService` and verify**

- `events(ownerId, monitorId, limit)` checks ownership with
  `findOwnedMonitor` and clamps the limit to `1..200`.
- `resume` now accepts `List(Active, Paused, Failed)`; the conflict message for
  `completed` stays.
- `toView` maps the two new nullable columns.

```bash
sbt "backend/testOnly lmbot.backend.MonitorEventRepoTest lmbot.backend.MonitorRepoTest lmbot.backend.AccountRepoTest lmbot.backend.UserRepoTest lmbot.backend.MonitorServiceTest"
```

---

### Task 3: Hold one Luxmed client and gate per account (closes #35)

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/account/AccountClientRegistry.scala`
- Modify: `backend/src/main/scala/lmbot/backend/account/DictionaryService.scala`
- Create: `backend/src/test/scala/lmbot/backend/AccountClientRegistryTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/DictionaryServiceTest.scala`

**Interfaces:**

- Produces `AccountClientRegistry.forAccount(accountId): Either[ApiError, LuxmedClient]`,
  returning the same client (and therefore the same `AccountGate`) on every
  call for the same account. The production factory is
  `AccountClientRegistry.production(accounts, factory)`.
- `DictionaryService` takes the registry instead of the factory, so browser
  dictionary calls queue behind the same gate as engine checks.

- [ ] **Step 1: Write the registry tests and verify red**

- Two calls for one account return the identical instance
  (`assert(clientA eq clientB)`).
- A missing account returns `ApiError.NotFound`.
- With a loopback Luxmed server recording request timestamps, two calls for
  the same account are spaced by at least the configured minimum and never
  overlap. This is the test issue #35 never had.

- [ ] **Step 2: Implement the registry**

```scala
final class AccountClientRegistry private (
    accounts: AccountRepo,
    factory: AccountClientFactory
):
  private val lock = new Object
  private val clients = mutable.Map.empty[AccountId, LuxmedClient]

  def forAccount(accountId: AccountId): Either[ApiError, LuxmedClient] =
    lock.synchronized:
      clients.get(accountId) match
        case Some(client) => Right(client)
        case None =>
          accounts
            .findById(accountId)
            .toRight(ApiError.NotFound)
            .flatMap(row =>
              factory.forStored(UserId(row.ownerUserId), accountId)
            )
            .map: client =>
              clients.update(accountId, client)
              client
```

The map is private, guarded by one lock, and bounded by the number of linked
accounts; that is the whole mutable state. The companion exposes only
`production(accounts, factory)`.

- [ ] **Step 3: Route `DictionaryService` through the registry and verify**

```bash
sbt "backend/testOnly lmbot.backend.AccountClientRegistryTest lmbot.backend.DictionaryServiceTest"
```

---

### Task 4: Search slots and classify their failures

**Files:**

- Modify: `backend/src/main/scala/lmbot/backend/luxmed/model/TermsModels.scala`
- Modify: `backend/src/main/scala/lmbot/backend/luxmed/LuxmedClient.scala`
- Create: `backend/src/main/scala/lmbot/backend/monitor/CheckFailure.scala`
- Create: `backend/src/main/scala/lmbot/backend/monitor/SlotSearch.scala`
- Create: `backend/src/main/scala/lmbot/backend/monitor/LuxmedSlotSearch.scala`
- Create: `backend/src/test/scala/lmbot/backend/luxmed/LuxmedSlotSearchTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/luxmed/DictionaryAndTermsTest.scala`

**Interfaces:**

- `TermsQuery` grows `facilityIds: List[FacilityId]` and
  `doctorIds: List[DoctorId]` (replacing the single-valued options), and
  `searchTerms` sends one repeated query parameter per id.
- Produces the engine's failure vocabulary:

```scala
enum CheckFailure:
  case AuthRejected
  case Challenge
  case RateLimited
  case VersionRejected
  case Transient(detail: String)
  case Persistent(detail: String)
```

- Produces `SlotSearch` (the engine's seam) and its production adapter:

```scala
trait SlotSearch:
  def search(monitor: MonitorRow): Either[CheckFailure, List[FoundSlot]]
```

- [ ] **Step 1: Extend `TermsQuery` and verify the existing terms tests**

Update `DictionaryAndTermsTest` (and any `GuidedContractExplorer` call sites) to
the list form, then assert the repeated parameters are actually sent.

- [ ] **Step 2: Write `LuxmedSlotSearchTest` and verify red**

Using the existing loopback/stub harness:

- A monitor row maps to the expected query parameters (city, service, dates,
  every facility and doctor id, Warsaw-local date range).
- A decoded `terms-dual-datetime.json` response maps each `Term` to a
  `FoundSlot` with `key = "<scheduleId>:<from ISO local>"`, clinic name,
  composed doctor name (`academicTitle + firstName + lastName`, empty parts
  dropped), Warsaw-local `from`/`to`, and the telemedicine flag.
- Each `LuxmedError` classifies as its `CheckFailure`:
  `AuthFailed → AuthRejected`, `UnexpectedAuthResponse → Challenge`,
  `RateLimited → RateLimited`, `VersionRejected → VersionRejected`,
  `NetworkFailure`/`Transient`/`PersistenceFailed → Transient`,
  `DecodeFailed`/`ProtocolViolation`/`ApiRejected → Persistent`. Details are
  `SafeDiagnostic.value`, never raw payloads.

- [ ] **Step 3: Implement until green**

`LuxmedSlotSearch` resolves the client through the registry and maps
`ApiError.NotFound` (account deleted between reconcile and check) to
`CheckFailure.Persistent("The Luxmed account is no longer available.")`.

- [ ] **Step 4: Run the Luxmed suite**

```bash
sbt "backend/testOnly lmbot.backend.luxmed.*"
```

---

### Task 5: Notify through a channel boundary, and link Telegram

**Files:**

- Move: `backend/src/main/scala/lmbot/backend/luxmed/AccountGate.scala`
  (`Sleeper` → `backend/src/main/scala/lmbot/backend/support/Sleeper.scala`)
- Create: `backend/src/main/scala/lmbot/backend/notify/NotificationChannel.scala`
- Create: `backend/src/main/scala/lmbot/backend/notify/NotificationService.scala`
- Create: `backend/src/main/scala/lmbot/backend/notify/TelegramBot.scala`
- Create: `backend/src/main/scala/lmbot/backend/notify/TelegramLinkService.scala`
- Create: `backend/src/main/scala/lmbot/backend/notify/TelegramLinkPoller.scala`
- Create: `backend/src/test/scala/lmbot/backend/notify/TelegramBotTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/notify/TelegramLinkServiceTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/notify/TelegramLinkPollerTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/notify/NotificationServiceTest.scala`

**Interfaces:**

- `Sleeper` moves to `lmbot.backend.support` (imports updated in `AccountGate`
  and `FakeTime`); it is no longer Luxmed-specific.
- Produces the channel boundary:

```scala
enum NotificationError:
  case Transient(detail: String)
  case Rejected(detail: String)

trait NotificationChannel:
  def send(chatId: Long, text: String)(using Async): Either[NotificationError, Unit]
```

- Produces `NotificationService`, which resolves the owner's chat id, records
  `notification_sent` / `notification_failed` events, and never lets delivery
  failure escape:

```scala
final class NotificationService(
    users: UserRepo,
    channel: Option[NotificationChannel],
    events: MonitorEventRepo,
    now: () => OffsetDateTime
):
  def notifySlot(ownerId: UserId, monitor: MonitorRow, slot: FoundSlot)(using Async): Unit
  def notifyAccountAuthFailure(ownerId: UserId, accountLabel: String, reason: AccountStatusReason)(using Async): Unit
  def notifyMonitorFailed(ownerId: UserId, monitor: MonitorRow, reason: String)(using Async): Unit
  def notifyMonitorCompleted(ownerId: UserId, monitor: MonitorRow)(using Async): Unit
  def notifyAdminsVersionRejected(detail: String)(using Async): Unit
```

- Produces `TelegramBot` (plain sttp, no bot framework):

```scala
final class TelegramBot private (token: Secret, baseUri: Uri, backend: SttpBackend[Identity, Any]):
  def sendMessage(chatId: Long, text: String)(using Async): Either[NotificationError, Unit]
  def getUpdates(offset: Long, timeoutSeconds: Int)(using Async): Either[NotificationError, List[TelegramUpdate]]
```

- Produces `TelegramLinkService` (10-character code from an unambiguous
  alphabet, Argon2id-hashed via the existing `Passwords`, 15-minute expiry,
  single use) and `TelegramLinkPoller.run(stop)(using Async, Spawn)`.

- [ ] **Step 1: Move `Sleeper` and keep every test green**

```bash
sbt "backend/testOnly lmbot.backend.luxmed.AccountGateTest lmbot.backend.luxmed.PostgresSessionStoreClientTest"
```

- [ ] **Step 2: Write `NotificationServiceTest` and verify red**

With a recording fake channel and a real `MonitorEventRepo`:

- A linked user gets exactly one send per new slot and a `notification_sent`
  event.
- An unlinked user gets no send and no notification event; the slot event
  recorded by the check is all there is (spec §3.5 degradation).
- A channel failure records `notification_failed` with the safe detail and
  does not throw.
- `notifyAdminsVersionRejected` sends to every admin with a chat id and to
  nobody else.

- [ ] **Step 3: Write `TelegramBotTest` against a loopback HTTP server and verify red**

Following `RealHttpLuxmedServer`:

- `sendMessage` posts `chat_id` and `text` to `/bot<token>/sendMessage` and
  treats `{"ok":true}` as success.
- `{"ok":false,"description":...}` is `Rejected`; 5xx and connection failures
  are `Transient`.
- `getUpdates` sends `offset`, `timeout`, and `allowed_updates`, and parses
  `update_id`, `message.chat.id`, and `message.text`; unknown fields and
  absent `text` are tolerated.
- The token never appears in an error value.

- [ ] **Step 4: Write `TelegramLinkServiceTest` and verify red**

- `createCode` returns a code, a `https://t.me/<bot>?start=<code>` deep link,
  and an expiry 15 minutes out; the database stores only a hash that is not the
  code.
- `consume` returns the user for the right code, `None` for a wrong code,
  `None` after expiry, and consumes the code so a second call fails.
- `unlink` clears the chat id and any pending code.
- With no bot configured, `status.available` is false and `createCode` returns
  `ApiError.Conflict` (not a crash).

- [ ] **Step 5: Write `TelegramLinkPollerTest` and verify red**

With a scripted bot (or loopback server) and a fake sleeper:

- `/start <valid code>` sets the chat id and replies with the confirmation
  text; the offset advances past the processed update.
- An unknown or expired code replies with the expired text and links nothing.
- A `Transient` failure sleeps the retry delay and resumes from the same
  offset (no update loss).
- Closing the stop channel ends `run` without swallowing cancellation.

- [ ] **Step 6: Implement until green, then format**

```bash
sbt "backend/testOnly lmbot.backend.notify.*"
sbt scalafmtAll
```

---

### Task 6: Run the engine — supervision, retry policy, and state transitions

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/monitor/MonitorCheck.scala`
- Create: `backend/src/main/scala/lmbot/backend/monitor/MonitorEngine.scala`
- Create: `backend/src/test/scala/lmbot/backend/monitor/MonitorCheckTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/monitor/EnginePolicyTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/monitor/MonitorEngineTest.scala`

**Interfaces:**

- `MonitorCheck.run(monitor, ownerId, today): CheckResult` owns one check's
  side effects and nothing else:

```scala
enum CheckResult:
  case Succeeded(slotsFound: Int, newSlots: Int)
  case Failed(failure: CheckFailure)
```

- `MonitorEngine.run(stop: ReadableChannel[Unit])(using Async, Spawn): Unit`
  is the supervisor. Its testable unit is one loop iteration:

```scala
private[monitor] enum Iteration:
  case Continue(sleepFor: FiniteDuration, persistentFailures: Int)
  case Stop

private[monitor] def iterate(
    monitor: MonitorRow,
    ownerId: UserId,
    persistentFailures: Int
): Iteration
```

- The pure decision function is separately testable:

```scala
private[monitor] object EnginePolicy:
  val MaxPersistentFailures = 3
  def after(
      result: CheckResult,
      persistentFailures: Int,
      intervalMinutes: Int,
      jitter: Double
  ): Iteration
```

- [ ] **Step 1: Write `MonitorCheckTest` and verify red**

With a fake `SlotSearch`, a real `MonitorEventRepo`, and a fake notifier:

- Slots outside the monitor's criteria are filtered before dedup; nothing is
  recorded for them.
- A new slot is recorded once and notified once; a second check with the same
  slot records nothing and notifies nobody.
- `Succeeded` counts match filtered and newly recorded slots.
- A `CheckFailure` propagates as `Failed` with no events written.

- [ ] **Step 2: Write `EnginePolicyTest` and verify red**

Pin the spec's numbers:

- Success sleeps `intervalMinutes * 60_000 * (0.8 + 0.4 * jitter)` ms and
  resets the persistent counter; jitter 0 and 1 give the ±20 % bounds.
- Transient backoff is 1 min, 2 min, 4 min… capped at 30 min; it does not
  increment the persistent counter.
- Rate-limited backoff is 5 min, 10 min… capped at 1 h.
- Persistent failures increment the counter; the third returns
  `Iteration.Stop` after the caller marks the monitor failed.
- `AuthRejected` and `Challenge` return a stop-and-pause decision with the
  matching `AccountStatusReason`; `VersionRejected` returns a
  notify-admin decision and the capped backoff.

- [ ] **Step 3: Write `MonitorEngineTest` and verify red**

Drive `iterate` and `run` with fakes (search, notifier, repositories via
`PostgresSuite`, `FakeTime`, an injected jitter, and an injected
`reconcileInterval`):

- A monitor whose `dateTo` is before Warsaw today is completed, notified once,
  and its loop stops.
- A monitor already paused/failed/deleted stops without a search.
- Auth rejection marks the account `auth_failed`, pauses that account's active
  monitors, notifies the owner once (a second monitor failing in the same
  episode does not notify again), and stops both loops.
- A decode failure three times in a row fails the monitor and notifies once;
  a success between failures resets the counter.
- Every check updates `last_check_at` and a human summary such as
  `"Found 2 new slots"` or `"No new slots"`.
- `run` starts loops for the active set, picks up a monitor created after
  startup at the next reconcile, cancels a loop whose monitor is paused, and
  returns when the stop channel closes — with all loops cancelled and awaited.
- A check that throws a bug is counted as a persistent failure (the
  `CancellationException` path is rethrown, not counted).

- [ ] **Step 4: Implement until green**

Spawn mechanics: `run` uses `Async.spawning.use` to get an engine-owned
`Spawn`; the reconciler and every monitor loop are `Future`s spawned with that
capability, so they are cancelled and awaited when `run` returns. If the
typing of `Async.spawning` fights the `Future.apply` constraints, fall back to
capturing the root `Async.Spawn` and passing it as both `async` and `spawnable`
— but prove it with the stop/cancel test before building on it.

- [ ] **Step 5: Run the engine tests and format**

```bash
sbt "backend/testOnly lmbot.backend.monitor.*"
sbt scalafmtAll
```

---

### Task 7: Wire configuration, composition, and lifecycle

**Files:**

- Create: `backend/src/main/scala/lmbot/backend/BackgroundWorker.scala`
- Modify: `backend/src/main/scala/lmbot/backend/config/Config.scala`
- Modify: `backend/src/main/resources/application.conf`
- Modify: `backend/src/main/resources/application-dev.conf`
- Modify: `backend/src/main/scala/lmbot/backend/BackendApplication.scala`
- Modify: `backend/src/test/scala/lmbot/backend/ConfigTest.scala`
- Modify: `backend/src/test/scala/lmbot/backend/BackendApplicationTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/BackgroundWorkerTest.scala`

**Interfaces:**

- `Config` grows `telegramBotToken: Option[Secret]`,
  `telegramBotUsername: Option[String]`, and
  `telegramApiBase: String = "https://api.telegram.org"`, with environment
  names `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_API_BASE`.
  Token and username are both-or-neither; the config error names both
  variables.
- Produces `BackgroundWorker.start(name)(body: ReadableChannel[Unit] ?=> Unit): AutoCloseable`,
  a virtual thread running `Async.fromSync`, closed by closing the stop channel
  and joining.
- `BackendApplication.start` builds the registry, event repo, notification
  service, check, engine, and (when Telegram is configured) the link service
  and poller; starts one worker; and closes it before the data source.

- [ ] **Step 1: Write config tests and verify red**

`ConfigTest` covers: both variables set → `Some`; neither → `None`; exactly one
→ an error naming both; empty string means absent; `TELEGRAM_API_BASE` defaults
to the real API and is overridable.

- [ ] **Step 2: Write `BackgroundWorkerTest` and verify red**

- A worker body that spawns a `Future` and blocks on the stop channel runs;
  `close()` returns after the body and its children have stopped.
- A worker body that throws does not prevent `close()`.

- [ ] **Step 3: Implement config and worker, then wire the composition**

In `BackendApplication.start`, after the routes are built:

```scala
val clients = AccountClientRegistry.production(accountRepo, accountClients)
val dictionaryService = DictionaryService(clients)
val eventRepo = MonitorEventRepo(xa)
val telegram = config.telegramBotToken.zip(config.telegramBotUsername).map: (token, _) =>
  TelegramBot.production(token, Uri.unsafeParse(config.telegramApiBase))
val notifier = NotificationService(users, telegram.map(TelegramNotificationChannel(_)), eventRepo, () => OffsetDateTime.now())
val check = MonitorCheck(LuxmedSlotSearch(clients), eventRepo, notifier, () => OffsetDateTime.now())
val engine = MonitorEngine(monitorRepo, accountRepo, users, eventRepo, check, notifier, ...)
val worker = BackgroundWorker.start("lm-bot-monitor-engine")(engine.run(stop))
```

The Telegram poller runs in the same worker: spawn it as a `Future` before
`engine.run(stop)` blocks, or start a second worker — whichever keeps the
shutdown test simplest. Include the worker in `BackendApplication`'s resource
list **before** the data source so it stops before the pool closes.

- [ ] **Step 4: Verify**

```bash
sbt "backend/testOnly lmbot.backend.ConfigTest lmbot.backend.BackgroundWorkerTest lmbot.backend.BackendApplicationTest"
sbt backend/testFull
```

---

### Task 8: Expose events and Telegram settings through Tapir

**Files:**

- Modify: `backend/src/main/scala/lmbot/backend/http/MonitorRoutes.scala`
- Create: `backend/src/main/scala/lmbot/backend/http/SettingsRoutes.scala`
- Modify: `backend/src/main/scala/lmbot/backend/BackendApplication.scala`
- Modify: `backend/src/test/scala/lmbot/backend/MonitorHttpApiTest.scala`
- Create: `backend/src/test/scala/lmbot/backend/SettingsHttpApiTest.scala`

**Interfaces:**

- `MonitorRoutes` gains the events route, delegating to
  `MonitorService.events`.
- `SettingsRoutes(auth, telegram: TelegramLinkService)` exposes status,
  link-code, and unlink; it is pure DB/code work, so no `AsyncBoundary` is
  needed.

- [ ] **Step 1: Write the HTTP tests and verify red**

`MonitorHttpApiTest` adds: a second user gets 404 for another owner's events;
the owner gets newest-first events with the requested limit; limit is clamped.

`SettingsHttpApiTest` adds: status reports `available = false` with no bot
configured; link-code with no bot returns the configured conflict; with a bot
configured, link-code returns a code and unlink clears the chat id; all three
require authentication.

- [ ] **Step 2: Implement the routes and register them**

```bash
sbt "backend/testOnly lmbot.backend.MonitorHttpApiTest lmbot.backend.SettingsHttpApiTest"
```

---

### Task 9: Show last-check summaries, the monitor detail view, and Telegram settings

**Files:**

- Modify: `frontend/src/main/scala/lmbot/frontend/AppState.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/Msg.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/Update.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/api/ApiClient.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/view/AppView.scala`
- Modify: `frontend/src/main/scala/lmbot/frontend/view/MonitorView.scala`
- Create: `frontend/src/main/scala/lmbot/frontend/view/SettingsView.scala`
- Modify: `frontend/src/test/scala/lmbot/frontend/UpdateTest.scala`

**Interfaces:**

- `ApiClient` gains `getMonitor(id)`, `monitorEvents(id, limit)`,
  `telegramStatus()`, `telegramLinkCode()`, and `telegramUnlink()`, all
  `Either[ApiError, _]` and all `(using Async)`.
- `AppState` gains:

```scala
case class MonitorDetail(
    monitor: MonitorView,
    events: LoadState[List[MonitorEventView]] = LoadState.NotAsked
)
case class TelegramSettings(
    status: LoadState[TelegramSettingsView] = LoadState.NotAsked,
    link: LoadState[TelegramLinkCodeView] = LoadState.NotAsked,
    submitting: Boolean = false,
    error: Option[String] = None
)
```

with `monitorDetail: Option[MonitorDetail] = None` and
`telegram: TelegramSettings = TelegramSettings()` on `AppState`.

- New messages: `MonitorDetailRequested`, `MonitorDetailClosed`,
  `MonitorEventsLoaded`, `MonitorEventsLoadFailed`, `TelegramStatusRequested`,
  `TelegramStatusLoaded`, `TelegramStatusLoadFailed`, `TelegramLinkRequested`,
  `TelegramLinkLoaded`, `TelegramLinkFailed`, `TelegramUnlinkRequested`,
  `TelegramUnlinked`, `TelegramUnlinkFailed`.

- [ ] **Step 1: Write the reducer tests and verify red**

`UpdateTest` covers: opening a detail emits one get + one events effect and
loads events keyed to the monitor id; a stale events response for a different
monitor is dropped; closing clears the detail; the Telegram status/link/unlink
flows with their stale-response guards; `LoggedOut` resets the new state; and
the whole-state equality test is updated.

- [ ] **Step 2: Implement `ApiClient`, `Msg`, `AppState`, and `Update` until green**

- [ ] **Step 3: Implement the views**

- `MonitorsView.monitorItem` shows the last-check line:
  `"Checked <yyyy-MM-dd HH:mm> Warsaw time — <summary>"` or
  `"Not checked yet."`, and a **Details** button.
- Detail mode replaces the list (like the form does): the monitor's criteria,
  state, last check, and the event log newest first, with each kind rendered as
  fixed text and slots rendered with clinic, doctor, and Warsaw datetimes.
  A **Back** button emits `MonitorDetailClosed`.
- The state toggle offers **Resume** for `failed` monitors (the backend now
  allows it); `completed` keeps no toggle.
- `SettingsView` is appended to the dashboard: Telegram status, a **Link
  Telegram** button that shows the deep link and code, and an **Unlink**
  button. When Telegram is not configured, it says notifications are
  unavailable.
- The monitor list warns once when the user is not Telegram-linked:
  `"No Telegram chat is linked — events are recorded here, but no
  notifications will be delivered."` linking to the settings section.

- [ ] **Step 4: Verify and link the frontend**

```bash
sbt frontend/testFull
sbt frontend/fastLinkJS
sbt "frontend/Test/runMain ..."   # or the dev harness
```

Linking is not evidence the UI works: load the dashboard in a real browser
(Node 26+) and exercise detail, resume-from-failed, and the Telegram section
against a development server before declaring this task done.

---

### Task 10: Exercise the whole flow end to end and close Plan 5

**Files:**

- Create: `backend/src/test/scala/lmbot/backend/Plan5AcceptanceApp.scala`
- Create: `backend/src/test/scala/lmbot/backend/Plan5AcceptanceConfig.scala`
- Create: `backend/src/test/scala/lmbot/backend/support/FakeTelegramServer.scala`
- Create: `docs/superpowers/reports/2026-09-20-plan-05-complete.md`
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-07-27-lm-bot-roadmap.md`

**Interfaces:**

- `Plan5AcceptanceApp` composes the Plan 4 harness graph plus the engine, a
  loopback Luxmed boundary whose `terms/index` returns a slot inside the
  created monitor's window, and `FakeTelegramServer` (a JDK `HttpServer`
  implementing `getUpdates`/`sendMessage`) selected by
  `TELEGRAM_API_BASE`.

- [ ] **Step 1: Build the harness**

- Fake Luxmed terms returns one slot per configured criteria; the existing
  auth/dictionary routing stays.
- `FakeTelegramServer` queues updates so the test can inject
  `/start <code>` after the browser requests a link code, and records sent
  messages for assertions.

- [ ] **Step 2: Run the browser acceptance scenarios**

Against the harness in Chromium via `agent-browser`:

1. Sign in and create a monitor whose window contains the fake slot.
2. Within one reconcile interval the monitor's row shows a last-check summary
   and the detail view shows a `slot_found` event and a `notification_sent`
   event.
3. The fake Telegram server received the slot notification.
4. Request a link code, inject `/start <code>`, reload: status shows linked,
   and the "no Telegram linked" warning is gone.
5. Pause the monitor: the loop stops; resume: the loop restarts.
6. Force a persistent failure (fake Luxmed answers malformed JSON three
   times): the monitor goes `failed`, the owner is notified once, and the UI
   offers **Resume**; resuming returns it to `active`.
7. Console is free of unexpected messages; no secret appears in any response
   or notification.

- [ ] **Step 3: Write the completion report and update the roadmap**

The report follows the Plan 4 report shape: changed-files, verification-run,
skipped-checks, branch, pr, blocker; what was delivered; what was deliberately
left (auto-booking, credential editing, event retention, metrics); and the
browser scenario table.

- [ ] **Step 4: Final gates**

```bash
sbt scalafmtAll
sbt testFull
sbt frontend/fastLinkJS
nix flake check
grep -rn --include='*.scala' 'scala\.concurrent' shared/src/main backend/src/main frontend/src/main | grep -v '/bridge/'
git diff --check
```

---

## Plan 5 Completion Criteria

| Criterion | Evidence |
|---|---|
| Monitors run on jittered per-monitor intervals and queue behind one account gate | `EnginePolicyTest`, `MonitorEngineTest`, `AccountClientRegistryTest`; issue #35 closed |
| Slot filtering is pure, Warsaw-local, and shared | `SlotFilterTest` |
| A slot is notified at most once per monitor, enforced by the database | `MonitorEventRepoTest`, `MonitorCheckTest` |
| The failure policy matches §5.5: backoff, auth-failure pause with reason, version rejection to admins, 3-strike retry budget | `EnginePolicyTest`, `MonitorEngineTest` |
| Restart and reconciliation resume the active set exactly, and new monitors start without a restart | `MonitorEngineTest` |
| Telegram linking works through a one-time `/start <code>` deep link over long polling | `TelegramLinkServiceTest`, `TelegramLinkPollerTest` |
| A user without Telegram still gets events in the UI and a visible warning | `NotificationServiceTest`, `UpdateTest`, browser scenario 4 |
| The monitor list shows a last-check summary and the detail view shows the event log | `MonitorHttpApiTest`, `UpdateTest`, browser scenarios 2–3 |
| A `failed` monitor can be resumed manually | `MonitorServiceTest`, `MonitorHttpApiTest`, browser scenario 6 |
| All gates pass with no required test skipped or excluded | `verification-run` and `skipped-checks` in the completion report |

## Out of scope

- **Auto-booking** (Plan 6). The `booking_*` event kinds exist; nothing writes
  them yet.
- **Editing Luxmed credentials.** An `auth_failed` account is recovered by
  deleting and re-linking it; a credential-edit flow is Plan 7 material.
- **Event retention/pruning.** `monitor_events` grows append-only; retention is
  an ops concern for Plan 7.
- **Live status push.** tapir-jdkhttp has no websockets; the UI refreshes when
  the user reloads or acts, as it does today.
- **Metrics.** §9 keeps v1 to structured logs and the `/health` endpoint.
