// Browser rendering/official-SDK bridge test, not a released-host or CSP qualification.
// The same-origin test frame permits DOM assertions; production isolation belongs to the host.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
assert.equal(args.length, 8, 'Required: --dependencies DIR --core DIR --chrome FILE --output NEW_DIR');
const options = {};
for (let index = 0; index < args.length; index += 2) {
  assert.ok(['--dependencies', '--core', '--chrome', '--output'].includes(args[index]));
  assert.ok(!Object.hasOwn(options, args[index]));
  options[args[index]] = path.resolve(args[index + 1]);
}
const output = options['--output'];
await mkdir(output);
const profile = path.join(output, 'private-profile');
const helper = name => import(pathToFileURL(path.join(options['--core'],
  'verification/interoperability/inspector', name)).href);
const { connectCdp } = await helper('cdp.mjs');
const { startProcess } = await helper('process.mjs');
const { chromeArguments } = await helper('web-probe.mjs');
const require = createRequire(path.join(options['--dependencies'], 'package.json'));
const { rolldown } = await import(pathToFileURL(require.resolve('rolldown')).href);
const sdk = JSON.parse(await readFile(path.join(options['--dependencies'],
  'node_modules/@modelcontextprotocol/ext-apps/package.json')));
assert.equal(sdk.version, '2.0.0');
const html = await readFile(path.join(directory, '../src/main/resources/mcp/apps/catalog.html'));
const digest = value => createHash('sha256').update(value).digest('hex');
const bundle = await rolldown({ input: path.join(directory, 'browser-host.mjs'), platform: 'browser',
  resolve: { alias: { '@modelcontextprotocol/ext-apps/app-bridge':
    require.resolve('@modelcontextprotocol/ext-apps/app-bridge') } } });
let host;
try {
  const built = await bundle.generate({ format: 'es', minify: { compress: { dropConsole: true } } });
  assert.equal(built.output.length, 1);
  host = built.output[0].code;
} finally { await bundle.close(); }

const page = '<!doctype html><meta charset="utf-8"><title>Toy Store App browser check</title>' +
  '<style>body{margin:0;background:#f5f3ee}iframe{border:0;width:100%;height:920px}</style>' +
  '<iframe id="app" title="Toy Store catalog" sandbox="allow-scripts allow-same-origin"></iframe>' +
  '<script type="module" src="/host.js"></script>';
let unexpectedRequests = 0;
const server = createServer((request, response) => {
  const routes = { '/': ['text/html', page], '/host.js': ['text/javascript', host],
    '/app.html': ['text/html', html], '/favicon.ico': ['image/x-icon', ''] };
  const route = routes[request.url];
  if (request.method !== 'GET' || !route) {
    unexpectedRequests++;
    response.writeHead(404).end();
    return;
  }
  response.writeHead(200, { 'Content-Type': route[0] + '; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(route[1]);
});
server.requestTimeout = 5000;
server.headersTimeout = 5000;
let browser, cdp, sessionId, exceptionCount = 0, blockedRequests = 0;
const exceptions = [];
const localeChecks = [];
let status = 'FAILED';
let version;
const failures = [];
const stop = () => { failures.push('INTERRUPTED'); void browser?.stop(); };
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
try {
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const origin = 'http://127.0.0.1:' + server.address().port;
  browser = startProcess(options['--chrome'], chromeArguments(profile), {
    cwd: output, env: { ...process.env }, timeoutMs: 60000, maxOutputBytes: 1024 * 1024,
  });
  const websocket = await new Promise((resolve, reject) => {
    let stderr = '';
    const timer = setTimeout(() => reject(new Error('Chrome startup timed out')), 10000);
    browser.child.stderr.on('data', chunk => {
      stderr += chunk.toString();
      const match = /DevTools listening on (ws:\/\/127\.0\.0\.1:[0-9]+\/devtools\/browser\/[a-zA-Z0-9-]+)/.exec(stderr);
      if (match) { clearTimeout(timer); resolve(match[1]); }
    });
    browser.completion.then(() => { clearTimeout(timer); reject(new Error('Chrome exited before readiness')); },
      error => { clearTimeout(timer); reject(error); });
  });
  cdp = await connectCdp(websocket);
  version = await cdp.send('Browser.getVersion');
  const { targetId } = await cdp.send('Target.createTarget', { url: 'about:blank' });
  ({ sessionId } = await cdp.send('Target.attachToTarget', { targetId, flatten: true }));
  await cdp.send('Page.enable', {}, sessionId);
  await cdp.send('Runtime.enable', {}, sessionId);
  cdp.on('Runtime.exceptionThrown', event => {
    exceptionCount++;
    // Only this isolated fixture's synthetic data enters the browser.
    exceptions.push(event.exceptionDetails.exception?.description ?? event.exceptionDetails.text);
  });
  cdp.on('Fetch.requestPaused', async (event, session) => {
    const allowed = event.request.method === 'GET' &&
      [origin + '/', origin + '/host.js', origin + '/app.html', origin + '/favicon.ico'].includes(event.request.url);
    if (!allowed) blockedRequests++;
    await cdp.send(allowed ? 'Fetch.continueRequest' : 'Fetch.failRequest', {
      requestId: event.requestId, ...(!allowed ? { errorReason: 'BlockedByClient' } : {}),
    }, session);
  });
  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] }, sessionId);
  const evaluate = async expression => {
    const result = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true }, sessionId);
    assert.ok(!result.exceptionDetails, 'Browser assertion expression failed');
    return result.result.value;
  };
  const wait = async expression => {
    const deadline = Date.now() + 10000;
    while (Date.now() < deadline) {
      if (await evaluate(expression)) return;
      await sleep(25);
    }
    const observation = await evaluate(`({ host: window.catalogTest?.state,
      appState: document.getElementById('app').contentDocument?.getElementById('catalog-root')?.dataset.state,
      disabled: document.getElementById('app').contentDocument?.getElementById('catalog-refresh')?.disabled })`);
    throw new Error('Browser state timed out: ' + expression + ' ' + JSON.stringify(observation));
  };
  const doc = "document.getElementById('app').contentDocument";
  const node = id => doc + ".getElementById('" + id + "')";
  const state = node('catalog-root') + '?.dataset.state';
  const ready = "window.catalogTest?.state.initialized && " + state + " === 'ready'";
  await cdp.send('Page.navigate', { url: origin + '/' }, sessionId);
  await wait(ready);
  assert.equal(await evaluate(node('catalog-items') + '.children.length'), 2);
  assert.match(await evaluate(node('catalog-items') + '.textContent'), /Comète Kite/);
  assert.match(await evaluate(node('catalog-items') + '.textContent'), /\$22\.50/);
  await writeFile(path.join(output, 'desktop.png'), Buffer.from(
    (await cdp.send('Page.captureScreenshot', { format: 'png' }, sessionId)).data, 'base64'));
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: 390, height: 844, deviceScaleFactor: 1, mobile: false,
  }, sessionId);
  await wait(doc + '.documentElement.clientWidth === 390');
  assert.ok(await evaluate(doc + '.documentElement.scrollWidth <= ' + doc + '.documentElement.clientWidth'),
    'Mobile layout overflows horizontally');
  await writeFile(path.join(output, 'mobile.png'), Buffer.from(
    (await cdp.send('Page.captureScreenshot', { format: 'png' }, sessionId)).data, 'base64'));
  // Deliberately omit allow-forms from the iframe sandbox: filtering must use
  // the bridge without relying on native HTML form submission permissions.
  await evaluate(node('catalog-query') + ".value = 'Comète'; " + node('catalog-refresh') + '.click()');
  await wait("window.catalogTest.state.calls.length === 1 && " + state + " === 'ready'");
  assert.deepEqual(await evaluate('window.catalogTest.state.calls[0]'),
    { name: 'list_toys', arguments: { query: 'Comète' } });
  assert.equal(await evaluate(node('catalog-items') + '.children.length'), 1);
  await evaluate("window.catalogTest.state.mode = 'hostile'; " + node('catalog-query') + '.focus()');
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 }, sessionId);
  await cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13 }, sessionId);
  await wait("window.catalogTest.state.calls.length === 2 && " + state + " === 'ready'");
  assert.match(await evaluate(node('catalog-items') + '.textContent'), /<img src=x/);
  assert.equal(await evaluate(node('catalog-items') + ".querySelectorAll('img,script').length"), 0);
  await evaluate("window.catalogTest.state.mode = 'error'; " + node('catalog-refresh') + '.click()');
  await wait(state + " === 'error'");
  assert.equal(await evaluate(node('catalog-items') + '.children.length'), 0);
  assert.doesNotMatch(await evaluate(node('catalog-root') + '.textContent'), /PRIVATE_ERROR_CANARY|Comète Kite/);
  // Navigation replaces the test host; seal its handled-error observation too.
  assert.equal(await evaluate('window.catalogTest.state.failure'), false);

  await cdp.send('Page.navigate', { url: origin + '/' }, sessionId);
  await wait(ready);
  await evaluate("window.catalogTest.state.mode = 'pending'; " + node('catalog-refresh') + '.click()');
  await wait("typeof window.catalogTest.state.release === 'function' && " + state + " === 'pending'");
  await evaluate('window.catalogTest.changeContext()');
  await wait(state + " === 'invalidated'");
  await evaluate('window.catalogTest.state.release()');
  await sleep(200);
  assert.equal(await evaluate(node('catalog-items') + '.children.length'), 0);
  assert.equal(await evaluate(state), 'invalidated');
  assert.equal(await evaluate('window.catalogTest.state.failure'), false);

  // Each new document gets only explicit synthetic inputs. A fixture ID keeps
  // readiness from accidentally matching the outgoing page during navigation.
  let fixtureScript;
  let fixtureId = 0;
  const loadFixture = async (configuration, expectedState = 'ready') => {
    if (fixtureScript)
      await cdp.send('Page.removeScriptToEvaluateOnNewDocument', { identifier: fixtureScript }, sessionId);
    const id = ++fixtureId;
    ({ identifier: fixtureScript } = await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
      source: 'window.catalogFixtureOptions = ' + JSON.stringify({ ...configuration, id }) + ';',
    }, sessionId));
    await cdp.send('Page.navigate', { url: origin + '/' }, sessionId);
    await wait(`window.catalogTest?.state.fixtureId === ${id} && window.catalogTest.state.initialized && `
      + state + ` === '${expectedState}'`);
  };
  const assertLanguage = async (locale, button) => {
    assert.equal(await evaluate(doc + '.documentElement.lang'), locale);
    assert.equal(await evaluate(doc + '.documentElement.dir'), 'ltr');
    assert.equal(await evaluate(node('catalog-refresh') + '.textContent'), button);
  };
  for (const sample of [
    { locale: 'de-DE', hostLocale: 'pt-BR', button: 'Aktualisieren', price: '22,50 $', date: '21. September 2026, 14:00' },
    { locale: 'pt-BR', hostLocale: 'de-DE', button: 'Atualizar', price: 'US$ 22,50', date: '21 de setembro de 2026, 09:00' },
  ]) {
    await loadFixture(sample);
    await assertLanguage(sample.locale, sample.button);
    const cardText = await evaluate(node('catalog-items') + '.textContent');
    assert.ok(cardText.includes(sample.price));
    assert.ok(cardText.includes(sample.date));
    assert.doesNotMatch(await evaluate(doc + '.title'), /Toy Store catalog/);
    assert.doesNotMatch(await evaluate(node('catalog-view') + ".getAttribute('aria-label')"), /^Toy catalog$/);
    assert.doesNotMatch(await evaluate(node('catalog-query') + '.placeholder'), /^All toys$/);
    for (const [layout, width, height] of [['desktop', 1280, 900], ['mobile', 390, 844]]) {
      await cdp.send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: false }, sessionId);
      await wait(doc + `.documentElement.clientWidth === ${width}`);
      assert.ok(await evaluate(doc + '.documentElement.scrollWidth <= ' + doc + '.documentElement.clientWidth'),
        sample.locale + ' layout overflows horizontally');
      await writeFile(path.join(output, `${layout}-${sample.locale}.png`), Buffer.from(
        (await cdp.send('Page.captureScreenshot', { format: 'png' }, sessionId)).data, 'base64'));
    }
    await evaluate("window.catalogTest.state.mode = 'pending'; " + node('catalog-refresh') + '.click()');
    await wait(state + " === 'pending' && typeof window.catalogTest.state.release === 'function'");
    await assertLanguage(sample.locale, sample.button);
    assert.doesNotMatch(await evaluate(node('catalog-status') + '.textContent'), /^Refreshing/);
    assert.equal(await evaluate(node('catalog-items') + '.children.length'), 0);
    assert.deepEqual(await evaluate('window.catalogTest.state.calls[0]'), { name: 'list_toys', arguments: {} });
    // A newly authorized result can change copy; host preferences cannot.
    const replacement = sample.locale === 'de-DE' ? 'pt-BR' : 'de-DE';
    const replacementButton = replacement === 'de-DE' ? 'Aktualisieren' : 'Atualizar';
    await evaluate(`window.catalogTest.state.locale = '${replacement}'; window.catalogTest.state.release()`);
    await wait(state + " === 'ready' && " + doc + `.documentElement.lang === '${replacement}'`);
    await assertLanguage(replacement, replacementButton);
    await evaluate("window.catalogTest.state.mode = 'error'; " + node('catalog-refresh') + '.click()');
    await wait(state + " === 'error'");
    await assertLanguage(replacement, replacementButton);
    assert.doesNotMatch(await evaluate(node('catalog-root') + '.textContent'), /PRIVATE_ERROR_CANARY|Comète Kite|The catalog is unavailable/);
    assert.equal(await evaluate('window.catalogTest.state.failure'), false);
    localeChecks.push(sample.locale);
  }

  await loadFixture({ locale: 'de-DE', hostLocale: 'pt-BR' });
  await evaluate("window.catalogTest.state.mode = 'pending'; " + node('catalog-refresh') + '.click()');
  await wait(state + " === 'pending' && typeof window.catalogTest.state.release === 'function'");
  await evaluate("window.catalogTest.changeContext('ja-JP')");
  await wait(state + " === 'invalidated'");
  await assertLanguage('de-DE', 'Aktualisieren');
  assert.doesNotMatch(await evaluate(node('catalog-status') + '.textContent'), /^Your viewing context/);
  await evaluate("window.catalogTest.state.locale = 'pt-BR'; window.catalogTest.state.release()");
  await sleep(200);
  await assertLanguage('de-DE', 'Aktualisieren');
  assert.equal(await evaluate(node('catalog-items') + '.children.length'), 0);
  assert.equal(await evaluate('window.catalogTest.state.failure'), false);
  localeChecks.push('host-change-clears-without-relocalizing');

  for (const fixture of [{ omitLocale: true }, { locale: 'fr-FR' }]) {
    await loadFixture({ ...fixture, hostLocale: 'pt-BR' });
    await assertLanguage('en-US', 'Refresh');
    assert.equal(await evaluate(node('catalog-heading') + '.textContent'), 'Browse the catalog');
    assert.equal(await evaluate('window.catalogTest.state.failure'), false);
    localeChecks.push(fixture.omitLocale ? 'missing-locale-fallback' : 'unsupported-locale-fallback');
  }
  await loadFixture({ locale: '<img src=x onerror=alert(1)>' }, 'error');
  assert.equal(await evaluate(node('catalog-items') + '.children.length'), 0);
  assert.doesNotMatch(await evaluate(node('catalog-root') + '.textContent'), /<img|Comète Kite/);
  localeChecks.push('malformed-locale-rejected');
  assert.equal(exceptionCount, 0);
  assert.equal(blockedRequests, 0);
  assert.equal(unexpectedRequests, 0);
  assert.equal(await evaluate('window.catalogTest.state.failure'), false);
  assert.equal(digest(await readFile(path.join(directory, '../src/main/resources/mcp/apps/catalog.html'))), digest(html));
  assert.deepEqual(failures, []);
  status = 'PASSED';
} catch (error) {
  failures.push(error.message);
} finally {
  try { if (cdp) await cdp.send('Browser.close'); } catch { /* independent group cleanup below */ }
  try { await cdp?.close(); } catch { /* stop handles browser exit */ }
  // Let a successful graceful close finish before group cleanup. Signalling
  // Chrome while it is exiting can leave helper processes awaiting reaping.
  try { if (browser) await Promise.race([browser.completion, sleep(3000)]); } catch { /* stop below */ }
  try { await browser?.stop(); } catch { failures.push('BROWSER_CLEANUP_FAILED'); }
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
  await rm(profile, { recursive: true, force: true });
  process.off('SIGINT', stop);
  process.off('SIGTERM', stop);
  if (exceptionCount || blockedRequests || unexpectedRequests)
    failures.push('BROWSER_OBSERVATIONS_FAILED');
  if (failures.length) status = 'FAILED';
  const receipt = { status, scope: 'SYNTHETIC_BROWSER_AND_OFFICIAL_SDK_BRIDGE',
    releaseHostQualification: false, candidateHtmlSha256: digest(html), sdk: sdk.version,
    browser: version?.product, localeChecks, exceptionCount, exceptions, blockedRequests, unexpectedRequests, failures };
  await writeFile(path.join(output, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
  console.log(JSON.stringify(receipt));
  process.exitCode = status === 'PASSED' ? 0 : 1;
}
