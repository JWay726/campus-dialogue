const $ = (s) => document.querySelector(s);
let profile = null;

/** 统一请求：401 直接跳登录页，其余错误抛给调用方提示。 */
async function api(path, options = {}) {
  const r = await fetch(path, {
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json' },
    ...options
  });
  if (r.status === 401) {
    location.href = '/login.html';
    throw new Error('登录已失效');
  }
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || ('请求失败(' + r.status + ')'));
  return data;
}

function fmtTime(ms) {
  if (!ms) return '';
  const d = new Date(Number(ms));
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function setMsg(sel, text, ok) {
  const node = $(sel);
  if (!node) return;
  node.textContent = text || '';
  node.className = 'msg' + (text ? (ok ? ' ok' : ' err') : '');
}

// ---------------- 渲染 ----------------

function render() {
  $('#username').textContent = profile.username;
  $('#avatar').textContent = (profile.username || '·').slice(0, 1);
  $('#role-tag').textContent = profile.role === 'ADMIN' ? '管理员' : '普通用户';
  $('#created').textContent = profile.createdAt ? '注册于 ' + fmtTime(profile.createdAt) : '';
  const nameInput = $('#new-username');
  if (nameInput) nameInput.placeholder = `当前：${profile.username}（3-32 位，不含空格）`;

  const bound = !!profile.studentNo;
  $('#student-bound').hidden = !bound;
  $('#student-form').hidden = bound;
  if (bound) $('#student-no').textContent = profile.studentNo;

  // 状态标签：未绑定时明确显示"未绑定"，解绑按钮同时置灰，避免点了才报错
  const stateTag = $('#student-state');
  stateTag.textContent = bound ? '已绑定' : '未绑定';
  stateTag.className = 'tag ' + (bound ? 'ok' : 'soft');
  const unbindBtn = $('#student-unbind');
  if (unbindBtn) {
    unbindBtn.disabled = !bound;
    unbindBtn.textContent = bound ? '解绑' : '未绑定';
  }
  if (!bound) $('#student-save').textContent = '绑定学号';
  const cancelBtn = $('#student-cancel');
  if (cancelBtn) cancelBtn.hidden = true;

  renderWechat();
}

function renderWechat() {
  $('#wechat-mode').textContent = profile.wechatConfigured ? '已配置开放平台' : '演示模式';
  const wechatTag = $('#wechat-state-tag');
  wechatTag.textContent = profile.wechatBound ? '已绑定' : '未绑定';
  wechatTag.className = 'tag ' + (profile.wechatBound ? 'ok' : 'soft');
  $('#wechat-desc').textContent = profile.wechatConfigured
    ? '点击绑定将跳转微信扫码授权，授权完成后自动回填 openid 与昵称。'
    : '未配置 WECHAT_APP_ID / WECHAT_APP_SECRET，当前为演示绑定：不访问微信服务器，仅记录一个演示身份，便于本地联调；配置凭据后自动切换为真实扫码。';

  const box = $('#wechat-state');
  box.innerHTML = '';

  if (profile.wechatBound) {
    const info = document.createElement('div');
    info.className = 'wechat-bound';
    const text = document.createElement('div');
    const name = document.createElement('div');
    name.className = 'wechat-name';
    name.textContent = profile.wechatNickname || '微信用户';
    const meta = document.createElement('div');
    meta.className = 'wechat-meta';
    meta.textContent = '绑定于 ' + (fmtTime(profile.wechatBoundAt) || '—');
    text.append(name, meta);
    info.appendChild(text);
    box.appendChild(info);

    const actions = document.createElement('div');
    actions.className = 'row-actions';
    const un = document.createElement('button');
    un.className = 'danger';
    un.type = 'button';
    un.textContent = '解绑微信';
    un.onclick = unbindWechat;
    actions.appendChild(un);
    box.appendChild(actions);
  } else {
    const btn = document.createElement('button');
    btn.id = 'wechat-bind';
    btn.type = 'button';
    btn.textContent = profile.wechatConfigured ? '微信扫码绑定' : '演示绑定微信';
    btn.onclick = startWechatBind;
    box.appendChild(btn);
  }
}

// ---------------- 学号 ----------------

async function saveStudentNo() {
  const v = $('#student-input').value.trim();
  if (!v) { setMsg('#student-msg', '请输入学号', false); return; }
  const btn = $('#student-save');
  btn.disabled = true;
  try {
    profile = await api('/api/me/student-no', { method: 'PUT', body: JSON.stringify({ studentNo: v }) });
    $('#student-input').value = '';
    setMsg('#student-msg', '学号已绑定：' + profile.studentNo, true);
    render();
  } catch (e) {
    setMsg('#student-msg', e.message, false);
  } finally {
    btn.disabled = false;
  }
}

function editStudentNo() {
  $('#student-bound').hidden = true;
  $('#student-form').hidden = false;
  $('#student-input').value = profile.studentNo || '';
  $('#student-save').textContent = '保存学号';
  $('#student-cancel').hidden = false;
  $('#student-input').focus();
  setMsg('#student-msg', '', true);
}

function cancelEditStudentNo() {
  $('#student-input').value = '';
  $('#student-save').textContent = '绑定学号';
  $('#student-cancel').hidden = true;
  setMsg('#student-msg', '', true);
  render();
}

async function unbindStudentNo() {
  if (!profile.studentNo) { setMsg('#student-msg', '当前未绑定学号，无需解绑', false); return; }
  const ok = await Dialog.confirm({
    title: '解绑学号',
    message: `确定解绑学号 ${profile.studentNo}？解绑后将失去与学籍信息的关联。`,
    okText: '解绑',
    cancelText: '取消',
    danger: true
  });
  if (!ok) return;
  try {
    profile = await api('/api/me/student-no', { method: 'PUT', body: JSON.stringify({ studentNo: '' }) });
    setMsg('#student-msg', '学号已解绑', true);
    render();
  } catch (e) {
    setMsg('#student-msg', e.message, false);
  }
}

// ---------------- 微信 ----------------

async function startWechatBind() {
  const btn = $('#wechat-bind');
  if (btn) btn.disabled = true;
  try {
    if (profile.wechatConfigured) {
      const { url } = await api('/api/me/wechat/authorize-url');
      if (url) { location.href = url; return; }
    }
    // 演示模式：提交一个本地 code，后端据此生成稳定的演示 openid
    profile = await api('/api/me/wechat/bind', {
      method: 'POST',
      body: JSON.stringify({ code: 'demo-' + Date.now() })
    });
    setMsg('#wechat-msg', profile.demo ? '已演示绑定（未接入真实微信服务器）' : '微信绑定成功', true);
    render();
  } catch (e) {
    setMsg('#wechat-msg', e.message, false);
    if (btn) btn.disabled = false;
  }
}

async function unbindWechat() {
  if (!profile.wechatBound) { setMsg('#wechat-msg', '当前未绑定微信，无需解绑', false); return; }
  const ok = await Dialog.confirm({
    title: '解绑微信',
    message: '确定解绑微信？解绑后将无法使用微信快捷登录。',
    okText: '解绑',
    cancelText: '取消',
    danger: true
  });
  if (!ok) return;
  try {
    profile = await api('/api/me/wechat/unbind', { method: 'POST' });
    setMsg('#wechat-msg', '微信已解绑', true);
    render();
  } catch (e) {
    setMsg('#wechat-msg', e.message, false);
  }
}

/** 真实模式扫码回调：URL 带 ?code=xxx 时自动提交绑定（state 原样带回，后端比对防 CSRF）。 */
async function handleWechatCallback() {
  const params = new URLSearchParams(location.search);
  const code = params.get('code');
  const state = params.get('state');
  if (!code) return;
  history.replaceState({}, '', location.pathname);
  try {
    profile = await api('/api/me/wechat/bind', { method: 'POST', body: JSON.stringify({ code, state }) });
    setMsg('#wechat-msg', '微信绑定成功', true);
    render();
  } catch (e) {
    setMsg('#wechat-msg', '微信绑定失败：' + e.message, false);
  }
}

// ---------------- 用户名 ----------------

async function saveUsername() {
  const input = $('#new-username');
  const newUsername = (input.value || '').trim();
  const password = $('#username-pwd').value;
  if (!newUsername) { setMsg('#username-msg', '请输入新用户名', false); return; }
  if (newUsername === profile.username) { setMsg('#username-msg', '新用户名与当前用户名相同', false); return; }
  if (newUsername.length < 3 || newUsername.length > 32) { setMsg('#username-msg', '用户名长度需为 3-32 位', false); return; }
  if (/\s/.test(newUsername)) { setMsg('#username-msg', '用户名不能包含空格', false); return; }
  if (!password) { setMsg('#username-msg', '请输入当前密码以确认身份', false); return; }

  const ok = await Dialog.confirm({
    title: '修改用户名',
    message: `用户名将从「${profile.username}」改为「${newUsername}」。`
      + '密码、学号、微信绑定和历史对话都会保留，之后请用新用户名登录。确定修改？',
    okText: '确认修改'
  });
  if (!ok) return;

  const btn = $('#save-username');
  btn.disabled = true;
  try {
    profile = await api('/api/me/username', {
      method: 'POST',
      body: JSON.stringify({ newUsername, password })
    });
    input.value = '';
    $('#username-pwd').value = '';
    setMsg('#username-msg', `用户名已修改为 ${profile.username}，下次登录请使用新用户名`, true);
    render();
  } catch (e) {
    // 若后端改名成功但登录身份刷新失败会返回 401，api() 已跳登录页，这里不再重复提示
    setMsg('#username-msg', e.message, false);
  } finally {
    btn.disabled = false;
  }
}

// ---------------- 修改密码 ----------------

async function savePassword() {
  const oldPassword = $('#old-pwd').value;
  const newPassword = $('#new-pwd').value;
  const confirmPwd = $('#confirm-pwd').value;
  if (!oldPassword || !newPassword) { setMsg('#pwd-msg', '请填写原密码与新密码', false); return; }
  if (newPassword !== confirmPwd) { setMsg('#pwd-msg', '两次输入的新密码不一致', false); return; }

  const btn = $('#save-pwd');
  btn.disabled = true;
  try {
    await api('/api/me/password', { method: 'POST', body: JSON.stringify({ oldPassword, newPassword }) });
    setMsg('#pwd-msg', '密码已修改，即将跳转登录…', true);
    setTimeout(async () => {
      try { await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }); } catch (e) {}
      location.href = '/login.html';
    }, 1200);
  } catch (e) {
    setMsg('#pwd-msg', e.message, false);
    btn.disabled = false;
  }
}

// ---------------- 初始化 ----------------

$('#student-save').onclick = saveStudentNo;
$('#student-edit').onclick = editStudentNo;
$('#student-unbind').onclick = unbindStudentNo;
$('#student-cancel').onclick = cancelEditStudentNo;
$('#student-input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); saveStudentNo(); }
});
$('#save-pwd').onclick = savePassword;
$('#save-username').onclick = saveUsername;
$('#username-pwd').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); saveUsername(); }
});
$('#logout-top').onclick = async () => {
  try { await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }); } catch (e) {}
  location.href = '/login.html';
};

(async () => {
  try {
    profile = await api('/api/me');
    render();
    await handleWechatCallback();
  } catch (e) {
    // 401 已由 api() 跳转；其余错误提示在页面上
    setMsg('#student-msg', e.message, false);
  }
})();
