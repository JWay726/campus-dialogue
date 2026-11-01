const api = {
  list:    () => authFetch('/api/admin/docs').then(r => r.json()),
  get:     (id) => authFetch('/api/admin/docs/' + id).then(r => r.json()),
  create:  (title, content) => authFetch('/api/admin/docs', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, content })
  }).then(r => r.json()),
  update:  (id, title, content) => authFetch('/api/admin/docs/' + id, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, content })
  }).then(r => r.json()),
  del:     (id) => authFetch('/api/admin/docs/' + id, { method: 'DELETE' }).then(okJson),
  wipeAll: () => authFetch('/api/admin/docs', { method: 'DELETE' }).then(okJson),
  importSamples: () => authFetch('/api/admin/docs/import-samples', { method: 'POST' }).then(okJson),
  reindex: () => authFetch('/api/docs/reindex', { method: 'POST' }).then(r => r.json()),
  status:  () => authFetch('/api/docs/status').then(r => r.json()),
  upload:  (files) => {
    const form = new FormData();
    for (const f of files) form.append('files', f, f.name);
    return authFetch('/api/docs/upload', { method: 'POST', body: form })
      .then(r => {
        if (!r.ok) return r.json().then(o => { throw new Error(o.message || o.error || ('HTTP ' + r.status)); });
        return r.json();
      });
  },
};

const usersApi = {
  list: () => authFetch('/api/admin/users').then(r => r.json()),
  del:  (id) => authFetch('/api/admin/users/' + id, { method: 'DELETE' }).then(r => r.json()),
};

/** 同源请求自动携带 campus_token Cookie；遇到 401（未登录/过期）跳登录页。 */
async function authFetch(url, opts = {}) {
  const res = await fetch(url, { credentials: 'same-origin', ...opts });
  if (res.status === 401) { location.href = '/login.html?from=/admin.html'; throw new Error('未登录'); }
  return res;
}

/** 读取 JSON 并按 HTTP 状态判定成功/失败，失败时把后端 message 抛给调用方（避免「看着成功其实没删」）。 */
async function okJson(res) {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(body.message || body.error || ('HTTP ' + res.status));
  }
  return body;
}

const $ = (s) => document.querySelector(s);

let currentId = null;
let docs = [];
let visible = [];

const titleEl = $('#doc-title');
const contentEl = $('#doc-content');
const saveHint = $('#save-hint');
const deleteBtn = $('#delete-doc');
const fileInput = $('#file-input');
const userEl = $('#admin-user');
const docView = $('#doc-view');
const userView = $('#user-view');
const userList = $('#user-list');
const userStatus = $('#user-status');
const userEditor = $('#user-editor');
const uUsername = $('#u-username');
const uPassword = $('#u-password');
const uRole = $('#u-role');
const uSave = $('#u-save');
const uCancel = $('#u-cancel');
const uHint = $('#u-hint');

const pickerTrigger = $('#picker-trigger');
const pickerPanel = $('#picker-panel');
const pickerSearch = $('#picker-search');
const pickerOptions = $('#picker-options');
const pickerTitleEl = $('#picker-title');
const pickerMetaEl = $('#picker-meta');
let pickerOpen = false;
let activeIndex = 0;

function escapeHtml(s) {
  return String(s || '').replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function fmtTime(ms) {
  if (!ms) return '';
  return new Date(ms).toLocaleString('zh-CN');
}

// ---------- 文档下拉选择器 ----------
function updateTriggerLabel() {
  const cur = docs.find(d => d.id === currentId);
  if (cur) {
    pickerTitleEl.textContent = cur.title || '未命名';
    pickerMetaEl.textContent = [cur.source, fmtTime(cur.updatedAt)].filter(Boolean).join(' · ');
  } else if (titleEl.value.trim()) {
    pickerTitleEl.textContent = '新文档（未保存）';
    pickerMetaEl.textContent = '填写正文后点“保存”';
  } else {
    pickerTitleEl.textContent = '请选择文档';
    pickerMetaEl.textContent = docs.length ? `共 ${docs.length} 篇文档` : '知识库为空，点“+ 新建”';
  }
}

function renderOptions(keyword) {
  const kw = String(keyword || '').trim().toLowerCase();
  visible = docs.filter(d =>
    !kw ||
    String(d.title || '').toLowerCase().includes(kw) ||
    String(d.source || '').toLowerCase().includes(kw));

  if (!visible.length) {
    pickerOptions.innerHTML =
      `<div class="picker-empty">${docs.length ? '没有匹配的文档' : '还没有文档，点“+ 新建”开始'}</div>`;
    return;
  }
  if (activeIndex >= visible.length) activeIndex = visible.length - 1;
  if (activeIndex < 0) activeIndex = 0;

  pickerOptions.innerHTML = visible.map((d, i) => `
    <div class="picker-option${d.id === currentId ? ' selected' : ''}${i === activeIndex ? ' active' : ''}"
         data-id="${d.id}" role="option">
      <span class="opt-main">
        <span class="opt-title">${escapeHtml(d.title || '未命名')}</span>
        <span class="opt-meta">${escapeHtml([d.source, fmtTime(d.updatedAt)].filter(Boolean).join(' · '))}</span>
      </span>
      <span class="opt-check">✓</span>
    </div>`).join('');

  pickerOptions.querySelectorAll('.picker-option').forEach(el => {
    el.onclick = () => {
      const id = Number(el.dataset.id);
      closePicker();
      loadDoc(id);
    };
  });
  const act = pickerOptions.querySelector('.picker-option.active');
  if (act) act.scrollIntoView({ block: 'nearest' });
}

function openPicker() {
  if (!docs.length) { toast('知识库还没有文档，先点“+ 新建”'); return; }
  pickerOpen = true;
  pickerSearch.value = '';
  activeIndex = Math.max(0, docs.findIndex(d => d.id === currentId));
  pickerPanel.hidden = false;
  pickerTrigger.classList.add('open');
  pickerTrigger.setAttribute('aria-expanded', 'true');
  renderOptions('');
  setTimeout(() => pickerSearch.focus(), 0);
}

function closePicker() {
  if (!pickerOpen) return;
  pickerOpen = false;
  pickerPanel.hidden = true;
  pickerTrigger.classList.remove('open');
  pickerTrigger.setAttribute('aria-expanded', 'false');
}

function togglePicker() { pickerOpen ? closePicker() : openPicker(); }

// ---------- 文档 CRUD ----------
async function refreshList() {
  try {
    docs = await api.list();
  } catch (e) {
    if (e.message !== '未登录') toast('文档列表加载失败');
    docs = docs || [];
  }
  updateTriggerLabel();
  if (pickerOpen) renderOptions(pickerSearch.value);
}

async function loadDoc(id) {
  try {
    const d = await api.get(id);
    currentId = id;
    titleEl.value = d.title || '';
    contentEl.value = d.content || '';
    deleteBtn.hidden = false;
    saveHint.textContent = '';
  } catch (e) {
    if (e.message !== '未登录') toast('文档加载失败');
    return;
  }
  updateTriggerLabel();
  if (pickerOpen) renderOptions(pickerSearch.value);
}

function newDoc() {
  currentId = null;
  titleEl.value = '';
  contentEl.value = '';
  deleteBtn.hidden = true;
  saveHint.textContent = '';
  closePicker();
  updateTriggerLabel();
  titleEl.focus();
}

async function save() {
  const title = titleEl.value.trim() || '未命名文档';
  const content = contentEl.value;
  try {
    const res = currentId ? await api.update(currentId, title, content)
                          : await api.create(title, content);
    currentId = res.id;
    deleteBtn.hidden = false;
    saveHint.textContent = '已保存，该文档索引已更新';
    await refreshList();
  } catch (e) {
    if (e.message !== '未登录') saveHint.textContent = '保存失败：' + e.message;
  }
}

async function del() {
  if (!currentId) return;
  const d = docs.find(x => x.id === currentId);
  const ok = await Dialog.confirm({
    title: '删除文档',
    message: `确认删除《${d ? (d.title || '未命名') : '该文档'}》？该文档的索引片段会一并清除，立即不再参与问答。`,
    okText: '删除',
    cancelText: '取消',
    danger: true
  });
  if (!ok) return;
  try {
    const r = await api.del(currentId);
    newDoc();
    await refreshList();
    await refreshStatus();
    toast(`已删除，同时清理 ${r.vectorsCleared ?? 0} 个索引片段${r.documents === 0 ? '（知识库已空）' : ''}`);
  } catch (e) {
    if (e.message !== '未登录') toast('删除失败：' + e.message);
  }
}

/** 清空整个知识库（文档 + 向量片段），用于彻底换一批资料。 */
async function wipeAll() {
  const ok = await Dialog.confirm({
    title: '清空知识库',
    message: '将删除全部知识库文档及其索引片段，问答将不再引用任何校园资料。此操作不可撤销，建议先确认已备份。',
    okText: '全部清空',
    cancelText: '取消',
    danger: true
  });
  if (!ok) return;
  try {
    const r = await api.wipeAll();
    newDoc();
    await refreshList();
    await refreshStatus();
    toast(`知识库已清空，删除文档 ${r.documentsDeleted ?? 0} 篇`);
  } catch (e) {
    if (e.message !== '未登录') toast('清空失败：' + e.message);
  }
}

/** 手动导入 classpath 内置的示例知识库（同名文件已存在会跳过）。 */
async function importSamples() {
  const btn = $('#import-samples');
  if (btn) { btn.disabled = true; btn.textContent = '导入中…'; }
  try {
    const r = await api.importSamples();
    await refreshList();
    await refreshStatus();
    toast(`已导入示例文档 ${r.imported ?? 0} 篇，当前共 ${r.documents ?? 0} 篇`);
  } catch (e) {
    if (e.message !== '未登录') toast('导入失败：' + e.message);
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = '⬇ 导入示例库'; }
  }
}

async function reindex() {
  const btn = $('#reindex-btn');
  btn.disabled = true;
  btn.textContent = '重建中…';
  try {
    const r = await api.reindex();
    await refreshStatus();
    toast(`索引已重建，共 ${r.chunks} 个片段`);
  } catch (e) {
    if (e.message !== '未登录') toast('重建失败：' + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = '🔄 重建索引';
  }
}

async function refreshStatus() {
  try {
    const s = await api.status();
    $('#kb-status').textContent =
      `文档 ${s.documents ?? 0} 篇 · 片段 ${s.chunks ?? 0} · RAG ${s.ragEnabled ? '开启' : '关闭'}`;
  } catch (e) {
    $('#kb-status').textContent = '状态获取失败';
  }
}

// ---------- 轻提示（统一走通用对话框组件 /dialog.js） ----------
function toast(msg, type) { Dialog.toast(msg, type); }

// ---------- 用户管理 ----------
async function refreshUsers() {
  try {
    const users = await usersApi.list();
    userList.innerHTML = '';
    users.forEach(u => {
      const tr = document.createElement('tr');
      tr.innerHTML = `<td>${escapeHtml(u.username)}</td>
        <td>${u.role === 'ADMIN' ? '管理员' : '普通用户'}</td>
        <td>${fmtTime(u.createdAt)}</td>
        <td><button class="user-del" data-id="${u.id}">删除</button></td>`;
      userList.appendChild(tr);
    });
    userList.querySelectorAll('.user-del').forEach(b => {
      b.onclick = () => delUser(Number(b.dataset.id));
    });
  } catch (e) {
    if (e.message !== '未登录') userStatus.textContent = '用户列表加载失败：' + e.message;
  }
}

async function createUser(username, password, role) {
  const res = await authFetch('/api/admin/users', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, role })
  });
  if (!res.ok) {
    const o = await res.json().catch(() => ({}));
    throw new Error(o.error || '创建失败');
  }
  return res.json();
}

async function delUser(id) {
  const ok = await Dialog.confirm({
    title: '删除用户',
    message: '确认删除该用户？删除后该账号将无法登录。',
    okText: '删除',
    cancelText: '取消',
    danger: true
  });
  if (!ok) return;
  try {
    await usersApi.del(id);
    await refreshUsers();
    userStatus.textContent = '用户已删除';
  } catch (e) {
    if (e.message !== '未登录') userStatus.textContent = '删除失败：' + e.message;
  }
}

function showUserEditor() {
  userEditor.hidden = false;
  uUsername.value = '';
  uPassword.value = '';
  uRole.value = 'USER';
  uHint.textContent = '';
  uUsername.focus();
}

async function saveUser() {
  try {
    await createUser(uUsername.value.trim(), uPassword.value, uRole.value);
    userEditor.hidden = true;
    await refreshUsers();
    userStatus.textContent = '用户已创建';
  } catch (e) {
    uHint.textContent = e.message;
  }
}

function setupTabs() {
  document.querySelectorAll('.tab').forEach(t => {
    t.onclick = () => {
      const which = t.dataset.tab;
      document.querySelectorAll('.tab').forEach(x => x.classList.toggle('active', x === t));
      docView.hidden = which !== 'docs';
      userView.hidden = which !== 'users';
      closePicker();
      if (which === 'users') refreshUsers();
    };
  });
}

async function logout() {
  try { await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }); } catch (e) {}
  location.href = '/login.html';
}

// ---------- 事件绑定 ----------
pickerTrigger.onclick = (e) => { e.stopPropagation(); togglePicker(); };
pickerPanel.onclick = (e) => e.stopPropagation();
pickerSearch.oninput = () => { activeIndex = 0; renderOptions(pickerSearch.value); };
pickerSearch.onkeydown = (e) => {
  if (e.key === 'Escape') { e.preventDefault(); closePicker(); pickerTrigger.focus(); }
  else if (e.key === 'ArrowDown') {
    e.preventDefault();
    if (visible.length) { activeIndex = (activeIndex + 1) % visible.length; renderOptions(pickerSearch.value); }
  } else if (e.key === 'ArrowUp') {
    e.preventDefault();
    if (visible.length) { activeIndex = (activeIndex - 1 + visible.length) % visible.length; renderOptions(pickerSearch.value); }
  } else if (e.key === 'Enter') {
    e.preventDefault();
    if (visible[activeIndex]) { const id = visible[activeIndex].id; closePicker(); loadDoc(id); }
  }
};
document.addEventListener('click', () => closePicker());
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closePicker(); });

$('#new-doc').onclick = newDoc;
$('#upload-doc').onclick = () => fileInput.click();
fileInput.onchange = async () => {
  const files = Array.from(fileInput.files || []);
  if (!files.length) return;
  const btn = $('#upload-doc');
  btn.disabled = true;
  btn.textContent = '上传中…';
  try {
    const r = await api.upload(files);
    toast(`已上传 ${r.uploaded} 个文件并即时索引，共 ${r.chunks} 个片段，可直接对话测试`);
    await refreshList();
    await refreshStatus();
  } catch (e) {
    if (e.message !== '未登录') toast('上传失败：' + e.message);
  } finally {
    btn.disabled = false;
    btn.textContent = '📎 上传文件';
    fileInput.value = '';
  }
};
$('#save-doc').onclick = save;
$('#delete-doc').onclick = del;
$('#wipe-all').onclick = wipeAll;
$('#import-samples').onclick = importSamples;
$('#reindex-btn').onclick = reindex;
$('#logout-btn').onclick = logout;
$('#new-user-btn').onclick = showUserEditor;
$('#u-save').onclick = saveUser;
$('#u-cancel').onclick = () => { userEditor.hidden = true; };
titleEl.oninput = () => { if (!currentId) { saveHint.textContent = ''; updateTriggerLabel(); } };

// Ctrl/Cmd + S 保存
document.addEventListener('keydown', (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 's') {
    e.preventDefault();
    if (!docView.hidden) save();
  }
});

(async () => {
  // 进入后台前先确认登录态，未登录直接跳登录页
  try {
    const r = await fetch('/api/auth/me', { credentials: 'same-origin' });
    if (!r.ok) { location.href = '/login.html?from=/admin.html'; return; }
    const me = await r.json();
    userEl.textContent = me.username || 'admin';
  } catch (e) {
    location.href = '/login.html?from=/admin.html';
    return;
  }
  setupTabs();
  await refreshList();
  await refreshStatus();
  // 默认打开第一篇，省去一次点击
  if (docs.length && docs[0].id) loadDoc(docs[0].id);
})();
