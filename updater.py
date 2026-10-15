
import argparse
import ctypes
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path


def wait_for_process(pid, timeout=60):
    SYNCHRONIZE = 0x00100000
    WAIT_TIMEOUT = 0x00000102

    kernel32 = ctypes.WinDLL(
        "kernel32", use_last_error=True
    )

    kernel32.OpenProcess.argtypes = [
        ctypes.c_ulong,
        ctypes.c_int,
        ctypes.c_ulong
    ]
    kernel32.OpenProcess.restype = ctypes.c_void_p

    kernel32.WaitForSingleObject.argtypes = [
        ctypes.c_void_p,
        ctypes.c_ulong
    ]
    kernel32.WaitForSingleObject.restype = ctypes.c_ulong

    kernel32.CloseHandle.argtypes = [
        ctypes.c_void_p
    ]

    handle = kernel32.OpenProcess(
        SYNCHRONIZE, False, pid
    )

    if not handle:
        raise RuntimeError(
            "Не удалось получить процесс приложения"
        )

    try:
        result = kernel32.WaitForSingleObject(
            handle, timeout * 1000
        )
        if result == WAIT_TIMEOUT:
            raise TimeoutError(
                "Программа не закрылась за 60 секунд"
            )
        if result != 0:
            raise RuntimeError(
                "Ошибка ожидания завершения программы"
            )
    finally:
        kernel32.CloseHandle(handle)


def install_update(pid, target, staged):
    target = Path(target).resolve()
    staged = Path(staged).resolve()

    if not target.is_file():
        raise FileNotFoundError(target)

    if not staged.is_file():
        raise FileNotFoundError(staged)

    if target.parent != staged.parent:
        raise RuntimeError(
            "Обновление должно находиться рядом с EXE"
        )

    if staged.name != target.name + ".update":
        raise RuntimeError(
            "Неверное имя файла обновления"
        )

    wait_for_process(pid)

    backup = target.with_name(
        target.name + ".backup"
    )

    shutil.copy2(target, backup)

    try:
        # Замена внутри одного диска.
        os.replace(staged, target)

        subprocess.Popen(
            [str(target)],
            cwd=str(target.parent)
        )

    except Exception:
        # Восстанавливаем предыдущую версию.
        os.replace(backup, target)
        raise

    else:
        # Оставляем backup для ручного восстановления,
        # если новая программа запустится, но не заработает.
        pass


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument("--pid", type=int, required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--staged", required=True)

    args = parser.parse_args()

    # Даем основному приложению время закрыться.
    time.sleep(1)

    install_update(
        args.pid,
        args.target,
        args.staged
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        log = Path.home() / "AudioBookUpdater-error.log"
        log.write_text(
            str(error),
            encoding="utf-8"
        )
        sys.exit(1)
