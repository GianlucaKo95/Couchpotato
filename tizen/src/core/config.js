// Filled in by the build (GitHub secrets SUPABASE_URL / SUPABASE_PUBLISHABLE_KEY).
/* global __CP_SUPABASE_URL__, __CP_SUPABASE_KEY__, __CP_VERSION__ */
export const CONFIG = {
  supabaseUrl: (typeof __CP_SUPABASE_URL__ !== 'undefined' ? __CP_SUPABASE_URL__ : '').replace(/\/+$/, ''),
  supabaseKey: typeof __CP_SUPABASE_KEY__ !== 'undefined' ? __CP_SUPABASE_KEY__ : '',
  version: typeof __CP_VERSION__ !== 'undefined' ? __CP_VERSION__ : 'dev',
};
