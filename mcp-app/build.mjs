import { createHash } from 'node:crypto';
import { readFile, readdir, realpath, mkdir, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Script } from 'node:vm';

const directory = path.dirname(fileURLToPath(import.meta.url));
export const OUTPUT = path.resolve(directory, '../src/main/resources/mcp/apps/catalog.html');
export const MAX_HTML_BYTES = 512 * 1024;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
// Reviewed published package files, including the native bundler. Generation is
// deliberately pinned to this recipe; serving the checked-in HTML is portable.
const pins = Object.freeze({
  '@modelcontextprotocol/client': ['2.0.0', 'd32253f41a5245732553e67885098eee60f2600cb8fd71169a40e0c86139db09'],
  '@modelcontextprotocol/core': ['2.0.0', 'bdae7a7b1bc378c0129297cf71f79748eb245c29e6863d501ef027f666ee0d83'],
  '@modelcontextprotocol/ext-apps': ['2.0.0', '7f800042aa874da28c8803d8fdb9def15a60b8dddb380d2f993796ecd0eab1fe'],
  '@oxc-project/types': ['0.150.0', '1487aa596c84f94d69efbd7ec4fed02ba4ddff3e482f5bc2f8a8733f9998c166'],
  '@rolldown/binding-darwin-arm64': ['1.2.9', '83c69b6576b08363c970fc9b4c648c8e2dbcaf76e00860caecaebb2dfdb8c659'],
  '@rolldown/pluginutils': ['1.0.1', 'fd1bee7028a0280c6b674a31887af93c7f63733f4bf027b91ceb452124134f1d'],
  'pkce-challenge': ['5.0.1', '3229017c69678bb8a24ef4484d407cd15d641382921766bcebd5a9c72998e649'],
  'rolldown': ['1.2.9', '05f99df5274f99da5b4a83745caca352d22c6ca04b2eac774952b5271cb4d1ea'],
  'zod': ['4.6.5', '984bd10276a45aa9cdbeab4e31a1e593696959f6d19874d1085f07dd693ab1eb'],
});
const manifestPins = Object.freeze({
  'package.json': '8fc642c9ec63e951bf987d4ccf2c7a198a33f350f7d424ad2e95a1f48dbfe2c0',
  'package-lock.json': '37dfcdb1476f7f31c4aeea13971a729588bad7719c67bb8349f7bd8c52d29f29',
});

export function argumentsForBuild(args) {
  if (args.length < 2 || args.length > 3 || args[0] !== '--dependencies'
      || !path.isAbsolute(args[1]) || (args.length === 3 && args[2] !== '--check'))
    throw new Error('Usage: node mcp-app/build.mjs --dependencies /absolute/reviewed/dependencies [--check]');
  return { dependencies: args[1], check: args[2] === '--check' };
}

async function packageFiles(root) {
  if (await realpath(root) !== root) throw new Error('BUILD_DEPENDENCIES_SYMLINKED');
  const files = [];
  async function visit(relative = '', depth = 0) {
    if (depth > 24) throw new Error('BUILD_DEPENDENCIES_TOO_LARGE');
    for (const entry of await readdir(path.join(root, relative), { withFileTypes: true })) {
      const filename = path.join(relative, entry.name);
      if (entry.isDirectory()) await visit(filename, depth + 1);
      else if (entry.isFile()) {
        const content = await readFile(path.join(root, filename));
        files.push({ path: filename, bytes: content.length, sha256: digest(content) });
      } else throw new Error('BUILD_DEPENDENCIES_NONREGULAR');
      if (files.length > 10_000) throw new Error('BUILD_DEPENDENCIES_TOO_LARGE');
    }
  }
  await visit();
  files.sort((a, b) => a.path.localeCompare(b.path, 'en'));
  return files;
}

export async function verifyDependencies(dependencies) {
  if (await realpath(dependencies) !== dependencies) throw new Error('BUILD_DEPENDENCIES_NONCANONICAL');
  for (const [name, expected] of Object.entries(manifestPins))
    if (digest(await readFile(path.join(dependencies, name))) !== expected) throw new Error('BUILD_MANIFEST_CHANGED');
  const verified = new Set();
  for (const [name, [version, expected]] of Object.entries(pins)) {
    const root = path.join(dependencies, 'node_modules', name);
    const manifest = JSON.parse(await readFile(path.join(root, 'package.json'), 'utf8'));
    if (manifest.name !== name || manifest.version !== version) throw new Error('BUILD_PACKAGE_VERSION_CHANGED');
    const files = await packageFiles(root);
    if (digest(JSON.stringify(files)) !== expected) throw new Error('BUILD_PACKAGE_FILES_CHANGED');
    for (const file of files) verified.add(path.join(root, file.path));
  }
  return verified;
}

export function inlineBundle(template, script, licenses) {
  if (template.split('<!-- TOYSTORE_APP_SCRIPT -->').length !== 2
      || template.split('<!-- TOYSTORE_APP_LICENSES -->').length !== 2
      || /<script\b/i.test(template) || !script || /<script\b|<!--/i.test(script)
      || /<!--|-->|<\/?script/i.test(licenses)) throw new Error('BUILD_HTML_SENTINEL_INVALID');
  const escaped = script.replace(/<\/script/gi, match => `<\\/${match.slice(2)}`);
  new Script(escaped);
  const html = template.replace('<!-- TOYSTORE_APP_SCRIPT -->', () => `<script>${escaped}</script>`)
    .replace('<!-- TOYSTORE_APP_LICENSES -->', () => `<!--\n${licenses}\n-->`);
  if (Buffer.byteLength(html) > MAX_HTML_BYTES) throw new Error('BUILD_HTML_TOO_LARGE');
  return html;
}

async function licensesFor(dependencies, modules) {
  const names = [...new Set(modules.map(filename => {
    const parts = path.relative(path.join(dependencies, 'node_modules'), filename).split(path.sep);
    return parts[0].startsWith('@') ? `${parts[0]}/${parts[1]}` : parts[0];
  }))].sort();
  const notices = ['Third-party notices for the Toy Store catalog. SDK console calls removed during minification.'];
  for (const name of names) {
    const root = path.join(dependencies, 'node_modules', name);
    const entries = (await readdir(root, { withFileTypes: true }))
      .filter(entry => /^(?:LICEN[SC]E|COPYING|NOTICE)(?:[.-].*)?$/i.test(entry.name));
    if (!entries.length || entries.some(entry => !entry.isFile())) throw new Error('BUILD_LICENSE_MISSING');
    for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name, 'en')))
      notices.push(`${name}/${entry.name}\n${await readFile(path.join(root, entry.name), 'utf8')}`);
  }
  return notices.join('\n\n');
}

export async function build({ dependencies, check = false }) {
  if (process.version !== 'v26.5.0' || process.platform !== 'darwin' || process.arch !== 'arm64')
    throw new Error('BUILD_REQUIRES_REVIEWED_NODE_26_5_0_MACOS_ARM64');
  const verified = await verifyDependencies(dependencies);
  const sourceNames = ['catalog-entry.mjs', 'catalog.mjs', 'catalog.html'];
  const sources = await Promise.all(sourceNames.map(name => readFile(path.join(directory, name))));
  const require = createRequire(path.join(dependencies, 'package.json'));
  const { rolldown } = await import(pathToFileURL(require.resolve('rolldown')).href);
  const nativeBindings = Object.keys(require.cache).filter(filename => filename.endsWith('.node'));
  if (nativeBindings.length !== 1 || !verified.has(nativeBindings[0])
      || !nativeBindings[0].startsWith(path.join(dependencies, 'node_modules/@rolldown/binding-darwin-arm64/')))
    throw new Error('BUILD_UNVERIFIED_NATIVE_BINDING');
  const bundle = await rolldown({ input: path.join(directory, 'catalog-entry.mjs'), platform: 'browser',
    resolve: { alias: { '@modelcontextprotocol/ext-apps': require.resolve('@modelcontextprotocol/ext-apps') } },
    onLog(level) { if (level === 'warn' || level === 'error') throw new Error('BUILD_BUNDLE_DIAGNOSTIC'); } });
  try {
    const { output } = await bundle.generate({ format: 'iife', codeSplitting: false,
      minify: { compress: { dropConsole: true } } });
    if (output.length !== 1 || output[0].type !== 'chunk' || output[0].imports.length
        || output[0].dynamicImports.length || /\bconsole\s*(?:\.|\[)/.test(output[0].code))
      throw new Error('BUILD_EXTERNAL_OR_LOGGING_CODE');
    const modules = output[0].moduleIds.filter(filename => !sourceNames.some(name => filename === path.join(directory, name)));
    if (modules.some(filename => !verified.has(filename))) throw new Error('BUILD_UNVERIFIED_MODULE');
    const html = inlineBundle(sources[2].toString('utf8'), output[0].code, await licensesFor(dependencies, modules));
    await verifyDependencies(dependencies);
    for (let index = 0; index < sourceNames.length; index++)
      if (!sources[index].equals(await readFile(path.join(directory, sourceNames[index])))) throw new Error('BUILD_SOURCE_CHANGED');
    if (check) {
      if (await readFile(OUTPUT, 'utf8') !== html) throw new Error('BUILD_GENERATED_HTML_STALE');
    } else {
      await mkdir(path.dirname(OUTPUT), { recursive: true });
      await writeFile(OUTPUT, html);
    }
    return { checked: check, bytes: Buffer.byteLength(html), sha256: digest(html), sdk: '2.0.0', bundler: '1.2.9' };
  } finally { await bundle.close(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { console.log(JSON.stringify(await build(argumentsForBuild(process.argv.slice(2))))); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
