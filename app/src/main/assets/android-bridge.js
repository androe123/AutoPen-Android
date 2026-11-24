/*
 * АвтоРучка для Android — мост между index.html и нативным кодом.
 * Эмулирует window.pywebview.api (как в десктопной версии на pywebview),
 * перехватывает скачивание файлов (PNG/PDF) и открытие внешних ссылок.
 */
(function () {
  'use strict';
  var B = window.AndroidBridge;
  if (!B) return; // открыто в обычном браузере — ничего не делаем

  // ---------- Аналог DesktopApi из app.py ----------
  function parse(raw, fallback) {
    if (!raw) return fallback;
    try { return JSON.parse(raw); } catch (e) { return fallback; }
  }

  window.pywebview = {
    api: {
      load_settings: function () {
        return Promise.resolve(parse(B.loadSettings(), null));
      },
      save_settings: function (payload) {
        return Promise.resolve(!!B.saveSettings(JSON.stringify(payload)));
      },
      load_photos: function () {
        return Promise.resolve(parse(B.loadPhotos(), []));
      },
      save_photos: function (list) {
        return Promise.resolve(!!B.savePhotos(JSON.stringify(list || [])));
      }
    }
  };

  // ---------- Сохранение файлов (PNG / PDF) в «Загрузки» ----------
  function guessMime(name) {
    var n = (name || '').toLowerCase();
    if (n.endsWith('.pdf')) return 'application/pdf';
    if (n.endsWith('.png')) return 'image/png';
    if (n.endsWith('.jpg') || n.endsWith('.jpeg')) return 'image/jpeg';
    return 'application/octet-stream';
  }

  function blobToBase64(blob) {
    return new Promise(function (resolve, reject) {
      var r = new FileReader();
      r.onload = function () { resolve(String(r.result).split(',')[1] || ''); };
      r.onerror = function () { reject(r.error); };
      r.readAsDataURL(blob);
    });
  }

  function isDownloadLink(a) {
    return a && a.hasAttribute && a.hasAttribute('download') && /^(data|blob):/i.test(a.href || '');
  }

  async function saveLink(a) {
    var name = a.getAttribute('download') || 'file';
    try {
      var blob = await (await fetch(a.href)).blob();
      var mime = blob.type || guessMime(name);
      var b64 = await blobToBase64(blob);
      B.saveFile(name, mime, b64);
    } catch (e) {
      B.toast('Не удалось сохранить файл: ' + (e && e.message ? e.message : e));
    }
  }

  function isExternal(a) {
    return a && /^https?:/i.test(a.href || '') && a.host !== location.host;
  }

  var nativeClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (isDownloadLink(this)) { saveLink(this); return; }
    if (isExternal(this)) { B.openUrl(this.href); return; }
    return nativeClick.call(this);
  };

  // jsPDF (FileSaver) вызывает a.dispatchEvent(new MouseEvent('click'))
  HTMLAnchorElement.prototype.dispatchEvent = function (ev) {
    if (ev && ev.type === 'click') {
      if (isDownloadLink(this)) { saveLink(this); return true; }
      if (isExternal(this)) { B.openUrl(this.href); return true; }
    }
    return EventTarget.prototype.dispatchEvent.call(this, ev);
  };

  // Обычные клики пользователя по ссылкам
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (!a) return;
    if (isDownloadLink(a)) { e.preventDefault(); saveLink(a); }
    else if (isExternal(a)) { e.preventDefault(); B.openUrl(a.href); }
  }, true);

  // ---------- Цвет системных панелей под тему приложения ----------
  function syncTheme() {
    var dark = document.documentElement.getAttribute('data-theme') !== 'light';
    try { B.setDarkTheme(dark); } catch (e) {}
  }
  new MutationObserver(syncTheme).observe(document.documentElement, {
    attributes: true, attributeFilter: ['data-theme']
  });
  document.addEventListener('DOMContentLoaded', syncTheme);

  // ---------- Для кнопки «Назад» ----------
  window.__autopenBackState = function () {
    var modal = document.getElementById('updateModal');
    if (modal && modal.classList.contains('show')) {
      modal.classList.remove('show');
      return 'modal';
    }
    try { if (typeof hasUnsavedChanges !== 'undefined' && hasUnsavedChanges) return 'dirty'; } catch (e) {}
    return 'clean';
  };
  window.__autopenSaveAndExit = function () {
    Promise.resolve(typeof saveNow === 'function' ? saveNow() : null)
      .catch(function () {})
      .then(function () { B.exitApp(); });
  };
})();
