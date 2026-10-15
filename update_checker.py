import hashlib
from pathlib import Path
from urllib.parse import urlparse

import requests
from packaging.version import Version
from version import APP_VERSION, GITHUB_API, EXE_NAME, GITHUB_OWNER, GITHUB_REPO

HEADERS = {
    "Accept": "application/vnd.github+json",
    "User-Agent": "AudioBookDownloader-Updater",
}
UPDATER_NAME = "AudioBookUpdater.exe"


def _asset(release, name):
    asset = next((a for a in release.get("assets", []) if a.get("name") == name), None)
    if not asset:
        raise RuntimeError(f"В релизе отсутствует {name}")
    digest = asset.get("digest") or ""
    if not digest.startswith("sha256:") or len(digest.split(":", 1)[1]) != 64:
        raise RuntimeError(f"GitHub не вернул SHA-256 для {name}")
    return {
        "name": name,
        "download_url": asset["browser_download_url"],
        "sha256": digest.split(":", 1)[1],
    }


def check_for_updates():
    response = requests.get(GITHUB_API, headers=HEADERS, timeout=15)
    if response.status_code == 404:
        return None
    response.raise_for_status()
    release = response.json()
    latest = release["tag_name"].lstrip("vV")
    if Version(latest) <= Version(APP_VERSION):
        return None
    main = _asset(release, EXE_NAME)
    return {
        "version": latest,
        "description": release.get("body") or "",
        **main,
        "updater": _asset(release, UPDATER_NAME),
    }


def download_update(update, destination, progress=None):
    destination = Path(destination)
    name = update["name"]
    if name not in (EXE_NAME, UPDATER_NAME):
        raise RuntimeError("Неизвестный файл обновления")
    url = update["download_url"]
    parsed = urlparse(url)
    prefix = f"/{GITHUB_OWNER}/{GITHUB_REPO}/releases/download/"
    if (parsed.scheme != "https" or parsed.hostname != "github.com"
            or not parsed.path.startswith(prefix)
            or not parsed.path.endswith("/" + name)):
        raise RuntimeError("Недопустимый URL обновления")

    digest = hashlib.sha256()
    try:
        with requests.get(url, stream=True, timeout=(15, 60), headers=HEADERS) as response:
            response.raise_for_status()
            total = int(response.headers.get("Content-Length") or 0)
            received = 0
            with destination.open("wb") as file:
                for chunk in response.iter_content(chunk_size=256 * 1024):
                    if not chunk:
                        continue
                    file.write(chunk)
                    digest.update(chunk)
                    received += len(chunk)
                    if progress is not None and total:
                        progress(min(99, received / total * 100))
        if digest.hexdigest().lower() != update["sha256"].lower():
            raise RuntimeError("SHA-256 скачанного файла не совпадает с GitHub")
    except Exception:
        destination.unlink(missing_ok=True)
        raise
    if progress is not None:
        progress(100)
    return destination
