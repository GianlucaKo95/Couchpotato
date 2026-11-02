-- Household sync, end-to-end encrypted.
-- A household is found by the SHA-256 of an access key the TVs derive from the household code
-- (PBKDF2, 210k rounds). Its data is one AES-256-GCM blob encrypted on the TVs with a second,
-- independent key from the same code – the server can neither read it nor derive the key.
create table if not exists public.couchpotato_households (
  lookup_hash text primary key check (lookup_hash ~ '^[0-9a-f]{64}$'),
  blob text not null check (length(blob) <= 2000000),
  version bigint not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

alter table public.couchpotato_households enable row level security;
revoke all on public.couchpotato_households from anon, authenticated;
grant all on public.couchpotato_households to service_role;

-- the earlier sync stored snapshots readable for the server: remove it completely
drop function if exists public.consume_couchpotato_pairing_code(text);
drop function if exists public.consume_couchpotato_pairing_code_v2(text);
drop table if exists public.couchpotato_sync_state;
drop table if exists public.couchpotato_sync_pairing_codes;
drop table if exists public.couchpotato_sync_devices;
drop table if exists public.couchpotato_sync_groups;
