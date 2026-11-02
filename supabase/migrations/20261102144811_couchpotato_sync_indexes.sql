create index if not exists couchpotato_sync_devices_group_id_idx
  on public.couchpotato_sync_devices(group_id);

create index if not exists couchpotato_sync_pairing_codes_group_id_idx
  on public.couchpotato_sync_pairing_codes(group_id);
