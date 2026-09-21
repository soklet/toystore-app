import { App } from '@modelcontextprotocol/ext-apps';
import { mountCatalog } from './catalog.mjs';

// The official SDK is bundled inline; the browser loads no package or CDN.
// Own resize cleanup: SDK 2.0.0's automatic observer outlives app.close().
const app = new App({ name: 'Toy Store catalog', version: '1.0.0' }, {}, { autoResize: false });
mountCatalog({ app, document });
