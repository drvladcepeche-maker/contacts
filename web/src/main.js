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
      <div class="contact-actions"><button id="createContact">Добавить</button><button id="mergeContacts" class="secondary" disabled>Объединить выбранные</button></div>
      <p class="hint">Изменения отправляются на телефон и применяются только после подтверждения на Android.</p>
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
  if (response.status === 202 && method.startsWith('notifications/')) return {};
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
    $('#mergeContacts').disabled = true;
    return;
  }
  $('#contacts').innerHTML = state.contacts.map((contact) => {
    const details = contactDetails(contact);
    const id = contact.local_contact_id ?? contact.id ?? contact.contact_id ?? '';
    const safeId = escapeHtml(id);
    return `<article class="contact">
      <input class="pick-contact" type="checkbox" data-id="${safeId}" aria-label="Выбрать ${escapeHtml(displayName(contact))}" ${id === '' ? 'disabled' : ''} />
      <div class="avatar">${escapeHtml(displayName(contact).trim().slice(0,1).toUpperCase() || '?')}</div>
      <div class="contact-body"><strong>${escapeHtml(displayName(contact))}</strong>${details.map(item => `<span>${escapeHtml(item)}</span>`).join('')}${id !== '' ? `<small>ID: ${safeId}</small>` : ''}</div>
      <div class="contact-row-actions"><button class="secondary edit-contact" data-id="${safeId}" ${id === '' ? 'disabled' : ''}>Изменить</button><button class="danger delete-contact" data-id="${safeId}" ${id === '' ? 'disabled' : ''}>Удалить</button></div>
    </article>`;
  }).join('');
  updateMergeButton();
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


function selectedContactIds() {
  return [...document.querySelectorAll('.pick-contact:checked')].map(input => Number(input.dataset.id)).filter(id => Number.isInteger(id) && id > 0);
}
function updateMergeButton() {
  const ids = selectedContactIds();
  $('#mergeContacts').disabled = ids.length < 2;
  $('#mergeContacts').textContent = ids.length >= 2 ? `Объединить выбранные (${ids.length})` : 'Объединить выбранные';
}
async function queueChange(operation, payload, confirmation) {
  const deviceId = $('#device').value;
  if (!deviceId) throw new Error('Сначала выберите Android-устройство.');
  if (!window.confirm(confirmation)) return;
  const result = await callTool('contacts_request_change', { device_id: deviceId, operation, ...payload });
  if (!result.queued) throw new Error(result.error || 'Сервер не поставил команду в очередь.');
  const commandId = result.command?.id || 'не указан';
  setStatus(`Команда отправлена на телефон. ID: ${commandId}. Подтвердите действие на Android.`, 'success');
}
$('#contacts').addEventListener('change', (event) => {
  if (event.target.matches('.pick-contact')) updateMergeButton();
});
$('#contacts').addEventListener('click', async (event) => {
  const edit = event.target.closest('.edit-contact');
  const remove = event.target.closest('.delete-contact');
  if (!edit && !remove) return;
  const id = Number((edit || remove).dataset.id);
  const contact = state.contacts.find(item => Number(item.local_contact_id ?? item.id ?? item.contact_id) === id);
  if (!contact || !Number.isInteger(id) || id <= 0) { setStatus('Не удалось определить контакт.', 'error'); return; }
  try {
    if (edit) {
      const name = window.prompt('Имя контакта:', displayName(contact));
      if (name === null) return;
      if (!name.trim()) { setStatus('Имя не может быть пустым.', 'error'); return; }
      const phone = window.prompt('Номер телефона:', contact.phone || contact.phone_number || '');
      if (phone === null) return;
      await queueChange('update', { id, name: name.trim(), phone: phone.trim() }, `Отправить на телефон запрос на изменение контакта «${displayName(contact)}»? Телефон попросит отдельное подтверждение.`);
    } else {
      await queueChange('delete', { id, confirmed_by_user: true }, `Отправить на телефон запрос на удаление контакта «${displayName(contact)}» (ID ${id})? Телефон попросит отдельное подтверждение.`);
    }
  } catch (error) { setStatus(error.message || 'Не удалось отправить команду.', 'error'); }
});
$('#createContact').addEventListener('click', async () => {
  const name = window.prompt('Имя нового контакта:');
  if (name === null) return;
  if (!name.trim()) { setStatus('Имя не может быть пустым.', 'error'); return; }
  const phone = window.prompt('Номер телефона (можно оставить пустым):', '');
  if (phone === null) return;
  try {
    await queueChange('create', { name: name.trim(), phone: phone.trim() }, `Создать на телефоне контакт «${name.trim()}» с номером «${phone.trim() || 'не указан'}»? Телефон попросит отдельное подтверждение.`);
  } catch (error) { setStatus(error.message || 'Не удалось отправить команду.', 'error'); }
});
$('#mergeContacts').addEventListener('click', async () => {
  const ids = selectedContactIds();
  if (ids.length < 2) return;
  const names = state.contacts.filter(item => ids.includes(Number(item.local_contact_id ?? item.id ?? item.contact_id))).map(displayName);
  try {
    await queueChange('merge', { ids, confirmed_by_user: true }, `Отправить на Android запрос на объединение контактов: ${names.join(', ')}? Проверьте выбранные контакты. Телефон попросит отдельное подтверждение.`);
  } catch (error) { setStatus(error.message || 'Не удалось отправить команду.', 'error'); }
});

$('#searchForm').addEventListener('submit', async (event) => {
  event.preventDefault();
  try { await loadContacts($('#query').value.trim()); }
  catch (error) { setStatus(error.message, 'error'); }
});
