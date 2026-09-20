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
    -- slot_from/slot_to are timestamp without time zone on purpose: they are
    -- Warsaw-local wall-clock values, like monitors.date_from/time_from.
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
-- The engine inserts with `on conflict ... do nothing`; a returned row means
-- the slot is new.
create unique index idx_monitor_events_slot_found
    on monitor_events (monitor_id, slot_key) where kind = 'slot_found';

alter table monitors
    add column last_check_at timestamptz,
    add column last_check_summary text;
