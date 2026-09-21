// Test-only host: official AppBridge, synthetic catalog data, no MCP server or credentials.
import { AppBridge, PostMessageTransport } from '@modelcontextprotocol/ext-apps/app-bridge';

const frame = document.getElementById('app');
const toys = [
  { toyId: '00000000-0000-0000-0000-000000000001', name: 'Comète Kite', price: 22.5,
    priceDescription: '$22.50', currencyCode: 'USD', currencySymbol: '$',
    currencyDescription: 'US Dollar', createdAt: '2026-09-21T12:00:00Z',
    createdAtDescription: 'Sep 21, 2026, 12:00 PM' },
  { toyId: '00000000-0000-0000-0000-000000000002', name: 'Maple Blocks', price: 18,
    priceDescription: '$18.00', currencyCode: 'USD', currencySymbol: '$',
    currencyDescription: 'US Dollar', createdAt: '2026-09-20T12:00:00Z',
    createdAtDescription: 'Sep 20, 2026, 12:00 PM' },
];
// Only the isolated browser runner supplies these synthetic fixture options.
const fixture = window.catalogFixtureOptions ?? {};
const state = { calls: [], initialized: false, mode: 'normal', release: undefined, failure: false,
  fixtureId: fixture.id ?? 0,
  locale: fixture.omitLocale ? undefined : Object.hasOwn(fixture, 'locale') ? fixture.locale : 'en-US' };
const formats = {
  'en-US': { summary: count => count + ' toys.', prices: ['$22.50', '$18.00'],
    dates: ['Sep 21, 2026, 12:00 PM', 'Sep 20, 2026, 12:00 PM'] },
  'de-DE': { summary: count => count + ' Spielzeug(e) gefunden.', prices: ['22,50 $', '18,00 $'],
    dates: ['21. September 2026, 14:00', '20. September 2026, 14:00'] },
  'pt-BR': { summary: count => count + ' brinquedo(s) encontrado(s).', prices: ['US$ 22,50', 'US$ 18,00'],
    dates: ['21 de setembro de 2026, 09:00', '20 de setembro de 2026, 09:00'] },
};
const result = values => {
  const format = Object.hasOwn(formats, state.locale) ? formats[state.locale] : formats['en-US'];
  const summary = format.summary(values.length);
  return { content: [{ type: 'text', text: summary }], structuredContent: {
    summary, ...(state.locale === undefined ? {} : { locale: state.locale }),
    toys: values.map(toy => {
      const index = toy.toyId === toys[1].toyId ? 1 : 0;
      return { ...toy, priceDescription: format.prices[index], createdAtDescription: format.dates[index] };
    }),
  } };
};
const bridge = new AppBridge(null, { name: 'Toy Store browser test', version: '1.0' },
  { serverTools: {} }, { hostContext: { locale: fixture.hostLocale ?? 'en-US', timeZone: 'UTC', displayMode: 'inline' } });
bridge.oncalltool = async params => {
  if (params.name !== 'list_toys' || !params.arguments ||
      Object.keys(params.arguments).some(key => key !== 'query') ||
      (params.arguments.query !== undefined && typeof params.arguments.query !== 'string'))
    throw new Error('Unexpected browser test tool call');
  state.calls.push({ name: params.name, arguments: params.arguments });
  if (state.mode === 'error')
    return { isError: true, content: [{ type: 'text', text: 'PRIVATE_ERROR_CANARY' }] };
  if (state.mode === 'pending')
    return await new Promise(resolve => { state.release = () => resolve(result(toys)); });
  if (state.mode === 'hostile')
    return result([{ ...toys[0], name: '<img src=x onerror="globalThis.injected=true"> & <script>bad()</script>' }]);
  const prefix = params.arguments.query ?? '';
  return result(toys.filter(toy => toy.name.startsWith(prefix)));
};
bridge.oninitialized = async () => {
  try {
    await bridge.sendToolInput({ arguments: {} });
    await bridge.sendToolResult(result(toys));
    state.initialized = true;
  } catch { state.failure = true; }
};
bridge.onerror = () => { state.failure = true; };
window.catalogTest = {
  state,
  changeContext: (locale = 'de-DE') => bridge.setHostContext({ locale, timeZone: 'UTC' }),
};
await bridge.connect(new PostMessageTransport(frame.contentWindow, frame.contentWindow));
frame.src = '/app.html';
