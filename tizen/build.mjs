// Builds the Samsung TV app: bundles the code for old TV browsers (Chromium 56, 2018 models),
// copies pictures and fonts and packs everything into dist/couchpotato.wgt.
import { build } from 'esbuild';
import { mkdirSync, rmSync, cpSync, readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, relative } from 'node:path';
import { zipSync } from 'fflate';

const out = 'dist/app';
const version = process.env.CP_VERSION || '1.0.0';
rmSync('dist', { recursive: true, force: true });
mkdirSync(join(out, 'img'), { recursive: true });
mkdirSync(join(out, 'fonts'), { recursive: true });

await build({
  entryPoints: ['src/main.js'],
  bundle: true,
  minify: true,
  format: 'iife',
  target: ['chrome56'],
  outfile: join(out, 'app.js'),
  define: {
    __CP_SUPABASE_URL__: JSON.stringify(process.env.SUPABASE_URL || ''),
    __CP_SUPABASE_KEY__: JSON.stringify(process.env.SUPABASE_PUBLISHABLE_KEY || ''),
    __CP_VERSION__: JSON.stringify(version),
  },
  legalComments: 'none',
});

cpSync('static/index.html', join(out, 'index.html'));
cpSync('static/style.css', join(out, 'style.css'));
cpSync('static/icon.png', join(out, 'icon.png'));
for (let i = 0; i < 12; i++) cpSync(`../ios/Couchpotato/Assets.xcassets/Animal${i}.imageset/a.png`, join(out, 'img', `animal${i}.png`));
for (const f of ['inter_400.ttf', 'inter_600.ttf', 'inter_700.ttf', 'baloo2_800.ttf']) cpSync(join('../app/src/main/res/font', f), join(out, 'fonts', f));
writeFileSync(join(out, 'config.xml'), readFileSync('static/config.xml', 'utf8').replace(/\$VERSION/g, version));
for (const f of ['../FONT_LICENSE_Baloo2.txt', '../FONT_LICENSE_Inter.txt']) if (existsSync(f)) cpSync(f, join(out, f.slice(3)));

// widget = zip of the folder
const files = {};
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p);
    else files[relative(out, p).split('\\').join('/')] = new Uint8Array(readFileSync(p));
  }
})(out);
writeFileSync('dist/couchpotato.wgt', zipSync(files, { level: 9 }));
console.log(`dist/couchpotato.wgt (${version}) – ${Object.keys(files).length} files`);
