import os
import sys
import subprocess
import re
import queue
import threading
import json
from pathlib import Path
from urllib.parse import urlparse
import requests
from bs4 import BeautifulSoup
import customtkinter as ctk
from tkinter import filedialog, messagebox

from version import APP_VERSION, EXE_NAME
from update_checker import (
    check_for_updates,
    download_update
)

# Настройки внешнего вида
ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

BG = "#0B1120"
CARD = "#151E30"
INPUT = "#202C40"
BLUE = "#397DFA"
TEXT = "#F1F5F9"
MUTED = "#94A3B8"


def read_update_state():
    state_path = Path(os.getenv("LOCALAPPDATA", str(Path.home()))) / "AudioBookDownloader" / "update_state.json"
    try:
        return json.loads(state_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


class AudioBookDownloader(ctk.CTk):

    def __init__(self):
        super().__init__()

        self.title(f"AudioBook Downloader {APP_VERSION}")

        # Размер окна
        window_width = 760
        window_height = 570

        # Получаем разрешение экрана
        screen_width = self.winfo_screenwidth()
        screen_height = self.winfo_screenheight()

        # Вычисляем координаты центра
        x = (screen_width - window_width) // 2
        y = (screen_height - window_height) // 2

        # Устанавливаем размер и положение окна
        self.geometry(f"{window_width}x{window_height}+{x}+{y}")

        # Запрещаем изменение размера
        self.resizable(False, False)

        self.configure(fg_color=BG)

        self.events = queue.Queue()
        self.downloading = False
        self.update_window = None
        self.update_progress = None
        self.update_status = None
        self.update_button = None
        self.update_busy = False

        self.url_var = ctk.StringVar()
        self.folder_var = ctk.StringVar(
            value=str(Path.home() / "Downloads")
        )

        self.build_ui()
        self.after(100, self.process_events)

        self.after(2000, self.start_update_check)

    def build_ui(self):

        # Заголовок
        header = ctk.CTkFrame(
            self, fg_color="transparent"
        )
        header.pack(
            fill="x", padx=35, pady=(30, 20)
        )

        ctk.CTkLabel(
            header,
            text="🎧  AudioBook Downloader",
            font=("Segoe UI", 25, "bold"),
            text_color=TEXT
        ).pack(anchor="w")

        ctk.CTkLabel(
            header,
            text="Скачивай любимые аудиокниги в один клик",
            font=("Segoe UI", 13),
            text_color=MUTED
        ).pack(anchor="w", pady=(5, 0))

        ctk.CTkButton(
            header, text="↩ Предыдущие версии", width=165, height=30,
            fg_color="#283852", command=self.show_backups
        ).pack(anchor="e", pady=(0, 0))

        # Основная карточка
        card = ctk.CTkFrame(
            self,
            fg_color=CARD,
            corner_radius=18
        )
        card.pack(
            fill="both",
            expand=True,
            padx=30,
            pady=(0, 25)
        )

        # Ссылка
        ctk.CTkLabel(
            card,
            text="ССЫЛКА НА АУДИОКНИГУ",
            font=("Segoe UI", 12, "bold"),
            text_color=MUTED
        ).pack(
            anchor="w",
            padx=25,
            pady=(25, 10)
        )

        url_frame = ctk.CTkFrame(
            card,
            fg_color=INPUT,
            corner_radius=10
        )
        url_frame.pack(
            fill="x",
            padx=25
        )

        self.url_entry = ctk.CTkEntry(
            url_frame,
            textvariable=self.url_var,
            placeholder_text=(
                "https://knigavuhe.org/book/..."
            ),
            fg_color="transparent",
            border_width=0,
            height=45,
            font=("Segoe UI", 13)
        )
        self.url_entry.pack(
            side="left",
            fill="x",
            expand=True,
            padx=(10, 0)
        )

        self.clear_button = ctk.CTkButton(
            url_frame,
            text="✕",
            width=38,
            height=35,
            fg_color="transparent",
            hover_color="#34445C",
            text_color=MUTED,
            font=("Segoe UI", 17),
            command=self.clear_url
        )
        self.clear_button.pack(
            side="right",
            padx=5
        )

        self.setup_paste(self.url_entry)

        # Папка
        ctk.CTkLabel(
            card,
            text="ПАПКА СОХРАНЕНИЯ",
            font=("Segoe UI", 12, "bold"),
            text_color=MUTED
        ).pack(
            anchor="w",
            padx=25,
            pady=(25, 10)
        )

        folder_frame = ctk.CTkFrame(
            card,
            fg_color="transparent"
        )
        folder_frame.pack(
            fill="x",
            padx=25
        )

        self.folder_entry = ctk.CTkEntry(
            folder_frame,
            textvariable=self.folder_var,
            height=45,
            fg_color=INPUT,
            border_width=0,
            corner_radius=10,
            font=("Segoe UI", 12)
        )
        self.folder_entry.pack(
            side="left",
            fill="x",
            expand=True
        )

        self.setup_paste(self.folder_entry)

        ctk.CTkButton(
            folder_frame,
            text="📁 Обзор",
            width=115,
            height=45,
            fg_color="#283852",
            hover_color="#344A6B",
            corner_radius=10,
            command=self.choose_folder
        ).pack(
            side="right",
            padx=(10, 0)
        )

        # Информация о загрузке
        info_frame = ctk.CTkFrame(
            card,
            fg_color="transparent"
        )
        info_frame.pack(
            fill="x",
            padx=25,
            pady=(35, 10)
        )

        self.status_label = ctk.CTkLabel(
            info_frame,
            text="Готов к загрузке",
            font=("Segoe UI", 13),
            text_color=MUTED
        )
        self.status_label.pack(side="left")

        self.percent_label = ctk.CTkLabel(
            info_frame,
            text="0%",
            font=("Segoe UI", 14, "bold"),
            text_color=BLUE
        )
        self.percent_label.pack(side="right")

        # Прогресс
        self.progress = ctk.CTkProgressBar(
            card,
            height=12,
            corner_radius=8,
            fg_color="#29354A",
            progress_color=BLUE
        )
        self.progress.pack(
            fill="x",
            padx=25,
            pady=(0, 15)
        )
        self.progress.set(0)

        self.parts_label = ctk.CTkLabel(
            card,
            text="Ожидание ссылки...",
            font=("Segoe UI", 12),
            text_color=MUTED
        )
        self.parts_label.pack()

        # Кнопка загрузки
        self.download_button = ctk.CTkButton(
            card,
            text="⬇  СКАЧАТЬ КНИГУ",
            height=52,
            corner_radius=12,
            fg_color=BLUE,
            hover_color="#2563EB",
            font=("Segoe UI", 15, "bold"),
            command=self.start_download
        )
        self.download_button.pack(
            fill="x",
            padx=25,
            pady=(25, 25)
        )

        # Нижняя подпись
        ctk.CTkLabel(
            self,
            text=f"AudioBook Downloader • Version {APP_VERSION}",
            font=("Segoe UI", 11),
            text_color="#64748B"
        ).pack(pady=(0, 12))

    # -------------------------
    # Обновления приложения
    # -------------------------

    def start_update_check(self):
        threading.Thread(target=self.update_check_worker, daemon=True).start()

    def update_check_worker(self):
        try:
            update = check_for_updates()
            if update:
                state = read_update_state()
                if update["version"] != state.get("ignored_version"):
                    self.send("update_available", update)
        except Exception as error:
            # Ошибка сети не должна мешать скачиванию книг.
            print(f"Проверка обновлений: {error}")

    def show_update_dialog(self, update):
        if self.update_window is not None and self.update_window.winfo_exists():
            return

        window = ctk.CTkToplevel(self)
        self.update_window = window
        window.title("Обновление AudioBook Downloader")
        window.geometry("470x365")
        window.resizable(False, False)
        window.configure(fg_color=BG)
        window.transient(self)

        def close_dialog():
            if self.update_busy:
                return
            window.destroy()
            self.update_window = None

        window.protocol("WM_DELETE_WINDOW", close_dialog)

        ctk.CTkLabel(
            window, text="Доступно обновление",
            font=("Segoe UI", 21, "bold"), text_color=TEXT
        ).pack(pady=(22, 8))

        ctk.CTkLabel(
            window,
            text=f"Текущая версия: {APP_VERSION}    Новая: {update['version']}",
            text_color=MUTED
        ).pack(pady=(0, 10))

        notes = ctk.CTkTextbox(window, height=120, fg_color=CARD, wrap="word")
        notes.pack(fill="x", padx=25)
        notes.insert("1.0", update.get("description") or "Доступна новая версия программы.")
        notes.configure(state="disabled")

        self.update_progress = ctk.CTkProgressBar(window, progress_color=BLUE)
        self.update_progress.pack(fill="x", padx=25, pady=(15, 6))
        self.update_progress.set(0)

        self.update_status = ctk.CTkLabel(
            window, text="Ожидание подтверждения", text_color=MUTED
        )
        self.update_status.pack()

        def begin_update():
            if self.update_busy:
                return
            if self.downloading:
                messagebox.showwarning(
                    "Подождите", "Сначала дождитесь окончания скачивания книги.",
                    parent=window
                )
                return
            if not getattr(sys, "frozen", False):
                messagebox.showinfo(
                    "Режим разработки",
                    "Автоустановка работает только в собранной EXE-версии.",
                    parent=window
                )
                return
            updater = Path(sys.executable).resolve().with_name("AudioBookUpdater.exe")
            if not updater.is_file():
                messagebox.showerror(
                    "Обновлятор не найден",
                    "Положите AudioBookUpdater.exe рядом с AudioBookDownloader.exe.",
                    parent=window
                )
                return
            self.update_busy = True
            self.update_button.configure(state="disabled", text="Скачивание...")
            self.update_status.configure(text="Скачивание обновления...")
            threading.Thread(
                target=self.update_download_worker,
                args=(update,), daemon=True
            ).start()

        self.update_button = ctk.CTkButton(
            window, text="Скачать и установить", command=begin_update,
            fg_color=BLUE, height=40
        )
        self.update_button.pack(fill="x", padx=25, pady=(12, 12))
        window.update_idletasks()
        x = self.winfo_rootx() + (self.winfo_width() - window.winfo_width()) // 2
        y = self.winfo_rooty() + (self.winfo_height() - window.winfo_height()) // 2
        window.geometry(f"+{x}+{y}")
        window.lift()

    def update_download_worker(self, update):
        exe = Path(sys.executable).resolve()
        staged = exe.with_name(exe.name + ".update")
        try:
            download_update(
                update, staged,
                progress=lambda value: self.send("update_progress", value)
            )
            self.send("update_ready", str(staged))
        except Exception as error:
            staged.unlink(missing_ok=True)
            self.send("update_error", str(error))

    def launch_updater(self, staged):
        exe = Path(sys.executable).resolve()
        updater = exe.with_name("AudioBookUpdater.exe")
        try:
            subprocess.Popen(
                [str(updater), "--pid", str(os.getpid()),
                 "--target", str(exe), "--staged", str(staged),
                 "--current-version", APP_VERSION],
                cwd=str(exe.parent)
            )
        except Exception as error:
            self.update_busy = False
            self.send("update_error", f"Не удалось запустить обновлятор: {error}")
            return
        self.destroy()

    # -------------------------
    # Работа с полями ввода
    # -------------------------

    def show_backups(self):
        if self.downloading or self.update_busy:
            messagebox.showwarning("Подождите", "Завершите текущую операцию.")
            return
        if not getattr(sys, "frozen", False):
            messagebox.showinfo("Режим разработки", "Откат доступен в собранном EXE.")
            return
        exe = Path(sys.executable).resolve()
        backup_dir = exe.parent / "backups"
        backups = []

        if backup_dir.exists():
            backups = list(
                backup_dir.glob("AudioBookDownloader-v*.exe")
            )

            legacy_backup = (
                    backup_dir / "AudioBookDownloader-legacy.exe"
            )

            if legacy_backup.is_file():
                backups.append(legacy_backup)

            backups.sort(
                key=lambda f: f.stat().st_mtime,
                reverse=True
            )
        dialog = ctk.CTkToplevel(self)
        dialog.title("Предыдущие версии")
        dialog.geometry("440x250")
        dialog.resizable(False, False)
        dialog.configure(fg_color=BG)
        dialog.transient(self)
        ctk.CTkLabel(dialog, text=f"Текущая версия: {APP_VERSION}",
                     font=("Segoe UI", 18, "bold"), text_color=TEXT).pack(pady=(18, 12))
        if not backups:
            ctk.CTkLabel(dialog, text="Резервных копий пока нет.",
                         text_color=MUTED).pack(pady=18)
        for backup in backups[:2]:
            if backup.name == "AudioBookDownloader-legacy.exe":
                version = "legacy"
                display_version = "Версия не определена"
            else:
                version = backup.stem.removeprefix("AudioBookDownloader-v")
                display_version = f"v{version}"
            def restore(path=backup, old_version=version):
                if not messagebox.askyesno("Подтвердить откат",
                    f"Вернуться с {APP_VERSION} на {display_version}?\nПрограмма перезапустится.",
                    parent=dialog):
                    return
                updater = exe.with_name("AudioBookUpdater.exe")
                if not updater.is_file():
                    messagebox.showerror("Ошибка", "AudioBookUpdater.exe не найден", parent=dialog)
                    return
                try:
                    subprocess.Popen([str(updater), "--pid", str(os.getpid()),
                                      "--target", str(exe), "--restore", str(path),
                                      "--current-version", APP_VERSION,
                                      "--restore-version", old_version], cwd=str(exe.parent))
                except OSError as error:
                    messagebox.showerror("Ошибка", str(error), parent=dialog)
                    return
                self.destroy()
            ctk.CTkButton(dialog, text=f"↩ Вернуться к v{display_version}",
                          command=restore, fg_color=BLUE, height=37).pack(fill="x", padx=25, pady=5)
        dialog.update_idletasks()
        x = self.winfo_rootx() + (self.winfo_width() - dialog.winfo_width()) // 2
        y = self.winfo_rooty() + (self.winfo_height() - dialog.winfo_height()) // 2
        dialog.geometry(f"+{x}+{y}")
        dialog.lift()

    def clear_url(self):
        self.url_var.set("")
        self.url_entry.focus_set()

    def setup_paste(self, entry):
        # Сочетания работают по физическим клавишам
        # независимо от выбранной раскладки.
        widget = entry._entry

        def paste(event=None):
            try:
                value = self.clipboard_get()
                widget.insert("insert", value)
            except Exception:
                pass
            return "break"

        def select_all(event=None):
            widget.select_range(0, "end")
            widget.icursor("end")
            return "break"

        def copy(event=None):
            selected = widget.selection_get()
            self.clipboard_clear()
            self.clipboard_append(selected)
            return "break"

        def cut(event=None):
            try:
                selected = widget.selection_get()
                self.clipboard_clear()
                self.clipboard_append(selected)
                widget.delete("sel.first", "sel.last")
            except Exception:
                pass
            return "break"

        # Keycode 86 = физическая V в Windows
        # Keycode 65 = A, 67 = C, 88 = X.
        def ctrl_keys(event):
            if not (event.state & 0x4):
                return

            keycode = event.keycode

            if keycode == 86:
                return paste()
            if keycode == 65:
                return select_all()
            if keycode == 67:
                return copy()
            if keycode == 88:
                return cut()

        widget.bind("<KeyPress>", ctrl_keys, add="+")

        widget.bind(
            "<Shift-Insert>",
            paste,
            add="+"
        )

        # Контекстное меню
        from tkinter import Menu

        menu = Menu(widget, tearoff=0)
        menu.add_command(
            label="Вырезать", command=cut
        )
        menu.add_command(
            label="Копировать", command=copy
        )
        menu.add_command(
            label="Вставить", command=paste
        )
        menu.add_separator()
        menu.add_command(
            label="Выделить всё",
            command=select_all
        )

        def show_menu(event):
            menu.tk_popup(event.x_root, event.y_root)
            menu.grab_release()

        widget.bind(
            "<Button-3>",
            show_menu,
            add="+"
        )

    def choose_folder(self):
        folder = filedialog.askdirectory()
        if folder:
            self.folder_var.set(folder)

    # -------------------------
    # Получение аудиофайлов
    # -------------------------

    @staticmethod
    def find_tracks(html):
        soup = BeautifulSoup(
            html, "html.parser"
        )

        tracks = []
        seen = set()

        for script in soup.find_all("script"):
            content = (
                script.string or script.get_text()
            )

            if not content:
                continue

            content = content.replace("\\/", "/")
            content = content.replace("\\u002F", "/")
            content = content.replace("\\u002f", "/")

            matches = re.findall(
                r'https?://[^\s"\'<>\\]+?\.mp3'
                r'(?:\?[^\s"\'<>\\]*)?',
                content,
                flags=re.IGNORECASE
            )

            for link in matches:
                link = link.replace(
                    "&amp;", "&"
                )

                if urlparse(link).path.endswith(
                    "/0.mp3"
                ):
                    continue

                if link not in seen:
                    seen.add(link)
                    tracks.append(link)

        return tracks, soup

    @staticmethod
    def safe_name(name):
        name = re.sub(
            r'[<>:"/\\|?*]',
            "_",
            name
        )
        return name.strip(" .")[:100] or "Аудиокнига"

    @staticmethod
    def get_book_title(soup):
        # Предпочитаем отдельное название произведения,
        # если оно указано в метаданных страницы.
        title = None

        og_title = soup.find(
            "meta",
            property="og:title"
        )

        if og_title:
            title = og_title.get("content")

        if not title:
            heading = soup.find("h1")
            if heading:
                title = heading.get_text(
                    " ", strip=True
                )

        if not title:
            return "Аудиокнига"

        title = re.sub(
            r"\s+",
            " ",
            title
        ).strip()

        # Удаляем распространённые подписи сайта.
        title = re.sub(
            r"\s*[-|—]\s*слушать.*$",
            "",
            title,
            flags=re.IGNORECASE
        )

        title = re.sub(
            r"\s*[-|—]\s*книга в ухе.*$",
            "",
            title,
            flags=re.IGNORECASE
        )

        # Убираем строки с указанием исполнителя.
        title = re.split(
            r"\s+(?:Читает|Исполнитель|Озвучивает)\s*:",
            title,
            maxsplit=1,
            flags=re.IGNORECASE
        )[0].strip()

        return title or "Аудиокнига"

    # -------------------------
    # Загрузка
    # -------------------------

    def start_download(self):
        if self.downloading:
            return

        url = self.url_var.get().strip()
        folder = self.folder_var.get().strip()

        parsed = urlparse(url)

        if (
            parsed.scheme not in ("http", "https")
            or parsed.hostname not in (
                "knigavuhe.org",
                "www.knigavuhe.org",
                "m.knigavuhe.org"
            )
            or not parsed.path.startswith("/book/")
        ):
            messagebox.showerror(
                "Ошибка",
                "Введите правильную ссылку на книгу"
            )
            return

        if not folder:
            messagebox.showerror(
                "Ошибка",
                "Выберите папку сохранения"
            )
            return

        self.downloading = True

        self.progress.set(0)
        self.percent_label.configure(
            text="0%"
        )

        self.download_button.configure(
            state="disabled",
            text="ЗАГРУЗКА..."
        )

        threading.Thread(
            target=self.download_book,
            args=(url, folder),
            daemon=True
        ).start()

    def send(self, kind, value):
        self.events.put((kind, value))

    def show_download_complete(self, details):
        """Показывает результат загрузки и открывает папку книги."""
        dialog = ctk.CTkToplevel(self)
        dialog.title("Загрузка завершена")
        dialog.geometry("490x285")
        dialog.resizable(False, False)
        dialog.configure(fg_color=BG)
        dialog.transient(self)
        dialog.grab_set()

        ctk.CTkLabel(
            dialog,
            text="✓ Аудиокнига скачана!",
            font=("Segoe UI", 21, "bold"),
            text_color="#4ADE80"
        ).pack(pady=(24, 12))

        ctk.CTkLabel(
            dialog,
            text=details["title"],
            font=("Segoe UI", 14, "bold"),
            text_color=TEXT,
            wraplength=430
        ).pack(padx=20, pady=(0, 6))

        ctk.CTkLabel(
            dialog,
            text=f"Скачано частей: {details['total']}",
            text_color=MUTED
        ).pack(pady=(0, 8))

        ctk.CTkLabel(
            dialog,
            text=details["folder"],
            text_color=MUTED,
            wraplength=440
        ).pack(padx=20, pady=(0, 15))

        buttons = ctk.CTkFrame(dialog, fg_color="transparent")
        buttons.pack(fill="x", padx=24, pady=(0, 15))

        def open_book_folder():
            try:
                os.startfile(details["folder"])
            except OSError as error:
                messagebox.showerror(
                    "Не удалось открыть папку", str(error), parent=dialog
                )
                return
            dialog.destroy()

        ctk.CTkButton(
            buttons,
            text="📁 Открыть папку",
            fg_color=BLUE,
            command=open_book_folder,
            height=42
        ).pack(side="left", fill="x", expand=True, padx=(0, 7))

        ctk.CTkButton(
            buttons,
            text="Закрыть",
            fg_color="#283852",
            command=dialog.destroy,
            height=42
        ).pack(side="left", fill="x", expand=True, padx=(7, 0))

        dialog.update_idletasks()
        x = self.winfo_rootx() + (self.winfo_width() - dialog.winfo_width()) // 2
        y = self.winfo_rooty() + (self.winfo_height() - dialog.winfo_height()) // 2
        dialog.geometry(f"+{x}+{y}")
        dialog.focus_force()

    def process_events(self):
        try:
            while True:
                kind, value = (
                    self.events.get_nowait()
                )

                if kind == "update_available":
                    self.show_update_dialog(value)

                elif kind == "update_progress":
                    if self.update_progress is not None:
                        self.update_progress.set(value / 100)
                    if self.update_status is not None:
                        self.update_status.configure(text=f"Скачивание: {value:.0f}%")

                elif kind == "update_ready":
                    if self.downloading:
                        self.update_busy = False
                        self.send("update_error", "Дождитесь окончания загрузки книги и повторите обновление.")
                    else:
                        self.launch_updater(value)

                elif kind == "update_error":
                    self.update_busy = False
                    if self.update_button is not None:
                        self.update_button.configure(state="normal", text="Повторить обновление")
                    if self.update_status is not None:
                        self.update_status.configure(text="Ошибка обновления")
                    messagebox.showerror("Ошибка обновления", value)

                elif kind == "status":
                    self.status_label.configure(
                        text=value
                    )

                elif kind == "parts":
                    self.parts_label.configure(
                        text=value
                    )

                elif kind == "progress":
                    self.progress.set(
                        value / 100
                    )
                    self.percent_label.configure(
                        text=f"{value:.0f}%"
                    )

                elif kind == "done":
                    self.downloading = False

                    self.download_button.configure(
                        state="normal",
                        text="⬇  СКАЧАТЬ КНИГУ"
                    )

                    self.status_label.configure(
                        text="✓ Загрузка завершена",
                        text_color="#4ADE80"
                    )

                    self.show_download_complete(value)

                elif kind == "error":
                    self.downloading = False

                    self.download_button.configure(
                        state="normal",
                        text="⬇  СКАЧАТЬ КНИГУ"
                    )

                    self.status_label.configure(
                        text="Ошибка загрузки",
                        text_color="#F87171"
                    )

                    messagebox.showerror(
                        "Ошибка",
                        value
                    )

        except queue.Empty:
            pass

        self.after(
            100,
            self.process_events
        )

    def download_book(self, url, folder):
        try:
            self.send(
                "status",
                "Поиск аудиофайлов..."
            )

            session = requests.Session()

            session.headers.update({
                "User-Agent": (
                    "Mozilla/5.0 "
                    "(Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 "
                    "Chrome/131.0 Safari/537.36"
                ),
                "Referer": url
            })

            response = session.get(
                url, timeout=30
            )
            response.raise_for_status()

            tracks, soup = self.find_tracks(
                response.text
            )

            if not tracks:
                raise RuntimeError(
                    "Аудиофайлы не найдены на странице"
                )

            title = self.get_book_title(soup)
            title = self.safe_name(title)

            book_folder = (
                Path(folder) / title
            )

            book_folder.mkdir(
                parents=True,
                exist_ok=True
            )

            total = len(tracks)

            self.send(
                "parts",
                f"Найдено аудиочастей: {total}"
            )

            for index, track_url in enumerate(
                tracks, 1
            ):
                filename = f"{index:03d}.mp3"

                destination = (
                    book_folder / filename
                )

                temporary = (
                    book_folder / f"{filename}.part"
                )

                self.send(
                    "status",
                    f"Скачивание части {index} из {total}"
                )

                self.send(
                    "parts",
                    f"Часть {index} / {total}"
                )

                if (
                    destination.exists()
                    and destination.stat().st_size > 0
                ):
                    self.send(
                        "progress",
                        index / total * 100
                    )
                    continue

                try:
                    with session.get(
                        track_url,
                        stream=True,
                        timeout=(15, 60)
                    ) as audio:

                        audio.raise_for_status()

                        content_type = (
                            audio.headers.get(
                                "Content-Type", ""
                            ).lower()
                        )

                        if (
                            "text/html" in content_type
                            or "application/json"
                            in content_type
                        ):
                            raise RuntimeError(
                                "Сервер вернул не аудиофайл"
                            )

                        size = int(
                            audio.headers.get(
                                "Content-Length", 0
                            )
                        )

                        downloaded = 0

                        with open(
                            temporary, "wb"
                        ) as file:

                            for chunk in (
                                audio.iter_content(
                                    chunk_size=65536
                                )
                            ):
                                if not chunk:
                                    continue

                                file.write(chunk)
                                downloaded += len(chunk)

                                fraction = (
                                    downloaded / size
                                    if size else 0
                                )

                                progress = (
                                    (index - 1 + fraction)
                                    / total * 100
                                )

                                self.send(
                                    "progress",
                                    progress
                                )

                        if size and downloaded != size:
                            raise RuntimeError(
                                "Файл получен не полностью"
                            )

                    temporary.replace(
                        destination
                    )

                except Exception:
                    temporary.unlink(
                        missing_ok=True
                    )
                    raise

                self.send(
                    "progress",
                    index / total * 100
                )

            self.send(
                "progress", 100
            )

            self.send(
                "parts",
                f"Успешно загружено: {total} частей"
            )

            self.send(
                "done",
                {
                    "title": title,
                    "total": total,
                    "folder": str(book_folder)
                }
            )

        except Exception as error:
            self.send(
                "error",
                str(error)
            )


if __name__ == "__main__":
    app = AudioBookDownloader()
    app.mainloop()
