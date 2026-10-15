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


def check_for_updates():
    response = requests.get(GITHUB_API, headers=HEADERS, timeout=15)
    if response.status_code == 404:
        return None
    response.raise_for_status()
    release = response.json()
    latest = release["tag_name"].lstrip("vV")
    if Version(latest) <= Version(APP_VERSION):
        return None
    asset = next((a for a in release.get("assets", []) if a.get("name") == EXE_NAME), None)
    if asset is None:
        raise RuntimeError(f"В релизе {latest} отсутствует {EXE_NAME}")
    digest = asset.get("digest") or ""
    if not digest.startswith("sha256:"):
        raise RuntimeError("GitHub не вернул SHA-256 для EXE; обновление отменено")
    return {
        "version": latest,
        "description": release.get("body") or "",
        "download_url": asset["browser_download_url"],
        "sha256": digest.split(":", 1)[1],
    }


def download_update(update, destination, progress=None):
    destination = Path(destination)
    url = update["download_url"]
    parsed = urlparse(url)
    expected_prefix = f"/{GITHUB_OWNER}/{GITHUB_REPO}/releases/download/"
    if (parsed.scheme != "https" or parsed.hostname != "github.com"
            or not parsed.path.startswith(expected_prefix)
            or not parsed.path.endswith("/" + EXE_NAME)):
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
