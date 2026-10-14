
import re
import queue
import threading
from pathlib import Path
from urllib.parse import urlparse

import requests
from bs4 import BeautifulSoup
import customtkinter as ctk
from tkinter import filedialog, messagebox

# Настройки внешнего вида
ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

BG = "#0B1120"
CARD = "#151E30"
INPUT = "#202C40"
BLUE = "#397DFA"
TEXT = "#F1F5F9"
MUTED = "#94A3B8"


class AudioBookDownloader(ctk.CTk):

    def __init__(self):
        super().__init__()

        self.title("AudioBook Downloader 1.0")

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

        self.url_var = ctk.StringVar()
        self.folder_var = ctk.StringVar(
            value=str(Path.home() / "Downloads")
        )

        self.build_ui()
        self.after(100, self.process_events)

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
            text="AudioBook Downloader • Version 2.0",
            font=("Segoe UI", 11),
            text_color="#64748B"
        ).pack(pady=(0, 12))

    # -------------------------
    # Работа с полями ввода
    # -------------------------

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

    def process_events(self):
        try:
            while True:
                kind, value = (
                    self.events.get_nowait()
                )

                if kind == "status":
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

                    messagebox.showinfo(
                        "Готово",
                        value
                    )

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
                f"Книга: {title}\n"
                f"Частей: {total}\n\n"
                f"Сохранено в:\n{book_folder}"
            )

        except Exception as error:
            self.send(
                "error",
                str(error)
            )


if __name__ == "__main__":
    app = AudioBookDownloader()
    app.mainloop()
