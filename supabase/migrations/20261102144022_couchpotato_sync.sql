create table if not exists public.couchpotato_sync_groups (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now()
);

create table if not exists public.couchpotato_sync_devices (
  token_hash text primary key,
  group_id uuid not null references public.couchpotato_sync_groups(id) on delete cascade,
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);

create table if not exists public.couchpotato_sync_pairing_codes (
  code_hash text primary key,
  group_id uuid not null references public.couchpotato_sync_groups(id) on delete cascade,
  expires_at timestamptz not null
);

create table if not exists public.couchpotato_sync_state (
  group_id uuid primary key references public.couchpotato_sync_groups(id) on delete cascade,
  snapshot jsonb not null,
  updated_at timestamptz not null default now()
);

create index if not exists couchpotato_sync_devices_group_id_idx
  on public.couchpotato_sync_devices(group_id);
create index if not exists couchpotato_sync_pairing_codes_group_id_idx
  on public.couchpotato_sync_pairing_codes(group_id);

alter table public.couchpotato_sync_groups enable row level security;
alter table public.couchpotato_sync_devices enable row level security;
alter table public.couchpotato_sync_pairing_codes enable row level security;
alter table public.couchpotato_sync_state enable row level security;

revoke all on public.couchpotato_sync_groups, public.couchpotato_sync_devices,
  public.couchpotato_sync_pairing_codes, public.couchpotato_sync_state from anon, authenticated;
grant all on public.couchpotato_sync_groups, public.couchpotato_sync_devices,
  public.couchpotato_sync_pairing_codes, public.couchpotato_sync_state to service_role;

create or replace function public.consume_couchpotato_pairing_code(p_code_hash text)
returns uuid
language plpgsql
security invoker
set search_path = ''
as $$
declare
  matched_group uuid;
begin
  delete from public.couchpotato_sync_pairing_codes
  where code_hash = p_code_hash and expires_at > now()
  returning group_id into matched_group;
  return matched_group;
end;
$$;

revoke all on function public.consume_couchpotato_pairing_code(text) from public, anon, authenticated;
grant execute on function public.consume_couchpotato_pairing_code(text) to service_role;
