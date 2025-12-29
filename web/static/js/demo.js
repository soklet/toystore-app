        // ============================================================
        // Configuration
        // ============================================================
        const config = {
            apiBaseUrl: '',  // Same origin for regular API
            sseBaseUrl: `${window.location.protocol}//${window.location.hostname}:8081`  // SSE server on port 8081
        };

        // ============================================================
        // State
        // ============================================================
        const state = {
            authToken: null,
            account: null,
            eventSource: null,
            browserLocale: null,
            browserTimeZone: null
        };

        // ============================================================
        // DOM Elements
        // ============================================================
        const elements = {
            // Settings
            settingsNote: document.getElementById('settings-note'),
            presetButtons: document.querySelectorAll('[data-account-preset]'),
            presetSection: document.getElementById('preset-section'),
            sseDrawer: document.getElementById('sse-drawer'),
            sseToggleBtn: document.getElementById('sse-toggle-btn'),

            // Auth
            authForm: document.getElementById('auth-form'),
            email: document.getElementById('email'),
            password: document.getElementById('password'),
            authBtn: document.getElementById('auth-btn'),
            signOutBtn: document.getElementById('sign-out-btn'),
            accountStatus: document.getElementById('account-status'),
            authResponse: document.getElementById('auth-response'),

            // Create Toy
            createToyForm: document.getElementById('create-toy-form'),
            toyName: document.getElementById('toy-name'),
            toyPrice: document.getElementById('toy-price'),
            toyCurrency: document.getElementById('toy-currency'),
            createToyResponse: document.getElementById('create-toy-response'),

            // List Toys
            listToysBtn: document.getElementById('list-toys-btn'),
            toySearch: document.getElementById('toy-search'),
            toysList: document.getElementById('toys-list'),

            // Purchase
            purchaseForm: document.getElementById('purchase-form'),
            purchaseToyId: document.getElementById('purchase-toy-id'),
            ccNumber: document.getElementById('cc-number'),
            ccExpiration: document.getElementById('cc-expiration'),
            purchaseResponse: document.getElementById('purchase-response'),

            // SSE
            sseConnectBtn: document.getElementById('sse-connect-btn'),
            sseDisconnectBtn: document.getElementById('sse-disconnect-btn'),
            sseClearBtn: document.getElementById('sse-clear-btn'),
            sseStatusDot: document.getElementById('sse-status-dot'),
            sseStatusText: document.getElementById('sse-status-text'),
            eventsLog: document.getElementById('events-log')
        };

        const accountPresets = {
            administrator: {
                email: 'admin@soklet.com',
                password: 'administrator-password'
            },
            customer: {
                email: 'customer@soklet.com',
                password: 'customer-password'
            },
            employee: {
                email: 'employee@soklet.com',
                password: 'employee-password'
            }
        };

        // ============================================================
        // Utility Functions
        // ============================================================
        function getHeaders() {
            const headers = {
                'Content-Type': 'application/json'
            };

            if (state.browserTimeZone) {
                headers['Time-Zone'] = state.browserTimeZone;
            }

            if (state.authToken) {
                headers['X-Access-Token'] = state.authToken;  // Fixed: was X-Authentication-Token
            }

            return headers;
        }

        function detectBrowserSettings() {
            state.browserLocale = navigator.language || 'unknown';
            state.browserTimeZone = Intl.DateTimeFormat().resolvedOptions().timeZone || null;
        }

        function updateSettingsNote() {
            if (!elements.settingsNote) {
                return;
            }

            const locale = state.browserLocale || 'unknown';
            const timeZone = state.browserTimeZone || 'unknown';
            elements.settingsNote.textContent = `Signed out: browser locale is ${locale} and time zone is ${timeZone}. Signing in may override these with account settings.`;
        }

        function showResponse(element, data, isError = false) {
            element.classList.remove('hidden', 'error', 'success');
            element.classList.add(isError ? 'error' : 'success');
            element.textContent = typeof data === 'string' ? data : JSON.stringify(data, null, 2);
        }

        function formatTime(date = new Date()) {
            return date.toLocaleTimeString('en-US', { hour12: false });
        }

        function addEventLogEntry(type, message, eventClass = 'system') {
            const entry = document.createElement('div');
            entry.className = `event-entry ${eventClass}`;
            entry.innerHTML = `
                <span class="event-time">${formatTime()}</span>
                <span class="event-type">${type}</span>
                <span>${message}</span>
            `;
            elements.eventsLog.insertBefore(entry, elements.eventsLog.firstChild);
        }

        function isSseDrawerCollapsed() {
            return elements.sseDrawer && elements.sseDrawer.classList.contains('sse-collapsed');
        }

        function setSseDrawerCollapsed(collapsed) {
            if (!elements.sseDrawer || !elements.sseToggleBtn) {
                return;
            }

            elements.sseDrawer.classList.toggle('sse-collapsed', collapsed);
            elements.sseToggleBtn.textContent = collapsed ? 'Expand' : 'Collapse';
            elements.sseToggleBtn.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
        }

        function toggleSseDrawer() {
            setSseDrawerCollapsed(!isSseDrawerCollapsed());
        }

        function expandSseDrawer() {
            if (isSseDrawerCollapsed()) {
                setSseDrawerCollapsed(false);
            }
        }

        // ============================================================
        // Authentication
        // ============================================================
        function updateAuthUI() {
            if (state.authToken && state.account) {
                elements.accountStatus.className = 'account-status signed-in';
                elements.accountStatus.innerHTML = `
                    ✓ Signed in as <strong>${state.account.name}</strong>
                    <div class="account-info">
                        Role: ${state.account.roleId}<br>
                        Email: ${state.account.emailAddress}
                    </div>
                `;
                elements.authForm.classList.add('hidden');
                elements.signOutBtn.classList.remove('hidden');
                if (elements.presetSection) {
                    elements.presetSection.classList.add('hidden');
                }
            } else {
                elements.accountStatus.className = 'account-status signed-out';
                elements.accountStatus.textContent = '✗ Not signed in';
                elements.authForm.classList.remove('hidden');
                elements.signOutBtn.classList.add('hidden');
                if (elements.presetSection) {
                    elements.presetSection.classList.remove('hidden');
                }
            }
        }

        function applyAccountPreset(presetKey) {
            const preset = accountPresets[presetKey];

            if (!preset) {
                return;
            }

            elements.email.value = preset.email;
            elements.password.value = preset.password;
        }

        async function signIn(e) {
            e.preventDefault();

            try {
                elements.authResponse.classList.add('hidden');
                const response = await fetch('/accounts/authenticate', {
                    method: 'POST',
                    headers: getHeaders(),
                    body: JSON.stringify({
                        emailAddress: elements.email.value,
                        password: elements.password.value
                    })
                });

                const data = await response.json();

                if (response.ok) {
                    state.authToken = data.authenticationToken;
                    state.account = data.account;
                    updateAuthUI();
                    showResponse(elements.authResponse, data);
                } else {
                    showResponse(elements.authResponse, data, true);
                }
            } catch (err) {
                showResponse(elements.authResponse, `Network error: ${err.message}`, true);
            }
        }

        function signOut() {
            // Disconnect SSE if connected
            if (state.eventSource) {
                disconnectSSE();
            }
            state.authToken = null;
            state.account = null;
            updateAuthUI();
            elements.authResponse.classList.add('hidden');
        }

        // ============================================================
        // Create Toy
        // ============================================================
        async function createToy(e) {
            e.preventDefault();

            try {
                const response = await fetch('/toys', {
                    method: 'POST',
                    headers: getHeaders(),
                    body: JSON.stringify({
                        name: elements.toyName.value,
                        price: elements.toyPrice.value,
                        currency: elements.toyCurrency.value
                    })
                });

                const data = await response.json();

                if (response.ok) {
                    showResponse(elements.createToyResponse, data);
                    elements.toyName.value = '';
                    elements.toyPrice.value = '';
                    listToys(); // Refresh list
                    refreshPurchaseToyOptions(); // Refresh purchase dropdown
                } else {
                    showResponse(elements.createToyResponse, data, true);
                }
            } catch (err) {
                showResponse(elements.createToyResponse, `Network error: ${err.message}`, true);
            }
        }

        // ============================================================
        // List Toys
        // ============================================================
        async function fetchToys(query = '') {
            const trimmedQuery = query.trim();
            const url = trimmedQuery ? `/toys?query=${encodeURIComponent(trimmedQuery)}` : '/toys';

            const response = await fetch(url, {
                method: 'GET',
                headers: getHeaders()
            });

            const data = await response.json();

            if (!response.ok || !data.toys) {
                const message = data.summary || 'Error loading toys';
                throw new Error(message);
            }

            return data.toys;
        }

        function renderToyList(toys) {
            if (toys.length === 0) {
                elements.toysList.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon">🏜️</div>
                        <p>No toys found</p>
                    </div>
                `;
                return;
            }

            elements.toysList.innerHTML = toys.map(toy => `
                <div class="toy-item">
                    <div>
                        <div class="toy-name">${escapeHtml(toy.name)}</div>
                        <div style="font-size: 0.8rem; color: var(--color-text-muted);">
                            ${escapeHtml(toy.toyId)}
                        </div>
                    </div>
                    <div class="toy-price">${escapeHtml(toy.priceDescription)}</div>
                </div>
            `).join('');
        }

        function renderPurchaseToyOptions(toys) {
            if (!elements.purchaseToyId) {
                return;
            }

            const selectedValue = elements.purchaseToyId.value;
            const options = toys.map(toy => `
                <option value="${escapeHtml(toy.toyId)}">
                    ${escapeHtml(toy.name)} — ${escapeHtml(toy.priceDescription)}
                </option>
            `);

            elements.purchaseToyId.innerHTML = [
                '<option value="">Select toy</option>',
                ...options
            ].join('');

            if (selectedValue) {
                elements.purchaseToyId.value = selectedValue;
            }
        }

        async function refreshPurchaseToyOptions() {
            try {
                const toys = await fetchToys();
                renderPurchaseToyOptions(toys);
            } catch (err) {
                if (elements.purchaseToyId) {
                    elements.purchaseToyId.innerHTML = '<option value="">Select toy</option>';
                }
            }
        }

        async function listToys() {
            try {
                const toys = await fetchToys(elements.toySearch.value);
                renderToyList(toys);
            } catch (err) {
                elements.toysList.innerHTML = `
                    <div class="empty-state">
                        <div class="empty-state-icon">❌</div>
                        <p>Network error: ${escapeHtml(err.message)}</p>
                    </div>
                `;
            }
        }

        function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        // ============================================================
        // Purchase Toy
        // ============================================================
        async function purchaseToy(e) {
            e.preventDefault();

            const toyId = elements.purchaseToyId.value.trim();
            const toyIdParam = toyId.length === 0 ? 'null' : toyId;

            try {
                const response = await fetch(`/toys/${encodeURIComponent(toyIdParam)}/purchase`, {
                    method: 'POST',
                    headers: getHeaders(),
                    body: JSON.stringify({
                        creditCardNumber: elements.ccNumber.value,
                        creditCardExpiration: elements.ccExpiration.value
                    })
                });

                const data = await response.json();
                showResponse(elements.purchaseResponse, data, !response.ok);
            } catch (err) {
                showResponse(elements.purchaseResponse, `Network error: ${err.message}`, true);
            }
        }

        // ============================================================
        // SSE (Server-Sent Events)
        // ============================================================
        function updateSSEStatus(connected) {
            elements.sseStatusDot.className = `status-dot ${connected ? 'connected' : 'disconnected'}`;
            elements.sseStatusText.textContent = connected ? 'Connected' : 'Disconnected';
            elements.sseConnectBtn.disabled = connected;
            elements.sseDisconnectBtn.disabled = !connected;
        }

        /**
         * Acquires a short-lived SSE context token from the server.
         * This token embeds the account ID, locale, and timezone for the SSE connection.
         * Per the README: SSE spec does not support custom headers, so we package up
         * authentication and locale/timezone information into a short-lived token.
         */
        async function acquireSseContextToken() {
            const response = await fetch('/accounts/sse-context-token', {
                method: 'POST',
                headers: getHeaders()
            });

            if (!response.ok) {
                const errorData = await response.json();
                throw new Error(errorData.summary || 'Failed to acquire SSE context token');
            }

            const data = await response.json();
            return data.serverSentEventContextToken;
        }

        async function connectSSE() {
            // Check if user is authenticated
            if (!state.authToken) {
                addEventLogEntry('Error', 'You must sign in before connecting to SSE', 'system');
                return;
            }

            // Close existing connection if any
            if (state.eventSource) {
                state.eventSource.close();
                state.eventSource = null;
            }

            try {
                addEventLogEntry('Info', 'Acquiring SSE context token...', 'system');

                // Step 1: Acquire a short-lived SSE context token
                // This token includes accountId, locale, and timezone
                const sseContextToken = await acquireSseContextToken();

                // Step 2: Build SSE URL with the context token as query parameter
                // Fixed: Use correct endpoint (/toys/event-source) and port (8081)
                const params = new URLSearchParams({
                    'X-Server-Sent-Event-Context-Token': sseContextToken
                });

                const url = `${config.sseBaseUrl}/toys/event-source?${params.toString()}`;

                addEventLogEntry('Info', `Connecting to ${url.substring(0, 60)}...`, 'system');

                state.eventSource = new EventSource(url);

                state.eventSource.onopen = () => {
                    updateSSEStatus(true);
                    addEventLogEntry('Connected', 'Listening for toy events (create, update, delete, purchase)', 'system');
                };

                state.eventSource.onerror = (err) => {
                    updateSSEStatus(false);
                    addEventLogEntry('Error', 'Connection lost or failed to connect', 'system');
                    state.eventSource.close();
                    state.eventSource = null;
                };

                // Listen for specific event types (these match the backend's SSE event names)
                ['toy-created', 'toy-updated', 'toy-deleted', 'toy-purchased'].forEach(eventType => {
                    state.eventSource.addEventListener(eventType, (event) => {
                        expandSseDrawer();
                        try {
                            const data = JSON.parse(event.data);
                            addEventLogEntry(eventType, JSON.stringify(data, null, 2), eventType);
                        } catch (parseError) {
                            addEventLogEntry(eventType, event.data, eventType);
                        }
                    });
                });

                // Fallback for generic messages (including heartbeat comments)
                state.eventSource.onmessage = (event) => {
                    expandSseDrawer();
                    addEventLogEntry('Message', event.data, 'system');
                };

            } catch (err) {
                addEventLogEntry('Error', `Failed to connect: ${err.message}`, 'system');
                updateSSEStatus(false);
            }
        }

        function disconnectSSE() {
            if (state.eventSource) {
                state.eventSource.close();
                state.eventSource = null;
                updateSSEStatus(false);
                addEventLogEntry('Disconnected', 'Connection closed by user', 'system');
            }
        }

        function clearEventLog() {
            elements.eventsLog.innerHTML = `
                <div class="event-entry system">
                    <span class="event-time">${formatTime()}</span>
                    <span class="event-type">System</span>
                    <span>Log cleared</span>
                </div>
            `;
        }

        // ============================================================
        // Event Listeners
        // ============================================================
        elements.authForm.addEventListener('submit', signIn);
        elements.signOutBtn.addEventListener('click', signOut);
        elements.createToyForm.addEventListener('submit', createToy);
        elements.listToysBtn.addEventListener('click', listToys);
        elements.purchaseForm.addEventListener('submit', purchaseToy);
        elements.sseConnectBtn.addEventListener('click', connectSSE);
        elements.sseDisconnectBtn.addEventListener('click', disconnectSSE);
        elements.sseClearBtn.addEventListener('click', clearEventLog);
        if (elements.sseToggleBtn) {
            elements.sseToggleBtn.addEventListener('click', toggleSseDrawer);
        }
        elements.presetButtons.forEach(button => {
            button.addEventListener('click', () => applyAccountPreset(button.dataset.accountPreset));
        });

        // Allow Enter key in search box to trigger list
        elements.toySearch.addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                listToys();
            }
        });

        // Initialize UI
        detectBrowserSettings();
        updateSettingsNote();
        updateAuthUI();
        updateSSEStatus(false);
        refreshPurchaseToyOptions();
        setSseDrawerCollapsed(true);
