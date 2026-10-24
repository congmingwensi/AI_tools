# GPT 纳西妲自动朗读

Android/Kotlin 应用，通过无障碍服务查找 ChatGPT 当前屏幕内可见的回答“复制”按钮，复制完整回答后请求电脑上的 TTS，并在手机播放音频。

## 直接安装使用

现成 APK 可从夸克网盘获取：

- 下载链接：https://pan.quark.cn/s/817d2b5b44f8
- 提取码：`eH92`

网盘提供的预配置 APK 默认访问作者本机（电脑端）的纳西妲音色服务，安装后不需要自行填写 URL 和接口密钥。使用时需要作者的电脑、TTS 服务和公网隧道保持在线；语音生成在电脑上完成，并非在手机离线运行。

本仓库只提供源码和构建脚本，不提交 TTS访问链接、签名文件或作者私人密钥。自行编译的源码版默认地址与密钥为空，需要按下面说明配置自己的服务。

## 工作方式

- 仅在 ChatGPT（`com.openai.chatgpt`）位于前台时，每 3 秒检查一次。
- 查找屏幕范围内可见的回答复制按钮，排除屏幕外和键盘覆盖区域；没有按钮就等待下一轮。
- 新回答和历史回答都可以读取；多个按钮同时可见时选择节点遍历顺序中的最后一个回答复制按钮。
- 点击复制后，通过短暂获得焦点的透明 Activity 读取剪贴板中的完整文字。
- 不截断正文，不在 App 中再次分块，由电脑端 TTS 处理长文本。
- 按完整正文的 SHA-256 去重，保留最近 100 条成功播放记录；完整播放结束后才记为已朗读。
- TTS 失败使用已获取的文字缓存重试，不为同一次失败重新复制正文。
- 等待时点击悬浮条可重读最近回答；朗读时点击暂停，再次点击继续。

透明 Activity 读取剪贴板可能短暂影响输入焦点；若复制按钮在键盘上方仍可见，仍会执行获取。本应用不通过关闭键盘来寻找按钮。ChatGPT 更新无障碍节点后，可能需要调整匹配逻辑。

## 自建电脑服务与 cpolar

需要先自行部署 VITS/TTS 服务并加载自己的音色。本目录仅包含 Android 客户端，不包含 VITS 服务端和模型。服务接口须符合下文的请求/响应约定。

1. 自行注册 cpolar 账号并安装 cpolar 客户端。
2. 将控制台提供的 cpolar **账号 authtoken** 配置到电脑端客户端：

   ```powershell
   .\cpolar.exe authtoken YOUR_CPOLAR_AUTHTOKEN
   ```

3. 假设受密钥保护的 TTS 服务监听 `127.0.0.1:5003`，建立隧道：

   ```powershell
   .\cpolar.exe http 5003 -region=cn
   ```

   使用 cpolar 输出的 HTTPS 域名；如果需要长期不变的地址，在自己的账号中预留域名，再按该账号的隧道配置启动。

4. 在 App 的“电脑 TTS 接口”填写 `https://你的域名/generate_audio`。
5. 在“接口密钥”填写你自己的 **TTS 接口密钥**，保存后点击“测试 TTS 连接并播放”。

注意两种密钥不同：cpolar authtoken 用于电脑登录隧道账号，只配置在电脑上；App 里填写的是你自行设置、并由 TTS 服务端校验的接口密钥，请勿把 cpolar authtoken 填到 App。作者的密钥文件不随源码提供。

### TTS 接口约定

App 发送以下请求，接口密钥位于 `X-Nahida-Token` 请求头：

```http
POST /generate_audio
Content-Type: application/json; charset=utf-8
Accept: audio/*
X-Nahida-Token: YOUR_TTS_API_TOKEN

{"text":"要朗读的完整回答","is_play":false,"return_audio":true}
```

成功时返回 HTTP 200 及可播放的音频二进制，推荐 `Content-Type: audio/mpeg`（MP3），也支持 WAV。不要只返回电脑上的音频路径或 JSON。公网传输使用 MP3 可减少下载量；电脑端如需转码，应自行配置 ffmpeg。

## 编译打包

### 环境

- Windows、PowerShell 5.1 或以上。
- Unity 2021.3.34f1 的 Android Build Support，包含 Android SDK、OpenJDK 与 Gradle 工具目录。
- 本构建脚本复用其中的 Kotlin 1.3.61 编译器，不依赖单独的 Gradle Wrapper。
- SDK 中需要 `platforms/android-30/android.jar`、`build-tools/30.0.2`。
- Gradle 的 `lib` 中需要 Kotlin 编译器及 `kotlin-stdlib-1.3.61.jar`、`kotlin-stdlib-jdk7-1.3.61.jar`、`kotlin-stdlib-jdk8-1.3.61.jar`。脚本会检查所需工具。

### 执行构建

在本目录打开 PowerShell，将下面路径换成你自己的 Unity AndroidPlayer 路径：

```powershell
cd .\gpt_nahida_tts
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 `
  -UnityAndroidPlayer 'C:\Program Files\Unity\Hub\Editor\2021.3.34f1\Editor\Data\PlaybackEngines\AndroidPlayer'
```

如果已经位于 `gpt_nahida_tts` 目录，跳过 `cd`。脚本生成资源、编译 Kotlin、转换 DEX、对齐并签名 APK；首次构建自动生成本地 `debug.keystore`，后续复用。该文件被 Git 忽略，不应提交。构建会清理本项目的 `build` 目录，请勿在其中存放需要保留的文件。

### 密钥文件：默认位置与指定路径

不传 `-TokenFile` 时，脚本默认读取 **`build.ps1` 所在目录下的 `nahida_tts_token.txt`**：

```text
AI_tools\gpt_nahida_tts\nahida_tts_token.txt
```

默认位置按脚本目录确定，与启动 PowerShell 时的工作目录无关。这里填写的是 **TTS 接口密钥**，不是 cpolar 账号 authtoken。

使用默认位置：

1. 在 `build.ps1` 旁创建 `nahida_tts_token.txt`，使用 UTF-8 编码保存。
2. 文件内容只放自己的密钥字符串，不写变量名、引号或 `key=` 前缀。
3. 执行前面的构建命令即可，无需额外传入 `-TokenFile`；脚本会读取文件并将密钥编入 APK。

如果想把密钥文件放在其他目录，在构建命令末尾加 `-TokenFile '文件完整路径'`。参数指向文件本身，而不是目录。例如先把自己的密钥保存到 `D:\private\my_tts_token.txt`，再执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 `
  -UnityAndroidPlayer 'D:\Unity\2021.3.34f1\Editor\Data\PlaybackEngines\AndroidPlayer' `
  -TokenFile 'D:\private\my_tts_token.txt'
```

`-TokenFile` 会覆盖默认文件位置；使用绝对路径并加引号，可避免工作目录或路径中空格造成歧义。修改文件内容后需要重新编译、安装 APK 才能更新内置密钥。

**没有密钥文件也可以编译。** 未找到文件或文件为空时，生成的 APK 不内置密钥，安装后在 App 的“接口密钥”中填写并保存即可。App 中已手动保存的密钥优先于 APK 内置默认值。

密钥仅在本地生成到 `build/generated/BuildSecrets.kt` 后编入 APK。默认密钥文件、生成代码及 APK 都被 Git 忽略；自定义路径下的私人密钥文件也不要提交。源码版的接口地址仍需在 App 中填写。手机不需要这个文件或它的路径，运行时只使用密钥内容。

### APK 输出位置

相对于本目录：

```text
build\outputs\gpt-nahida-accessibility-probe-debug.apk
```

可能同时生成 `.apk.idsig` 签名辅助文件，手机安装只需 APK。签名验证结果会打印在构建日志末尾。

USB 调试已授权时，可以使用 Android SDK 自带的 ADB 安装：

```powershell
adb devices
adb install -r .\build\outputs\gpt-nahida-accessibility-probe-debug.apk
```

只有签名一致才能覆盖安装。自行编译生成的新签名通常与网盘 APK 不同；若提示签名不匹配，卸载旧版后再安装，自行备份需要保留的 App 设置。

## 手机上启用

1. 安装 APK，在系统无障碍设置中启用“GPT 纳西妲自动朗读”。若系统限制侧载应用开启无障碍服务，请在应用信息菜单中允许受限设置。
2. 源码编译版先设置自己的接口地址与 TTS 密钥，保存后测试连接。
3. 打开自动朗读并进入 ChatGPT，让回答的复制按钮出现在屏幕范围内。
4. 若想重新自动朗读历史回答，可点击“允许重新朗读历史回答”清空去重记录。

## 可选诊断

App 内提供 Accessibility JSON 的开始记录、停止记录、导出和清空功能。默认不记录诊断文件；手动开启后会记录 ChatGPT 节点及文字，导出文件可能包含聊天内容，不应提交到 Git。播放完成的手机端音频缓存会删除。
