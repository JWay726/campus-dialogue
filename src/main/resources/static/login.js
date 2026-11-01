const form = document.getElementById('login-form');
const errEl = document.getElementById('error');
const btn = document.getElementById('submit-btn');
const connStatus = document.getElementById('conn-status');
const params = new URLSearchParams(location.search);
/** 切换账号模式：从已登录状态点「切换账号」过来，此时不自动跳走、不登出，允许反悔返回。 */
const isSwitch = params.get('switch') === '1';
/** 来源页（仅接受站内绝对路径，防止 ?from=//evil.com 之类的开放重定向）。 */
const safeFrom = (() => {
  const f = params.get('from');
  return f && f.startsWith('/') && !f.startsWith('//') ? f : null;
})();
/** 切换账号前的登录身份（真实用户名），用于判断登录后是否真的换了账号。 */
let previousUsername = null;

// 页面加载即检测后端连通性，后端没起时给出明确提示，避免“点了没反应”的错觉
function setConnStatus(ok) {
  if (!connStatus) return;
  if (ok) {
    connStatus.textContent = '';
    connStatus.className = 'conn-status';
  } else {
    connStatus.textContent = '⚠️ 无法连接后端：请确认项目已启动后访问 ' + location.origin + '/login.html';
    connStatus.className = 'conn-status warn';
  }
}

const redirectByRole = (role) => {
  location.href = role === 'ADMIN'
    ? (safeFrom || '/admin.html')
    : (params.get('code') ? '/' : '/');
};

/**
 * 「← 返回对话」只在「从对话页主动跳过来」时出现（最典型的是点了侧边栏「切换账号」又不想切了）。
 * 直接打开登录页、注册后跳来、退出登录时都没有可返回的场景，隐藏它避免点了像没反应。
 */
(function initBackLink() {
  const back = document.getElementById('login-back');
  if (!back) return;
  if (safeFrom) {
    back.href = safeFrom;
    back.style.display = '';
  } else {
    back.style.display = 'none';
  }
})();

// 微信扫码回调：URL 带 code 时优先走扫码登录（code 仅可用一次，处理完即清理）
// state 由后端生成并存在服务端会话里，这里原样带回，后端比对通过才认这次授权（防 CSRF）
(async () => {
  const code = params.get('code');
  const state = params.get('state');
  if (code) {
    await handleWechatCallback(code, state);
    return;
  }
  // 已登录则直接进入对应页面，避免重复登录；「切换账号」模式下停留本页并可反悔返回
  try {
    const r = await fetch('/api/auth/me');
    if (r.ok) {
      setConnStatus(true);
      const me = await r.json();
      if (isSwitch) {
        showCurrentAccount(me);
      } else {
        location.href = me.role === 'ADMIN' ? '/admin.html' : '/';
      }
    } else {
      setConnStatus(true); // 后端可达但未登录，属正常
    }
  } catch (e) {
    setConnStatus(false); // 后端不可达
  }
})();

/** 切换账号模式：提示当前身份，说明直接登录新账号才会切换、返回则保留原登录。 */
function showCurrentAccount(me) {
  const el = document.getElementById('current-account');
  if (!el) return;
  previousUsername = me.username;
  const roleText = me.role === 'ADMIN' ? '管理员' : '普通用户';
  const noText = me.studentNo ? `，学号 ${me.studentNo}` : '';
  el.textContent = `当前已登录：${me.username}（${roleText}${noText}）。输入其他账号登录即可切换；`
    + '直接点下方「← 返回对话」则保留当前登录、继续使用。';
  el.style.display = '';
}

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
  const username = document.getElementById('username').value;
  const password = document.getElementById('password').value;
  try {
    let res = await postLogin(username, password);
    if (!res.ok) {
      errEl.textContent = res.body.error || '登录失败';
      return;
    }
    // 登录响应里的 username 是「真实用户名」（用学号登录时也一样），据此判断是否真的换了账号
    const target = res.body.username || username;
    if (isSwitch && previousUsername && target !== previousUsername) {
      // 想换成别人：确认服务端认证真的换成了新账号（若后端沿用了旧会话，就清掉重登一次）
      const me = await currentMe();
      if (!me || me.username !== target) {
        await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }).catch(() => {});
        res = await postLogin(username, password);
        if (!res.ok) {
          errEl.textContent = (res.body.error || '切换失败') + '（原账号已退出，请重新登录）';
          return;
        }
      }
    }
    redirectByRole(res.body.role);
  } catch (err) {
    if (err && /fetch|network|Failed/i.test(err.message || '')) {
      errEl.textContent = '无法连接后端：请先启动项目后重试';
      setConnStatus(false);
    } else {
      errEl.textContent = '网络错误：' + (err && err.message ? err.message : err);
    }
  } finally {
    btn.disabled = false;
    btn.textContent = '登录';
  }
});

/** 提交账号密码（Spring Security 表单登录只接受 application/x-www-form-urlencoded）。 */
async function postLogin(username, password) {
  const r = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    credentials: 'same-origin',
    body: new URLSearchParams({ username, password })
  });
  const body = await r.json().catch(() => ({}));
  return { ok: r.ok, body };
}

/** 当前会话的登录身份，未登录返回 null。 */
async function currentMe() {
  try {
    const r = await fetch('/api/auth/me', { credentials: 'same-origin' });
    return r.ok ? await r.json() : null;
  } catch (e) {
    return null;
  }
}

// 微信扫码登录按钮：配置凭据则跳真实扫码，未配置走演示登录
const wxBtn = document.getElementById('wechat-login-btn');
const wxHint = document.getElementById('wechat-hint');
if (wxBtn) {
  wxBtn.onclick = async () => {
    wxBtn.disabled = true;
    try {
      // 切换账号：先清掉旧会话，扫码成功后才会成为新的登录身份（state 会存到新会话里）
      if (isSwitch) {
        await fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' }).catch(() => {});
      }
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
