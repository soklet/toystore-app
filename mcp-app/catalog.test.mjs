import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { argumentsForBuild, inlineBundle, MAX_HTML_BYTES, OUTPUT } from './build.mjs';
import { BRIDGE_TIMEOUT_MS, MAX_DISPLAY_ITEMS, mountCatalog, projectCatalog } from './catalog.mjs';

const result = (name = 'Comète Kite', locale) => ({ structuredContent: { ...(locale === undefined ? {} : { locale }), summary: 'Found 1 toy.', toys: [{
  toyId: 'toy-1', name, price: 22.5, priceDescription: '22,50 €', currencyCode: 'EUR',
  currencySymbol: '€', currencyDescription: 'Euro', createdAt: '2026-09-21T12:00:00Z',
  createdAtDescription: '21. September 2026, 14:00',
}] } });
const turn = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

class Node {
  constructor(tag = 'div') {
    this.tagName = tag; this.children = []; this.dataset = {}; this.attributes = {};
    this.listeners = {}; this.hidden = false; this.disabled = false; this.value = ''; this.text = '';
  }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  set innerHTML(value) { throw new Error('UNSAFE_HTML_ASSIGNMENT'); }
  setAttribute(key, value) { this.attributes[key] = value; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.text = ''; this.children = children; }
  addEventListener(name, handler) { this.listeners[name] = handler; }
}

function fixture(overrides = {}, browser) {
  const nodes = Object.fromEntries(['root', 'filter', 'query', 'refresh', 'status', 'view', 'summary', 'items',
    'eyebrow', 'heading', 'intro', 'query-label', 'footer']
    .map(name => [name, new Node()]));
  const document = { getElementById: id => nodes[id.replace('catalog-', '')], createElement: tag => new Node(tag),
    defaultView: browser, body: { getBoundingClientRect: () => ({ width: 390.1, height: 460.3 }) }, documentElement: new Node() };
  const tasks = new Map();
  let nextTimer = 0;
  const timers = { setTimeout(fn, ms) { assert.equal(ms, BRIDGE_TIMEOUT_MS); tasks.set(++nextTimer, fn); return nextTimer; },
    clearTimeout(id) { tasks.delete(id); } };
  const calls = [];
  const app = { connect: async () => {}, getHostContext: () => ({ locale: 'de-DE', timeZone: 'Europe/Berlin' }),
    close: async () => { calls.push('close'); },
    callServerTool: async (request, options) => { calls.push({ request, options }); return result('Maple Blocks'); }, ...overrides };
  const controller = mountCatalog({ app, document, timers });
  return { app, nodes, calls, controller, tasks, document,
    expire() { for (const [key, fn] of [...tasks]) { tasks.delete(key); fn(); } },
    submit() { return nodes.refresh.listeners.click({ preventDefault() {} }); } };
}

function resizeBrowser() {
  const frames = new Map();
  let observer;
  let nextFrame = 0;
  const browser = {
    ResizeObserver: class {
      constructor(callback) { this.callback = callback; this.observed = []; this.disconnected = false; observer = this; }
      observe(node) { this.observed.push(node); }
      disconnect() { this.disconnected = true; }
    },
    requestAnimationFrame(callback) { frames.set(++nextFrame, callback); return nextFrame; },
    cancelAnimationFrame(id) { frames.delete(id); },
  };
  return { browser, frames, observer: () => observer,
    tick() { for (const [id, callback] of [...frames]) { frames.delete(id); callback(); } } };
}

test('projection retains only display strings, preserves server formatting and treats markup as text', () => {
  const projected = projectCatalog(result('<img src=x onerror=alert(1)>'));
  assert.deepEqual(Object.keys(projected.toys[0]), ['name', 'priceDescription', 'currencyCode', 'createdAtDescription']);
  assert.equal(projected.toys[0].name, '<img src=x onerror=alert(1)>');
  assert.equal(projected.toys[0].priceDescription, '22,50 €');
  assert.equal(Object.isFrozen(projected.toys), true);
  assert.equal(Object.isFrozen(projected.toys[0]), true);
});

test('malformed, error and excessive results are rejected atomically; empty catalog is valid', () => {
  for (const invalid of [null, {}, { ...result(), isError: true }, { structuredContent: [] },
    { structuredContent: { summary: 'x', toys: Array(MAX_DISPLAY_ITEMS + 1).fill({}) } },
    { structuredContent: { summary: 'x', toys: [{}] } },
    { structuredContent: { summary: 'x', toys: [{ ...result().structuredContent.toys[0], currencyCode: '<x>' }] } }])
    assert.throws(() => projectCatalog(invalid), /CATALOG_RESULT_INVALID/);
  assert.deepEqual(projectCatalog({ structuredContent: { summary: 'No toys.', toys: [] } }), { locale: 'en-US', summary: 'No toys.', toys: [] });
});

test('only supported server locales select translations; missing and valid unsupported locales use English', () => {
  for (const locale of ['en-US', 'de-DE', 'pt-BR'])
    assert.equal(projectCatalog(result('Toy', locale)).locale, locale);
  assert.equal(projectCatalog(result('Toy', 'de-de')).locale, 'de-DE');
  for (const locale of [undefined, 'fr-FR', 'de-AT', 'pt', 'ar-EG', 'en-US-u-ca-gregory'])
    assert.equal(projectCatalog(result('Toy', locale)).locale, 'en-US');
  for (const locale of [undefined, null, true, 42, [], {}, '', ' de-DE', 'de_DE', '<script>', 'x'.repeat(129)]) {
    const response = result();
    response.structuredContent.locale = locale;
    assert.throws(() => projectCatalog(response), /CATALOG_RESULT_INVALID/);
  }
});

for (const [locale, copy] of [
  ['en-US', { title: 'Toy Store catalog', eyebrow: 'Toy Store · Read-only', heading: 'Browse the catalog',
    intro: 'A little inspiration for the next playtime.', queryLabel: 'Name starts with', placeholder: 'All toys',
    refresh: 'Refresh', viewLabel: 'Toy catalog', added: 'Added',
    footer: 'Read-only view. Prices and dates are formatted by Toy Store. Refresh uses your current authorized connection.' }],
  ['de-DE', { title: 'Toy Store – Katalog', eyebrow: 'Toy Store · Schreibgeschützt', heading: 'Im Katalog stöbern',
    intro: 'Ein wenig Inspiration für die nächste Spielzeit.', queryLabel: 'Name beginnt mit', placeholder: 'Alle Spielzeuge',
    refresh: 'Aktualisieren', viewLabel: 'Spielzeugkatalog', added: 'Hinzugefügt am',
    footer: 'Schreibgeschützte Ansicht. Preise und Datumsangaben werden von Toy Store formatiert. Die Aktualisierung verwendet Ihre aktuelle autorisierte Verbindung.' }],
  ['pt-BR', { title: 'Catálogo da Toy Store', eyebrow: 'Toy Store · Somente leitura', heading: 'Explore o catálogo',
    intro: 'Um pouco de inspiração para a próxima brincadeira.', queryLabel: 'Nome começa com', placeholder: 'Todos os brinquedos',
    refresh: 'Atualizar', viewLabel: 'Catálogo de brinquedos', added: 'Adicionado em',
    footer: 'Visualização somente para leitura. Os preços e as datas são formatados pela Toy Store. A atualização usa sua conexão autorizada atual.' }],
]) test(`${locale} localizes all static UI text and accessibility without reformatting server data`, async () => {
  const f = fixture();
  await f.controller.ready;
  f.app.ontoolresult(result('Comète Kite', locale));
  assert.equal(f.document.title, copy.title);
  assert.deepEqual(f.document.documentElement.attributes, { lang: locale, dir: 'ltr' });
  for (const name of ['eyebrow', 'heading', 'intro', 'refresh', 'footer'])
    assert.equal(f.nodes[name].textContent, copy[name]);
  assert.equal(f.nodes['query-label'].textContent, copy.queryLabel);
  assert.equal(f.nodes.query.attributes.placeholder, copy.placeholder);
  assert.equal(f.nodes.view.attributes['aria-label'], copy.viewLabel);
  assert.equal(f.nodes.items.children[0].children[2].textContent, `${copy.added} 21. September 2026, 14:00`);
  assert.equal(f.nodes.items.children[0].children[1].textContent, '22,50 €EUR');
  assert.equal(f.nodes.summary.textContent, 'Found 1 toy.');
});

test('host locale, initial tool arguments, and browser preferences do not select UI language', async () => {
  const browser = { navigator: { language: 'pt-BR', languages: ['pt-BR'] } };
  const f = fixture({}, browser);
  assert.equal(f.document.documentElement.attributes.lang, 'en-US');
  assert.equal(f.nodes.status.textContent, 'Connecting to the catalog…');
  await f.controller.ready;
  f.app.ontoolinput({ arguments: { query: 'Com', locale: 'de-DE' } });
  assert.equal(f.document.documentElement.attributes.lang, 'en-US');
  f.app.ontoolresult(result('Comète Kite', 'en-US'));
  assert.equal(f.document.documentElement.attributes.lang, 'en-US');
  f.app.onhostcontextchanged({ locale: 'de-DE', timeZone: 'Europe/Berlin' });
  assert.equal(f.nodes.root.dataset.state, 'ready');
  f.app.onhostcontextchanged({ locale: 'pt-BR' });
  assert.equal(f.nodes.root.dataset.state, 'invalidated');
  assert.equal(f.document.documentElement.attributes.lang, 'en-US');
  assert.equal(f.nodes.status.textContent, 'Your viewing context changed. Reopen this view to refresh the catalog.');
  f.app.ontoolresult(result('Late toy', 'pt-BR'));
  assert.equal(f.document.documentElement.attributes.lang, 'en-US');
  assert.equal(f.nodes.items.children.length, 0);
});

test('an authenticated refresh changes language only with a fully valid result; pending retains the selected copy', async () => {
  const pending = deferred();
  const f = fixture({ callServerTool: () => pending.promise });
  await f.controller.ready;
  f.app.ontoolresult(result('Old toy', 'de-DE'));
  const refresh = f.submit();
  assert.equal(f.nodes.status.textContent, 'Katalog wird aktualisiert…');
  assert.equal(f.document.documentElement.attributes.lang, 'de-DE');
  assert.equal(f.nodes.items.children.length, 0);
  pending.resolve(result('New toy', 'pt-BR'));
  await refresh;
  assert.equal(f.document.documentElement.attributes.lang, 'pt-BR');
  assert.equal(f.nodes.refresh.textContent, 'Atualizar');
  assert.match(f.nodes.items.textContent, /New toy.*Adicionado em/);
  assert.doesNotMatch(f.nodes.items.textContent, /Old toy/);
});

test('valid unsupported or older-server refresh restores English copy with its new result', async () => {
  for (const locale of ['fr-FR', undefined]) {
    const f = fixture({ callServerTool: async () => result('New toy', locale) });
    await f.controller.ready;
    f.app.ontoolresult(result('Old toy', 'pt-BR'));
    await f.submit();
    assert.equal(f.document.documentElement.attributes.lang, 'en-US');
    assert.equal(f.nodes.refresh.textContent, 'Refresh');
    assert.match(f.nodes.items.textContent, /New toy.*Added/);
  }
});

test('malformed locale or payload cannot partly switch copy or disclose old data', async () => {
  const malformedToy = result('New toy', 'pt-BR');
  malformedToy.structuredContent.toys[0].currencyCode = 'invalid';
  for (const response of [result('New toy', null), result('New toy', '<de-DE>'), malformedToy]) {
    const f = fixture({ callServerTool: async () => response });
    await f.controller.ready;
    f.app.ontoolresult(result('Private old toy', 'de-DE'));
    await f.submit();
    assert.equal(f.nodes.root.dataset.state, 'error');
    assert.equal(f.document.documentElement.attributes.lang, 'de-DE');
    assert.equal(f.nodes.refresh.textContent, 'Aktualisieren');
    assert.equal(f.nodes.status.textContent, 'Der Katalog ist nicht verfügbar. Öffnen Sie diese Ansicht erneut, um es noch einmal zu versuchen.');
    assert.equal(f.nodes.items.children.length, 0);
    assert.equal(f.nodes.summary.textContent, '');
    f.app.ontoolresult(result('Late toy', 'pt-BR'));
    assert.equal(f.document.documentElement.attributes.lang, 'de-DE');
    assert.equal(f.nodes.items.children.length, 0);
  }
});

test('Brazilian Portuguese pending, errors and closure retain the last server-selected language', async () => {
  const pending = deferred();
  const f = fixture({ callServerTool: () => pending.promise });
  await f.controller.ready;
  f.app.ontoolresult(result('Private toy', 'pt-BR'));
  const refresh = f.submit();
  assert.equal(f.nodes.status.textContent, 'Atualizando o catálogo…');
  pending.reject(new Error('Private diagnostic'));
  await refresh;
  assert.equal(f.nodes.status.textContent, 'O catálogo não está disponível. Reabra esta visualização para tentar novamente.');
  assert.equal(f.document.documentElement.attributes.lang, 'pt-BR');
  assert.equal(f.nodes.items.children.length, 0);
  const g = fixture();
  await g.controller.ready;
  g.app.ontoolresult(result('Toy', 'pt-BR'));
  g.app.onteardown();
  assert.equal(g.nodes.status.textContent, 'Esta visualização do catálogo está fechada.');
  assert.equal(g.document.documentElement.attributes.lang, 'pt-BR');
});

test('App heading omits duplicated plain-text catalog details while cards keep the catalog', () => {
  const response = result();
  response.structuredContent.summary = 'Found 1 toy.\nComète Kite — 22,50 € (EUR)';
  const projected = projectCatalog(response);
  assert.equal(projected.summary, 'Found 1 toy.');
  assert.equal(projected.toys.length, 1);
  assert.equal(response.structuredContent.summary, 'Found 1 toy.\nComète Kite — 22,50 € (EUR)');
});

test('initial result renders accessible text-only cards and input prefix', async () => {
  const f = fixture();
  assert.equal(f.nodes.refresh.disabled, true);
  await f.controller.ready;
  f.app.ontoolinput({ arguments: { query: 'Com' } });
  f.app.ontoolresult(result('<svg/onload=alert(1)>'));
  assert.equal(f.nodes.root.dataset.state, 'ready');
  assert.equal(f.nodes.root.attributes['aria-busy'], 'false');
  assert.equal(f.nodes.view.hidden, false);
  assert.equal(f.nodes.query.value, 'Com');
  assert.equal(f.nodes.refresh.disabled, false);
  assert.equal(f.nodes.items.children[0].children[0].tagName, 'h2');
  assert.match(f.nodes.items.textContent, /<svg\/onload=alert\(1\)>22,50 €EURAdded 21\. September/);
  assert.equal(f.tasks.size, 0);
});

test('Refresh/filter calls only list_toys with optional query; pending clears old data and duplicate submits', async () => {
  const pending = deferred();
  const calls = [];
  const f = fixture({ callServerTool: (request, options) => { calls.push({ request, options }); return pending.promise; } });
  await f.controller.ready;
  f.app.ontoolresult(result());
  f.nodes.query.value = '  Maple  ';
  const refresh = f.submit();
  assert.equal(f.nodes.root.dataset.state, 'pending');
  assert.equal(f.nodes.summary.textContent, '');
  assert.equal(f.nodes.items.children.length, 0);
  assert.equal(f.nodes.refresh.disabled, true);
  await f.submit();
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0].request, { name: 'list_toys', arguments: { query: 'Maple' } });
  assert.equal(calls[0].options.timeout, BRIDGE_TIMEOUT_MS);
  pending.resolve(result('Maple Blocks'));
  await refresh;
  assert.equal(f.nodes.root.dataset.state, 'ready');
  assert.match(f.nodes.items.textContent, /Maple Blocks/);
  assert.equal(f.tasks.size, 0);
});

test('empty filter sends no query and ignores unrelated late tool notifications', async () => {
  const f = fixture();
  await f.controller.ready;
  f.app.ontoolresult(result());
  f.nodes.query.value = '   ';
  await f.submit();
  assert.deepEqual(f.calls[0].request, { name: 'list_toys', arguments: {} });
  f.app.ontoolresult(result('Stale toy'));
  f.app.ontoolinput({ arguments: { query: 'stale' } });
  assert.doesNotMatch(f.nodes.items.textContent, /Stale/);
  assert.equal(f.nodes.query.value, '   ');
});

test('Enter refreshes without native form submission and other keys do not call the bridge', async () => {
  const f = fixture();
  await f.controller.ready;
  f.app.ontoolresult(result());
  f.nodes.query.value = 'Maple';
  let prevented = false;
  f.nodes.query.listeners.keydown({ key: 'x', preventDefault() { prevented = true; } });
  assert.equal(f.calls.length, 0);
  assert.equal(prevented, false);
  f.nodes.query.listeners.keydown({ key: 'Enter', preventDefault() { prevented = true; } });
  await turn();
  assert.equal(prevented, true);
  assert.deepEqual(f.calls[0].request, { name: 'list_toys', arguments: { query: 'Maple' } });
  assert.equal(f.nodes.root.dataset.state, 'ready');
});

test('form submit fallback prevents navigation and preserves the same bridge behavior', async () => {
  const f = fixture();
  await f.controller.ready;
  f.app.ontoolresult(result());
  let prevented = false;
  await f.nodes.filter.listeners.submit({ preventDefault() { prevented = true; } });
  assert.equal(prevented, true);
  assert.deepEqual(f.calls[0].request, { name: 'list_toys', arguments: {} });
});

for (const [name, terminate] of [
  ['disconnect', f => f.app.onclose()],
  ['error', f => f.app.onerror(new Error('do-not-display-server-secret'))],
  ['cancellation', f => f.app.ontoolcancelled({ reason: 'do-not-display-server-secret' })],
  ['context invalidation', f => f.app.onhostcontextchanged({ locale: 'en-US' })],
  ['teardown', f => f.app.onteardown()],
  ['dispose', f => f.controller.dispose()],
]) test(`${name} clears old data and rejects a late refresh result`, async () => {
  const pending = deferred();
  let options;
  const f = fixture({ callServerTool: (request, supplied) => { options = supplied; return pending.promise; } });
  await f.controller.ready;
  f.app.ontoolresult(result('Private old toy', 'de-DE'));
  f.nodes.query.value = 'Private';
  const refresh = f.submit();
  await terminate(f);
  assert.equal(options.signal.aborted, true);
  pending.resolve(result('Private late toy', 'pt-BR'));
  await refresh;
  f.app.ontoolresult(result('Another late toy', 'en-US'));
  assert.equal(f.nodes.items.children.length, 0);
  assert.equal(f.nodes.summary.textContent, '');
  assert.equal(f.nodes.query.value, '');
  assert.equal(f.nodes.view.hidden, true);
  assert.equal(f.nodes.refresh.disabled, true);
  assert.equal(f.document.documentElement.attributes.lang, 'de-DE');
  assert.equal(f.nodes.refresh.textContent, 'Aktualisieren');
  if (f.nodes.root.dataset.state === 'invalidated')
    assert.equal(f.nodes.status.textContent, 'Ihr Anzeigekontext hat sich geändert. Öffnen Sie diese Ansicht erneut, um den Katalog zu aktualisieren.');
  if (f.nodes.root.dataset.state === 'closed')
    assert.equal(f.nodes.status.textContent, 'Diese Katalogansicht ist geschlossen.');
  assert.doesNotMatch(f.nodes.status.textContent, /secret|Private|late/);
  assert.equal(f.tasks.size, 0);
});

test('theme-only or unchanged host preferences keep the view usable', async () => {
  const f = fixture();
  await f.controller.ready;
  f.app.ontoolresult(result());
  f.app.onhostcontextchanged({ theme: 'dark' });
  f.app.onhostcontextchanged({ locale: 'de-DE', timeZone: 'Europe/Berlin' });
  assert.equal(f.nodes.root.dataset.state, 'ready');
});

test('a missing initial result times out; late result cannot reopen the view', async () => {
  const f = fixture();
  await f.controller.ready;
  f.expire();
  await turn();
  f.app.ontoolresult(result());
  assert.equal(f.nodes.root.dataset.state, 'error');
  assert.equal(f.nodes.items.children.length, 0);
});

test('connect and refresh are bounded and clear any private content', async () => {
  const connect = deferred();
  const f = fixture({ connect: () => connect.promise });
  f.expire();
  await f.controller.ready;
  connect.resolve();
  await turn();
  assert.equal(f.nodes.root.dataset.state, 'error');
  const refresh = deferred();
  const g = fixture({ callServerTool: () => refresh.promise });
  await g.controller.ready;
  g.app.ontoolresult(result());
  const done = g.submit();
  g.expire();
  await done;
  refresh.resolve(result());
  await turn();
  assert.equal(g.nodes.root.dataset.state, 'error');
  assert.equal(g.nodes.items.children.length, 0);
});

test('malformed or error refresh fails closed without displaying diagnostic text', async () => {
  const f = fixture({ callServerTool: async () => ({ isError: true, content: [{ type: 'text', text: 'secret diagnostic' }] }) });
  await f.controller.ready;
  f.app.ontoolresult(result());
  await f.submit();
  assert.equal(f.nodes.root.dataset.state, 'error');
  assert.equal(f.nodes.items.children.length, 0);
  assert.doesNotMatch(f.nodes.status.textContent, /secret diagnostic/);
});

test('initial result may precede handshake completion but refresh stays disabled until connected', async () => {
  const connect = deferred();
  const f = fixture({ connect: () => connect.promise });
  f.app.ontoolresult(result());
  assert.equal(f.nodes.refresh.disabled, true);
  connect.resolve();
  await f.controller.ready;
  assert.equal(f.nodes.refresh.disabled, false);
});

test('owned resize reporting starts after connect, coalesces frames, and avoids unchanged notifications', async () => {
  const resize = resizeBrowser();
  const connect = deferred();
  const sizes = [];
  const f = fixture({ connect: () => connect.promise, sendSizeChanged: async size => { sizes.push(size); } }, resize.browser);
  assert.equal(resize.observer(), undefined);
  connect.resolve();
  await f.controller.ready;
  assert.equal(resize.observer().observed.length, 2);
  resize.observer().callback();
  resize.observer().callback();
  assert.equal(resize.frames.size, 1);
  resize.tick();
  await turn();
  assert.deepEqual(sizes, [{ width: 391, height: 461 }]);
  resize.observer().callback();
  resize.tick();
  await turn();
  assert.equal(sizes.length, 1);
  f.controller.dispose();
  assert.equal(resize.observer().disconnected, true);
});

test('close cancels queued resize and late observer/frame callbacks cannot send after disconnect', async () => {
  const resize = resizeBrowser();
  const sizes = [];
  const f = fixture({ sendSizeChanged: async size => { sizes.push(size); } }, resize.browser);
  await f.controller.ready;
  const lateFrame = [...resize.frames.values()][0];
  f.app.onclose();
  assert.equal(resize.observer().disconnected, true);
  assert.equal(resize.frames.size, 0);
  resize.observer().callback();
  lateFrame();
  await turn();
  assert.equal(resize.frames.size, 0);
  assert.deepEqual(sizes, []);
});

test('terminal state between resize frame and send suppresses notification', async () => {
  const resize = resizeBrowser();
  const sizes = [];
  const f = fixture({ sendSizeChanged: async size => { sizes.push(size); } }, resize.browser);
  await f.controller.ready;
  resize.tick();
  f.controller.dispose();
  await turn();
  assert.deepEqual(sizes, []);
});

test('resize rejection racing closure is handled and cannot restore stale content', async () => {
  const resize = resizeBrowser();
  const send = deferred();
  const f = fixture({ sendSizeChanged: () => send.promise }, resize.browser);
  await f.controller.ready;
  f.app.ontoolresult(result());
  resize.tick();
  await turn();
  f.app.onclose();
  send.reject(new Error('Not connected'));
  await turn();
  assert.equal(f.nodes.root.dataset.state, 'error');
  assert.equal(f.nodes.items.children.length, 0);
  assert.equal(resize.observer().disconnected, true);
});

test('build arguments require explicit dependencies and optionally read-only check', () => {
  assert.deepEqual(argumentsForBuild(['--dependencies', '/tmp/dependencies', '--check']), { dependencies: '/tmp/dependencies', check: true });
  for (const args of [[], ['--dependencies', 'relative'], ['--dependencies', '/tmp/deps', '--write'],
    ['--dependencies', '/tmp/deps', '--check', 'extra']]) assert.throws(() => argumentsForBuild(args), /Usage/);
});

test('inline bundle escapes raw-text terminators, keeps exact licenses and rejects active sentinels', () => {
  const template = '<body><!-- TOYSTORE_APP_SCRIPT --><!-- TOYSTORE_APP_LICENSES --></body>';
  const html = inlineBundle(template, 'const s="</ScRiPt>";', 'License\nPermission is hereby granted.\n');
  assert.match(html, /<\\\/ScRiPt>/);
  assert.ok(html.includes('License\nPermission is hereby granted.\n'));
  for (const [source, code, license] of [[template, '"<!--";', 'license'], [template, '"<script>";', 'license'],
    [template, '0;', '-->'], [template.replace('<!-- TOYSTORE_APP_SCRIPT -->', ''), '0;', 'license']])
    assert.throws(() => inlineBundle(source, code, license), /BUILD_HTML_SENTINEL_INVALID/);
});

test('checked-in HTML is a self-contained bounded artifact with notices and no external assets', async () => {
  const html = await readFile(OUTPUT, 'utf8');
  assert.ok(Buffer.byteLength(html) <= MAX_HTML_BYTES);
  assert.equal((html.match(/<script>/g) ?? []).length, 1);
  assert.equal((html.match(/<\/script>/g) ?? []).length, 1);
  assert.doesNotMatch(html, /<(?:script|img|iframe|link)\b[^>]*(?:src|href)=/i);
  assert.doesNotMatch(html, /TOYSTORE_APP_SCRIPT|TOYSTORE_APP_LICENSES/);
  assert.match(html, /@modelcontextprotocol\/ext-apps\/LICENSE/);
  assert.match(html, /Permission is hereby granted/);
  assert.match(html, /<form id="catalog-filter">/);
  assert.match(html, /id="catalog-refresh" type="button"/);
  assert.match(html, /role="status" aria-live="polite"/);
});
