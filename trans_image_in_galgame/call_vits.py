# flake8: noqa: E402
# -*- coding: utf-8 -*-
import os
import gc
import random
from itertools import chain
from datetime import datetime

import string
import logging
import re_matching
from flask import Flask, request, jsonify


from tools.sentence import split_by_language

logging.getLogger("numba").setLevel(logging.WARNING)
logging.getLogger("markdown_it").setLevel(logging.WARNING)
logging.getLogger("urllib3").setLevel(logging.WARNING)
logging.getLogger("matplotlib").setLevel(logging.WARNING)

logging.basicConfig(
    level=logging.INFO, format="| %(name)s | %(levelname)s | %(message)s"
)

logger = logging.getLogger(__name__)

import torch
import utils
from flask import Flask, request, jsonify
from infer import infer, latest_version, get_net_g, infer_multilang
import gradio as gr
import webbrowser
import numpy as np
import soundfile as sf
from config import config
import librosa
import simpleaudio as sa

class CreateVitsAudio:
    def __init__(self):
        self.device = config.webui_config.device
        if self.device == "mps":
            os.environ["PYTORCH_ENABLE_MPS_FALLBACK"] = "1"
        self.hps = utils.get_hparams_from_file(config.webui_config.config_path)
        self.version = self.hps.version if hasattr(self.hps, "version") else latest_version
        self.net_g = get_net_g(
            model_path=config.webui_config.model, version=self.version, device=self.device, hps=self.hps
        )

    def __generate_random_filename(self,length=10):  # 生成一个由字母和数字组成的随机字符串
        return ''.join(random.choices(string.ascii_letters + string.digits, k=length))

    def save_audio_to_file(self,audio_data, sample_rate=44100):
        random_filename = self.__generate_random_filename() + '.wav'
        file_path = os.path.join(os.getcwd(),"temp_audio", random_filename)  # 保存到当前工作目录
        sf.write(file_path, audio_data, sample_rate)
        return file_path  # 返回文件完整路径

    def tts_split(self,
            text: str,
            speaker,
            sdp_ratio,
            noise_scale,
            noise_scale_w,
            length_scale,
            language,
            cut_by_sent,
            interval_between_para,
            interval_between_sent,
            reference_audio,
            emotion,
            style_text,
            style_weight,
            is_play
    ):
        if style_text == "":
            style_text = None
        if language == "mix":
            return ("invalid", None)
        while text.find("\n\n") != -1:
            text = text.replace("\n\n", "\n")
        para_list = re_matching.cut_para(text)
        audio_list = []
        for idx, p in enumerate(para_list):
            skip_start = idx != 0
            skip_end = idx != len(para_list) - 1
            audio = infer(
                p,
                reference_audio=reference_audio,
                emotion=emotion,
                sdp_ratio=sdp_ratio,
                noise_scale=noise_scale,
                noise_scale_w=noise_scale_w,
                length_scale=length_scale,
                sid=speaker,
                language=language,
                hps=self.hps,
                net_g=self.net_g,
                device=self.device,
                style_text=style_text,
                style_weight=style_weight,
                skip_start=skip_start,
                skip_end=skip_end,
            )
            audio16bit = (audio * 32767).astype(np.int16)
            audio_list.append(audio16bit)
            silence = np.zeros((int)(44100 * interval_between_para), dtype=np.int16)
            audio_list.append(silence)
            gc.collect()  # 显式调用垃圾回收
            torch.cuda.empty_cache()
        audio_concat = np.concatenate(audio_list)
        wave_obj = sa.WaveObject(audio_concat.tobytes(), 1, 2, 44100)
        if is_play:
            wave_obj.play()
        return self.save_audio_to_file(audio_concat)

    def generator_audio(self, text,is_play=True):
        if config.webui_config.debug:
            logger.info("Enable DEBUG-LEVEL log")
            logging.basicConfig(level=logging.DEBUG)

        file_path = self.tts_split(
            text,
            "nahida",
            0.7,
            0.7,
            0.7,
            1,
            "ZH",
            True,
            1,
            0.2,
            None,
            "Happy",
            "",
            0.7,
            is_play
        )
        print(file_path)
        return file_path

app = Flask(__name__)
audio_generator = CreateVitsAudio()
@app.route('/generate_audio', methods=['POST'])
def generate_audio():
    data = request.get_json()
    text = data.get('text', '')
    is_play = data.get('is_play', True)
    if not text:
        return jsonify({'error': 'No text provided'}), 400
    # 调用音频生成函数
    print("vits")
    file_path = audio_generator.generator_audio(text,is_play)
    return jsonify({'status': 'success', 'file_path': file_path}), 200

if __name__ == "__main__":
    app.run(host='0.0.0.0', port=5002)