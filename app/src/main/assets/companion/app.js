// ShrugTV phone companion (docs/companion_plan.md). Plain JS, no framework, no external requests
// except Twitch's image CDN. User content is always inserted as text, never as HTML.
'use strict';

const T = window.T || { en: {} };
// "55 s ago" / "vor 55 s"; plain duration with bare = true
const AGO = {
  en: d => `${d} ago`, de: d => `vor ${d}`, es: d => `hace ${d}`, fr: d => `il y a ${d}`, pt: d => `há ${d}`, it: d => `${d} fa`,
  pl: d => `${d} temu`, tr: d => `${d} önce`, ru: d => `${d} назад`, ja: d => `${d}前`, ko: d => `${d} 전`, zh: d => `${d}前`
};
function ago(s, lang, bare) {
  const d = s < 60 ? `${s} s` : s < 3600 ? `${Math.floor(s / 60)} min` : `${Math.floor(s / 3600)} h`;
  if (bare) return d;
  return (AGO[lang] || AGO.en)(d);
}
// The browser's language if the page has it, English otherwise
const LANG = ['en', 'de', 'es', 'fr', 'pt', 'it', 'pl', 'tr', 'ru', 'ja', 'ko', 'zh']
  .find(c => (navigator.language || 'en').toLowerCase().startsWith(c) && T[c]) || 'en';
// Texts a language does not have yet show in English
const L = new Proxy(T[LANG], { get: (t, k) => (k in t ? t[k] : T.en[k]) });
document.documentElement.lang = LANG;

const $ = id => document.getElementById(id);
let ws = null;
let state = {};
let chatOn = false;
let reconnectDelay = 1000;

function applyTexts() {
  for (const el of document.querySelectorAll('[data-i18n]')) el.textContent = L[el.dataset.i18n];
  for (const el of document.querySelectorAll('[data-i18n-label]')) el.setAttribute('aria-label', L[el.dataset.i18nLabel]);
  for (const el of document.querySelectorAll('[data-i18n-placeholder]')) el.placeholder = L[el.dataset.i18nPlaceholder];
  for (const el of document.querySelectorAll('[data-i18n-title]')) el.title = L[el.dataset.i18nTitle];
}

// ---------------------------------------------------------------- shared helpers
// Pure functions without the DOM, so a plain Node script can test them.

/** Keeps only the newest of overlapping requests: begin() aborts the one before, stale() tells an old answer. */
function newestOnly() {
  let n = 0;
  let ctl = null;
  return {
    begin() {
      if (ctl) ctl.abort();
      ctl = typeof AbortController === 'function' ? new AbortController() : null;
      const id = ++n;
      return { signal: ctl ? ctl.signal : undefined, stale: () => id !== n };
    },
    cancel() {
      if (ctl) ctl.abort();
      ctl = null;
      n++;
    }
  };
}

/** Calls fn once, ms after the last call. */
function debounce(fn, ms) {
  let t = 0;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}
// ---------------------------------------------------------------- end of shared helpers

/** Every answer of the TV; 401 always leads back to the pairing page, the caller only sees the error. */
async function api(path, options) {
  const r = await fetch(path, Object.assign({ credentials: 'same-origin' }, options || {}));
  if (r.status === 401) { showPair(''); throw new Error('unpaired'); }
  return r.json();
}

/**
 * Loading, empty and error in place of a list, like the TV's StatusNote (docs/ui_rules.md): kind is 'loading',
 * 'empty' (with the page's own reason), 'error' (with "Try again", which calls retry) or null (hide).
 */
function setStatus(el, kind, text, retry) {
  el.hidden = !kind;
  el.className = 'status-note' + (kind === 'error' ? ' error' : '');
  if (!kind) { el.replaceChildren(); return; }
  const p = document.createElement('p');
  p.textContent = kind === 'loading' ? L.stLoading : kind === 'error' ? L.stError : text;
  el.replaceChildren(p);
  if (kind === 'error' && retry) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = L.stRetry;
    b.addEventListener('click', retry);
    el.append(b);
  }
}

function showPair(message) {
  $('app').hidden = true; // also ends the log and update polling, which check it
  $('pair').hidden = false;
  $('pair-error').textContent = message || '';
}

async function start() {
  applyTexts();
  if (new URLSearchParams(location.search).has('expired')) history.replaceState(null, '', '/');
  try {
    const s = await api('/api/state');
    $('pair').hidden = true;
    $('app').hidden = false;
    render(s);
    connect();
    routeFromHash();
    updateBadge();
    loadSkin();
  } catch (e) {
    showPair(location.search.includes('expired') ? L.expired : '');
  }
}

$('pin-form').addEventListener('submit', async ev => {
  ev.preventDefault();
  let r;
  try {
    r = await fetch('/api/pin', { method: 'POST', body: JSON.stringify({ pin: $('pin').value }), credentials: 'same-origin' });
  } catch (e) {
    showPair(L.offline); // TV not reachable, the PIN may well be right
    return;
  }
  if (r.ok) start(); else showPair(r.status === 401 ? L.pinWrong : L.offline);
});

let reconnectTimer = 0;
let everConnected = false;
// The server sends a sign of life every 20 s; after 50 s of silence the connection is dead without the browser noticing
let lastHeard = Date.now();
setInterval(() => {
  if (ws && ws.readyState === WebSocket.OPEN && Date.now() - lastHeard > 50000) ws.close();
}, 5000);

// One socket at a time; a late close of an old one must not start another
function connect() {
  if (ws && (ws.readyState === WebSocket.CONNECTING || ws.readyState === WebSocket.OPEN)) return;
  clearTimeout(reconnectTimer);
  const sock = new WebSocket(`ws://${location.host}/ws`);
  ws = sock;
  sock.onopen = () => {
    $('offline').hidden = true;
    reconnectDelay = 1000;
    lastHeard = Date.now();
    if (chatOn) { $('chat').replaceChildren(); send({ t: 'chat', on: true }); }
    // After a break the settings may have changed on the TV meanwhile
    if (everConnected && currentTab === 'settings') loadSettings();
    everConnected = true;
  };
  sock.onmessage = ev => {
    lastHeard = Date.now();
    const msg = JSON.parse(ev.data);
    if (msg.t === 'state') render(msg.d);
    else if (msg.t === 'chat') addChat(msg.d.lines);
    else if (msg.t === 'settings') { settingsChanged(msg.d.key); if (/^pref_(theme_|skin$|custom_skins$|companion_skin$)/.test(msg.d.key || '')) loadSkin(); }
  };
  sock.onclose = ev => {
    if (sock !== ws) return;
    $('offline').hidden = false;
    if (ev.code === 1008) { showPair(''); return; } // session no longer valid
    // With jitter, so phones that lost the Wi-Fi together do not knock together
    reconnectTimer = setTimeout(connect, reconnectDelay * (0.8 + Math.random() * 0.4));
    reconnectDelay = Math.min(reconnectDelay * 2, 15000);
  };
}

// Back in the network or in front again: do not wait for the next retry
function reconnectNow() {
  if ($('app').hidden || (ws && ws.readyState !== WebSocket.CLOSED)) return;
  reconnectDelay = 1000;
  connect();
}
window.addEventListener('online', reconnectNow);
document.addEventListener('visibilitychange', () => { if (!document.hidden) reconnectNow(); });

// True if it was sent. Otherwise the banner says why nothing happens, and callers keep what the user typed.
function send(obj) {
  if (ws && ws.readyState === WebSocket.OPEN) { ws.send(JSON.stringify(obj)); return true; }
  $('offline').hidden = false;
  return false;
}
const key = k => send({ t: 'key', k });
const cmd = (name, payload) => send({ t: 'cmd', name, payload: payload == null ? '' : String(payload) });

function render(s) {
  state = s;
  const active = s.kind && s.kind !== 'none';
  $('now-avatar').src = s.avatar_url || '';
  $('now-avatar').hidden = !s.avatar_url;
  $('now-channel').textContent = active ? (s.channel || s.login || '') : L.nothing;
  const live = $('now-live');
  live.textContent = s.kind === 'live' ? 'LIVE' : '';
  live.classList.toggle('behind', s.behind_live_s > 30);
  const meta = [];
  if (s.kind === 'live') {
    if (s.viewers) meta.push(L.viewers(s.viewers));
    if (s.behind_live_s > 30) meta.push(L.behind(s.behind_live_s));
    else if (s.uptime_min != null) meta.push(L.since(s.uptime_min));
  }
  if (s.language) meta.push(s.language);
  $('now-meta').textContent = meta.join(' · ');
  $('now-title').textContent = [s.category, s.title].filter(Boolean).join(' · ');
  if (document.activeElement !== $('volume')) $('volume').value = s.volume == null ? 100 : s.volume;
  const q = $('quality');
  const opts = (s.qualities || []).join('|');
  if (q.dataset.opts !== opts) {
    q.replaceChildren(...(s.qualities || []).map(name => new Option(name, name)));
    q.dataset.opts = opts;
  }
  q.value = s.quality || 'Auto';
  $('layout').value = s.chat_layout || 'side_by_side';
  $('chat-form').hidden = !s.chat_allowed;
  $('chat-off').hidden = !!s.chat_allowed;
  $('btn-replay').hidden = s.kind !== 'live';
  renderSeek(s);
  const ao = $('btn-audio');
  ao.hidden = !active;
  ao.setAttribute('aria-pressed', String(!!s.audio_only));
  if (active && s.login) rememberChannel(s);
  paintNowStar();
  $('open-twitch').href = s.login ? `https://www.twitch.tv/${encodeURIComponent(s.login)}` : '#';
}

// ---------------------------------------------------------------- chat
function addChat(lines) {
  const box = $('chat');
  const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
  for (const l of lines) {
    if (l.upd) {
      const old = box.querySelector(`[data-id="${l.id}"]`);
      if (old) markDeleted(old);
      continue;
    }
    if (l.id != null && box.querySelector(`[data-id="${l.id}"]`)) continue; // the backlog arrives again after a tab change or reconnect
    const div = document.createElement('div');
    div.className = 'msg' + (l.no ? ' notice' : '') + (l.hl ? ' hl' : '');
    div.dataset.id = l.id;
    if (l.n && !l.no) div.dataset.name = l.n;
    for (const e of l.e || []) noteEmote((l.x || '').slice(e[0], e[1] + 1), e[2]);
    if (l.r) {
      const r = document.createElement('span');
      r.className = 'r';
      r.textContent = '↪ ' + l.r;
      div.append(r);
    }
    if (l.n && !l.no) {
      for (const url of l.b || []) {
        if (!/^https:\/\//.test(url)) continue;
        const b = document.createElement('img');
        b.className = 'b';
        b.src = url;
        b.alt = '';
        div.append(b);
      }
      const n = document.createElement('span');
      n.className = 'n';
      n.style.color = /^#[0-9A-F]{6}$/i.test(l.c) ? l.c : '';
      n.textContent = l.n;
      div.append(n, document.createTextNode(': '));
    }
    if (l.d) { div.append(document.createTextNode(L.removed)); div.classList.add('del'); }
    else div.append(...withEmotes(l.x || '', l.e || []));
    box.append(div);
  }
  while (box.children.length > 200) box.firstChild.remove();
  if (atBottom) box.scrollTop = box.scrollHeight;
}

function markDeleted(el) {
  const name = el.querySelector('.n');
  el.replaceChildren(...(name ? [name, document.createTextNode(': ')] : []), document.createTextNode(L.removed));
  el.classList.add('del');
}

// Text with emote images at their positions [start, end, url] (end inclusive, UTF-16)
function withEmotes(text, emotes) {
  const parts = [];
  const sorted = emotes.slice().sort((a, b) => a[0] - b[0]);
  let pos = 0;
  for (const [s, e, url] of sorted) {
    if (s < pos || !/^https:\/\//.test(url)) continue;
    if (s > pos) parts.push(document.createTextNode(text.slice(pos, s)));
    const img = document.createElement('img');
    img.src = url;
    img.alt = text.slice(s, e + 1);
    img.loading = 'lazy';
    parts.push(img);
    pos = e + 1;
  }
  if (pos < text.length) parts.push(document.createTextNode(text.slice(pos)));
  return parts;
}

$('chat-form').addEventListener('submit', ev => {
  ev.preventDefault();
  let text = $('chat-input').value.trim();
  if (text && replyTo && !/^@/.test(text)) text = `@${replyTo} ${text}`;
  if (!text) return;
  if (!cmd('chat_message', text.slice(0, 500))) return; // not connected: keep the text
  $('chat-input').value = '';
  setReply(null);
  showEmotes(false);
});

// ---------------------------------------------------------------- find
/** Channel cards; a search adds its categories below them. */
function renderCards(items, title, categories) {
  $('find-title').textContent = title;
  const cats = (categories || []).filter(c => c.id && /^[0-9]{1,12}$/.test(c.id));
  $('cards').replaceChildren(...items.map(it => {
    const li = document.createElement('li');
    const b = document.createElement('button');
    const img = document.createElement('img');
    img.alt = '';
    if (it.avatar || it.image) img.src = it.avatar || it.image;
    const t = document.createElement('div');
    t.className = 't';
    const name = document.createElement('div');
    name.textContent = (it.name || it.login) + (it.live === false ? ` · ${L.offlineCh}` : '');
    name.style.fontWeight = '700';
    const meta = document.createElement('div');
    meta.className = 'muted';
    meta.textContent = [it.category, it.viewers ? L.viewers(it.viewers) : ''].filter(Boolean).join(' · ');
    t.append(name, meta);
    if (it.title) {
      const title = document.createElement('div');
      title.className = 'muted title';
      title.textContent = it.title;
      t.append(title);
    }
    b.append(img, t);
    b.addEventListener('click', () => { if (it.login) cmd('watch', it.login); });
    li.append(b);
    if (it.id && /^[0-9]{1,12}$/.test(it.id)) {
      li.classList.add('has-star');
      li.append(starButton(it.id, !!it.favorite));
      if (it.login) {
        // Recordings and clips of the channel
        li.classList.add('has-more');
        const m = document.createElement('button');
        m.type = 'button';
        m.className = 'more-btn';
        m.textContent = '⋯';
        m.title = L.chMore;
        m.setAttribute('aria-label', `${L.chMore}: ${it.name || it.login}`);
        m.addEventListener('click', ev => { ev.stopPropagation(); openChannel({ id: it.id, login: it.login, name: it.name || it.login }, 'videos'); });
        li.append(m);
      }
    }
    return li;
  }), ...(cats.length ? [sectionRow(L.catSection)] : []), ...cats.map(c => {
    const li = document.createElement('li');
    const b = document.createElement('button');
    const img = document.createElement('img');
    img.alt = '';
    img.className = 'box';
    if (c.image) img.src = c.image;
    const t = document.createElement('div');
    t.className = 't';
    const name = document.createElement('div');
    name.textContent = c.name || '';
    name.style.fontWeight = '700';
    t.append(name);
    b.append(img, t);
    b.addEventListener('click', () => openCategory({ id: c.id, name: c.name || '' }));
    li.append(b);
    return li;
  }));
}

function sectionRow(text) {
  const li = document.createElement('li');
  li.className = 'sep';
  li.textContent = text;
  return li;
}

/** "1:02:03" or "4:05" for a length in seconds. */
function fmtLength(s) {
  s = Math.max(0, Math.round(Number(s) || 0));
  const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60), sec = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

function fmtDay(iso) {
  const d = iso ? new Date(iso) : null;
  return d && !isNaN(d) ? d.toLocaleDateString(LANG) : '';
}

/** Recordings or clips of a channel; one tap starts it on the TV with the "watch" command. */
function renderMedia(items, title, what) {
  $('find-title').textContent = title;
  $('cards').replaceChildren(...items.map(it => {
    const li = document.createElement('li');
    const b = document.createElement('button');
    const img = document.createElement('img');
    img.alt = '';
    img.className = 'thumb';
    if (it.image) img.src = it.image;
    const t = document.createElement('div');
    t.className = 't';
    const name = document.createElement('div');
    name.textContent = it.title || it.name || '';
    name.style.fontWeight = '700';
    const meta = document.createElement('div');
    meta.className = 'muted';
    meta.textContent = [fmtDay(it.date), it.duration_s ? fmtLength(it.duration_s) : '', it.views ? L.views(it.views) : ''].filter(Boolean).join(' · ');
    t.append(name, meta);
    b.append(img, t);
    b.addEventListener('click', () => {
      const payload = what === 'videos' ? { vod_id: Number(it.vod_id) } : { clip: it.clip };
      if (cmd('watch', JSON.stringify(payload))) buzz(10);
    });
    li.append(b);
    return li;
  }));
}

// The lists of the TV's channel switcher: the same channels, one tap starts one on the TV
const LIST_KEYS = { followed: 'listFollowed', same_game: 'listSameGame', same_game_language: 'listSameGameLanguage',
  history: 'listHistory', top_streams: 'listTopStreams', top_language: 'listTopLanguage' };
let currentList = 'followed';
let listEnabled = Object.keys(LIST_KEYS);

// A category or a channel opened from the cards: null, {kind: 'category', id, name} or
// {kind: 'channel', id, login, name, what: 'videos'|'clips'}. "Back" returns to the list or search it came from.
let findView = null;

function chip(text, pressed, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = text;
  if (pressed !== null) b.setAttribute('aria-pressed', String(pressed));
  b.addEventListener('click', onClick);
  return b;
}

function renderListChips() {
  if (findView) {
    const v = findView;
    const chips = [chip(L.findBack, null, leaveView)];
    if (v.kind === 'channel') {
      chips.push(chip(L.chVideos, v.what === 'videos', () => openChannel(v, 'videos')),
        chip(L.chClips, v.what === 'clips', () => openChannel(v, 'clips')));
    }
    $('lists').replaceChildren(...chips);
    return;
  }
  $('lists').replaceChildren(...listEnabled.filter(n => LIST_KEYS[n]).map(n => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = L[LIST_KEYS[n]];
    b.setAttribute('aria-pressed', String(n === currentList && !$('search').value.trim()));
    b.addEventListener('click', () => { $('search').value = ''; currentList = n; loadLive(); });
    return b;
  }));
}

// One request at a time for the cards: a newer chip, search or list replaces the one still on its way
const findReq = newestOnly();
let cardsShown = null; // what the cards show: 'list:<name>' or 'search:<text>'

/** Shows what is asked for: another list or search empties the cards at once, the same one keeps them while loading. */
function cardsFor(what, title) {
  if (cardsShown !== what) { renderCards([], title); cardsShown = what; }
  $('find-title').textContent = title;
  if (!$('cards').childElementCount) setStatus($('cards-status'), 'loading');
}

function openCategory(c) {
  findView = { kind: 'category', id: c.id, name: c.name };
  loadView();
}

function openChannel(ch, what) {
  findView = { kind: 'channel', id: ch.id, login: ch.login, name: ch.name, what };
  loadView();
}

function leaveView() {
  findView = null;
  const q = $('search').value.trim();
  if (q) runSearch(q); else loadLive();
}

/** The find tab as it was left: a category or channel stays open when the tab is shown again. */
function refreshFind() {
  if (findView) loadView(); else loadLive();
}

async function loadView() {
  const v = findView;
  if (!v) { loadLive(); return; }
  renderListChips();
  const isCat = v.kind === 'category';
  const title = isCat ? (v.name || L.catSection) : `${v.name} · ${v.what === 'videos' ? L.chVideos : L.chClips}`;
  const req = findReq.begin();
  cardsFor(isCat ? 'cat:' + v.id : `ch:${v.id}:${v.what}`, title);
  const path = (isCat ? '/api/category' : `/api/channel/${v.what}`) + '?id=' + encodeURIComponent(v.id);
  let r;
  try {
    r = await api(path, { signal: req.signal });
    if (!r || !Array.isArray(r.items)) throw new Error('no list'); // an error answer is no empty list
  } catch (e) {
    if (!req.stale() && e.message !== 'unpaired') { cardsShown = null; renderCards([], title); setStatus($('cards-status'), 'error', null, loadView); }
    return;
  }
  if (req.stale() || findView !== v) return;
  if (isCat) renderCards(r.items, title); else renderMedia(r.items, title, v.what);
  setStatus($('cards-status'), r.items.length ? null : 'empty', isCat ? L.catEmpty : v.what === 'videos' ? L.vodsEmpty : L.clipsEmpty);
}

async function loadLive() {
  findView = null;
  renderListChips();
  const list = currentList;
  const title = L[LIST_KEYS[list]] || L.liveNow;
  const req = findReq.begin();
  cardsFor('list:' + list, title);
  let r;
  try {
    r = await api('/api/lists?name=' + encodeURIComponent(list), { signal: req.signal });
    if (!r || !Array.isArray(r.items)) throw new Error('no list'); // an error answer is no empty list
  } catch (e) {
    if (!req.stale() && e.message !== 'unpaired') { cardsShown = null; renderCards([], title); setStatus($('cards-status'), 'error', null, loadLive); }
    return;
  }
  if (req.stale()) return;
  if (Array.isArray(r.enabled)) {
    listEnabled = r.enabled;
    // The list was switched off on the TV: show the first one still on instead
    if (!listEnabled.includes(list)) { currentList = listEnabled[0] || 'followed'; if (currentList !== list) { loadLive(); return; } }
    renderListChips();
  }
  const items = r.items;
  renderCards(items, title);
  const needsPlaying = (list === 'same_game' || list === 'same_game_language') && !r.playing;
  setStatus($('cards-status'), items.length ? null : 'empty', needsPlaying ? L.listNeedsPlaying : L.listEmpty);
}

async function runSearch(q) {
  findView = null;
  const req = findReq.begin();
  renderListChips();
  cardsFor('search:' + q, L.results);
  let r;
  try {
    r = await api('/api/search?q=' + encodeURIComponent(q), { signal: req.signal });
    if (!r || !Array.isArray(r.items)) throw new Error('no list'); // an error answer is no empty list
  } catch (e) {
    if (!req.stale() && e.message !== 'unpaired') { cardsShown = null; renderCards([], L.results); setStatus($('cards-status'), 'error', null, () => runSearch(q)); }
    return;
  }
  if (req.stale()) return;
  const items = r.items;
  const cats = Array.isArray(r.categories) ? r.categories : [];
  renderCards(items, L.results, cats);
  setStatus($('cards-status'), items.length || cats.length ? null : 'empty', L.searchEmpty);
}

$('search-form').addEventListener('submit', ev => {
  ev.preventDefault();
  const q = $('search').value.trim();
  if (q) runSearch(q); else loadLive();
});

// ---------------------------------------------------------------- statistics, short
const statsReq = newestOnly();

/** "1:05 h" for seconds watched. */
function fmtWatched(s) {
  const m = Math.round((Number(s) || 0) / 60);
  return `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')} h`;
}

function statsTile(label, p) {
  const d = document.createElement('div');
  d.className = 'stats-tile';
  const head = document.createElement('div');
  head.className = 'muted';
  head.textContent = label;
  const big = document.createElement('div');
  big.className = 'big';
  big.textContent = fmtWatched(p.total_s);
  d.append(head, big);
  for (const [k, v] of [[L.statsTopChannel, p.channel], [L.statsTopCategory, p.category]]) {
    if (!v) continue;
    const line = document.createElement('div');
    line.className = 'muted line';
    line.textContent = `${k}: ${v}`;
    d.append(line);
  }
  return d;
}

/** Today and the last 7 days from the TV's statistics, below the version. */
async function loadStats() {
  const req = statsReq.begin();
  if (!$('stats-tiles').childElementCount) setStatus($('stats-status'), 'loading');
  let r;
  try {
    r = await api('/api/stats', { signal: req.signal });
    if (!r || !r.today || !r.week) throw new Error('no stats'); // an error answer is no empty statistics
  } catch (e) {
    if (!req.stale() && e.message !== 'unpaired') { $('stats-tiles').replaceChildren(); setStatus($('stats-status'), 'error', null, loadStats); }
    return;
  }
  if (req.stale()) return;
  if (!r.week.sessions) {
    $('stats-tiles').replaceChildren();
    setStatus($('stats-status'), 'empty', L.statsEmpty);
    return;
  }
  $('stats-tiles').replaceChildren(statsTile(L.statsToday, r.today), statsTile(L.statsWeek, r.week));
  setStatus($('stats-status'), null);
}

// ---------------------------------------------------------------- tabs, deep links and controls
// Every tab and settings group has its own address: #remote, #chat, #find, #player, #settings,
// #settings/<group key>, #settings/pinned (bookmarkable, like Glasshouse's ?tab=…)
const TABS = ['remote', 'chat', 'find', 'player', 'update', 'settings', 'log'];
let currentTab = 'remote';

function showTab(name, updateHash) {
  if (!TABS.includes(name)) name = 'remote';
  currentTab = name;
  for (const t of document.querySelectorAll('[role=tab]')) t.setAttribute('aria-selected', String(t.dataset.tab === name));
  for (const s of document.querySelectorAll('.tab')) s.hidden = s.id !== 'tab-' + name;
  $('set-groups').hidden = name !== 'settings';
  const wasOn = chatOn;
  chatOn = name === 'chat';
  // The server sends the backlog again whenever the chat is switched on: start from an empty box
  if (chatOn && !wasOn) $('chat').replaceChildren();
  send({ t: 'chat', on: chatOn });
  if (name === 'find') refreshFind();
  if (name === 'settings') loadSettings();
  if (name === 'log') logStart(); else logStop();
  if (name === 'update') { updateStart(); loadStats(); } else updateStop();
  if (updateHash !== false) setHash();
}

function setHash() {
  let h = '#' + currentTab;
  if (currentTab === 'settings' && setTree) h += '/' + (setGroup === PINNED ? 'pinned' : (setTree.pages[setGroup] || {}).key || '');
  if (location.hash !== h) history.replaceState(null, '', location.pathname + location.search + h);
}

let wantedGroup = null; // group key from the address, applied once the tree is loaded
function routeFromHash() {
  const [tab, group] = location.hash.replace(/^#/, '').split('/');
  wantedGroup = group || null;
  if (group) $('set-search').value = '';
  if (tab === currentTab && tab === 'settings' && setTree) { applyWantedGroup(); renderGroups(); renderSettings(); return; }
  showTab(tab || 'remote', false);
}
window.addEventListener('hashchange', routeFromHash);

for (const tab of document.querySelectorAll('[role=tab]')) tab.addEventListener('click', () => showTab(tab.dataset.tab));
// Arrow keys, Home and End move between the tabs (tablist pattern)
$('side').addEventListener('keydown', ev => {
  const tabs = [...document.querySelectorAll('[role=tab]')];
  const at = tabs.indexOf(document.activeElement);
  if (at < 0) return;
  const to = { ArrowRight: at + 1, ArrowDown: at + 1, ArrowLeft: at - 1, ArrowUp: at - 1, Home: 0, End: tabs.length - 1 }[ev.key];
  if (to == null) return;
  ev.preventDefault();
  const next = tabs[(to + tabs.length) % tabs.length];
  next.focus();
  showTab(next.dataset.tab);
});
for (const b of document.querySelectorAll('[data-key]')) b.addEventListener('click', () => key(b.dataset.key));
for (const b of document.querySelectorAll('[data-cmd]')) b.addEventListener('click', () => cmd(b.dataset.cmd, b.dataset.payload));
$('volume').addEventListener('change', ev => cmd('volume', ev.target.value));
$('quality').addEventListener('change', ev => cmd('quality', ev.target.value));
$('layout').addEventListener('change', ev => cmd('chat_layout', ev.target.value));

// ---------------------------------------------------------------- theme and keyboard
function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  document.querySelector('meta[name=theme-color]').content = t === 'light' ? '#F7F7F8' : '#0B0B0E';
  $('theme').textContent = t === 'light' ? '☾' : '☀'; // shows what a click switches to
}
$('theme').addEventListener('click', () => {
  const t = document.documentElement.dataset.theme === 'light' ? 'dark' : 'light';
  applyTheme(t);
  store('shrugtv.theme', t);
});
applyTheme(document.documentElement.dataset.theme === 'light' ? 'light' : 'dark');
$('set-search').value = ''; // browsers restore form fields on reload

// "/" or Ctrl/Cmd+K: search the settings from anywhere (computer keyboard)
document.addEventListener('keydown', ev => {
  const typing = ev.target && (ev.target.tagName === 'INPUT' || ev.target.tagName === 'SELECT' || ev.target.tagName === 'TEXTAREA');
  if ((ev.key === 'k' && (ev.ctrlKey || ev.metaKey)) || (ev.key === '/' && !typing)) {
    if ($('app').hidden) return;
    ev.preventDefault();
    if (currentTab !== 'settings') showTab('settings');
    $('set-search').focus();
    $('set-search').select();
  } else if (ev.key === 'Escape' && ev.target === $('set-search') && $('set-search').value) {
    $('set-search').value = '';
    renderGroups();
    renderSettings();
  }
});

// Computer keyboard as the remote on the Remote and Player tabs: arrows, Enter, Backspace/Escape, Space.
// Not while something that has its own use for the keys has the focus.
document.addEventListener('keydown', ev => {
  if ($('app').hidden || ev.ctrlKey || ev.metaKey || ev.altKey || (currentTab !== 'remote' && currentTab !== 'player')) return;
  const t = ev.target;
  if (t && (t.closest('input, select, textarea, button, a, [contenteditable], #pad') || t.isContentEditable)) return;
  const k = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'ok', Backspace: 'back', Escape: 'back', ' ': 'play_pause' }[ev.key];
  if (!k) return;
  ev.preventDefault();
  key(k);
});

// Small per-browser memory (theme, pinned settings, opened sections)
function load(name, fallback) {
  try { const v = JSON.parse(localStorage.getItem(name)); return v == null ? fallback : v; } catch (e) { return fallback; }
}
function store(name, value) {
  try { localStorage.setItem(name, typeof value === 'string' ? value : JSON.stringify(value)); } catch (e) { /* private mode */ }
}

// ---------------------------------------------------------------- settings (all app settings, docs/companion_plan.md 8)
const PINNED = -1;
let setTree = null;
let setGroup = 0;
let setValues = {};
let setTimer = null;
let pins = load('shrugtv.pins', []);
let openSections = load('shrugtv.open', []);
const ownKeys = new Map(); // key -> time of our own change (skip the echo from the TV)

async function loadSettings() {
  try {
    const r = await fetch('/api/settings?lang=' + LANG, { credentials: 'same-origin' });
    if (r.status === 401) { showPair(''); return; }
    const off = r.status === 403;
    $('set-off').hidden = !off;
    $('set-ui').hidden = off;
    if (off) { $('set-groups').replaceChildren(); return; }
    setTree = await r.json();
    setValues = Object.assign({}, setTree.standards);
    collectValues(setTree.pages);
    applyWantedGroup();
    const focusKey = document.activeElement && document.activeElement.dataset ? document.activeElement.dataset.key : null;
    renderGroups();
    renderSettings();
    setHash();
    if (focusKey) {
      const el = document.querySelector(`#set-content [data-key="${CSS.escape(focusKey)}"]`);
      if (el) el.focus();
    }
  } catch (e) { /* offline: the banner says so */ }
}

function applyWantedGroup() {
  if (!wantedGroup || !setTree) return;
  if (wantedGroup === 'pinned') setGroup = PINNED;
  else {
    const i = setTree.pages.findIndex(p => p.key === wantedGroup);
    if (i >= 0) setGroup = i;
  }
  wantedGroup = null;
}

function collectValues(list) {
  for (const e of list) {
    if (e.children) collectValues(e.children);
    else if (e.key && e.value !== undefined) setValues[e.key] = e.value;
  }
}

/** Every setting with the titles of the pages above it. */
function allEntries() {
  const out = [];
  const walk = (list, path) => {
    for (const e of list) {
      if (e.children) walk(e.children, e.type === 'page' ? path.concat(e.title) : path);
      else out.push([e, path]);
    }
  };
  walk(setTree.pages, []);
  return out;
}

function settingsChanged(key) {
  const own = ownKeys.get(key);
  if (own && Date.now() - own < 3000) return;
  if (currentTab !== 'settings') return;
  // Do not pull the field from under the user's fingers while typing
  const a = document.activeElement;
  if (a && a.tagName === 'INPUT' && a.type !== 'checkbox' && $('tab-settings').contains(a)) return;
  clearTimeout(setTimer);
  setTimer = setTimeout(loadSettings, 400);
}

function groupButton(text, current, onClick, cls) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = text;
  if (cls) b.className = cls;
  b.setAttribute('aria-current', String(current));
  b.addEventListener('click', onClick);
  return b;
}

function selectGroup(i) {
  setGroup = i;
  $('set-search').value = '';
  renderGroups();
  renderSettings();
  setHash();
  $('set-content').scrollIntoView({ block: 'start', behavior: 'smooth' });
}

function renderGroups() {
  if (!setTree) return;
  const pages = setTree.pages;
  if (setGroup >= pages.length || (setGroup === PINNED && !pins.length)) setGroup = 0;
  const searching = !!searchText();
  const buttons = [];
  if (pins.length) buttons.push(groupButton('★ ' + L.pinned, setGroup === PINNED && !searching, () => selectGroup(PINNED), 'pin-group'));
  pages.forEach((p, i) => buttons.push(groupButton(p.title, i === setGroup && !searching, () => selectGroup(i))));
  $('set-groups').replaceChildren(...buttons);
}

const searchText = () => $('set-search').value.trim().toLowerCase();

function renderSettings() {
  const box = $('set-content');
  const q = searchText();
  if (q) {
    const hits = allEntries().filter(([e]) => e.type !== 'tip' && [e.title, e.text, e.key].some(t => t && t.toLowerCase().includes(q)));
    box.replaceChildren(hits.length ? flatGroup(hits) : para(L.noResults, 'muted'));
  } else if (setGroup === PINNED) {
    const byKey = new Map(allEntries().filter(([e]) => e.key).map(x => [x[0].key, x]));
    const list = pins.map(k => byKey.get(k)).filter(Boolean);
    const h = document.createElement('h2');
    h.textContent = '★ ' + L.pinned;
    box.replaceChildren(h, list.length ? flatGroup(list) : para(L.noPins, 'muted'));
  } else {
    const page = setTree.pages[setGroup];
    const h = document.createElement('h2');
    h.textContent = page.title;
    box.replaceChildren(h, ...(page.text ? [para(page.text, 'muted')] : []), ...renderList(page.children, 0));
  }
  updateActive();
  if ($('mqtt-card')) refreshMqtt();
}

// ---------------------------------------------------------------- MQTT connection card
const MQTT_GROUPS = { pref_mqtt_entities_info: 'info', pref_mqtt_entities_avatar: 'avatar', pref_mqtt_entities_preview: 'preview', pref_mqtt_entities_events: 'events', pref_mqtt_entities_followed: 'followed', pref_mqtt_commands: 'controls' };
let mqttTimer = null;

function mqttCard() {
  const card = document.createElement('div');
  card.id = 'mqtt-card';
  card.className = 'conn';
  const h = document.createElement('div');
  h.className = 'conn-title';
  h.textContent = L.connection;
  card.append(h, connRow('tv', 'ShrugTV', L.reachable + (setTree.version ? ` · ${setTree.version}` : ''), 'ok'), connRow('mqtt', L.mqttBroker, '…', 'off'));
  const facts = document.createElement('dl');
  facts.className = 'conn-facts';
  card.append(facts);
  return card;
}

function connRow(id, name, sub, dot) {
  const row = document.createElement('div');
  row.className = 'conn-row';
  row.id = 'conn-' + id;
  const icon = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  icon.setAttribute('viewBox', '0 0 24 24');
  icon.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', id === 'tv' ? 'M3 5h18v12H3zM8 21h8M12 17v4' : 'M6 12a2 2 0 1 0 0 .01M18 6a2 2 0 1 0 0 .01M18 18a2 2 0 1 0 0 .01M8 11l8-4M8 13l8 4');
  icon.append(path);
  const text = document.createElement('div');
  text.className = 'conn-text';
  const n = document.createElement('strong');
  n.textContent = name;
  const s = document.createElement('span');
  s.className = 'muted conn-sub';
  s.textContent = sub;
  text.append(n, s);
  const d = document.createElement('span');
  d.className = 'dot ' + dot;
  row.append(icon, text, d);
  return row;
}

async function refreshMqtt() {
  clearTimeout(mqttTimer);
  const card = $('mqtt-card');
  if (!card || currentTab !== 'settings') return;
  try {
    const r = await fetch('/api/mqtt', { credentials: 'same-origin' });
    if (!r.ok) return;
    const m = await r.json();
    const row = $('conn-mqtt');
    const status = L.mqttStates[m.status] || m.status;
    row.querySelector('strong').textContent = m.broker ? m.broker.replace(/^tcp:\/\//, '') : L.mqttBroker;
    const sub = [m.connected_s != null ? L.connectedFor(m.connected_s) : status];
    if (m.last_publish_s != null) sub.push(L.lastPublish(m.last_publish_s));
    if (m.broker && !m.tls) sub.push(L.noTls);
    row.querySelector('.conn-sub').textContent = sub.join(' · ');
    row.querySelector('.dot').className = 'dot ' + (m.connected ? 'ok' : m.status === 'off' ? 'off' : m.status === 'connecting' ? 'wait' : 'bad');
    const facts = card.querySelector('.conn-facts');
    facts.replaceChildren(...[[L.deviceId, m.device_id + (m.name_taken ? ` (${L.nameTaken})` : '')], [L.stateTopic, m.state_topic], [L.commandTopic, m.command_topic]].flatMap(([k, v]) => {
      const dt = document.createElement('dt');
      dt.textContent = k;
      const dd = document.createElement('dd');
      dd.textContent = v || '–';
      return [dt, dd];
    }));
    for (const [key, group] of Object.entries(MQTT_GROUPS)) {
      const title = document.querySelector(`[data-row="${key}"] .set-title`);
      if (!title || m.counts[group] == null) continue;
      let c = title.parentElement.querySelector('.count');
      if (!c) { c = document.createElement('span'); c.className = 'count muted'; title.after(c); }
      c.textContent = L.entities(m.counts[group]);
    }
  } catch (e) { /* offline */ }
  mqttTimer = setTimeout(refreshMqtt, 5000);
}

// ---------------------------------------------------------------- remembered devices
function devicesCard() {
  const card = document.createElement('div');
  card.id = 'devices-card';
  card.className = 'conn';
  const h = document.createElement('div');
  h.className = 'conn-title';
  h.textContent = L.devicesTitle;
  card.append(h, para('…', 'muted'));
  setTimeout(loadDevices, 0);
  return card;
}

function relTime(t) {
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 90) return L.justNow;
  return ago(s, LANG);
}

async function loadDevices() {
  const card = $('devices-card');
  if (!card) return;
  try {
    const r = await fetch('/api/devices', { credentials: 'same-origin' });
    if (!r.ok) return;
    const d = await r.json();
    const rows = d.devices.map(dev => {
      const row = document.createElement('div');
      row.className = 'conn-row';
      const text = document.createElement('div');
      text.className = 'conn-text';
      const n = document.createElement('strong');
      n.textContent = dev.name + (dev.id === d.current ? ` (${L.thisDevice})` : '');
      const s = document.createElement('span');
      s.className = 'muted conn-sub';
      s.textContent = L.deviceSeen(relTime(dev.seen), new Date(dev.paired).toLocaleDateString(LANG));
      text.append(n, s);
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'small';
      b.textContent = L.remove;
      b.addEventListener('click', async () => {
        if (!confirm(L.removeDevice(dev.name))) return;
        try {
          await postJson('/api/devices/remove', { id: dev.id });
          // Removing this device ends its pairing: the page asks for the code again
          if (dev.id === d.current) { showPair(''); return; }
          loadDevices();
        } catch (e) { /* offline */ }
      });
      row.append(text, b);
      return row;
    });
    const head = card.querySelector('.conn-title');
    card.replaceChildren(head, ...(rows.length ? rows : [para(d.remember ? L.noDevices : L.rememberOff, 'muted')]));
  } catch (e) { /* offline */ }
}

/** Search results and pinned settings: one box, each row with its place in the settings. */
function flatGroup(items) {
  const group = document.createElement('section');
  group.className = 'set-group';
  group.append(...items.map(([e, path]) => {
    const row = renderEntry(e);
    row.prepend(para(path.join(' › '), 'crumb'));
    return row;
  }));
  return group;
}

function para(text, cls) {
  const p = document.createElement('p');
  p.className = cls || '';
  p.textContent = text;
  return p;
}

function renderList(list, depth) {
  const out = [];
  for (const e of list) {
    if (e.type === 'page') {
      // Sub-pages (clock, remote shortcuts, filters …) are folded away; the browser remembers which are open
      const d = document.createElement('details');
      d.className = 'set-group sub';
      if (e.dep) d.dataset.dep = e.dep;
      d.open = openSections.includes(e.key);
      if (e.key) d.dataset.section = e.key;
      const sum = document.createElement('summary');
      sum.textContent = e.title;
      d.append(sum);
      if (e.text) d.append(para(e.text, 'muted'));
      d.append(...renderList(e.children, depth + 1));
      d.addEventListener('toggle', () => {
        if (!e.key) return;
        openSections = openSections.filter(k => k !== e.key);
        if (d.open) openSections.push(e.key);
        store('shrugtv.open', openSections);
      });
      out.push(d);
    } else if (e.type === 'category' || e.type === 'page') {
      const sec = document.createElement('section');
      sec.className = 'set-group';
      if (e.dep) sec.dataset.dep = e.dep;
      const h = document.createElement(depth === 0 ? 'h3' : 'h4');
      h.className = 'set-heading';
      const ht = document.createElement('span');
      ht.textContent = e.title;
      h.append(ht);
      sec.append(h);
      if (e.text) sec.append(para(e.text, 'muted'));
      // The group's guide sits behind an (i) next to its title instead of taking room in the list
      const guide = e.children.find(c => c.type === 'tip');
      if (guide) {
        const box = para(guide.text || guide.title, 'set-tip');
        box.hidden = true;
        box.id = 'tip-' + Math.random().toString(36).slice(2);
        h.append(infoButton(box));
        sec.append(box);
      }
      // The connection card replaces the "Connection status" button in the browser
      const own = e.children.filter(c => c !== guide && !(e.panel === 'mqtt' && c.key === 'pref_mqtt_status') &&
        !(e.panel === 'devices' && c.key === 'pref_companion_devices'));
      const children = renderList(own, depth + 1);
      if (e.panel === 'mqtt') children.unshift(mqttCard()); // live card, like Glasshouse's MQTT page
      if (e.panel === 'devices') children.push(devicesCard());
      if (e.panel === 'design') children.push(designCard());
      sec.append(...children);
      out.push(sec);
    } else {
      out.push(renderEntry(e));
    }
  }
  return out;
}

function pinButton(e) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'pin';
  const on = pins.includes(e.key);
  b.textContent = on ? '★' : '☆';
  b.setAttribute('aria-pressed', String(on));
  b.setAttribute('aria-label', on ? L.unpin : L.pin);
  b.title = on ? L.unpin : L.pin;
  b.addEventListener('click', () => {
    pins = pins.includes(e.key) ? pins.filter(k => k !== e.key) : pins.concat(e.key);
    store('shrugtv.pins', pins);
    renderGroups();
    if (setGroup === PINNED || !pins.length) renderSettings();
    else {
      const now = pins.includes(e.key);
      b.textContent = now ? '★' : '☆';
      b.setAttribute('aria-pressed', String(now));
      b.setAttribute('aria-label', now ? L.unpin : L.pin);
      b.title = now ? L.unpin : L.pin;
    }
  });
  return b;
}

function renderEntry(e) {
  // The live preview is the TV's; the browser changes the TV directly
  if (e.type === 'preview') return document.createComment('preview');
  const row = document.createElement('div');
  if (e.type === 'tip') {
    // A guide outside a group (sub-pages, search): a small (i) line that opens the text
    row.className = 'set-tip-line';
    const box = para(e.text || e.title, 'set-tip');
    box.hidden = true;
    box.id = 'tip-' + Math.random().toString(36).slice(2);
    const label = document.createElement('span');
    label.className = 'muted';
    label.textContent = L.guide;
    row.append(infoButton(box), label, box);
    return row;
  }
  row.className = 'set-row';
  if (e.dep) row.dataset.dep = e.dep;
  if (e.key) row.dataset.row = e.key;
  const label = document.createElement('div');
  label.className = 'set-label';
  const id = 'set-' + (e.key || Math.random().toString(36).slice(2));
  const head = document.createElement('div');
  head.className = 'set-head';
  const title = document.createElement(e.type === 'action' ? 'span' : 'label');
  title.className = 'set-title';
  title.textContent = e.place ? `${e.place}. ${e.title}` : e.title;
  if (e.type !== 'action') title.htmlFor = id;
  head.append(title);
  if (e.key && e.type !== 'action') head.append(pinButton(e));
  label.append(head);
  const desc = toggleText(e);
  if (desc) label.append(para(desc, 'muted desc'));
  row.append(label);

  let control = null;
  if (e.type === 'toggle') {
    control = document.createElement('input');
    control.type = 'checkbox';
    control.className = 'switch';
    control.checked = !!setValues[e.key];
    control.addEventListener('change', () => save(e, control.checked, row, control));
  } else if (e.type === 'list') {
    control = document.createElement('select');
    control.replaceChildren(...e.options.map(([v, n]) => new Option(n, v)));
    control.value = e.value;
    control.addEventListener('change', () => save(e, control.value, row, control));
  } else if (e.type === 'number') {
    control = document.createElement('input');
    control.type = 'number';
    control.min = e.min;
    control.max = e.max;
    control.step = e.step;
    control.inputMode = 'numeric';
    control.value = e.value;
    control.addEventListener('change', () => {
      const n = Math.round(Number(control.value));
      if (control.value === '' || !Number.isFinite(n)) { revert(e, control); return; }
      const clamped = Math.min(e.max, Math.max(e.min, n));
      control.value = clamped;
      save(e, clamped, row, control);
    });
    control.addEventListener('keydown', ev => { if (ev.key === 'Enter') control.blur(); });
  } else if (e.type === 'text') {
    control = document.createElement('input');
    control.maxLength = 500;
    control.autocomplete = 'off';
    if (e.secret) {
      control.type = 'password';
      control.placeholder = e.isSet ? L.secretSet : L.secretEmpty;
    } else {
      control.value = e.value || '';
    }
    control.addEventListener('change', () => { if (!e.secret || control.value) save(e, control.value, row, control); });
    control.addEventListener('keydown', ev => { if (ev.key === 'Enter') control.blur(); });
  } else if (e.type === 'color') {
    // A colour of the design: colour field plus "from the skin"
    control = document.createElement('span');
    control.className = 'color-pick';
    const field = document.createElement('input');
    field.type = 'color';
    field.value = (e.value && e.value !== 'skin' ? e.value : e.effective || '#9147ff').toLowerCase();
    const reset = document.createElement('button');
    reset.type = 'button';
    reset.className = 'small';
    reset.textContent = L.fromSkin;
    reset.hidden = !e.value || e.value === 'skin';
    field.addEventListener('change', async () => {
      await save(e, field.value.toUpperCase(), row, field);
      reset.hidden = false;
    });
    reset.addEventListener('click', async () => {
      await save(e, 'skin', row, field);
      reset.hidden = true;
      setTimeout(loadSettings, 300); // the field shows the skin's colour again
    });
    control.append(field, reset);
  } else if (e.type === 'skin') {
    control = skinTiles(e);
    row.classList.add('set-row-wide');
  } else if (e.run) {
    control = document.createElement('button');
    control.type = 'button';
    control.textContent = e.place ? L.moveUp : L.run;
    control.addEventListener('click', () => runAction(e, row));
  }
  if (control) {
    control.id = id;
    if (e.key) control.dataset.key = e.key;
    row.append(control);
    // Without a PIN the TV keeps these to itself: shown, but not changeable from here
    if (e.tv_only) {
      row.classList.add('tv-only');
      label.append(para(L.tvOnlyPin, 'muted'));
      for (const c of [control, ...control.querySelectorAll('input, select, button')]) c.disabled = true;
    }
  } else if (e.type === 'action') {
    row.append(para(e.info || L.tvOnly, e.info ? 'set-info' : 'muted'));
  }
  if (e.run && e.info) label.append(para(`${L.status}: ${e.info}`, 'muted'));
  return row;
}

/** The skins as tiles in their own colours; a click picks one and clears own changes, as on the TV. */
function skinTiles(e) {
  const box = document.createElement('div');
  box.className = 'skins';
  for (const [id, name, bg, surface, accent, text] of e.options) {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'skin' + (id === e.value ? ' on' : '');
    b.style.background = bg;
    b.style.color = text;
    b.setAttribute('aria-pressed', String(id === e.value));
    const card = document.createElement('span');
    card.className = 'skin-card';
    card.style.background = surface;
    const dot = document.createElement('span');
    dot.className = 'skin-accent';
    dot.style.background = accent;
    const label = document.createElement('span');
    label.className = 'skin-name';
    label.textContent = name;
    b.append(card, dot, label);
    b.addEventListener('click', async () => {
      try {
        const res = await postJson('/api/design', { op: 'pick', id });
        if (res.ok) loadSettings();
      } catch (err) { /* offline */ }
    });
    box.append(b);
  }
  return box;
}

/** Own skins: save the current design, delete, export as a file, import a file. */
function designCard() {
  const card = document.createElement('div');
  card.className = 'conn';
  const h = document.createElement('div');
  h.className = 'conn-title';
  h.textContent = L.designTitle;
  const skinEntry = findEntry(setTree.pages, 'pref_skin');
  const own = skinEntry ? skinEntry.options.filter(o => o[6]) : [];
  const status = para('', 'muted');
  const rows = own.map(([id, name]) => {
    const row = document.createElement('div');
    row.className = 'conn-row';
    const n = document.createElement('strong');
    n.textContent = name;
    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'small';
    del.textContent = L.remove;
    del.addEventListener('click', async () => {
      if (!confirm(L.designDelete(name))) return;
      try { await postJson('/api/design', { op: 'delete', id }); loadSettings(); } catch (err) { /* offline */ }
    });
    row.append(n, del);
    return row;
  });
  const saveRow = document.createElement('div');
  saveRow.className = 'conn-row';
  const nameField = document.createElement('input');
  nameField.maxLength = 40;
  nameField.placeholder = L.designName;
  const saveBtn = document.createElement('button');
  saveBtn.type = 'button';
  saveBtn.textContent = L.designSave;
  saveBtn.addEventListener('click', async () => {
    const name = nameField.value.trim();
    if (!name) { nameField.focus(); return; }
    try {
      const res = await postJson('/api/design', { op: 'save', name });
      if (res.ok) { status.textContent = L.designSaved(name); loadSettings(); } else status.textContent = res.error === 'full' ? L.designFull : L.notSaved;
    } catch (err) { status.textContent = L.offline; }
  });
  saveRow.append(nameField, saveBtn);
  const fileRow = document.createElement('div');
  fileRow.className = 'conn-row';
  const exp = document.createElement('button');
  exp.type = 'button';
  exp.className = 'small';
  exp.textContent = L.designExport;
  exp.addEventListener('click', async () => {
    try {
      const name = nameField.value.trim() || 'ShrugTV';
      const res = await postJson('/api/design', { op: 'export', name });
      if (!res.ok) return;
      const a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([res.json], { type: 'application/json' }));
      a.download = 'shrugtv-design-' + name.replace(/[^\w-]+/g, '_') + '.json';
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    } catch (err) { status.textContent = L.offline; }
  });
  const file = document.createElement('input');
  file.type = 'file';
  file.accept = '.json,application/json';
  file.hidden = true;
  const imp = document.createElement('button');
  imp.type = 'button';
  imp.className = 'small';
  imp.textContent = L.designImport;
  imp.addEventListener('click', () => file.click());
  file.addEventListener('change', async () => {
    const f = file.files && file.files[0];
    if (!f) return;
    try {
      const res = await postJson('/api/design', { op: 'import', json: await f.text() });
      if (res.ok) { status.textContent = L.designImported(res.name); loadSettings(); }
      else status.textContent = res.error === 'full' ? L.designFull : L.designBad;
    } catch (err) { status.textContent = L.offline; }
    file.value = '';
  });
  fileRow.append(exp, imp, file);
  card.append(h, ...(rows.length ? rows : [para(L.designNone, 'muted')]), saveRow, fileRow, status);
  return card;
}

function findEntry(list, key) {
  for (const e of list || []) {
    if (e.key === key) return e;
    const hit = findEntry(e.children, key);
    if (hit) return hit;
  }
  return null;
}

/** (i) button that shows or hides a guide. */
function infoButton(box) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'info';
  b.textContent = 'i';
  b.setAttribute('aria-label', L.guideShow);
  b.title = L.guideShow;
  b.setAttribute('aria-expanded', 'false');
  b.setAttribute('aria-controls', box.id);
  b.addEventListener('click', ev => {
    ev.preventDefault();
    ev.stopPropagation(); // inside a <summary> it must not fold the section
    box.hidden = !box.hidden;
    b.setAttribute('aria-expanded', String(!box.hidden));
  });
  return b;
}

function toggleText(e) {
  if (e.type === 'toggle' && !e.text) return setValues[e.key] ? (e.textOn || '') : (e.textOff || '');
  return e.text || '';
}

// Rows whose switch (android:dependency) is off are greyed out, as on the TV
function updateActive() {
  for (const el of document.querySelectorAll('#set-content [data-dep]')) el.classList.toggle('inactive', !setValues[el.dataset.dep]);
  for (const c of document.querySelectorAll('#set-content .set-row > input, #set-content .set-row > select, #set-content .set-row > button')) {
    c.disabled = !!c.closest('.inactive, .tv-only');
  }
}

function flash(row, text, bad) {
  const old = row.querySelector('.flash');
  if (old) old.remove();
  const s = document.createElement('span');
  s.className = 'flash' + (bad ? ' error' : '');
  s.setAttribute('role', bad ? 'alert' : 'status');
  s.textContent = text;
  row.querySelector('.set-head').append(s);
  setTimeout(() => s.remove(), 2500);
}

async function postJson(path, body) {
  const r = await fetch(path, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify(body) });
  if (r.status === 401) { showPair(''); throw new Error('unpaired'); }
  return r.json();
}

/** Puts the stored value back into the control after a refused or failed change. */
function revert(e, control) {
  if (e.type === 'toggle') control.checked = !!setValues[e.key];
  else if (e.type === 'list') control.value = e.value;
  else if (e.type === 'number') control.value = e.value;
  else if (e.type === 'text') control.value = e.secret ? '' : (e.value || '');
}

async function save(e, value, row, control) {
  ownKeys.set(e.key, Date.now());
  try {
    const res = await postJson('/api/settings', { values: { [e.key]: value } });
    if (res.stored) {
      setValues[e.key] = value;
      if (e.type === 'list' || e.type === 'number') e.value = value; else if (e.type === 'text' && !e.secret) e.value = value;
      if (e.secret) { e.isSet = value !== ''; control.value = ''; control.placeholder = e.isSet ? L.secretSet : L.secretEmpty; }
      if (e.type === 'toggle' && !e.text) {
        const p = row.querySelector('.desc');
        const t = toggleText(e);
        if (p) p.textContent = t; else if (t) row.querySelector('.set-label').append(para(t, 'muted desc'));
      }
      flash(row, L.saved);
      updateActive();
    } else {
      revert(e, control);
      flash(row, res.error === 'disabled' ? L.settingsOff : res.error === 'tv only' ? L.tvOnlyPin : L.notSaved, true);
    }
  } catch (err) {
    revert(e, control);
    flash(row, L.offline, true);
  }
}

async function runAction(e, row) {
  try {
    const res = await postJson('/api/settings/action', { key: e.key });
    if (res.ok) {
      flash(row, res.message ? `${L.status}: ${res.message}` : L.done);
      // The home rows list is sorted by place: load it again
      if (e.place || e.key === 'pref_row_order_reset') setTimeout(loadSettings, 300);
    } else {
      flash(row, res.error === 'no_screen' ? L.noScreen : L.tvOnly, true);
    }
  } catch (err) { flash(row, L.offline, true); }
}

$('set-search').addEventListener('input', () => { if (setTree) { renderGroups(); renderSettings(); } });

// Backup: every value except secrets, MQTT and companion (those belong to this TV), as a JSON file
$('set-export').addEventListener('click', () => {
  if (!setTree) return;
  const settings = {};
  for (const [e] of allEntries()) {
    if (e.key && !e.secret && !e.noBackup && e.value !== undefined) settings[e.key] = e.key in setValues ? setValues[e.key] : e.value;
  }
  const data = { app: 'ShrugTV', version: setTree.version, exported: new Date().toISOString(), settings };
  const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `shrugtv-settings-${new Date().toISOString().slice(0, 10)}.json`;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  $('set-status').textContent = L.exported(Object.keys(settings).length);
});

$('set-import').addEventListener('change', async ev => {
  const file = ev.target.files[0];
  ev.target.value = '';
  if (!file) return;
  let values = null;
  try {
    const data = JSON.parse(await file.text());
    if (data && data.app === 'ShrugTV' && data.settings && typeof data.settings === 'object') values = data.settings;
  } catch (e) { values = null; }
  if (!values) { $('set-status').textContent = L.importBad; return; }
  const clean = {};
  for (const [k, v] of Object.entries(values)) if (typeof v === 'boolean' || typeof v === 'string') clean[k] = v;
  for (const k of Object.keys(clean)) ownKeys.set(k, Date.now());
  try {
    const res = await postJson('/api/settings', { values: clean, backup: true });
    // Keys the TV keeps to itself without a PIN (tv_only) count as skipped, each key once
    const skipped = new Set([...(res.rejected || []), ...(res.skipped || []), ...(res.tv_only || [])]);
    $('set-status').textContent = L.imported(res.stored || 0, skipped.size);
    loadSettings();
  } catch (e) { $('set-status').textContent = L.offline; }
});

// ---------------------------------------------------------------- log (like Glasshouse's Tools tab)
const LOG_MAX = 5000;
let logLines = [];      // [seq, time, level, tag, msg, pid, tid]
let logLast = 0;
let logTimer = null;
let logBusy = false;    // a request is on its way: no second poll chain
let logLevel = 'all';
let logPaused = false;
let logInfo = {};
let logOpen = null;     // seq of the line whose details are open
// Kept up to date line by line instead of scanning the whole buffer on every poll
const logN = { E: 0, W: 0 };
const logTags = new Map(); // tag -> number of lines
let logShownN = 0;         // lines in the view

const levelOf = l => (l === 'F' || l === 'A') ? 'E' : l === 'V' ? 'D' : l;

function logStart() {
  logPoll();
}

function logStop() {
  clearTimeout(logTimer);
  logTimer = null;
}

/** Adds (by = 1) or removes (by = -1) a line from the counters. */
function logCount(row, by) {
  const l = levelOf(row[2]);
  if (l in logN) logN[l] += by;
  const n = (logTags.get(row[3]) || 0) + by;
  if (n > 0) logTags.set(row[3], n); else logTags.delete(row[3]);
}

/** Exactly one chain: a call while a request is on its way or a timer is set only moves the timer. */
async function logPoll() {
  clearTimeout(logTimer);
  logTimer = null;
  if (currentTab !== 'log' || $('app').hidden || logBusy) return;
  // Fetch only what is new; nothing while paused or while the browser tab is hidden
  if (!logPaused && !document.hidden) {
    logBusy = true;
    try {
      const r = await fetch(`/api/log?after=${logLast}`, { credentials: 'same-origin' });
      if (r.status === 401) { showPair(''); return; }
      const off = r.status === 403;
      $('log-off').hidden = !off;
      $('log-ui').hidden = off;
      if (!off) {
        const d = await r.json();
        logInfo = d;
        // Only lines after the last one: an answer that overlaps the one before adds nothing twice
        const fresh = d.lines.filter(l => l[0] > logLast);
        if (fresh.length) logAdd(fresh);
        else if (!$('log-view').childElementCount) logRender();
      }
    } catch (e) {
      /* offline: the banner says so */
    } finally {
      logBusy = false;
    }
  }
  if (currentTab === 'log' && !$('app').hidden && !logTimer) logTimer = setTimeout(logPoll, 2000);
}
document.addEventListener('visibilitychange', () => { if (!document.hidden && currentTab === 'log') logPoll(); });

/** New lines into the buffer and the view; the oldest beyond LOG_MAX leave both. */
function logAdd(fresh) {
  for (const row of fresh) logCount(row, 1);
  logLines = logLines.concat(fresh);
  logLast = fresh[fresh.length - 1][0];
  const extra = logLines.length - LOG_MAX;
  let dropUpTo = -Infinity;
  if (extra > 0) {
    for (const row of logLines.slice(0, extra)) logCount(row, -1);
    dropUpTo = logLines[extra - 1][0];
    logLines = logLines.slice(extra);
  }
  logAppend(fresh, dropUpTo);
}

/** The filter of the view as one function (level, source and search read once, not per line). */
function logFilter() {
  const level = logLevel;
  const src = $('log-tag').value;
  const q = $('log-search').value.trim().toLowerCase();
  return row => {
    const [, , lv, tag, msg] = row;
    if (level !== 'all' && levelOf(lv) !== level) return false;
    if (src && tag !== src) return false;
    return !q || msg.toLowerCase().includes(q) || tag.toLowerCase().includes(q);
  };
}

const pad = (n, w) => String(n).padStart(w || 2, '0');
function logTime(t, withDate) {
  const d = new Date(t);
  const time = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
  return withDate ? `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${time}` : time;
}

/** Text with the search term marked (text nodes only, never HTML). */
function marked(text, q) {
  if (!q) return [document.createTextNode(text)];
  const out = [];
  const low = text.toLowerCase();
  let pos = 0;
  let i = low.indexOf(q);
  while (i >= 0) {
    if (i > pos) out.push(document.createTextNode(text.slice(pos, i)));
    const m = document.createElement('mark');
    m.textContent = text.slice(i, i + q.length);
    out.push(m);
    pos = i + q.length;
    i = low.indexOf(q, pos);
  }
  if (pos < text.length) out.push(document.createTextNode(text.slice(pos)));
  return out;
}

function logRow(row, q) {
  const [seq, t, level, tag, msg] = row;
  const div = document.createElement('div');
  div.className = 'll lv-' + levelOf(level);
  div.dataset.seq = seq;
  const time = document.createElement('span');
  time.className = 'lt';
  time.textContent = logTime(t);
  const lv = document.createElement('span');
  lv.className = 'lv';
  lv.textContent = level;
  const tg = document.createElement('span');
  tg.className = 'lg';
  tg.append(...marked(tag, q));
  const m = document.createElement('span');
  m.className = 'lm';
  m.append(...marked(msg, q));
  div.append(time, lv, tg, m);
  return div;
}

/** Inspector under a line: all fields, JSON in the message pretty-printed. */
function logDetails(row) {
  const [seq, t, level, tag, msg, pid, tid] = row;
  const box = document.createElement('div');
  box.className = 'ld';
  box.dataset.for = seq;
  const dl = document.createElement('dl');
  for (const [k, v] of [[L.logTime, logTime(t, true)], [L.logLevel, level], [L.logTag, tag], [L.logThread, `${pid} / ${tid}`]]) {
    const dt = document.createElement('dt');
    dt.textContent = k;
    const dd = document.createElement('dd');
    dd.textContent = v;
    dl.append(dt, dd);
  }
  const pre = document.createElement('pre');
  const start = msg.search(/[{[]/);
  let pretty = null;
  if (start >= 0) {
    try { pretty = msg.slice(0, start) + '\n' + JSON.stringify(JSON.parse(msg.slice(start)), null, 2); } catch (e) { pretty = null; }
  }
  pre.textContent = pretty || msg;
  box.append(dl, pre);
  return box;
}

function atBottom(el) {
  return el.scrollHeight - el.scrollTop - el.clientHeight < 30;
}

function logRender() {
  const view = $('log-view');
  const ok = logFilter();
  const q = $('log-search').value.trim().toLowerCase();
  const shown = logLines.filter(r => ok(r));
  const frag = document.createDocumentFragment();
  for (const row of shown) {
    frag.append(logRow(row, q));
    if (logOpen === row[0]) frag.append(logDetails(row));
  }
  view.replaceChildren(frag);
  logShownN = shown.length;
  if (!shown.length) view.append(para(logLines.length ? L.logEmpty : '…', 'muted'));
  view.scrollTop = view.scrollHeight;
  $('log-latest').hidden = true;
  logCounts();
}

/** Appends the new lines that pass the filter and removes the lines up to dropUpTo, which left the buffer. */
function logAppend(rows, dropUpTo) {
  const view = $('log-view');
  const follow = atBottom(view) || !view.querySelector('.ll');
  if (!view.querySelector('.ll')) view.replaceChildren();
  const ok = logFilter();
  const q = $('log-search').value.trim().toLowerCase();
  const frag = document.createDocumentFragment();
  for (const row of rows) if (ok(row)) { frag.append(logRow(row, q)); logShownN++; }
  view.append(frag);
  // The view holds no line the buffer has dropped; the oldest are at the top
  for (let first = view.firstElementChild; first && first.classList.contains('ll') && Number(first.dataset.seq) <= dropUpTo; first = view.firstElementChild) {
    if (first.nextElementSibling && first.nextElementSibling.classList.contains('ld')) first.nextElementSibling.remove();
    first.remove();
    logShownN--;
  }
  if (follow) view.scrollTop = view.scrollHeight; else $('log-latest').hidden = false;
  logCounts();
}

let logTagsKey = '';
function logCounts() {
  $('n-E').textContent = logN.E || '';
  $('n-W').textContent = logN.W || '';
  const sel = $('log-tag');
  const key = [...logTags.keys()].sort().join('|');
  if (logTagsKey !== key) {
    const current = sel.value;
    const list = [...logTags.keys()].sort((a, b) => a.localeCompare(b));
    sel.replaceChildren(new Option(L.logAllSources, ''), ...list.map(t => new Option(t, t)));
    sel.value = logTags.has(current) ? current : '';
    logTagsKey = key;
  }
  $('log-status').textContent = L.logShown(logShownN, logLines.length) + (logPaused ? ` · ${L.logPaused}` : '') +
    (logInfo.version ? ` · ShrugTV ${logInfo.version} · ${logInfo.device || ''}` : '');
}

$('log-view').addEventListener('scroll', () => { if (atBottom($('log-view'))) $('log-latest').hidden = true; });
$('log-latest').addEventListener('click', () => {
  $('log-view').scrollTop = $('log-view').scrollHeight;
  $('log-latest').hidden = true;
});
$('log-view').addEventListener('click', ev => {
  const line = ev.target.closest('.ll');
  if (!line) return;
  const seq = Number(line.dataset.seq);
  const open = $('log-view').querySelector('.ld');
  if (open) open.remove();
  if (logOpen === seq) { logOpen = null; return; }
  logOpen = seq;
  const row = logLines.find(r => r[0] === seq);
  if (row) line.after(logDetails(row));
});
for (const b of document.querySelectorAll('[data-level]')) {
  b.addEventListener('click', () => {
    logLevel = b.dataset.level;
    for (const o of document.querySelectorAll('[data-level]')) o.setAttribute('aria-pressed', String(o === b));
    logRender();
  });
}
$('log-search').addEventListener('input', debounce(logRender, 250)); // up to 5000 lines: not on every key
$('log-tag').addEventListener('change', logRender);
$('log-pause').addEventListener('click', () => {
  logPaused = !logPaused;
  $('log-pause').setAttribute('aria-pressed', String(logPaused));
  $('log-pause').textContent = logPaused ? L.logResume : L.logPause;
  logCounts();
  if (!logPaused) logPoll();
});

// Private details for copying and exporting (the view always shows the full log, as in Glasshouse)
const PRIVATE = [
  [/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, '<ip>'],
  [/\b(?:[0-9a-f]{1,4}:){4,7}[0-9a-f]{1,4}\b/gi, '<ip6>'],
  [/\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b/gi, '<mac>'],
  [/[\w.+-]+@[\w-]+\.[\w.-]+/g, '<email>'],
  [/\b(user_id|broadcaster_user_id|userId|device_id|serial)([=": ]+)\w+/gi, '$1$2<id>'],
  [/(SSID[=": ]+)[^,\s"]+/gi, '$1<wifi>']
];
function scrub(text) {
  return $('log-private').checked ? PRIVATE.reduce((s, [re, rep]) => s.replace(re, rep), text) : text;
}

function logText() {
  const ok = logFilter();
  const shown = logLines.filter(r => ok(r));
  const head = `ShrugTV ${logInfo.version || ''} log, ${logInfo.device || ''}, exported ${logTime(Date.now(), true)}` +
    ($('log-search').value ? `, search "${$('log-search').value}"` : '') + (logLevel !== 'all' ? `, level ${logLevel}` : '') +
    ($('log-tag').value ? `, source ${$('log-tag').value}` : '');
  const body = shown.map(([, t, level, tag, msg, pid, tid]) => `${logTime(t, true)} ${pid} ${tid} ${level} ${tag}: ${msg}`).join('\n');
  return [shown.length, scrub(head + '\n' + body + '\n')];
}

$('log-copy').addEventListener('click', async () => {
  const [n, text] = logText();
  try {
    await navigator.clipboard.writeText(text);
  } catch (e) {
    // Plain HTTP: the clipboard API may be missing, fall back to a hidden text field
    const ta = document.createElement('textarea');
    ta.value = text;
    document.body.append(ta);
    ta.select();
    document.execCommand('copy');
    ta.remove();
  }
  $('log-status').textContent = L.logCopied(n);
});

$('log-export').addEventListener('click', () => {
  const [n, text] = logText();
  const d = new Date();
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `shrugtv-log-${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}.txt`;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  $('log-status').textContent = L.logSaved(n);
});

start();


// ---------------------------------------------------------------- remote: touchpad, audio only, recent channels
const buzz = ms => { try { if (navigator.vibrate) navigator.vibrate(ms); } catch (e) { /* not supported */ } };

function setMode(mode) {
  const pad = mode === 'pad';
  $('pad').hidden = !pad;
  $('dpad').hidden = pad;
  $('mode-pad').setAttribute('aria-pressed', String(pad));
  $('mode-keys').setAttribute('aria-pressed', String(!pad));
  store('shrugtv.remote', JSON.stringify(mode));
}
$('mode-pad').addEventListener('click', () => setMode('pad'));
$('mode-keys').addEventListener('click', () => setMode('keys'));
setMode(load('shrugtv.remote', matchMedia('(pointer:coarse)').matches ? 'pad' : 'keys') === 'pad' ? 'pad' : 'keys');

(function padInit() {
  const el = $('pad');
  const arrow = $('pad-arrow');
  const STEP = 34; // pixels of dragging per key press, so a long swipe scrolls
  const pointers = new Map();
  let most = 0, moved = false, sx = 0, sy = 0, timer = 0;
  const flash = k => {
    arrow.textContent = { up: '▲', down: '▼', left: '◀', right: '▶', ok: '●', back: '↩' }[k] || '';
    arrow.classList.add('show');
    clearTimeout(timer);
    timer = setTimeout(() => arrow.classList.remove('show'), 60);
  };
  el.addEventListener('pointerdown', ev => {
    ev.preventDefault();
    try { el.setPointerCapture(ev.pointerId); } catch (e) { /* ignore */ }
    pointers.set(ev.pointerId, 1);
    most = Math.max(most, pointers.size);
    if (pointers.size === 1) { moved = false; sx = ev.clientX; sy = ev.clientY; }
    el.classList.add('on');
  });
  el.addEventListener('pointermove', ev => {
    if (!pointers.has(ev.pointerId) || pointers.size !== 1) return;
    const dx = ev.clientX - sx, dy = ev.clientY - sy;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < STEP) return;
    moved = true;
    const k = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'down' : 'up');
    key(k); flash(k); buzz(8);
    sx = ev.clientX; sy = ev.clientY;
  });
  const up = ev => {
    if (!pointers.delete(ev.pointerId)) return;
    if (pointers.size > 0) return;
    el.classList.remove('on');
    if (!moved && ev.type === 'pointerup') {
      const k = most >= 2 ? 'back' : 'ok';
      key(k); flash(k); buzz(k === 'back' ? 20 : 15);
    }
    most = 0;
  };
  el.addEventListener('pointerup', up);
  el.addEventListener('pointercancel', up);
  el.addEventListener('contextmenu', ev => ev.preventDefault());
  // Keyboard on a computer: arrows, Enter, Escape while the pad has the focus
  el.addEventListener('keydown', ev => {
    const k = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'ok', ' ': 'ok', Escape: 'back' }[ev.key];
    if (k) { ev.preventDefault(); key(k); flash(k); }
  });
})();

$('btn-audio').addEventListener('click', () => { cmd('audio_only', state.audio_only ? 'OFF' : 'ON'); buzz(10); });
$('btn-replay').addEventListener('click', () => buzz(10));
for (const b of document.querySelectorAll('.row button, .k')) b.addEventListener('click', () => buzz(8));

// Last channels, kept in this browser
let lastLogin = null;
function recentList() {
  const r = load('shrugtv.recent', []);
  return Array.isArray(r) ? r : [];
}
function rememberChannel(s) {
  if (s.login === lastLogin) return;
  lastLogin = s.login;
  const list = recentList().filter(c => c.login !== s.login);
  list.unshift({ login: s.login, name: s.channel || s.login, avatar: s.avatar_url || '' });
  store('shrugtv.recent', list.slice(0, 8));
  renderRecent();
}
function renderRecent() {
  const list = recentList().filter(c => c.login !== state.login && /^[A-Za-z0-9_]{1,25}$/.test(c.login || ''));
  $('recent-box').hidden = list.length === 0;
  $('recent').replaceChildren(...list.map(c => {
    const b = document.createElement('button');
    if (/^https:\/\//.test(c.avatar)) { const i = document.createElement('img'); i.alt = ''; i.src = c.avatar; b.append(i); }
    b.append(document.createTextNode(c.name || c.login));
    b.addEventListener('click', () => { cmd('watch', c.login); buzz(10); });
    return b;
  }));
}
renderRecent();

// ---------------------------------------------------------------- stars: favorites
const favorites = new Map(); // user id -> true/false as last set from here
function paintStar(b, id, on) {
  b.textContent = on ? '★' : '☆';
  b.setAttribute('aria-pressed', String(on));
  b.setAttribute('aria-label', on ? L.favRemove : L.favAdd);
  b.title = on ? L.favRemove : L.favAdd;
}
async function setFavorite(id, on) {
  const r = await api('/api/favorite', { method: 'POST', body: JSON.stringify({ id, on }) });
  if (!r.ok) throw new Error('favorite');
  favorites.set(id, on);
}
function starButton(id, initial) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'star';
  paintStar(b, id, favorites.has(id) ? favorites.get(id) : initial);
  b.addEventListener('click', async ev => {
    ev.stopPropagation();
    const on = b.getAttribute('aria-pressed') !== 'true';
    paintStar(b, id, on);
    buzz(10);
    try { await setFavorite(id, on); } catch (e) { paintStar(b, id, !on); alert(L.favFailed); }
  });
  return b;
}
function paintNowStar() {
  const b = $('now-star');
  const id = state.user_id;
  b.hidden = !id;
  if (id) paintStar(b, id, favorites.has(id) ? favorites.get(id) : !!state.favorite);
}
$('now-star').addEventListener('click', async () => {
  const id = state.user_id;
  if (!id) return;
  const on = $('now-star').getAttribute('aria-pressed') !== 'true';
  paintStar($('now-star'), id, on);
  buzz(10);
  try { await setFavorite(id, on); } catch (e) { paintStar($('now-star'), id, !on); alert(L.favFailed); }
});

// ---------------------------------------------------------------- chat: reply, emotes, quick phrases
let replyTo = null;
function setReply(name) {
  replyTo = name;
  $('reply-bar').hidden = !name;
  $('reply-text').textContent = name ? L.replyTo(name) : '';
  if (name) $('chat-input').focus();
}
$('reply-x').addEventListener('click', () => setReply(null));
$('chat').addEventListener('click', ev => {
  const msg = ev.target.closest('.msg');
  if (msg && msg.dataset.name) { setReply(msg.dataset.name); buzz(10); }
});

// Emotes seen in this chat (name -> image), most recent last
const seenEmotes = new Map();
function noteEmote(name, url) {
  if (!name || name.length > 40 || !/^https:\/\//.test(url)) return;
  seenEmotes.delete(name);
  seenEmotes.set(name, url);
  if (seenEmotes.size > 150) seenEmotes.delete(seenEmotes.keys().next().value);
  if (!$('emotes').hidden) emoteDirty = true;
}
let emoteDirty = false;
function renderEmotes() {
  const box = $('emotes');
  if (seenEmotes.size === 0) {
    const p = document.createElement('p');
    p.className = 'muted';
    p.textContent = L.noEmotes;
    box.replaceChildren(p);
    return;
  }
  box.replaceChildren(...[...seenEmotes].reverse().map(([name, url]) => {
    const b = document.createElement('button');
    b.type = 'button';
    b.title = name;
    const i = document.createElement('img');
    i.src = url;
    i.alt = name;
    b.append(i);
    b.addEventListener('click', () => {
      const input = $('chat-input');
      const at = input.selectionStart == null ? input.value.length : input.selectionStart;
      const before = input.value.slice(0, at), after = input.value.slice(input.selectionEnd == null ? at : input.selectionEnd);
      const pad = before && !/\s$/.test(before) ? ' ' : '';
      input.value = (before + pad + name + ' ' + after).slice(0, 500);
      const pos = (before + pad + name + ' ').length;
      input.focus();
      try { input.setSelectionRange(pos, pos); } catch (e) { /* ignore */ }
      buzz(8);
    });
    return b;
  }));
}
function showEmotes(on) {
  $('emotes').hidden = !on;
  $('emote-btn').setAttribute('aria-expanded', String(on));
  if (on) { renderEmotes(); emoteDirty = false; }
}
$('emote-btn').addEventListener('click', () => showEmotes($('emotes').hidden));
setInterval(() => { if (emoteDirty && !$('emotes').hidden) { renderEmotes(); emoteDirty = false; } }, 2000);

const QUICK_DEFAULT = ['GG', 'o/', '❤', 'LUL'];
function quickList() {
  const q = load('shrugtv.quick', null);
  return Array.isArray(q) ? q.filter(t => typeof t === 'string').slice(0, 12) : QUICK_DEFAULT.slice();
}
function renderQuick() {
  const list = quickList();
  const chips = list.map(text => {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = text;
    let hold = 0, held = false;
    b.addEventListener('pointerdown', () => { held = false; hold = setTimeout(() => {
      held = true;
      if (confirm(L.quickRemove(text))) { store('shrugtv.quick', quickList().filter(t => t !== text)); renderQuick(); }
    }, 650); });
    for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) b.addEventListener(ev, () => clearTimeout(hold));
    b.addEventListener('click', () => { if (!held && state.chat_allowed !== false) { cmd('chat_message', text); buzz(10); } });
    return b;
  });
  const add = document.createElement('button');
  add.type = 'button';
  add.className = 'add';
  add.textContent = '+';
  add.setAttribute('aria-label', L.quickAdd);
  add.addEventListener('click', () => {
    const t = (prompt(L.quickNew) || '').trim().slice(0, 60);
    if (t && !quickList().includes(t)) { store('shrugtv.quick', quickList().concat(t).slice(0, 12)); renderQuick(); }
  });
  $('quick').replaceChildren(...chips, add);
}
renderQuick();

// ---------------------------------------------------------------- version and update
let updTimer = 0;
let updGen = 0; // a chain from before the last start or stop ends itself
let updInfo = null;

// Every 30 s while the tab is open and visible, every 1.5 s while the TV checks, downloads or verifies
function updateStop() { clearTimeout(updTimer); updTimer = 0; updGen++; }
function updateStart() {
  updateStop();
  if (!updInfo) setStatus($('upd-status'), 'loading');
  updateTick(updGen);
}
async function updateTick(gen) {
  if (!document.hidden) await updateLoad();
  if (gen !== updGen) return;
  updateSchedule();
}
function updateSchedule() {
  clearTimeout(updTimer);
  updTimer = 0;
  if (currentTab !== 'update' || $('app').hidden) return;
  const gen = updGen;
  const busy = updInfo && (updInfo.checking || ['downloading', 'verifying'].includes(updInfo.install.phase));
  updTimer = setTimeout(() => updateTick(gen), busy ? 1500 : 30000);
}
document.addEventListener('visibilitychange', () => { if (!document.hidden && currentTab === 'update') updateStart(); });
const updReq = newestOnly();
async function updateLoad() {
  const req = updReq.begin();
  try {
    const u = await api('/api/update', { signal: req.signal });
    if (req.stale()) return;
    updInfo = u;
  } catch (e) {
    // Without a first answer the tab would stay empty: say so, with "Try again"
    if (!req.stale() && !updInfo && e.message !== 'unpaired') setStatus($('upd-status'), 'error', null, updateStart);
    return;
  }
  updateRender();
}
async function updatePost(path) {
  updReq.cancel(); // an older answer must not overwrite this one
  try {
    updInfo = await api(path, { method: 'POST', body: '{}' });
    updateRender();
    updateSchedule(); // a check or download that just started is followed closely
  } catch (e) { /* offline or not paired */ }
}

function fmtTime(ms) {
  if (!ms) return L.verNever;
  const d = new Date(ms);
  return d.toLocaleString(LANG, { dateStyle: 'medium', timeStyle: 'short' });
}
function mk(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}
function fact(dl, k, v) { dl.append(mk('dt', null, k), mk('dd', null, v)); }

function updateRender() {
  const u = updInfo;
  if (!u) { if (!$('upd').childElementCount) setStatus($('upd-status'), 'loading'); return; }
  setStatus($('upd-status'), null);
  document.querySelector('[data-tab=update] .new-dot').hidden = u.status !== 'available';

  const cur = mk('div', 'card');
  cur.append(mk('h2', null, L.verInstalled), mk('div', 'ver', 'ShrugTV ' + u.version));
  const dl = mk('dl', 'facts');
  fact(dl, L.verEdition, u.edition === 'public' ? L.verEditionPublic : L.verEditionPrivate);
  fact(dl, L.verDevice, u.device || '?');
  fact(dl, L.verAndroid, u.android || '?');
  if (u.check_supported) fact(dl, L.verLastCheck, fmtTime(u.last_check));
  cur.append(dl);

  const box = mk('div', 'card' + (u.status === 'available' ? ' hot' : ''));
  const btns = mk('div', 'btns');
  const inst = u.install || {};
  if (u.status === 'unsupported') {
    box.append(mk('p', 'muted', L.verUnsupported));
  } else {
    if (u.status === 'available') {
      box.append(mk('h2', null, L.verAvailable(u.latest.version)));
      if (u.latest.notes) { box.append(mk('div', 'muted', L.verNotes), mk('p', 'notes', u.latest.notes)); }
    } else {
      box.append(mk('div', 'stat', (u.status === 'current' ? '✓ ' : '') + (u.status === 'current' ? L.verCurrent : L.verUnknown)));
    }
    if (u.checking) box.append(mk('p', 'muted', L.verChecking));
    const working = ['downloading', 'verifying'].includes(inst.phase);
    if (inst.phase === 'downloading') {
      box.append(mk('p', 'stat', L.verDownloading(inst.percent)));
      const bar = mk('div', 'bar'); const i = mk('i'); i.style.width = inst.percent + '%'; bar.append(i); box.append(bar);
    } else if (inst.phase === 'verifying') {
      box.append(mk('p', 'stat', L.verVerifying));
    } else if (inst.phase === 'error') {
      const known = L.verErrors[inst.message];
      box.append(mk('p', 'error', known || inst.message));
    }
    const check = mk('button', null, u.checking ? L.verChecking : L.verCheck);
    check.type = 'button';
    check.disabled = !!u.checking || working;
    check.addEventListener('click', () => updatePost('/api/update/check'));
    if (u.status === 'available' && u.install_enabled && u.latest.installable) {
      const go = mk('button', 'primary', L.verInstall);
      go.type = 'button';
      go.disabled = working;
      go.addEventListener('click', () => { if (confirm(L.verInstallConfirm(u.latest.version))) updatePost('/api/update/install'); });
      btns.append(go);
    }
    btns.append(check);
    if (u.status === 'available' && /^https:\/\//.test(u.latest.url || '')) {
      const a = mk('a', 'btn', L.verRelease);
      a.href = u.latest.url; a.target = '_blank'; a.rel = 'noopener noreferrer';
      btns.append(a);
    }
    box.append(btns);
    if (u.status === 'available' && u.install_supported && !u.install_enabled) box.append(mk('p', 'muted', L.verInstallOff));
    if (!u.check_enabled) box.append(mk('p', 'muted', L.verAutoOff));
  }
  const home = mk('div', 'card install-hint');
  home.append(mk('h2', null, L.homeTitle), mk('div', 'muted', L.homeHint));
  const ol = mk('ol');
  ol.append(mk('li', null, L.homeIos), mk('li', null, L.homeAndroid));
  home.append(ol);
  $('upd').replaceChildren(cur, box, home);
}

// The red dot on the tab: asked once at the start (the TV answers from its last check, no network call)
function updateBadge() { api('/api/update').then(u => { updInfo = u; updateRender(); }).catch(() => {}); }


// ---------------------------------------------------------------- skin of the TV on this page
const SKIN_VARS = ['--bg', '--surface', '--surface2', '--text', '--muted', '--accent', '--accent2', '--live', '--on-accent', '--focus', '--on-focus',
  '--border', '--press', '--placeholder', '--line', '--switch-off', '--tip-bg', '--tip-text', '--hl', '--behind', '--deleted'];
const rgb = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const mix = (a, b, t) => { const x = rgb(a), y = rgb(b); return '#' + x.map((v, i) => Math.round(v + (y[i] - v) * t).toString(16).padStart(2, '0')).join(''); };
const alpha = (h, t) => `rgba(${rgb(h).join(',')},${t})`;
let skinOn = false;

function applySkin(s) {
  const root = document.documentElement;
  const valid = s && s.on && s.colors && Object.values(s.colors).every(v => /^#[0-9A-F]{6}$/i.test(v));
  skinOn = !!valid;
  $('theme').hidden = skinOn;
  if (!valid) {
    for (const v of SKIN_VARS) root.style.removeProperty(v);
    let t = 'dark';
    try { if (localStorage.getItem('shrugtv.theme') === 'light') t = 'light'; } catch (e) { /* private mode */ }
    applyTheme(t);
    return;
  }
  const c = s.colors;
  const set = {
    '--bg': c.background, '--surface': c.surface, '--surface2': c.surfaceHigh, '--text': c.text, '--muted': c.textMuted,
    '--accent': c.accent, '--accent2': c.accentLight, '--live': c.live, '--on-accent': c.onAccent, '--focus': c.text, '--on-focus': c.background,
    '--border': mix(c.surfaceHigh, c.text, 0.12), '--press': mix(c.surfaceHigh, c.text, 0.12), '--placeholder': c.surfaceHigh,
    '--line': mix(c.surface, c.text, 0.10), '--switch-off': mix(c.surfaceHigh, c.text, 0.22), '--tip-bg': alpha(c.accent, 0.14),
    '--tip-text': c.accentLight, '--hl': alpha(c.accent, 0.2), '--behind': mix(c.surfaceHigh, c.text, 0.3), '--deleted': c.textMuted
  };
  for (const [k, v] of Object.entries(set)) root.style.setProperty(k, v);
  root.dataset.theme = s.dark ? 'dark' : 'light';
  document.querySelector('meta[name=theme-color]').content = c.background;
}
async function loadSkin() {
  try { applySkin(await api('/api/theme')); } catch (e) { /* offline or not paired */ }
}

// ---------------------------------------------------------------- text and links to the TV, seeking, held keys
// Text for the field that has the focus on the TV (search, dialogs)
$('type-form').addEventListener('submit', ev => {
  ev.preventDefault();
  const text = $('type-input').value;
  if (!text || !send({ t: 'text', s: text.slice(0, 200) })) return;
  $('type-input').value = '';
});

// A Twitch link, a recording or clip address or just a channel name -> the payload of the "watch" command
function watchPayload(text) {
  const t = text.trim().replace(/^@/, '');
  if (!t) return null;
  let m = t.match(/^(?:https?:\/\/)?(?:www\.|m\.)?twitch\.tv\/videos\/(\d{1,12})(?:[/?#].*)?$/i);
  if (m) return JSON.stringify({ vod_id: Number(m[1]) });
  m = t.match(/^(?:https?:\/\/)?clips\.twitch\.tv\/([A-Za-z0-9_-]{5,120})(?:[/?#].*)?$/i)
    || t.match(/^(?:https?:\/\/)?(?:www\.|m\.)?twitch\.tv\/[A-Za-z0-9_]{1,25}\/clip\/([A-Za-z0-9_-]{5,120})(?:[/?#].*)?$/i);
  if (m) return JSON.stringify({ clip: m[1] });
  m = t.match(/^(?:https?:\/\/)?(?:www\.|m\.)?twitch\.tv\/([A-Za-z0-9_]{1,25})(?:[/?#].*)?$/i) || t.match(/^([A-Za-z0-9_]{1,25})$/);
  return m ? m[1].toLowerCase() : null;
}
$('link-form').addEventListener('submit', ev => {
  ev.preventDefault();
  const payload = watchPayload($('link-input').value);
  const err = $('link-error');
  err.hidden = !!payload;
  if (!payload) return;
  if (cmd('watch', payload)) $('link-input').value = '';
});

// Recordings: the slider moves to a place by opening the recording there again (the TV has no seek command)
$('seek').addEventListener('input', ev => { $('seek-time').textContent = clock(Number(ev.target.value)); });
$('seek').addEventListener('change', ev => {
  if (state.vod_id > 0) cmd('watch', JSON.stringify({ vod_id: state.vod_id, position_s: Number(ev.target.value) }));
});
function clock(sec) {
  const h = Math.floor(sec / 3600), m = Math.floor(sec % 3600 / 60), s = Math.floor(sec % 60);
  return (h ? h + ':' + pad(m) : String(m)) + ':' + pad(s);
}
function renderSeek(s) {
  const on = s.vod_id > 0 && s.vod_length_s > 0;
  $('seek-row').hidden = !on;
  if (!on || document.activeElement === $('seek')) return;
  $('seek').max = s.vod_length_s;
  $('seek').value = s.vod_position_s || 0;
  $('seek-time').textContent = clock(s.vod_position_s || 0);
}

// Holding an arrow button repeats the key (like holding it on the real remote)
for (const b of document.querySelectorAll('.k[data-key]')) {
  let wait = 0, every = 0;
  const stop = () => { clearTimeout(wait); clearInterval(every); };
  b.addEventListener('pointerdown', ev => {
    if (ev.pointerType === 'mouse' && ev.button !== 0) return;
    stop();
    wait = setTimeout(() => { every = setInterval(() => key(b.dataset.key), 110); }, 450);
  });
  for (const t of ['pointerup', 'pointercancel', 'pointerleave']) b.addEventListener(t, stop);
}
