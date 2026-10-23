import './style.css';

const ENDPOINT = 'https://xevqbgjtxuveeqaiacvm.supabase.co/functions/v1/contacts-mcp';
const state = { token: '', devices: [], selectedDevice: '', contacts: [], connected: false, busy: false, requestId: 0 };

document.querySelector('#app').innerHTML = `
  <main class="shell">
    <header class="top">
      <div class="mark">МК</div>
      <div><p class="eyebrow">ЛИЧНЫЙ ИНСТРУМЕНТ</p><h1>Мои контакты</h1><p class="muted">Веб-доступ для бесплатного тарифа ChatGPT</p></div>
    </header>
    <section class="panel auth">
      <h2>Подключение</h2>
      <p class="hint">Введите свой токен доступа. Он хранится только в памяти этой вкладки и не сохраняется в браузере. Не вставляйте токен в публичные чаты или репозитории.</p>
      <label for="token">Bearer-токен Supabase</label>
      <input id="token" type="password" autocomplete="off" spellcheck="false" placeholder="Вставьте токен доступа" />
      <div class="actions"><button id="connect">Подключить</button><button id="disconnect" class="secondary" disabled>Отключить</button></div>
      <p id="connection" class="status" role="status">Не подключено</p>
    </section>
    <section class="panel" id="devicesPanel" hidden>
      <div class="section-head"><h2>Устройство</h2><button id="refresh" class="secondary">Обновить</button></div>
      <div class="stats"><div><span>Кэш контактов</span><strong id="count">—</strong></div><div><span>Устройства</span><strong id="deviceCount">—</strong></div></div>
      <label for="device">Телефон Android</label><select id="device"></select>
      <p id="syncInfo" class="hint">Данные могут быть неактуальны до синхронизации телефона.</p>
    </section>
    <section class="panel" id="contactsPanel" hidden>
      <div class="section-head"><h2>Контакты</h2><span id="resultCount" class="pill">0</span></div>
      <form id="searchForm" class="search"><input id="query" type="search" placeholder="Имя, телефон или email" aria-label="Поиск контактов" /><button>Найти</button></form>
      <div id="contacts" class="contacts"><p class="empty">Подключите устройство, чтобы загрузить контакты.</p></div>
    </section>
    <section class="panel note">
      <h2>Безопасность</h2>
      <ul><li>Этот интерфейс не сохраняет токен после перезагрузки вкладки.</li><li>Изменения контактов должны подтверждаться на Android-телефоне.</li><li>Не используйте общий или публичный компьютер для доступа к контактам.</li></ul>
    </section>
    <footer>Персональный веб-интерфейс · данные загружаются из вашего Supabase bridge</footer>
  </main>
`;

const $ = (selector) => document.querySelector(selector);
const setStatus = (message, type = '') => { $('#connection').textContent = message; $('#connection').className = 'status ' + type; };
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (ch) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));
const displayName = (contact) => contact.display_name || contact.name || contact.full_name || [contact.given_name, contact.family_name].filter(Boolean).join(' ') || 'Без имени';
const contactDetails = (contact) => {
  const fields = [];
  const phone = contact.phone || contact.phone_number || (Array.isArray(contact.phones) ? contact.phones.map(p => typeof p === 'string' ? p : p.number || p.value).filter(Boolean).join(', ') : '');
  const email = contact.email || (Array.isArray(contact.emails) ? contact.emails.map(e => typeof e === 'string' ? e : e.address || e.value).filter(Boolean).join(', ') : '');
  if (phone) fields.push(phone);
  if (email) fields.push(email);
  return fields;
};

async function rpc(method, params = {}) {
  if (!state.token) throw new Error('Сначала введите токен доступа.');
  const response = await fetch(ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + state.token },
    body: JSON.stringify({ jsonrpc: '2.0', id: ++state.requestId, method, params })
  });
  if (!response.ok) {
    const body = await response.text();
    throw new Error(response.status === 401 ? 'Токен отклонён. Проверьте токен доступа.' : `Ошибка сервера (${response.status}): ${body.slice(0, 180)}`);
  }
  const payload = await response.json();
  if (payload.error) throw new Error(payload.error.message || 'Ошибка MCP');
  return payload.result;
}

async function callTool(name, args = {}) {
  const result = await rpc('tools/call', { name, arguments: args });
  if (result?.isError) {
    const textValue = result.content?.find(item => item.type === 'text')?.text;
    throw new Error(textValue || 'Инструмент вернул ошибку.');
  }
  const textValue = result?.content?.find(item => item.type === 'text')?.text;
  if (!textValue) return result;
  try { return JSON.parse(textValue); } catch { return { message: textValue }; }
}

function renderContacts(contacts) {
  state.contacts = Array.isArray(contacts) ? contacts : [];
  $('#resultCount').textContent = String(state.contacts.length);
  if (!state.contacts.length) {
    $('#contacts').innerHTML = '<p class="empty">Контакты не найдены.</p>';
    return;
  }
  $('#contacts').innerHTML = state.contacts.map((contact) => {
    const details = contactDetails(contact);
    const id = contact.id ?? contact.local_contact_id ?? contact.contact_id ?? '';
    return `<article class="contact"><div class="avatar">${escapeHtml(displayName(contact).trim().slice(0,1).toUpperCase() || '?')}</div><div class="contact-body"><strong>${escapeHtml(displayName(contact))}</strong>${details.map(item => `<span>${escapeHtml(item)}</span>`).join('')}${id !== '' ? `<small>ID: ${escapeHtml(id)}</small>` : ''}</div></article>`;
  }).join('');
}

async function loadStatus() {
  const status = await callTool('contacts_status');
  const devices = status.devices || [];
  state.devices = devices;
  $('#count').textContent = status.cached_contact_count ?? '—';
  $('#deviceCount').textContent = devices.length;
  $('#device').innerHTML = devices.length ? devices.map((d, i) => {
    const id = d.id || d.device_id;
    const label = d.device_name || d.name || 'Android ' + (i + 1);
    return `<option value="${escapeHtml(id)}">${escapeHtml(label)}</option>`;
  }).join('') : '<option value="">Устройства не зарегистрированы</option>';
  state.selectedDevice = $('#device').value;
  $('#devicesPanel').hidden = false;
  $('#contactsPanel').hidden = false;
  if (devices.length) await loadContacts();
  else renderContacts([]);
}

async function loadContacts(query = '') {
  const deviceId = $('#device').value;
  if (!deviceId) { renderContacts([]); return; }
  $('#contacts').innerHTML = '<p class="empty">Загружаю контакты…</p>';
  const result = query
    ? await callTool('contacts_search', { device_id: deviceId, query, limit: 200 })
    : await callTool('contacts_list', { device_id: deviceId, limit: 200 });
  renderContacts(result.contacts || []);
  $('#syncInfo').textContent = 'Показаны кэшированные данные. Последняя синхронизация может быть не текущей.';
}

$('#connect').addEventListener('click', async () => {
  const token = $('#token').value.trim();
  if (!token) { setStatus('Введите токен доступа.', 'error'); return; }
  state.token = token;
  $('#connect').disabled = true;
  setStatus('Подключаюсь…');
  try {
    await rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'moi-kontakty-web', version: '0.1.0' } });
    await rpc('notifications/initialized', {});
    await loadStatus();
    state.connected = true;
    $('#disconnect').disabled = false;
    setStatus('Подключено', 'success');
  } catch (error) {
    state.token = '';
    state.connected = false;
    setStatus(error.message || 'Не удалось подключиться.', 'error');
    $('#devicesPanel').hidden = true;
    $('#contactsPanel').hidden = true;
  } finally { $('#connect').disabled = false; }
});

$('#disconnect').addEventListener('click', () => {
  state.token = '';
  state.connected = false;
  state.devices = [];
  state.contacts = [];
  $('#token').value = '';
  $('#devicesPanel').hidden = true;
  $('#contactsPanel').hidden = true;
  $('#disconnect').disabled = true;
  setStatus('Отключено');
});

$('#refresh').addEventListener('click', async () => {
  try { await loadStatus(); setStatus('Данные обновлены', 'success'); }
  catch (error) { setStatus(error.message, 'error'); }
});

$('#device').addEventListener('change', async () => {
  try { await loadContacts($('#query').value.trim()); }
  catch (error) { setStatus(error.message, 'error'); }
});

$('#searchForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  try { await loadContacts($('#query').value.trim()); }
  catch (error) { setStatus(error.message, 'error'); }
});
