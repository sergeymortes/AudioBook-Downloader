"""Windows installer, rollback and second-stage updater replacement."""
import argparse
import ctypes
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

APP_EXE = 'AudioBookDownloader.exe'
UPDATER_EXE = 'AudioBookUpdater.exe'


def log_error(exc):
    (Path.home() / 'AudioBookUpdater-error.log').write_text(
        f'{type(exc).__name__}: {exc}', encoding='utf-8')


def wait_for_process(pid, timeout=60):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    kernel.WaitForSingleObject.restype = ctypes.c_ulong
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    kernel.CloseHandle.restype = ctypes.c_int
    handle = kernel.OpenProcess(0x00100000, False, pid)
    if not handle:
        error = ctypes.get_last_error()
        if error == 87:  # PID already gone
            return
        raise OSError(error, 'Не удалось открыть процесс приложения')
    try:
        result = kernel.WaitForSingleObject(handle, timeout * 1000)
        if result == 0:
            return
        if result == 258:
            raise TimeoutError('Приложение не завершилось за 60 секунд')
        raise OSError(ctypes.get_last_error(), f'Ошибка ожидания процесса: {result}')
    finally:
        kernel.CloseHandle(handle)


def backup_path(target, version):
    folder = target.parent / 'backups'
    if version is None or version == 'legacy':
        return folder / 'AudioBookDownloader-legacy.exe'
    if not version or any(c not in '0123456789.' for c in version):
        raise ValueError('Недопустимый номер версии')
    return folder / f'AudioBookDownloader-v{version}.exe'


def trim_backups(folder):
    copies = list(folder.glob('AudioBookDownloader-v*.exe'))
    legacy = folder / 'AudioBookDownloader-legacy.exe'
    if legacy.is_file():
        copies.append(legacy)
    copies.sort(key=lambda p: p.stat().st_mtime, reverse=True)
    for old in copies[2:]:
        old.unlink()


def update_state(ignore_version):
    folder = Path(os.getenv('LOCALAPPDATA', str(Path.home()))) / 'AudioBookDownloader'
    folder.mkdir(parents=True, exist_ok=True)
    dest = folder / 'update_state.json'
    temp = folder / 'update_state.json.tmp'
    temp.write_text(json.dumps({'ignored_version': ignore_version}), encoding='utf-8')
    os.replace(temp, dest)


def launch(target):
    subprocess.Popen([str(target)], cwd=str(target.parent))


def finalize_updater(pid, target, staged_updater):
    target = Path(target).resolve()
    staged_updater = Path(staged_updater).resolve()
    if target.name != APP_EXE or not target.is_file():
        raise RuntimeError('Некорректный основной EXE')
    updater = target.with_name(UPDATER_EXE)
    if staged_updater != updater.with_name(UPDATER_EXE + '.update'):
        raise RuntimeError('Некорректный путь обновлятора')
    try:
        wait_for_process(pid)
        if not staged_updater.is_file():
            raise RuntimeError('Не найден скачанный обновлятор')
        # Antivirus/indexing can briefly hold the old updater executable.
        last_error = None
        for _ in range(30):
            try:
                os.replace(staged_updater, updater)
                last_error = None
                break
            except OSError as exc:
                last_error = exc
                time.sleep(0.5)
        if last_error is not None:
            raise last_error
    finally:
        # Even if the updater replacement failed, start the already-installed app.
        launch(target)


def replace_exe(pid, target, source, current_version, restoring=False,
                restore_version=None, staged_updater=None):
    target = Path(target).resolve()
    source = Path(source).resolve()
    if target.name != APP_EXE or not target.is_file() or not source.is_file():
        raise RuntimeError('Не найден основной EXE или файл обновления')
    if restoring:
        if source != backup_path(target, restore_version).resolve():
            raise RuntimeError('Некорректный путь резервной копии')
        if not current_version:
            raise RuntimeError('При откате необходимо указать текущую версию')
        if staged_updater:
            raise RuntimeError('Откат не поддерживает замену обновлятора')
    elif source != target.with_name(APP_EXE + '.update'):
        raise RuntimeError('Некорректный путь обновления')

    staged = None
    if staged_updater:
        staged = Path(staged_updater).resolve()
        if staged != target.with_name(UPDATER_EXE + '.update') or not staged.is_file():
            raise RuntimeError('Не найден скачанный обновлятор')
        if not getattr(sys, 'frozen', False):
            raise RuntimeError('Обновление двух EXE работает только в собранном приложении')

    wait_for_process(pid)
    folder = target.parent / 'backups'
    folder.mkdir(exist_ok=True)
    old_backup = backup_path(target, current_version)
    prepared = target.with_name(APP_EXE + '.installing')
    shutil.copy2(source, prepared)
    shutil.copy2(target, old_backup)
    try:
        os.replace(prepared, target)
        update_state(current_version if restoring else None)
        if staged is not None:
            # Running updater cannot replace itself on Windows.
            # Spawn a copy that finishes the installation after we exit.
            helper = target.with_name('AudioBookUpdater.finalizer.exe')
            shutil.copy2(Path(sys.executable).resolve(), helper)
            subprocess.Popen(
                [str(helper), '--finalize', '--pid', str(os.getpid()),
                 '--target', str(target), '--staged-updater', str(staged)],
                cwd=str(target.parent)
            )
        else:
            launch(target)
    except Exception:
        shutil.copy2(old_backup, target)
        raise
    finally:
        prepared.unlink(missing_ok=True)
    if not restoring:
        source.unlink(missing_ok=True)
    trim_backups(folder)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--target', required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--staged')
    mode.add_argument('--restore')
    mode.add_argument('--finalize', action='store_true')
    parser.add_argument('--current-version', default=None)
    parser.add_argument('--restore-version')
    parser.add_argument('--staged-updater')
    args = parser.parse_args()
    if args.pid <= 0:
        parser.error('Некорректный PID')
    if args.finalize:
        if not args.staged_updater:
            parser.error('--staged-updater обязателен для --finalize')
        finalize_updater(args.pid, args.target, args.staged_updater)
        return
    if args.restore and not args.restore_version:
        parser.error('--restore-version обязателен при откате')
    time.sleep(0.5)
    replace_exe(args.pid, args.target, args.restore or args.staged,
                args.current_version, restoring=bool(args.restore),
                restore_version=args.restore_version,
                staged_updater=args.staged_updater)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        log_error(exc)
        sys.exit(1)
