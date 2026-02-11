import os
# Attempt to fix DLL load errors (Intel OpenMP / Torch)
os.environ["KMP_DUPLICATE_LIB_OK"] = "TRUE"

import tkinter as tk
from tkinter import font
import threading
import time
import requests
import pyautogui
import numpy as np
import sys
import ctypes
import traceback

# Optional imports with safety checks
try:
    import easyocr
except (ImportError, OSError):
    easyocr = None
    print("Warning: EasyOCR not available. OCR features will be disabled.")

try:
    import mutagen
except ImportError:
    mutagen = None
    
import wave
import contextlib

# DPI Awareness
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(1)
except Exception:
    pass

# --- Backend/Logic ---

class LogicController:
    def __init__(self):
        self.reader = None
        self.ocr_available = False
        
        if easyocr:
            try:
                print("Initializing EasyOCR...")
               # 尝试使用GPU模式，如果没显卡会自动回退到CPU
                self.reader = easyocr.Reader(['ch_sim', 'en'], gpu=True)
                self.ocr_available = True
            except Exception as e:
                print(f"EasyOCR Init Failed: {e}")
        
        self.is_running = False
        self.thread = None
        self.current_region = None
        self.status_msg = "stopping"

    def set_region(self, region):
        self.current_region = region

    def start_loop(self):
        if self.is_running:
            return
        self.is_running = True
        self.thread = threading.Thread(target=self._run_loop, daemon=True)
        self.thread.start()
        print("Automation started.")

    def stop_loop(self):
        self.is_running = False
        self.status_msg = "stopping"
        print("Automation stopped.")

    def toggle(self):
        if self.is_running:
            self.stop_loop()
        else:
            self.start_loop()
        return self.is_running

    def _run_loop(self):
        while self.is_running:
            if not self.current_region:
                time.sleep(1)
                continue

            try:
                self.status_msg = "calling..."
                if not self.ocr_available:
                    print("OCR Unavailable.")
                    time.sleep(2)
                    continue

                # 1. Capture & OCR
                img_bytes = self._capture_region(self.current_region)
                text = self._ocr_image(img_bytes)
                
                if not text.strip():
                    time.sleep(1)
                    continue

                print(f"Detected: {text[:30]}...")

                # 2. TTS
                audio_path = self._post_tts(text)

                # 3. Wait Logic
                duration = self._get_audio_duration(audio_path)
                print(duration)
                self.status_msg = "waiting..."

                # Auto Click Logic: Click immediately when starting to wait (to skip animation or prep next line?)
                # user Request: "Click during waiting". "Previous click at end was too late".
                # Also removed x,y arguments to click at CURRENT mouse position (avoids moving mouse)
                if self.is_running:
                     try:
                        pyautogui.click(clicks=2, interval=0.1)
                        print("Auto-clicked (2x) at current pos.")
                     except Exception as e_click:
                        print(f"Click failed: {e_click}")

                # Wait loop
                start_wait = time.time()
                while time.time() - start_wait < duration*0.6:
                    if not self.is_running:
                        return
                    time.sleep(0.1)

            except Exception as e:
                print(f"Loop Error: {e}")
                traceback.print_exc()
                time.sleep(2)

    def _capture_region(self, region):
        left, top, width, height = region
        return pyautogui.screenshot(region=(int(left), int(top), int(width), int(height)))

    def _ocr_image(self, pil_img) -> str:
        if not self.reader:
            return ""
        np_img = np.array(pil_img)
        result = self.reader.readtext(
            np_img, 
            detail=0,
            paragraph=False, 
            width_ths=0.3,
            height_ths=0.3
        )
        return "\n".join(result)

    def _post_tts(self, text):
        url = "http://localhost:5002/generate_audio"
        try:
            response = requests.post(url, json={"text": text})
            if response.status_code == 200:
                result = response.json()
                path = result.get('file_path')
                print(f"Audio: {path}")
                return path
        except Exception as e:
            print(f"TTS Error: {e}")
        return None

    def _get_audio_duration(self, file_path):
        target_path = file_path
        
        # 1. Resolve Path
        if not os.path.exists(target_path):
            # Try abspath if it looks relative
            local_path = os.path.abspath(file_path)
            if os.path.exists(local_path):
                target_path = local_path
            elif os.path.basename(file_path) == file_path:
                # If just a filename, check cwd
                cwd_path = os.path.join(os.getcwd(), file_path)
                if os.path.exists(cwd_path):
                    target_path = cwd_path
        
        if not os.path.exists(target_path):
            print(f"Warning: Audio file not found at: {file_path}")
            return 0
            
        print(f"Checking duration for: {target_path}")

        # 2. Try Wave (Standard Lib, Robust for .wav)
        try:
            if target_path.lower().endswith('.wav'):
                with contextlib.closing(wave.open(target_path, 'r')) as f:
                    frames = f.getnframes()
                    rate = f.getframerate()
                    duration = frames / float(rate)
                    return duration
        except Exception as e:
            print(f"Wave duration failed: {e}")

        # 3. Try Mutagen (Fallback)
        if mutagen:
            try:
                audio = mutagen.File(target_path)
                if audio and audio.info:
                    return audio.info.length
            except Exception as e:
                print(f"Mutagen duration failed: {e}")
        
        return 0

# --- GUI ---

class ResizableWindow:
    def __init__(self, root, border_color='#00ff00', border_width=4):
        self.root = root
        self.border_color = border_color
        
        self.canvas = tk.Canvas(root, bg=root['bg'], highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True)

        self.rect_id = self.canvas.create_rectangle(0, 0, 1, 1, outline=self.border_color, width=border_width)

        self.canvas.bind("<Button-1>", self.start_move)
        self.canvas.bind("<B1-Motion>", self.do_move)
        
        self.grips = {}
        self._create_grips()
        self.root.bind("<Configure>", self.on_window_resize)

        self._start_x = 0
        self._start_y = 0
        self._start_w = 0
        self._start_h = 0
        self._start_root_x = 0
        self._start_root_y = 0
        self._resize_dir = None

    def _create_grips(self):
        grip_specs = {
            'n': 'sb_v_double_arrow', 's': 'sb_v_double_arrow',
            'e': 'sb_h_double_arrow', 'w': 'sb_h_double_arrow',
            'nw': 'size_nw_se', 'ne': 'size_ns',
            'sw': 'size_ne_sw', 'se': 'size_we',
        }
        for d, cur in grip_specs.items():
            g = tk.Frame(self.root, bg=self.border_color, cursor=cur)
            g.bind("<Button-1>", lambda e, dir=d: self.start_resize(e, dir))
            g.bind("<B1-Motion>", self.do_resize)
            self.grips[d] = g

    def start_move(self, event):
        self._start_x = event.x
        self._start_y = event.y

    def do_move(self, event):
        x = self.root.winfo_x() + (event.x - self._start_x)
        y = self.root.winfo_y() + (event.y - self._start_y)
        self.root.geometry(f"+{x}+{y}")

    def start_resize(self, event, direction):
        self._resize_dir = direction
        self._start_root_x = event.x_root
        self._start_root_y = event.y_root
        self._start_w = self.root.winfo_width()
        self._start_h = self.root.winfo_height()
        self._start_x = self.root.winfo_x()
        self._start_y = self.root.winfo_y()

    def do_resize(self, event):
        dx = event.x_root - self._start_root_x
        dy = event.y_root - self._start_root_y
        
        x, y = self._start_x, self._start_y
        w, h = self._start_w, self._start_h
        
        if 'e' in self._resize_dir: w = self._start_w + dx
        if 'w' in self._resize_dir: w = self._start_w - dx; x = self._start_x + dx
        if 's' in self._resize_dir: h = self._start_h + dy
        if 'n' in self._resize_dir: h = self._start_h - dy; y = self._start_y + dy
            
        if w < 50: w = 50 
        if h < 50: h = 50
        
        self.root.geometry(f"{w}x{h}+{x}+{y}")

    def on_window_resize(self, event):
        w, h = self.root.winfo_width(), self.root.winfo_height()
        self.canvas.coords(self.rect_id, 0, 0, w, h)
        bw = 8
        self.grips['n'].place(x=bw, y=0, width=w-2*bw, height=bw)
        self.grips['s'].place(x=bw, y=h-bw, width=w-2*bw, height=bw)
        self.grips['e'].place(x=w-bw, y=bw, height=h-2*bw, width=bw)
        self.grips['w'].place(x=0, y=bw, height=h-2*bw, width=bw)
        self.grips['nw'].place(x=0, y=0, width=bw, height=bw)
        self.grips['ne'].place(x=w-bw, y=0, width=bw, height=bw)
        self.grips['sw'].place(x=0, y=h-bw, width=bw, height=bw)
        self.grips['se'].place(x=w-bw, y=h-bw, width=bw, height=bw)

    def set_border_color(self, color):
        self.border_color = color
        self.canvas.itemconfig(self.rect_id, outline=color)
        for g in self.grips.values(): g.config(bg=color)

class OverlayApp:
    def __init__(self):
        self.logic = LogicController()
        self.root = tk.Tk()
        self.root.title("OCR Auto Reader")
        self.root.overrideredirect(True)
        self.root.wm_attributes('-topmost', True)
        self.root.wm_attributes('-transparentcolor', '#000001')
        self.root.config(bg='#000001')
        self.root.geometry("2000x150+200+1180")
        
        self.window_manager = ResizableWindow(self.root, border_color='gray')
        
        self.toggle_btn = tk.Button(self.root, text="Start Auto", command=self.on_toggle,
                                  bg="gray", fg="white", font=("Arial", 10, "bold"), cursor="hand2")
        self.toggle_btn.place(relx=0.5, y=5, anchor='n', height=25)
        
        self.update_status_loop()
        
        tk.Button(self.root, text="X", command=self.close, bg="red", fg="white",
                 font=("Arial", 8, "bold")).place(relx=1.0, y=5, anchor='ne', x=-5)
        
        self.update_region()

    def update_region(self):
        if self.root:
            self.logic.set_region((self.root.winfo_rootx(), self.root.winfo_rooty(), 
                                 self.root.winfo_width(), self.root.winfo_height()))
        self.root.after(500, self.update_region)

    def on_toggle(self):
        if not self.logic.ocr_available:
            self.toggle_btn.config(text="OCR Error!")
            return
        running = self.logic.toggle()
        if running:
            self.toggle_btn.config(bg="#00ff00", fg="black")
            self.window_manager.set_border_color("#00ff00")
        else:
            self.toggle_btn.config(bg="gray", fg="white")
            self.window_manager.set_border_color("gray")

    def update_status_loop(self):
        if self.logic.ocr_available:
            status = self.logic.status_msg
            prefix = "Auto: ON" if self.logic.is_running else "Auto: OFF"
            # Combine them: "Auto: ON | calling..."
            # Adjust spacing or format as desired
            display_text = f"{prefix} | {status}"
            self.toggle_btn.config(text=display_text)
        else:
            self.toggle_btn.config(text="OCR Error!")
            
        self.root.after(200, self.update_status_loop)

    def close(self):
        self.logic.stop_loop()
        self.root.destroy()
        sys.exit(0)

    def run(self):
        self.root.mainloop()

if __name__ == "__main__":
    OverlayApp().run()