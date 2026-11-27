"""
Превью Android-версии АвтоРучки на компьютере (для правки кода в PyCharm).

Открывает app/src/main/assets/index.html в окне pywebview — так же, как
десктопная версия. Окно имеет ширину телефона, чтобы сразу видеть
мобильную вёрстку (mobile.css). Настройки хранятся отдельно от десктопной
версии, в папке %APPDATA%/АвтоРучка-Android-preview.
Запуск:  python preview.py
"""
from pathlib import Path
import json
import os

import webview

ASSETS = Path(__file__).resolve().parent / "app" / "src" / "main" / "assets"


def data_dir():
    root = os.environ.get("APPDATA") or os.path.expanduser("~")
    d = os.path.join(root, "АвтоРучка-Android-preview")
    os.makedirs(d, exist_ok=True)
    return d


def _read(name, default):
    path = os.path.join(data_dir(), name)
    if not os.path.exists(path):
        return default
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _write(name, payload):
    try:
        with open(os.path.join(data_dir(), name), "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
        return True
    except Exception:
        return False


class PreviewApi:
    """То же API, что DesktopApi в app.py и AndroidBridge на телефоне."""

    def load_settings(self):
        return _read("settings.json", None)

    def save_settings(self, payload):
        return _write("settings.json", payload)

    def load_photos(self):
        return _read("photos.json", [])

    def save_photos(self, photos_list):
        return _write("photos.json", photos_list)


if __name__ == "__main__":
    webview.create_window(
        "АвтоРучка (Android-превью)",
        url=(ASSETS / "index.html").as_uri(),
        width=420,
        height=860,
        min_size=(360, 600),
        js_api=PreviewApi(),
        background_color="#0c0e1a",
    )
    webview.start(debug=True)
