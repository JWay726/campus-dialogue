const API = {
  sessions: '/api/sessions',
  chat: '/api/chat',
  docsStatus: '/api/docs/status',
  reindex: '/api/docs/reindex'
};

let currentSessionId = null;
let currentUser = null;
let currentRole = null;
let editingFromMsgId = null;

/** 按角色控制入口：知识库管理与重建索引仅 ADMIN 可见。 */
function applyRole() {
  const show = (currentRole === 'ADMIN');
  const link = document.getElementById('admin-link');
  const reindexBtn = document.getElementById('reindex-btn');
  if (link) link.style.display = show ? '' : 'none';
  if (reindexBtn) reindexBtn.style.display = show ? '' : 'none';
}

const $ = (s) => document.querySelector(s);
const sessionListEl = $('#session-list');
const messagesEl = $('#messages');
const inputEl = $('#input');
const kbStatusEl = $('#kb-status');
const currentTitleEl = $('#current-title');

function scrollBottom() { messagesEl.scrollTop = messagesEl.scrollHeight; }

/** 底部轻量提示条：msg 文本，type='error' 时红色。2.5s 后自动消失。 */
function toast(msg, type) {
  const el = document.getElementById('toast');
  if (!el) return;
  el.textContent = msg;
  el.className = 'toast' + (type === 'error' ? ' error' : '');
  el.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.hidden = true; }, 2500);
}

/**
 * 自定义模态框，替代原生 confirm()/prompt()（原生弹窗标题会显示宿主浏览器名）。
 * opts: { title, message, input:boolean, defaultValue, okText, danger }
 * 返回：input 模式为 string|null（取消=null）；否则为 boolean。
 */
function showModal(opts) {
  return new Promise((resolve) => {
    const mask = document.getElementById('modal-mask');
    const titleEl = document.getElementById('modal-title');
    const bodyEl = document.getElementById('modal-body');
    const inputField = document.getElementById('modal-input');
    const okBtn = document.getElementById('modal-ok');
    const cancelBtn = document.getElementById('modal-cancel');
    // 极端兜底：容器缺失时退回原生（正常情况下不会走到）
    if (!mask) { resolve(opts.input ? prompt(opts.title || '', opts.defaultValue || '') : confirm(opts.message || '')); return; }

    titleEl.textContent = opts.title || '提示';
    bodyEl.textContent = opts.message || '';
    bodyEl.hidden = !opts.message;
    if (opts.input) {
      inputField.hidden = false;
      inputField.value = opts.defaultValue || '';
    } else {
      inputField.hidden = true;
      inputField.value = '';
    }
    okBtn.className = 'modal-btn ' + (opts.danger ? 'danger' : 'primary');
    okBtn.textContent = opts.okText || (opts.danger ? '删除' : '确定');
    cancelBtn.textContent = '取消';

    mask.hidden = false;
    if (opts.input) { inputField.focus(); inputField.select(); }

    let done = false;
    function cleanup() {
      if (done) return false;
      done = true;
      mask.hidden = true;
      okBtn.onclick = null;
      cancelBtn.onclick = null;
      mask.onclick = null;
      document.removeEventListener('keydown', onKey);
      return true;
    }
    function finish(value) { if (cleanup()) resolve(value); }
    function onKey(e) {
      if (e.key === 'Escape') { e.preventDefault(); finish(opts.input ? null : false); }
      else if (e.key === 'Enter') { e.preventDefault(); finish(opts.input ? inputField.value : true); }
    }
    okBtn.onclick = () => finish(opts.input ? inputField.value : true);
    cancelBtn.onclick = () => finish(opts.input ? null : false);
    mask.onclick = (e) => { if (e.target === mask) finish(opts.input ? null : false); };
    document.addEventListener('keydown', onKey);
  });
}

/** 进入“修改历史消息”模式：把消息内容回填输入框，显示提示条。 */
function enableEdit(msgId, text) {
  editingFromMsgId = msgId;
  inputEl.value = text || '';
  showEditBar();
  inputEl.focus();
}
function cancelEdit() {
  editingFromMsgId = null;
  inputEl.value = '';
  hideEditBar();
}
function showEditBar() { const b = document.getElementById('edit-bar'); if (b) b.hidden = false; }
function hideEditBar() { const b = document.getElementById('edit-bar'); if (b) b.hidden = true; }

async function loadSessions() {
  try {
    const res = await fetch(API.sessions);
    const list = await res.json();
    sessionListEl.innerHTML = '';
    list.forEach(s => {
      const li = document.createElement('div');
      li.className = 'session-item' + (s.id === currentSessionId ? ' active' : '');
      const title = document.createElement('span');
      title.className = 'session-title';
      title.textContent = s.title || ('对话 ' + s.id);
      const del = document.createElement('button');
      del.className = 'session-del';
      del.textContent = '×';
      del.title = '删除会话';
      del.onclick = (e) => { e.stopPropagation(); deleteSession(s.id); };
      li.appendChild(title);
      li.appendChild(del);
      li.dataset.title = s.title || ('对话 ' + s.id);
      li.onclick = () => selectSession(s.id);
      // 右键弹出操作菜单（重命名 / 删除）
      li.oncontextmenu = (e) => { e.preventDefault(); openContextMenu(e, s.id, li.dataset.title); };
      sessionListEl.appendChild(li);
    });
  } catch (e) { console.error(e); }
}

async function selectSession(id) {
  editingFromMsgId = null;
  hideEditBar();
  currentSessionId = id;
  await loadSessions();
  const res = await fetch(`${API.sessions}/${id}/messages`);
  if (!res.ok) return; // 会话不可见/不存在（理论上不会发生：列表只展示自己的会话）
  const session = await res.json();
  currentTitleEl.textContent = session.title || ('对话 ' + id);
  messagesEl.innerHTML = '';
  renderMessages(session.messages || []);
}

function renderMessages(messages) {
  messages.forEach(m => appendMessage(m.role, m.content, m.timestamp, m.id));
  scrollBottom();
}

function appendMessage(role, content, ts, msgId) {
  const wrap = document.createElement('div');
  wrap.className = 'msg ' + (role === 'user' ? 'user' : 'assistant');
  if (msgId != null) wrap.dataset.msgId = msgId;
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  if (role === 'assistant') {
    bubble.innerHTML = renderMarkdown(content || '');
  } else {
    bubble.textContent = content || '';
  }
  wrap.appendChild(bubble);
  if (role === 'user' && msgId != null) {
    const editBtn = document.createElement('button');
    editBtn.className = 'msg-edit';
    editBtn.type = 'button';
    editBtn.textContent = '✎ 修改';
    editBtn.title = '修改这条消息并重新提问';
    editBtn.onclick = () => enableEdit(msgId, content);
    wrap.appendChild(editBtn);
  }
  messagesEl.appendChild(wrap);
  scrollBottom();
  return bubble;
}

async function newChat() {
  const res = await fetch(API.sessions, { method: 'POST' });
  const s = await res.json();
  currentSessionId = s.id;
  currentTitleEl.textContent = s.title || ('对话 ' + s.id);
  messagesEl.innerHTML = '';
  await loadSessions();
}

async function deleteSession(id) {
  const ok = await showModal({ title: '删除对话', message: '确定删除该对话？删除后不可恢复。', okText: '删除', danger: true });
  if (!ok) return;
  try {
    const res = await fetch(`${API.sessions}/${id}`, { method: 'DELETE' });
    if (!res.ok) {
      const o = await res.json().catch(() => ({}));
      throw new Error(o.error || o.message || ('HTTP ' + res.status));
    }
    toast('对话已删除');
    // 真正删除：不再自动新建对话。删掉的是当前对话时回到空状态。
    if (currentSessionId === id) {
      currentSessionId = null;
      currentTitleEl.textContent = '校园智答';
      messagesEl.innerHTML =
        '<div class="empty-tip">👋 你好，我是校园智答。问问我关于选课、考试、宿舍、图书馆、就业等校园问题吧～</div>';
    }
    await loadSessions();
  } catch (err) {
    toast('删除失败：' + err.message, 'error');
  }
}

/** 右键会话菜单状态 */
let ctxSessionId = null;
let ctxSessionTitle = '';

function openContextMenu(e, sessionId, title) {
  const menu = document.getElementById('ctx-menu');
  if (!menu) return;
  ctxSessionId = sessionId;
  ctxSessionTitle = title || '';
  menu.hidden = false;
  const mw = menu.offsetWidth, mh = menu.offsetHeight;
  let x = e.clientX, y = e.clientY;
  if (x + mw > window.innerWidth) x = window.innerWidth - mw - 4;
  if (y + mh > window.innerHeight) y = window.innerHeight - mh - 4;
  menu.style.left = x + 'px';
  menu.style.top = y + 'px';
  e.stopPropagation();
}

function hideContextMenu() {
  const menu = document.getElementById('ctx-menu');
  if (menu) menu.hidden = true;
  ctxSessionId = null;
  ctxSessionTitle = '';
}

async function renameSession(id, defaultTitle) {
  const input = await showModal({ title: '重命名对话', input: true, defaultValue: defaultTitle || '', okText: '保存' });
  if (input === null) return;          // 取消
  const title = input.trim();
  if (!title) { toast('名称不能为空', 'error'); return; }
  try {
    const res = await fetch(`${API.sessions}/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ title })
    });
    if (!res.ok) {
      const o = await res.json().catch(() => ({}));
      throw new Error(o.error || o.message || ('HTTP ' + res.status));
    }
    toast('已重命名为「' + title + '」');
    if (currentSessionId === id) currentTitleEl.textContent = title;
    await loadSessions();
  } catch (err) {
    toast('重命名失败：' + err.message, 'error');
  }
}

/**
 * 发送提问并流式接收回答。
 * @param {string} [retryText] 「重新回答」时重发的原文（此时不再重复写入提问）
 * @param {boolean} [isRetry] 是否重答模式
 */
async function send(retryText, isRetry = false) {
  const text = isRetry && typeof retryText === 'string' ? retryText.trim() : inputEl.value.trim();
  if (!text) return;
  if (!isRetry) {
    inputEl.value = '';
  }
  const empty = messagesEl.querySelector('.empty-tip');
  if (empty) empty.remove();

  // 修改重问模式：先删除该消息及其之后，再从修改后的内容重新提问
  if (!isRetry && editingFromMsgId != null) {
    const fromId = editingFromMsgId;
    editingFromMsgId = null;
    hideEditBar();
    try {
      const dr = await fetch(`${API.sessions}/${currentSessionId}/messages?from=${fromId}`, { method: 'DELETE', credentials: 'same-origin' });
      if (!dr.ok) {
        const o = await dr.json().catch(() => ({}));
        throw new Error(o.error || o.message || ('HTTP ' + dr.status));
      }
    } catch (e) {
      toast('修改失败：' + e.message, 'error');
      return;
    }
    await selectSession(currentSessionId); // 重渲：仅保留被改消息之前的对话
  }

  if (!currentSessionId) {
    const r = await fetch(API.sessions, { method: 'POST' });
    const s = await r.json();
    currentSessionId = s.id;
    await loadSessions();
  }
  if (!isRetry) appendMessage('user', text);

  const assistantWrap = document.createElement('div');
  assistantWrap.className = 'msg assistant';
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = '';
  assistantWrap.appendChild(bubble);
  messagesEl.appendChild(assistantWrap);
  scrollBottom();

  const btn = $('#send');
  btn.disabled = true;
  let sourcesContext = null;
  let sourcesHit = false;
  let raw = '';
  let gotToken = false;
  let failed = false;
  try {
    const res = await fetch(API.chat, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId: currentSessionId, message: text, retry: isRetry })
    });
    // 非 2xx：后端抛错（如向量化失败、模型不可用）时会走到这里，必须显示出来
    if (!res.ok) {
      throw new Error(await describeHttpError(res));
    }
    const ct = (res.headers.get('Content-Type') || '').toLowerCase();
    if (!ct.includes('text/event-stream') || !res.body) {
      const raw = await res.text();
      throw new Error('响应格式异常（期望 SSE，实际 ' + (ct || '未知') + '）：' + raw.slice(0, 200));
    }
    const sid = res.headers.get('X-Session-Id');
    if (sid) currentSessionId = Number(sid);
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf('\n\n')) >= 0) {
        const block = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        const { event, data } = parseSseBlock(block);
        if (event === 'sources') {
          try {
            const o = JSON.parse(data);
            sourcesContext = o.context || '';
            sourcesHit = o.hasHit === true;
          } catch { sourcesContext = ''; sourcesHit = false; }
        } else if (event === 'token') {
          gotToken = true;
          raw += data;
          bubble.textContent = raw;            // 流式实时纯文本，保证中间可见
          scrollBottom();
        } else if (event === 'error') {
          failed = true;
          bubble.textContent += (bubble.textContent ? '\n\n' : '') + data;
          assistantWrap.classList.add('failed');
          scrollBottom();
        } else if (event === 'done') {
          // 命中知识库则展示片段，否则提示“未检索到相关资料”
          if (sourcesContext !== null) attachSources(assistantWrap, sourcesContext, sourcesHit);
          sourcesContext = null;
          // 最终用 Markdown 渲染（已做 HTML 转义，安全）
          if (raw.trim()) bubble.innerHTML = renderMarkdown(raw);
        }
      }
    }
    // 流正常结束但一个字都没收到：给出可操作的提示，避免“空白气泡”看起来像没反应
    if (!gotToken && !bubble.textContent.trim()) {
      failed = true;
      bubble.textContent = '未收到回复：模型可能未返回内容，请重试一次。';
      assistantWrap.classList.add('failed');
    }
  } catch (err) {
    failed = true;
    bubble.textContent = (bubble.textContent ? bubble.textContent + '\n\n' : '') + '⚠️ ' + err.message;
    assistantWrap.classList.add('failed');
  } finally {
    btn.disabled = false;
    scrollBottom();
    if (currentSessionId) await selectSession(currentSessionId); // 重渲，使每条消息带 id 可编辑
    if (failed && currentSessionId) attachRetryBar(text);
    await loadStatus();
  }
}

/** 失败气泡下挂一个「重新回答」按钮：清掉这条错误记录后重发同一提问，避免留下重复问答。 */
function attachRetryBar(text) {
  const last = messagesEl.lastElementChild;
  if (!last || !last.classList.contains('assistant')) return;
  const bar = document.createElement('div');
  bar.className = 'retry-bar';
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'retry-btn';
  btn.textContent = '↻ 重新回答';
  btn.onclick = async () => {
    btn.disabled = true;
    btn.textContent = '重试中…';
    const errorMsgId = last.dataset.msgId;
    if (errorMsgId) {
      try {
        await fetch(`${API.sessions}/${currentSessionId}/messages?from=${errorMsgId}`,
          { method: 'DELETE', credentials: 'same-origin' });
      } catch (e) { /* 删除失败也继续重答，只是会话里会多一条错误记录 */ }
    }
    await send(text, true);
  };
  bar.appendChild(btn);
  last.appendChild(bar);
  scrollBottom();
}

/** 把 HTTP 错误响应翻译成一句话：优先取后端返回的 error/message 字段。 */
async function describeHttpError(res) {
  let detail = '';
  try {
    const raw = await res.text();
    try {
      const o = JSON.parse(raw);
      detail = o.error || o.message || o.detail || '';
    } catch { detail = raw.slice(0, 200); }
  } catch { /* 读不到响应体就只报状态码 */ }
  if (res.status === 401) return '登录已失效，请重新登录。';
  if (res.status === 403) return '没有权限执行该操作。';
  if (res.status >= 500) {
    return '服务端错误（HTTP ' + res.status + '）' + (detail ? '：' + detail : '') +
           '。常见原因：网络/DNS 不稳定导致模型服务不可达，请稍后重试。';
  }
  return '请求失败（HTTP ' + res.status + '）' + (detail ? '：' + detail : '');
}

function parseSseBlock(block) {
  let event = 'message';
  const dataLines = [];
  block.split('\n').forEach(line => {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
  });
  return { event, data: dataLines.join('\n') };
}

function attachSources(wrap, text, hit) {
  if (wrap.querySelector('.sources')) return;
  const details = document.createElement('details');
  details.className = 'sources' + (hit ? '' : ' no-hit');
  const summary = document.createElement('summary');
  summary.textContent = hit ? '参考知识库' : '未检索到相关资料（以下为通用回答）';
  const pre = document.createElement('pre');
  pre.textContent = text || '（无）';
  details.appendChild(summary);
  details.appendChild(pre);
  wrap.insertBefore(details, wrap.firstChild);
}

/** HTML 转义，防止 Markdown 渲染时注入脚本（LLM 输出也可能含 <script> 等）。 */
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}

/**
 * 轻量 Markdown 渲染（安全版）：先整体转义 HTML，再做语法替换，杜绝 XSS。
 * 支持：代码块、行内代码、标题、有序/无序列表、引用、加粗、斜体、链接。
 * 不依赖任何第三方库，离线可用。
 */
function renderMarkdown(src) {
  if (!src) return '';
  const codeBlocks = [];
  const inlineCodes = [];
  // 1) 提取围栏代码块（```...```），内容已转义
  let text = escapeHtml(src).replace(/```(\w*)\n([\s\S]*?)```/g, (m, lang, code) => {
    const idx = codeBlocks.push('<pre class="md-pre"><code>' + code.replace(/\n$/, '') + '</code></pre>') - 1;
    return '@CB' + idx + '@';
  });
  // 2) 提取行内代码 `...`
  text = text.replace(/`([^`\n]+?)`/g, (m, code) => {
    const idx = inlineCodes.push('<code class="md-code">' + code + '</code>') - 1;
    return '@IC' + idx + '@';
  });
  // 3) 逐行块级处理
  const lines = text.split('\n');
  const out = [];
  let inList = false, listType = '';
  const closeList = () => { if (inList) { out.push('</' + listType + '>'); inList = false; } };
  for (const line of lines) {
    const cb = line.match(/^@CB(\d+)@$/);
    if (cb) { closeList(); out.push(codeBlocks[Number(cb[1])]); continue; }
    if (/^\s*$/.test(line)) { closeList(); continue; }
    let m;
    if ((m = line.match(/^(#{1,6})\s+(.*)$/))) {
      closeList();
      const lvl = m[1].length;
      out.push('<h' + lvl + '>' + inline(m[2].trim()) + '</h' + lvl + '>');
      continue;
    }
    if ((m = line.match(/^\s*[-*]\s+(.*)$/))) {
      if (!inList || listType !== 'ul') { closeList(); out.push('<ul>'); inList = true; listType = 'ul'; }
      out.push('<li>' + inline(m[1]) + '</li>');
      continue;
    }
    if ((m = line.match(/^\s*\d+\.\s+(.*)$/))) {
      if (!inList || listType !== 'ol') { closeList(); out.push('<ol>'); inList = true; listType = 'ol'; }
      out.push('<li>' + inline(m[1]) + '</li>');
      continue;
    }
    if ((m = line.match(/^>\s?(.*)$/))) {
      closeList();
      out.push('<blockquote>' + inline(m[1]) + '</blockquote>');
      continue;
    }
    closeList();
    out.push('<p>' + inline(line) + '</p>');
  }
  closeList();
  let html = out.join('\n');
  // 4) 回填占位
  html = html.replace(/@IC(\d+)@/g, (m, i) => inlineCodes[Number(i)]);
  html = html.replace(/@CB(\d+)@/g, (m, i) => codeBlocks[Number(i)]);
  return html;
}

/** 行内语法：加粗、斜体、链接（链接仅允许 http/https 与相对路径，防 javascript: 等注入）。 */
function inline(s) {
  return s
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|\s)\*([^*\n]+?)\*(?=\s|$)/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+|\.{0,2}\/[^\s)]*)\)/g,
      (m, t, u) => '<a href="' + u + '" target="_blank" rel="noopener noreferrer">' + t + '</a>');
}

async function loadStatus() {
  try {
    const res = await fetch(API.docsStatus);
    const o = await res.json();
    kbStatusEl.textContent = `知识库：${o.chunks} 个片段`;
  } catch { kbStatusEl.textContent = '知识库状态未知'; }
}

async function reindex() {
  const btn = $('#reindex-btn');
  btn.disabled = true;
  kbStatusEl.textContent = '正在重建索引…';
  try {
    const res = await fetch(API.reindex, { method: 'POST', credentials: 'same-origin' });
    if (res.status === 401) {
      location.href = '/login.html';
      return;
    }
    if (res.status === 403) {
      kbStatusEl.textContent = '无权限：仅管理员可重建索引';
      return;
    }
    const o = await res.json();
    kbStatusEl.textContent = `知识库：${o.chunks} 个片段（已重建）`;
  } catch (e) {
    kbStatusEl.textContent = '重建失败：' + e.message;
  } finally {
    btn.disabled = false;
  }
}

inputEl.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});
$('#send').onclick = () => send();
$('#edit-cancel').onclick = cancelEdit;
$('#new-chat').onclick = newChat;
$('#reindex-btn').onclick = reindex;

// 右键会话菜单：点击项执行对应操作；点击别处 / 滚动 / Esc 关闭
const ctxMenuEl = document.getElementById('ctx-menu');
if (ctxMenuEl) {
  ctxMenuEl.addEventListener('click', (e) => {
    const btn = e.target.closest('button');
    if (!btn) return;
    const id = ctxSessionId;
    const title = ctxSessionTitle;
    const action = btn.dataset.action;
    hideContextMenu();
    if (!id) return;
    if (action === 'rename') renameSession(id, title);
    else if (action === 'delete') deleteSession(id);
  });
}
document.addEventListener('click', hideContextMenu);
document.addEventListener('scroll', hideContextMenu, true);
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') hideContextMenu(); });

// 切换/退出：均先调用 logout 清除 cookie，再跳登录页
async function logoutAndRedirect(from) {
  try { await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }); } catch (e) {}
  location.href = from ? ('/login.html?from=' + encodeURIComponent(from)) : '/login.html';
}

function setupUserMenu() {
  const btn = document.getElementById('user-btn');
  const dd = document.getElementById('user-dropdown');
  if (!btn || !dd) return;
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    dd.classList.toggle('open');
  });
  document.addEventListener('click', () => dd.classList.remove('open'));
  const sw = document.getElementById('switch-account');
  const lo = document.getElementById('logout-link');
  if (sw) sw.addEventListener('click', (e) => { e.preventDefault(); logoutAndRedirect('/'); });
  if (lo) lo.addEventListener('click', (e) => { e.preventDefault(); logoutAndRedirect(null); });
}

/** 把账号资料渲染到侧边栏用户区：头像首字母 / 用户名 / 角色·学号 */
function renderUser(me) {
  currentUser = me.username;
  currentRole = me.role;
  const menu = document.getElementById('user-menu');
  if (menu) menu.style.display = '';
  const nameEl = document.getElementById('user-name');
  if (nameEl) nameEl.textContent = me.username;
  const avatarEl = document.getElementById('user-avatar');
  if (avatarEl) avatarEl.textContent = (me.username || '·').slice(0, 1);
  const roleText = me.role === 'ADMIN' ? '管理员' : '普通用户';
  const subEl = document.getElementById('user-sub');
  if (subEl) subEl.textContent = roleText + ' · ' + (me.studentNo ? '学号 ' + me.studentNo : '未绑定学号');
  applyRole();
  setupUserMenu();
}

// 全站登录：未登录跳登录页；已登录渲染侧边栏用户区
(async () => {
  try {
    const r = await fetch('/api/me', { credentials: 'same-origin' });
    if (!r.ok) { location.href = '/login.html'; return; }
    renderUser(await r.json());
  } catch (e) {
    location.href = '/login.html';
    return;
  }
  loadSessions();
  loadStatus();
})();
