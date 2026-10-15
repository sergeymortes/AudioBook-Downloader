"""Standalone Windows updater and rollback helper."""
import argparse
import ctypes
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path


def wait_for_process(pid, timeout=60):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
    kernel.OpenProcess.restype = ctypes.c_void_p
    kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
    kernel.WaitForSingleObject.restype = ctypes.c_ulong
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = kernel.OpenProcess(0x00100000, False, pid)
    if not handle:
        code = ctypes.get_last_error()
        if code == 87:  # ERROR_INVALID_PARAMETER: PID already exited
            return
        raise OSError(code, 'Не удалось открыть процесс основной программы')
    try:
        result = kernel.WaitForSingleObject(handle, timeout * 1000)
        if result != 0:
            raise RuntimeError(f'Ожидание завершения процесса: код {result}')
    finally:
        kernel.CloseHandle(handle)


def backup_path(target, version):
    if not version or any(ch not in '0123456789.' for ch in version):
        raise ValueError('Недопустимый номер версии')
    return target.parent / 'backups' / f'AudioBookDownloader-v{version}.exe'


def trim_backups(folder):
    copies = sorted(folder.glob('AudioBookDownloader-v*.exe'),
                    key=lambda p: p.stat().st_mtime, reverse=True)
    for old in copies[2:]:
        old.unlink()


def update_state(ignore_version):
    folder = Path(os.getenv('LOCALAPPDATA', str(Path.home()))) / 'AudioBookDownloader'
    folder.mkdir(parents=True, exist_ok=True)
    dest = folder / 'update_state.json'
    temporary = folder / 'update_state.json.tmp'
    temporary.write_text(json.dumps({'ignored_version': ignore_version}), encoding='utf-8')
    os.replace(temporary, dest)


def replace_exe(pid, target, source, current_version, restoring=False, restore_version=None):
    target = Path(target).resolve()
    source = Path(source).resolve()
    if target.name != 'AudioBookDownloader.exe' or not target.is_file() or not source.is_file():
        raise RuntimeError('Не найден основной EXE или файл обновления')
    if restoring:
        if source != backup_path(target, restore_version).resolve():
            raise RuntimeError('Некорректный путь резервной копии')
    elif source != target.with_name(target.name + '.update'):
        raise RuntimeError('Некорректный путь обновления')

    wait_for_process(pid)
    folder = target.parent / 'backups'
    folder.mkdir(exist_ok=True)
    old_backup = backup_path(target, current_version)
    # Copy new bytes to a temporary file on the same disk, so os.replace is atomic.
    prepared = target.with_name(target.name + '.installing')
    shutil.copy2(source, prepared)
    shutil.copy2(target, old_backup)
    try:
        os.replace(prepared, target)
        if restoring:
            # Don't immediately prompt to reinstall the version just rolled back from.
            update_state(current_version)
        else:
            update_state(None)
        subprocess.Popen([str(target)], cwd=str(target.parent))
    except Exception:
        # Restore the previous EXE if replacement or launch fails.
        shutil.copy2(old_backup, target)
        raise
    finally:
        prepared.unlink(missing_ok=True)
    if not restoring:
        source.unlink(missing_ok=True)
    # Preserve the restored version's backup until after cleanup; newest two remain.
    trim_backups(folder)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--target', required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--staged')
    mode.add_argument('--restore')
    parser.add_argument('--current-version', required=True)
    parser.add_argument('--restore-version')
    args = parser.parse_args()
    if args.restore and not args.restore_version:
        parser.error('--restore-version обязателен при откате')
    time.sleep(0.5)
    replace_exe(args.pid, args.target, args.restore or args.staged,
                args.current_version, restoring=bool(args.restore),
                restore_version=args.restore_version)


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        (Path.home() / 'AudioBookUpdater-error.log').write_text(
            f'{type(exc).__name__}: {exc}', encoding='utf-8')
        sys.exit(1)
