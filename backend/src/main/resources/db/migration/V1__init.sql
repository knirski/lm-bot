create table users (
    id               bigserial   primary key,
    username         text        not null unique,
    display_name     text        not null,
    password_hash    text        not null,
    role             text        not null check (role in ('admin', 'user')),
    telegram_chat_id bigint,
    disabled         boolean     not null default false,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now()
);

-- The opaque session token is never stored; only its hash is, so a database
-- leak does not hand over live sessions. Revocation is a row delete.
create table sessions (
    token_hash text        primary key,
    user_id    bigint      not null references users (id) on delete cascade,
    expires_at timestamptz not null,
    created_at timestamptz not null default now()
);

create index sessions_user_id_idx on sessions (user_id);
create index sessions_expires_at_idx on sessions (expires_at);
