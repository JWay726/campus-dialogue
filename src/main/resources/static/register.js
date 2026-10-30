const form = document.getElementById('register-form');
const errEl = document.getElementById('error');
const btn = document.getElementById('submit-btn');

// 已登录则直接跳走，避免重复注册
(async () => {
  try {
    const r = await fetch('/api/auth/me');
    if (r.ok) {
      const me = await r.json();
      location.href = me.role === 'ADMIN' ? '/admin.html' : '/';
    }
  } catch (e) { /* 未登录，停留在注册页 */ }
})();

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  errEl.textContent = '';
  btn.disabled = true;
  btn.textContent = '注册中…';
  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;
  const confirm = document.getElementById('confirm').value;
  if (password !== confirm) {
    errEl.textContent = '两次输入的密码不一致';
    btn.disabled = false; btn.textContent = '注册';
    return;
  }
  try {
    const r = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ username, password })
    });
    const o = await r.json().catch(() => ({}));
    if (r.ok) {
      const go = await Dialog.confirm({
        title: '注册成功',
        message: '账号已创建，是否前往登录？',
        okText: '去登录',
        cancelText: '留在本页'
      });
      if (go) location.href = '/login.html';
      else { form.reset(); errEl.textContent = ''; }
      return;
    }
    errEl.textContent = o.error || '注册失败';
  } catch (err) {
    errEl.textContent = '网络错误：' + err.message;
  } finally {
    btn.disabled = false;
    btn.textContent = '注册';
  }
});
