create sequence luxmed_account_id_seq;

create table luxmed_accounts (
    id                    bigint primary key default nextval('luxmed_account_id_seq'),
    owner_user_id         bigint not null references users(id) on delete cascade,
    label                 text not null,
    luxmed_username       text not null,
    encrypted_password    text not null,
    encrypted_device_uuid text not null,
    encrypted_session     text,
    status                text not null
                          check (status in ('active','auth_failed','disabled')),
    status_reason         text,
    last_successful_login timestamptz,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    unique (owner_user_id, label)
);

create index idx_luxmed_accounts_owner_user_id on luxmed_accounts (owner_user_id);

create sequence monitor_id_seq;

create table monitors (
    id                    bigint primary key default nextval('monitor_id_seq'),
    luxmed_account_id     bigint not null references luxmed_accounts(id) on delete cascade,
    name                  text not null,
    city_id               bigint not null,
    city_name             text not null,
    service_id            bigint not null,
    service_name          text not null,
    facility_ids          bigint[] not null default array[]::bigint[],
    facility_names        text[] not null default array[]::text[],
    doctor_ids            bigint[] not null default array[]::bigint[],
    doctor_names          text[] not null default array[]::text[],
    date_from             date not null,
    date_to               date not null,
    time_from             time not null,
    time_to               time not null,
    days_of_week          smallint not null,
    auto_book             boolean not null default false,
    interval_minutes      int not null check (interval_minutes >= 5),
    state                 text not null check (state in ('active','paused','completed','failed')),
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    constraint facility_arrays_same_cardinality
        check (cardinality(facility_ids) = cardinality(facility_names)),
    constraint doctor_arrays_same_cardinality
        check (cardinality(doctor_ids) = cardinality(doctor_names))
);

create index idx_monitors_luxmed_account_id on monitors (luxmed_account_id);
