
import hashlib
from pathlib import Path
from urllib.parse import urlparse

import requests
from packaging.version import Version

from version import (
    APP_VERSION,
    GITHUB_API,
    EXE_NAME
)


HEADERS = {
    "Accept": "application/vnd.github+json",
    "User-Agent": "AudioBookDownloader-Updater"
}


def check_for_updates():
    response = requests.get(
        GITHUB_API,
        headers=HEADERS,
        timeout=15
    )

    if response.status_code == 404:
        return None

    response.raise_for_status()
    release = response.json()

    latest = release["tag_name"].lstrip("vV")

    if Version(latest) <= Version(APP_VERSION):
        return None

    asset = next(
        (
            item for item in release.get("assets", [])
            if item["name"] == EXE_NAME
        ),
        None
    )

    if not asset:
        raise RuntimeError(
            "В релизе нет файла " + EXE_NAME
        )

    digest = asset.get("digest", "")

    if not digest.startswith("sha256:"):
        raise RuntimeError(
            "GitHub не предоставил SHA-256 файла"
        )

    return {
        "version": latest,
        "description": release.get("body") or "",
        "url": release["html_url"],
        "download_url": asset[
            "browser_download_url"
        ],
        "sha256": digest.split(":", 1)[1]
    }


def download_update(update, destination, progress=None):
    destination = Path(destination)

    url = update["download_url"]

    parsed = urlparse(url)
    if (
        parsed.scheme != "https"
        or parsed.hostname != "github.com"
        or not parsed.path.startswith(
            "/sergeymortes/AudioBook-Downloader/releases/download/"
        )
    ):
        raise RuntimeError(
            "Недопустимый адрес обновления"
        )

    sha256 = hashlib.sha256()

    with requests.get(
        url,
        stream=True,
        timeout=(15, 60)
    ) as response:
        response.raise_for_status()

        total = int(
            response.headers.get("Content-Length", 0)
        )
        downloaded = 0

        with open(destination, "wb") as file:
            for chunk in response.iter_content(1024 * 256):
                if not chunk:
                    continue

                file.write(chunk)
                sha256.update(chunk)
                downloaded += len(chunk)

                if progress and total:
                    progress(
                        min(downloaded / total * 100, 99)
                    )

    if sha256.hexdigest().lower() != update["sha256"].lower():
        destination.unlink(missing_ok=True)
        raise RuntimeError(
            "Контрольная сумма обновления не совпадает"
        )

    if progress:
        progress(100)

    return destination
