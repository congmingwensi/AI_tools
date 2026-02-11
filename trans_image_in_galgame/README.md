# OCR-TTS Screen Reader (Galgames Helper)

A Python tool that automatically captures a specific region of your screen (e.g., a dialogue box in a visual novel game), extracts the text using OCR (EasyOCR), and reads it aloud using a local TTS server (IndexTTS). It also includes auto-click functionality to advance the game text automatically.

## Features

- **Screen Selection**: Drag and resize a transparent window to cover the game's text box.
- **OCR**: Uses [EasyOCR](https://github.com/JaidedAI/EasyOCR) to read Chinese/English text from the selected region.
- **TTS**: Sends the extracted text to a local TTS server (compatible with standard IndexTTS/Bert-VITS2 APIs).
- **Auto-Advance**: Automatically double-clicks the mouse to advance the text after the audio finishes playing.
- **Overlay UI**: Minimalist overlay UI that stays on top of your game window.

## Requirements

- Python 3.8+
- NVIDIA GPU (Recommended for faster OCR and TTS)
- Windows (for `pyautogui` and `ctypes` features)

## Installation

1.  **Install Dependencies**:
    ```bash
    pip install -r requirements_reader.txt
    ```

    *Note: If you have an NVIDIA GPU, ensure you install the CUDA version of PyTorch first.*

2.  **Start TTS Server**:
    Ensure your TTS backend is running. By default, the script connects to:
    `http://localhost:5002/generate_audio`
    
    *If your TTS server runs on a different port (e.g., 8860), update the URL in `trans_image.py`.*

## Usage

1.  **Run the Script**:
    ```bash
    python trans_image.py
    ```
    *Or use the provided `run_trans_image.bat` if you have a specific Conda environment set up.*

2.  **Select Region**:
    - A transparent green-bordered window will appear.
    - Drag and resize this window so the green frame perfectly encloses the text area of your game.

3.  **Start Automation**:
    - Click the **"Start Auto"** button on the top bar of the overlay.
    - The button will turn Green ("Auto: ON").

4.  **Stop**:
    - Click the button again to stop, or click the **"X"** to close the program.

## Configuration

- **Audio Duration Multiplier**: Controls how long to wait before auto-clicking. Currently set to `0.6` (60% of audio length) in `trans_image.py`.
- **Click Behavior**: Performs a double-click at the current mouse position.

## Troubleshooting

- **"OCR Unavailable"**: Ensure EasyOCR is installed correctly (`pip install easyocr`).
- **"TTS Error"**: Check if your TTS server is running and the URL in `trans_image.py` matches your server's address.
- **Mouse Clicks Not working**: Ensure you are running the script with Administrator privileges if the game is running as Admin.
