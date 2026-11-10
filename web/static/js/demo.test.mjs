import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('./demo.js', import.meta.url), 'utf8');

class Node {
    constructor(tagName = 'div') {
        this.tagName = tagName;
        this.children = [];
        this.listeners = new Map();
        this.classes = new Set();
        this.classList = {
            add: (...names) => names.forEach(name => this.classes.add(name)),
            remove: (...names) => names.forEach(name => this.classes.delete(name)),
            contains: name => this.classes.has(name),
            toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name)
        };
        this.value = '';
        this.htmlWrites = [];
        this.text = '';
    }

    set textContent(value) {
        this.children = [];
        this.text = String(value);
    }

    get textContent() {
        return this.text + this.children.map(child => child.textContent).join('');
    }

    set innerHTML(value) {
        this.htmlWrites.push(value);
        this.children = [];
        this.text = value;
    }

    get innerHTML() {
        return this.text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
    }

    append(...children) {
        this.children.push(...children.map(child => typeof child === 'string'
            ? Object.assign(new Node('#text'), { text: child }) : child));
    }

    replaceChildren(...children) {
        this.children = [];
        this.text = '';
        this.append(...children);
    }

    insertBefore(child) {
        this.children.unshift(child);
    }

    get firstChild() {
        return this.children[0] ?? null;
    }

    addEventListener(name, listener) {
        this.listeners.set(name, listener);
    }

    setAttribute() {}
}

// Model the browser's selectedIndex=-1 behavior when assigning a missing option.
class SelectNode extends Node {
    set value(value) {
        this.selectedIndex = this.children.findIndex(option => option.value === value);
    }
    get value() {
        return this.children[this.selectedIndex]?.value ?? '';
    }
    replaceChildren(...children) {
        super.replaceChildren(...children);
        this.selectedIndex = children.length ? 0 : -1;
    }
}

function fixture() {
    const nodes = new Map();
    const document = {
        getElementById: id => {
            if (!nodes.has(id)) nodes.set(id, id === 'purchase-toy-id' ? new SelectNode('select') : new Node());
            return nodes.get(id);
        },
        querySelectorAll: () => [],
        createElement: tagName => new Node(tagName)
    };
    class EventSource {
        static latest;
        static instances = [];
        constructor(url) {
            this.url = url;
            this.listeners = new Map();
            EventSource.latest = this;
            EventSource.instances.push(this);
        }
        addEventListener(name, listener) { this.listeners.set(name, listener); }
        close() { this.closed = true; }
    }
    const context = vm.createContext({
        document,
        window: { location: { protocol: 'http:', hostname: 'localhost' } },
        navigator: { language: 'en-US' },
        Intl,
        URLSearchParams,
        EventSource,
        fetch: async url => {
            if (url === '/accounts/sse-access-token')
                return { ok: true, json: async () => ({ accessToken: 'short-lived-secret' }) };
            throw new Error('No network in this test');
        }
    });
    vm.runInContext(source, context);
    return { context, nodes, EventSource };
}

test('SSE data and account fields render as text, without embedding token in the event log', async () => {
    const { context, nodes, EventSource } = fixture();
    const attack = '<img src=x onerror=alert(1)>';
    context.attack = attack;
    vm.runInContext(`state.authToken = 'api-token';
        state.account = { name: attack, roleId: 'EMPLOYEE', emailAddress: attack,
            locale: 'en-US', timeZone: 'UTC' }; updateAuthUI();`, context);

    const status = nodes.get('account-status');
    assert.equal(status.children[1].textContent, attack);
    assert.match(status.textContent, /<img src=x onerror=alert\(1\)>/);
    assert.equal(status.htmlWrites.length, 0);

    await vm.runInContext('connectSSE()', context);
    const eventData = JSON.stringify({ toy: { name: attack } });
    EventSource.latest.listeners.get('toy-created')({ data: eventData });

    const eventLog = nodes.get('events-log');
    const event = eventLog.children[0];
    assert.equal(event.children[2].textContent, JSON.stringify(JSON.parse(eventData), null, 2));
    assert.equal(event.htmlWrites.length, 0);
    assert.doesNotMatch(eventLog.textContent, /short-lived-secret/);
});

test('catalog data cannot become option markup or attributes', () => {
    const { context, nodes } = fixture();
    const hostileId = 'id" onmouseover="alert(1)';
    const hostileName = '<svg onload=alert(1)>';
    context.toys = [{ toyId: hostileId, name: hostileName, priceDescription: '$1.00' }];
    vm.runInContext('renderPurchaseToyOptions(toys)', context);

    const select = nodes.get('purchase-toy-id');
    assert.equal(select.htmlWrites.length, 0);
    assert.equal(select.children[1].value, hostileId);
    assert.equal(select.children[1].textContent, `${hostileName} — $1.00`);
});

test('failed or changed toy lists restore the placeholder when the selected toy disappears', async () => {
    const { context, nodes } = fixture();
    await new Promise(setImmediate); // Let the initial page-load refresh settle.
    context.toys = [{ toyId: 't1', name: 'First', priceDescription: '$1.00' }];
    vm.runInContext('renderPurchaseToyOptions(toys)', context);
    const select = nodes.get('purchase-toy-id');
    select.value = 't1';
    vm.runInContext('renderPurchaseToyOptions(toys)', context);
    assert.equal(select.value, 't1');
    assert.equal(select.selectedIndex, 1);
    await vm.runInContext('refreshPurchaseToyOptions()', context);
    assert.equal(select.value, '');
    assert.equal(select.selectedIndex, 0);
    assert.equal(select.children[0].textContent, 'Select toy');
});


test('overlapping SSE connects keep only the newest connection and sign-out closes it', async () => {
    const { context, EventSource } = fixture();
    const pending = [];
    context.fetch = () => new Promise(resolve => pending.push(resolve));
    vm.runInContext("state.authToken = 'api-token'", context);
    const first = vm.runInContext('connectSSE()', context);
    const second = vm.runInContext('connectSSE()', context);
    pending[1]({ ok: true, json: async () => ({ accessToken: 'second' }) });
    await second;
    pending[0]({ ok: true, json: async () => ({ accessToken: 'first' }) });
    await first;
    assert.equal(EventSource.instances.length, 1);
    assert.match(EventSource.latest.url, /second/);
    vm.runInContext('signOut()', context);
    assert.equal(EventSource.latest.closed, true);
});

test('sign-out while acquiring an SSE token prevents a late connection', async () => {
    const { context, EventSource } = fixture();
    let complete;
    context.fetch = () => new Promise(resolve => { complete = resolve; });
    vm.runInContext("state.authToken = 'api-token'", context);
    const pending = vm.runInContext('connectSSE()', context);
    vm.runInContext('signOut()', context);
    complete({ ok: true, json: async () => ({ accessToken: 'late' }) });
    await pending;
    assert.equal(EventSource.instances.length, 0);
});

function authenticationResponse(token) {
    return { ok: true, json: async () => ({ authenticationToken: token,
        account: { name: token, roleId: 'CUSTOMER', emailAddress: 'example@example.test',
            locale: 'en-US', timeZone: 'UTC' } }) };
}

test('double sign-in followed by visible sign-out cannot restore a stale authentication', async () => {
    const { context, nodes, EventSource } = fixture();
    const pending = [];
    context.fetch = url => url === '/accounts/authenticate'
        ? new Promise(resolve => pending.push(resolve)) : Promise.reject(new Error('No follow-up network'));
    const form = nodes.get('auth-form');
    const signOut = nodes.get('sign-out-btn');
    assert.equal(form.classList.contains('hidden'), false);
    const first = form.listeners.get('submit')({ preventDefault() {} });
    const second = form.listeners.get('submit')({ preventDefault() {} });
    pending[1](authenticationResponse('latest-token'));
    await second;
    assert.equal(signOut.classList.contains('hidden'), false);
    signOut.listeners.get('click')();
    assert.equal(vm.runInContext('state.authToken', context), null);
    pending[0](authenticationResponse('stale-token'));
    await first;
    assert.equal(vm.runInContext('state.authToken', context), null);
    assert.equal(vm.runInContext('state.account', context), null);
    assert.equal(form.classList.contains('hidden'), false);
    assert.equal(signOut.classList.contains('hidden'), true);
    assert.equal(EventSource.instances.length, 0);
});

test('only the newest pending sign-in may install credentials', async () => {
    const { context, nodes } = fixture();
    const pending = [];
    context.fetch = url => url === '/accounts/authenticate'
        ? new Promise(resolve => pending.push(resolve)) : Promise.reject(new Error('No follow-up network'));
    const submit = nodes.get('auth-form').listeners.get('submit');
    const first = submit({ preventDefault() {} });
    const second = submit({ preventDefault() {} });
    pending[0](authenticationResponse('stale-token'));
    await first;
    assert.equal(vm.runInContext('state.authToken', context), null);
    assert.equal(nodes.get('auth-form').classList.contains('hidden'), false);
    pending[1](authenticationResponse('latest-token'));
    await second;
    assert.equal(vm.runInContext('state.authToken', context), 'latest-token');
    assert.equal(nodes.get('account-status').textContent.includes('latest-token'), true);
});

test('late authentication network failure cannot repaint a signed-out page', async () => {
    const { context, nodes } = fixture();
    const pending = [];
    context.fetch = url => url === '/accounts/authenticate'
        ? new Promise((resolve, reject) => pending.push({ resolve, reject })) : Promise.reject(new Error('No follow-up network'));
    const submit = nodes.get('auth-form').listeners.get('submit');
    const first = submit({ preventDefault() {} });
    const second = submit({ preventDefault() {} });
    pending[1].resolve(authenticationResponse('latest-token'));
    await second;
    nodes.get('sign-out-btn').listeners.get('click')();
    pending[0].reject(new Error('stale authentication failure'));
    await first;
    assert.equal(nodes.get('auth-response').classList.contains('hidden'), true);
    assert.equal(nodes.get('auth-response').textContent.includes('stale authentication failure'), false);
});
