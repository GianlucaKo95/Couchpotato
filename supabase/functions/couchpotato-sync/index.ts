import { createClient } from "@supabase/supabase-js";

// Household sync, end-to-end encrypted: this function only ever sees an access key (stored as its
// SHA-256) and an opaque AES-GCM blob. It cannot read profiles, progress or IPTV logins.
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
const MAX_BLOB = 2_000_000;
const TABLE = "couchpotato_households";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "POST required" }, 405);
  try {
    const raw = await req.text();
    if (raw.length > MAX_BLOB + 10_000) return json({ error: "Request too large" }, 413);
    const body = JSON.parse(raw);
    const action = String(body.action ?? "");
    const auth = String(body.auth ?? "");
    if (body.v !== 3) return json({ error: "Bitte Couchpotato aktualisieren" }, 426);
    if (!/^[0-9a-f]{64}$/.test(auth)) return json({ error: "Invalid access key" }, 400);

    const url = Deno.env.get("SUPABASE_URL");
    const secrets = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
    const key = secrets.default;
    if (!url || !key) throw new Error("Server is not configured");
    const db = createClient(url, key, { auth: { persistSession: false } });
    const lookup = await sha256(auth);
    const blob = typeof body.blob === "string" ? body.blob : "";
    const blobOk = blob.length > 0 && blob.length <= MAX_BLOB && /^[A-Za-z0-9+/=]+$/.test(blob);

    if (action === "create") {
      if (!blobOk) return json({ error: "Invalid blob" }, 400);
      const { data, error } = await db.from(TABLE).insert({ lookup_hash: lookup, blob }).select("version").single();
      if (error) return error.code === "23505" ? json({ error: "Exists" }, 409) : (() => { throw error; })();
      return json({ version: data.version });
    }
    if (action === "pull") {
      const { data, error } = await db.from(TABLE).select("blob, version").eq("lookup_hash", lookup).maybeSingle();
      if (error) throw error;
      if (!data) return json({ error: "Unknown household" }, 404);
      return json({ blob: data.blob, version: data.version });
    }
    if (action === "push") {
      const base = Number(body.base_version);
      if (!blobOk || !Number.isSafeInteger(base)) return json({ error: "Invalid push" }, 400);
      const { data, error } = await db.from(TABLE)
        .update({ blob, version: base + 1, updated_at: new Date().toISOString() })
        .eq("lookup_hash", lookup).eq("version", base).select("version");
      if (error) throw error;
      if (data && data.length > 0) return json({ version: data[0].version });
      const { data: exists } = await db.from(TABLE).select("version").eq("lookup_hash", lookup).maybeSingle();
      return exists ? json({ error: "Changed meanwhile" }, 409) : json({ error: "Unknown household" }, 404);
    }
    if (action === "delete") {
      const { error } = await db.from(TABLE).delete().eq("lookup_hash", lookup);
      if (error) throw error;
      return json({ ok: true });
    }
    return json({ error: "Unknown action" }, 400);
  } catch (error) {
    console.error("couchpotato-sync", error);
    return json({ error: "Sync request failed" }, 500);
  }
});
