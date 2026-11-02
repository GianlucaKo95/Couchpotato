import { createClient } from "@supabase/supabase-js";

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { ...cors, "Content-Type": "application/json" },
});
const sha256 = async (value: string) => {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
};
const makeCode = () => {
  const alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  const bytes = crypto.getRandomValues(new Uint8Array(12));
  return [...bytes].map((b) => alphabet[b & 31]).join("");
};

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST required" }, 405);
  try {
    const raw = await req.text();
    if (raw.length > 1_500_000) return json({ error: "Request too large" }, 413);
    const body = JSON.parse(raw);
    const action = String(body.action ?? "");
    const token = String(body.device_token ?? "");
    if (!/^[A-Za-z0-9_-]{40,50}$/.test(token)) return json({ error: "Invalid device token" }, 400);

    const url = Deno.env.get("SUPABASE_URL");
    const secrets = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
    const key = secrets.default;
    if (!url || !key) throw new Error("Server is not configured");
    const db = createClient(url, key, { auth: { persistSession: false } });
    const tokenHash = await sha256(token);

    if (action === "create") {
      let { data: device } = await db.from("couchpotato_sync_devices").select("group_id").eq("token_hash", tokenHash).maybeSingle();
      if (!device) {
        const { data: group, error: ge } = await db.from("couchpotato_sync_groups").insert({}).select("id").single();
        if (ge) throw ge;
        const { error: de } = await db.from("couchpotato_sync_devices").insert({ token_hash: tokenHash, group_id: group.id });
        if (de) throw de;
        device = { group_id: group.id };
      }
      await db.from("couchpotato_sync_pairing_codes").delete().lt("expires_at", new Date().toISOString());
      const code = makeCode();
      const { error: ce } = await db.from("couchpotato_sync_pairing_codes").insert({
        code_hash: await sha256(code), group_id: device.group_id, expires_at: new Date(Date.now() + 10 * 60_000).toISOString(),
      });
      if (ce) throw ce;
      return json({ group_id: device.group_id, code });
    }

    if (action === "join") {
      const code = String(body.code ?? "").toUpperCase().replace(/[^0-9A-HJKMNP-TV-Z]/g, "");
      if (code.length !== 12) return json({ error: "Invalid or expired pairing code" }, 400);
      const codeHash = await sha256(code);
      const { data: groupId, error: pe } = await db.rpc("consume_couchpotato_pairing_code", { p_code_hash: codeHash });
      if (pe || !groupId) return json({ error: "Invalid or expired pairing code" }, 400);
      const { error: de } = await db.from("couchpotato_sync_devices").insert({ token_hash: tokenHash, group_id: groupId });
      if (de) throw de;
      const { data: state } = await db.from("couchpotato_sync_state").select("snapshot, updated_at")
        .eq("group_id", groupId).maybeSingle();
      return json({ group_id: groupId, snapshot: state?.snapshot ?? null, updated_at: state?.updated_at ? Date.parse(state.updated_at) : 0 });
    }

    const { data: device, error: authError } = await db.from("couchpotato_sync_devices")
      .select("group_id").eq("token_hash", tokenHash).maybeSingle();
    if (authError || !device || device.group_id !== body.group_id) return json({ error: "Device is not paired to this group" }, 403);
    await db.from("couchpotato_sync_devices").update({ last_seen_at: new Date().toISOString() }).eq("token_hash", tokenHash);

    if (action === "pull") {
      const { data: state, error } = await db.from("couchpotato_sync_state").select("snapshot, updated_at")
        .eq("group_id", device.group_id).maybeSingle();
      if (error) throw error;
      return json({ snapshot: state?.snapshot ?? null, updated_at: state?.updated_at ? Date.parse(state.updated_at) : 0 });
    }
    if (action === "push") {
      if (!body.snapshot || body.snapshot.version !== 1 || typeof body.snapshot.preferences !== "object") {
        return json({ error: "Invalid snapshot" }, 400);
      }
      const { data: state, error } = await db.from("couchpotato_sync_state").upsert({
        group_id: device.group_id, snapshot: body.snapshot, updated_at: new Date().toISOString(),
      }).select("updated_at").single();
      if (error) throw error;
      return json({ updated_at: Date.parse(state.updated_at) });
    }
    return json({ error: "Unknown action" }, 400);
  } catch (error) {
    console.error("couchpotato-sync", error);
    return json({ error: "Sync request failed" }, 500);
  }
});
