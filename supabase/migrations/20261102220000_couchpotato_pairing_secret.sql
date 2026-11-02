-- One-time, end-to-end encrypted hand-over of the IPTV login while pairing.
-- The blob is encrypted on the TV with a key derived from the pairing code (the server keeps only
-- the code's hash), lives at most as long as the code (10 minutes) and is deleted when consumed.
alter table public.couchpotato_sync_pairing_codes
  add column if not exists secret_blob text;

alter table public.couchpotato_sync_pairing_codes
  drop constraint if exists couchpotato_sync_pairing_codes_secret_blob_len;
alter table public.couchpotato_sync_pairing_codes
  add constraint couchpotato_sync_pairing_codes_secret_blob_len
  check (secret_blob is null or length(secret_blob) <= 4096);

create or replace function public.consume_couchpotato_pairing_code_v2(p_code_hash text)
returns table (group_id uuid, secret_blob text)
language plpgsql
security invoker
set search_path = ''
as $$
begin
  return query
    delete from public.couchpotato_sync_pairing_codes c
    where c.code_hash = p_code_hash and c.expires_at > now()
    returning c.group_id, c.secret_blob;
end;
$$;

revoke all on function public.consume_couchpotato_pairing_code_v2(text) from public, anon, authenticated;
grant execute on function public.consume_couchpotato_pairing_code_v2(text) to service_role;
