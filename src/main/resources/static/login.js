const form = document.getElementById('login-form');
const errEl = document.getElementById('error');
const btn = document.getElementById('submit-btn');
const connStatus = document.getElementById('conn-status');
const params = new URLSearchParams(location.search);

// 页面加载即检测后端连通性，后端没起时给出明确提示，避免“点了没反应”的错觉
function setConnStatus(ok) {
  if (!connStatus) return;
  if (ok) {
    connStatus.textContent = '';
    connStatus.className = 'conn-status';
  } else {
    connStatus.textContent = '⚠️ 无法连接后端：请确认项目已启动并访问 http://localhost:8088/login.html';
    connStatus.className = 'conn-status warn';
  }
}

const redirectByRole = (role) => {
  location.href = role === 'ADMIN'
    ? (params.get('from') || '/admin.html')
    : (params.get('code') ? '/' : '/');
};

// 微信扫码回调：URL 带 code 时优先走扫码登录（code 仅可用一次，处理完即清理）
// state 由后端生成并存在服务端会话里，这里原样带回，后端比对通过才认这次授权（防 CSRF）
(async () => {
  const code = params.get('code');
  const state = params.get('state');
  if (code) {
    await handleWechatCallback(code, state);
    return;
  }
  // 已登录则直接进入对应页面，避免重复登录
  try {
    const r = await fetch('/api/auth/me');
    if (r.ok) {
      setConnStatus(true);
      const me = await r.json();
      location.href = me.role === 'ADMIN' ? '/admin.html' : '/';
    } else {
      setConnStatus(true); // 后端可达但未登录，属正常
    }
  } catch (e) {
    setConnStatus(false); // 后端不可达
  }
})();

async function handleWechatCallback(code, state) {
  history.replaceState({}, '', location.pathname); // 清掉 code，避免刷新重复消费
  btn.disabled = true;
  try {
    const r = await fetch('/api/auth/wechat/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin',
      body: JSON.stringify({ code, state })
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) { errEl.textContent = data.error || '微信登录失败'; return; }
    location.href = data.role === 'ADMIN' ? '/admin.html' : '/';
  } catch (e) {
    errEl.textContent = '网络错误：' + e.message;
  } finally {
    btn.disabled = false;
  }
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  errEl.textContent = '';
  btn.disabled = true;
  btn.textContent = '登录中…';
  try {
    // Spring Security 的表单登录只接受 application/x-www-form-urlencoded（username/password 字段名固定）
    const r = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      credentials: 'same-origin',
      body: new URLSearchParams({
        username: document.getElementById('username').value,
        password: document.getElementById('password').value
      })
    });
    if (!r.ok) {
      const o = await r.json().catch(() => ({}));
      errEl.textContent = o.error || '登录失败';
      return;
    }
    const data = await r.json();
    redirectByRole(data.role);
  } catch (err) {
    if (err && /fetch|network|Failed/i.test(err.message || '')) {
      errEl.textContent = '无法连接后端：请先启动项目（http://localhost:8088）';
      setConnStatus(false);
    } else {
      errEl.textContent = '网络错误：' + (err && err.message ? err.message : err);
    }
  } finally {
    btn.disabled = false;
    btn.textContent = '登录';
  }
});

// 微信扫码登录按钮：配置凭据则跳真实扫码，未配置走演示登录
const wxBtn = document.getElementById('wechat-login-btn');
const wxHint = document.getElementById('wechat-hint');
if (wxBtn) {
  wxBtn.onclick = async () => {
    wxBtn.disabled = true;
    try {
      const r = await fetch('/api/auth/wechat/authorize-url');
      const o = await r.json();
      if (o.demo) {
        const res = await fetch('/api/auth/wechat/login', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          credentials: 'same-origin',
          body: JSON.stringify({ code: 'demo-' + Date.now() })
        });
        const data = await res.json().catch(() => ({}));
        if (!res.ok) { errEl.textContent = data.error || '微信登录失败'; wxBtn.disabled = false; return; }
        location.href = data.role === 'ADMIN' ? '/admin.html' : '/';
      } else {
        location.href = o.url; // 跳转微信扫码
      }
    } catch (e) {
      errEl.textContent = '网络错误：' + e.message;
      wxBtn.disabled = false;
    }
  };
  // 演示模式提示（包进 async IIFE，避免顶层 await 导致普通 script 解析失败）
  (async () => {
    try {
      const r = await fetch('/api/auth/wechat/authorize-url');
      const o = await r.json();
      if (o.demo) wxHint.textContent = '演示模式：未配置微信开放平台凭据，点击将创建演示账号登录';
    } catch (e) {}
  })();
}
