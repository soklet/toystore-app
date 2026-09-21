export const BRIDGE_TIMEOUT_MS = 10_000;
export const MAX_QUERY_LENGTH = 256;
export const MAX_DISPLAY_ITEMS = 2_000;

const COPY = Object.freeze({
  'en-US': Object.freeze({
    title: 'Toy Store catalog', eyebrow: 'Toy Store · Read-only', heading: 'Browse the catalog',
    intro: 'A little inspiration for the next playtime.', queryLabel: 'Name starts with',
    placeholder: 'All toys', refresh: 'Refresh', viewLabel: 'Toy catalog', added: 'Added',
    footer: 'Read-only view. Prices and dates are formatted by Toy Store. Refresh uses your current authorized connection.',
    waiting: 'Connecting to the catalog…', pending: 'Refreshing the catalog…',
    error: 'The catalog is unavailable. Reopen this view to try again.',
    invalidated: 'Your viewing context changed. Reopen this view to refresh the catalog.',
    closed: 'This catalog view is closed.', ready: '',
  }),
  'de-DE': Object.freeze({
    title: 'Toy Store – Katalog', eyebrow: 'Toy Store · Schreibgeschützt', heading: 'Im Katalog stöbern',
    intro: 'Ein wenig Inspiration für die nächste Spielzeit.', queryLabel: 'Name beginnt mit',
    placeholder: 'Alle Spielzeuge', refresh: 'Aktualisieren', viewLabel: 'Spielzeugkatalog', added: 'Hinzugefügt am',
    footer: 'Schreibgeschützte Ansicht. Preise und Datumsangaben werden von Toy Store formatiert. Die Aktualisierung verwendet Ihre aktuelle autorisierte Verbindung.',
    waiting: 'Verbindung zum Katalog wird hergestellt…', pending: 'Katalog wird aktualisiert…',
    error: 'Der Katalog ist nicht verfügbar. Öffnen Sie diese Ansicht erneut, um es noch einmal zu versuchen.',
    invalidated: 'Ihr Anzeigekontext hat sich geändert. Öffnen Sie diese Ansicht erneut, um den Katalog zu aktualisieren.',
    closed: 'Diese Katalogansicht ist geschlossen.', ready: '',
  }),
  'pt-BR': Object.freeze({
    title: 'Catálogo da Toy Store', eyebrow: 'Toy Store · Somente leitura', heading: 'Explore o catálogo',
    intro: 'Um pouco de inspiração para a próxima brincadeira.', queryLabel: 'Nome começa com',
    placeholder: 'Todos os brinquedos', refresh: 'Atualizar', viewLabel: 'Catálogo de brinquedos', added: 'Adicionado em',
    footer: 'Visualização somente para leitura. Os preços e as datas são formatados pela Toy Store. A atualização usa sua conexão autorizada atual.',
    waiting: 'Conectando ao catálogo…', pending: 'Atualizando o catálogo…',
    error: 'O catálogo não está disponível. Reabra esta visualização para tentar novamente.',
    invalidated: 'Seu contexto de visualização mudou. Reabra esta visualização para atualizar o catálogo.',
    closed: 'Esta visualização do catálogo está fechada.', ready: '',
  }),
});

function localeFor(data) {
  // Only the admitted server result selects UI text. An older server may omit
  // locale; a future server may use a valid language this bundle does not know.
  if (!Object.hasOwn(data, 'locale')) return 'en-US';
  if (typeof data.locale !== 'string' || !data.locale.length || data.locale.length > 128)
    throw new Error('CATALOG_RESULT_INVALID');
  let locale;
  try { [locale] = Intl.getCanonicalLocales(data.locale); }
  catch { throw new Error('CATALOG_RESULT_INVALID'); }
  return Object.hasOwn(COPY, locale) ? locale : 'en-US';
}

function displayText(value, maximum = 1_024) {
  if (typeof value !== 'string' || value.length === 0 || value.length > maximum)
    throw new Error('CATALOG_RESULT_INVALID');
  return value;
}

// Only select display fields. Nothing from the tool response becomes markup,
// a URL, a CSS value, an identity, or a new authorization decision.
export function projectCatalog(result) {
  const data = result?.structuredContent;
  if (result?.isError || !data || typeof data !== 'object' || Array.isArray(data)
      || !Array.isArray(data.toys) || data.toys.length > MAX_DISPLAY_ITEMS)
    throw new Error('CATALOG_RESULT_INVALID');
  const locale = localeFor(data);
  // show_toy_catalog appends catalog details for plain-text clients. The cards
  // already contain those details, so retain only its count heading here.
  const summary = displayText(data.summary, 262_144).split('\n', 1)[0];
  const toys = data.toys.map(toy => {
    if (!toy || typeof toy !== 'object' || Array.isArray(toy)
        || typeof toy.currencyCode !== 'string' || !/^[A-Z]{3}$/.test(toy.currencyCode))
      throw new Error('CATALOG_RESULT_INVALID');
    return Object.freeze({ name: displayText(toy.name), priceDescription: displayText(toy.priceDescription),
      currencyCode: toy.currencyCode, createdAtDescription: displayText(toy.createdAtDescription) });
  });
  return Object.freeze({ locale, summary, toys: Object.freeze(toys) });
}

export function mountCatalog({ app, document, timers = globalThis }) {
  const nodes = Object.fromEntries(['root', 'filter', 'query', 'refresh', 'status', 'view', 'summary', 'items',
    'eyebrow', 'heading', 'intro', 'query-label', 'footer']
    .map(id => [id, document.getElementById(`catalog-${id}`)]));
  if (Object.values(nodes).some(node => !node)) throw new Error('CATALOG_SHELL_INVALID');
  let copy = COPY['en-US'];
  let state = 'waiting';
  let connected = false;
  let terminal = false;
  let initialResultSeen = false;
  let generation = 0;
  let pending;
  let initialTimer;
  let hostPreferences;
  const browser = document.defaultView;
  let resizeObserver;
  let resizeFrame;
  let previousSize;

  function applyLocale(locale) {
    copy = COPY[locale];
    document.title = copy.title;
    document.documentElement.setAttribute('lang', locale);
    document.documentElement.setAttribute('dir', 'ltr');
    for (const name of ['eyebrow', 'heading', 'intro', 'refresh', 'footer']) nodes[name].textContent = copy[name];
    nodes['query-label'].textContent = copy.queryLabel;
    nodes.query.setAttribute('placeholder', copy.placeholder);
    nodes.view.setAttribute('aria-label', copy.viewLabel);
  }

  function clear(nextState) {
    state = nextState;
    nodes.root.dataset.state = state;
    nodes.root.setAttribute('aria-busy', String(state === 'waiting' || state === 'pending'));
    nodes.view.hidden = true;
    nodes.summary.textContent = '';
    nodes.items.replaceChildren();
    nodes.query.disabled = true;
    nodes.refresh.disabled = true;
    nodes.status.textContent = copy[state];
    nodes.status.hidden = state === 'ready';
  }

  function stop(nextState, closeBridge = true) {
    if (terminal) return;
    terminal = true;
    connected = false;
    generation += 1;
    timers.clearTimeout(initialTimer);
    pending?.abort();
    resizeObserver?.disconnect();
    if (resizeFrame !== undefined) browser.cancelAnimationFrame(resizeFrame);
    resizeFrame = undefined;
    clear(nextState);
    nodes.query.value = '';
    if (closeBridge) Promise.resolve().then(() => app.close()).catch(() => {});
  }

  function observeSize() {
    if (!browser?.ResizeObserver || !browser.requestAnimationFrame || !browser.cancelAnimationFrame
        || !document.body || typeof app.sendSizeChanged !== 'function') return;
    const schedule = () => {
      if (terminal || !connected || resizeFrame !== undefined) return;
      resizeFrame = browser.requestAnimationFrame(() => {
        resizeFrame = undefined;
        if (terminal || !connected) return;
        const bounds = document.body.getBoundingClientRect();
        const size = { width: Math.ceil(bounds.width), height: Math.ceil(bounds.height) };
        if (![size.width, size.height].every(value => Number.isFinite(value) && value > 0)
            || (size.width === previousSize?.width && size.height === previousSize?.height)) return;
        previousSize = size;
        // Closing may race an already queued frame or notification. Guard the
        // last asynchronous hop and always handle a rejected bridge send.
        Promise.resolve().then(() => {
          if (!terminal && connected) return app.sendSizeChanged(size);
        }).catch(() => stop('error'));
      });
    };
    resizeObserver = new browser.ResizeObserver(schedule);
    resizeObserver.observe(document.body);
    resizeObserver.observe(document.documentElement);
    schedule();
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function render(result) {
    const data = projectCatalog(result);
    applyLocale(data.locale);
    clear('ready');
    nodes.summary.textContent = data.summary;
    const cards = data.toys.map(toy => {
      const card = element('li', 'toy');
      const price = element('p', 'price');
      price.append(element('span', 'amount', toy.priceDescription), element('span', 'currency', toy.currencyCode));
      card.append(element('h2', undefined, toy.name), price,
        element('p', 'created', `${copy.added} ${toy.createdAtDescription}`));
      return card;
    });
    nodes.items.replaceChildren(...cards);
    nodes.view.hidden = false;
    nodes.query.disabled = !connected;
    nodes.refresh.disabled = !connected;
  }

  async function bounded(operation) {
    const controller = new AbortController();
    pending = controller;
    let timer;
    const timeout = new Promise((resolve, reject) => {
      timer = timers.setTimeout(() => {
        controller.abort();
        reject(new Error('CATALOG_BRIDGE_TIMEOUT'));
      }, BRIDGE_TIMEOUT_MS);
      controller.signal.addEventListener('abort', () => reject(new Error('CATALOG_BRIDGE_ABORTED')), { once: true });
    });
    try {
      return await Promise.race([operation({ timeout: BRIDGE_TIMEOUT_MS, signal: controller.signal }), timeout]);
    } finally {
      timers.clearTimeout(timer);
      if (pending === controller) pending = undefined;
    }
  }

  async function refresh(event) {
    event?.preventDefault();
    if (terminal || !connected || state !== 'ready') return;
    const query = nodes.query.value.trim();
    if (query.length > MAX_QUERY_LENGTH) { stop('error'); return; }
    const started = ++generation;
    clear('pending');
    try {
      // The host forwards this request using the existing authenticated MCP
      // connection. Never send browser-supplied identity or locale overrides.
      const result = await bounded(options => app.callServerTool({ name: 'list_toys',
        arguments: query ? { query } : {} }, options));
      if (!terminal && generation === started) render(result);
    } catch {
      if (!terminal && generation === started) stop('error');
    }
  }

  app.ontoolinput = input => {
    if (terminal || initialResultSeen) return;
    const query = input?.arguments?.query;
    if (query !== undefined && (typeof query !== 'string' || query.length > MAX_QUERY_LENGTH)) {
      stop('error'); return;
    }
    nodes.query.value = query ?? '';
  };
  app.ontoolresult = result => {
    if (terminal || initialResultSeen) return;
    initialResultSeen = true;
    timers.clearTimeout(initialTimer);
    try { render(result); } catch { stop('error'); }
  };
  app.ontoolcancelled = () => stop('error');
  app.onerror = () => stop('error');
  app.onclose = () => stop('error', false);
  app.onteardown = async () => { stop('closed', false); return {}; };
  app.onhostcontextchanged = context => {
    if (terminal || !context || typeof context !== 'object') return;
    if (['locale', 'timeZone'].some(key => Object.hasOwn(context, key)
        && (!hostPreferences || context[key] !== hostPreferences[key]))) stop('invalidated');
  };
  nodes.filter.addEventListener('submit', refresh);
  // Apps are commonly sandboxed without allow-forms. Do not depend on native
  // form submission to dispatch the bridge call (and never navigate the frame).
  nodes.refresh.addEventListener('click', refresh);
  nodes.query.addEventListener('keydown', event => {
    if (event.key === 'Enter') {
      event.preventDefault();
      void refresh();
    }
  });
  applyLocale('en-US');
  clear('waiting');
  const ready = (async () => {
    try {
      await bounded(options => app.connect(undefined, options));
      if (terminal) return;
      const context = app.getHostContext() ?? {};
      hostPreferences = { locale: context.locale, timeZone: context.timeZone };
      connected = true;
      observeSize();
      nodes.query.disabled = state !== 'ready';
      nodes.refresh.disabled = state !== 'ready';
      if (!initialResultSeen) initialTimer = timers.setTimeout(() => stop('error'), BRIDGE_TIMEOUT_MS);
    } catch { stop('error'); }
  })();
  return Object.freeze({ ready, dispose: () => stop('closed') });
}
