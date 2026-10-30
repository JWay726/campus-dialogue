/* 通用自绘对话框：Dialog.confirm / Dialog.prompt / Dialog.toast
   用于替代原生 confirm()、alert()、prompt()——原生弹窗由宿主浏览器渲染标题，
   在 WorkBuddy 内置预览里会显示成 "WorkBuddy"，体验不一致。 */
(function () {
  'use strict';

  var mask = null, boxEl = null, titleEl = null, msgEl = null, inputEl = null;
  var okBtn = null, cancelBtn = null;
  var settle = null, lastFocus = null;

  function build() {
    if (mask) return;
    mask = document.createElement('div');
    mask.className = 'wb-dialog-mask';
    mask.hidden = true;
    mask.innerHTML =
      '<div class="wb-dialog" role="dialog" aria-modal="true">' +
        '<h3 class="wb-dialog-title"></h3>' +
        '<p class="wb-dialog-msg"></p>' +
        '<input class="wb-dialog-input" type="text" autocomplete="off" />' +
        '<div class="wb-dialog-actions">' +
          '<button type="button" class="wb-btn ghost wb-cancel">取消</button>' +
          '<button type="button" class="wb-btn primary wb-ok">确定</button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(mask);

    boxEl = mask.querySelector('.wb-dialog');
    titleEl = mask.querySelector('.wb-dialog-title');
    msgEl = mask.querySelector('.wb-dialog-msg');
    inputEl = mask.querySelector('.wb-dialog-input');
    okBtn = mask.querySelector('.wb-ok');
    cancelBtn = mask.querySelector('.wb-cancel');

    okBtn.addEventListener('click', function () { close(true); });
    cancelBtn.addEventListener('click', function () { close(false); });
    mask.addEventListener('mousedown', function (e) { if (e.target === mask) close(false); });
    document.addEventListener('keydown', onKey, true);
  }

  function onKey(e) {
    if (!mask || mask.hidden) return;
    if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); close(false); }
    else if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); e.stopPropagation(); close(true); }
  }

  function close(ok) {
    if (!settle) return;
    var value = inputEl.hidden ? ok : (ok ? inputEl.value : null);
    var done = settle;
    settle = null;
    mask.hidden = true;
    if (lastFocus && lastFocus.focus) { try { lastFocus.focus(); } catch (err) {} }
    lastFocus = null;
    done({ ok: ok, value: value });
  }

  function open(opts) {
    build();
    var o = (typeof opts === 'string') ? { message: opts } : (opts || {});
    titleEl.textContent = o.title || '请确认';
    titleEl.hidden = !o.title;
    msgEl.textContent = o.message || '';
    msgEl.hidden = !o.message;

    var withInput = !!o.input;
    inputEl.hidden = !withInput;
    inputEl.value = withInput ? (o.defaultValue || '') : '';
    inputEl.placeholder = o.placeholder || '';

    okBtn.textContent = o.okText || '确定';
    okBtn.className = 'wb-btn wb-ok ' + (o.danger ? 'danger' : 'primary');
    cancelBtn.textContent = o.cancelText || '取消';
    cancelBtn.hidden = o.cancel === false;

    lastFocus = document.activeElement;
    mask.hidden = false;

    return new Promise(function (resolve) {
      settle = function (r) { resolve(withInput ? r.value : r.ok); };
      setTimeout(function () {
        if (withInput) { inputEl.focus(); inputEl.select(); }
        else okBtn.focus();
      }, 0);
    });
  }

  var toastTimer = null;
  function toast(msg, type) {
    var el = document.querySelector('.wb-toast');
    if (!el) {
      el = document.createElement('div');
      el.className = 'wb-toast';
      document.body.appendChild(el);
    }
    el.textContent = msg || '';
    el.classList.toggle('error', type === 'error');
    // 强制回流，保证连续调用时动画重放
    void el.offsetWidth;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { el.classList.remove('show'); }, 2400);
  }

  window.Dialog = {
    confirm: function (opts) { return open(opts); },
    alert: function (opts) { return open(opts); },
    prompt: function (opts) {
      var o = (typeof opts === 'string') ? { title: opts } : (opts || {});
      o.input = true;
      return open(o);
    },
    toast: toast
  };
})();
