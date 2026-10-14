# MeloTTS FP32 中文试听评估

本轮先执行试听，再依据用户确认接入 Android。初版裸输入被反馈过快、短词丢读；用户随后确认“原 Melo 首尾边界＋0.7 倍”显著更好，正式默认采用该方案。词语不额外加句号，例句保留自身标点；词库、Room 和英语语音算法保持。后续按用户要求打包为 `1.4.1 / 7` Debug，覆盖安装到已连接手机并启动；实际证据见版本日志。

## 固定输入

- Melo 中文 ONNX revision：`a0d5c6a264c0ef92d70d8661d8cc502d79627cd6`，FP32 `model.onnx` 为 170,429,550 字节，SHA-256 `bf30582eb1b012250a35b1a4a80e7dfbcf8485e7bb9de0d95efbbeef0e4ad86d`。
- 模型、小型音素资源、源码和许可证的 URL、字节数及 SHA-256 位于 `tools/melo_resources/source-lock.json`。模型与缓存资源下载约 177 MB，不表示安装后 APK 或运行内存大小。
- Melo 源码 revision：`209145371cff8fc3bd60d7be902ea69cbdb7965a`；sherpa 推理参考 revision：`6e7ea3e409f90aaf1541c87f21a013c4f20b9680`。
- 单普通话声线；`speaker_id`、`sample_rate`、`add_blank` 从模型元数据读取。默认 `speed=1.0`、`noise_scale=0.6`、`noise_scale_w=0.8`，44100 Hz 单声道 WAV，不裁切尾音。
- ONNX 转换将 BERT 特征置零；原始 Python Melo 演示的音质不能直接作为本资源的证据。参考脚本和相关许可链接保存在固定清单内。

Melo 代码及转换权重保留 MyShell MIT 声明。原 `tone_sandhi.py` 自带 PaddlePaddle Apache-2.0 头部，单独保留 Apache-2.0 许可证；音素表是 Melo 源仓内的映射文本，不下载训练录音数据集。模型许可声明不重新许可现有词库文本。

## App 资源与链路

```powershell
python -B tools/build_chinese_resources.py --prepare
python -B tools/build_chinese_resources.py --build
python -B tools/build_chinese_resources.py --check
```

`--prepare` 下载固定资源到项目缓存；`--build` 使用已有受校验的本机 Python 包生成 `chinese-speech` 音素／调值分片、来源与许可；`--check` 仅使用 Python 标准库，离线核对当前 CSV、全量词语／例句／写字请求、分片和许可哈希及约 200 MB 资源上限。CI 只执行固定资源准备与 `--check`，不需要本机试听包或 Windows Python 原生库。

正式资源共 38,610 个读音键（包含写字），38,541 个支持，69 个不能可靠转换并保留系统回退说明。分片与模型合计 184,572,011 字节，约 185 MB。该覆盖是静态发音转换证据，不是全库声音或语言认证。

模型权重保留在 `.gradle/melo-resource-source/model.onnx`，不作为超过 GitHub 普通文件上限的大文件入库。Gradle 在 asset 合并前检查固定体积与 SHA-256，再复制至忽略的生成资源；缺少模型必须先准备，不静默构建一个缺少内置模型的包。`onnx` 资源声明为不压缩，运行时用 APK asset 文件描述符只读映射创建 ORT session，不再向 App 缓存复制中文模型。后续 Debug APK 检查确认 Melo 权重为 ZIP_STORED，模型与 264 个中文资源均与固定字节一致。

`SpeechRequest.chinesePronunciation` 携带与中文原文绑定的拼音／例句分块，双语展开只保留匹配的中文 hint。写字单字按当前位置取主词音节；英语目标答题前仍不提供隐藏中文答案。查找键包含汉字、拼音和分块边界，区分同形异音。

默认词库使用构建期完整前端的音素／调值分片；新导入内容使用 Android 内置的有界拼音解析和保守变调。无法唯一对齐、未知音素或三声长串词界不明确时整条回退，不漏掉一部分后冒充成功。固定 voice 使用 0.7 倍 `length_scale` 和首尾边界，再一次 `add_blank`，保留 44100 Hz 和完整尾音。

播放器沿用原来的焦点、等待真正播放完成、双语顺序与 30 秒请求截止。每次中文原生推理使用独立取消对象；快速切卡或进入后台取消不会回退重播。合成前失败／近乎静音可使用已安装的离线系统语音；播放后失败不从头重复。近期 PCM 限制为 24 条／8 MiB，仅保存在内存，关闭时清理。

## 执行

在仓库根目录使用 Python 3.12 Windows x64。当前桌面运行时为 Codex 自带 Python；无需全局安装。

```powershell
python -B tools/evaluate_melo_tts.py --prepare
python -B tools/evaluate_melo_tts.py --samples
python -B tools/evaluate_melo_tts.py --check
```

`--prepare` 仅下载固定来源至 `.gradle/melo-resource-source/`，逐字节核对清单。下载有读取超时、整体截止时间和精确体积限制；哈希匹配后才将临时文件替换为正式缓存。它不获取 INT8 模型、不安装软件、不触发 Android 构建。

`--samples` 复用此前准备的 `.gradle/chinese-resource-source/python/` 中 NumPy 2.2.6、ONNX Runtime 1.31.0、pypinyin 0.55.0 和 jieba 0.42.1。执行前同时验证 `tools/melo_resources/runtime-lock.json` 的原始归档及 1341 个已解包包文件，再导入原生库。它不加载 Kokoro 模型或 Misaki。

当前第一阶段依赖本机保留的上述归档、包文件及 `.gradle/chinese-speech-samples/samples.json` 和旧 WAV。重置／删除这些忽略目录后需重新准备对应来源；工具会明确失败，不自动联网安装依赖或替代旧对照音频。该脚本并非已经具备跨平台自举的生产资源生成器。

试听输出为 `.gradle/melo-speech-samples/index.html`、`samples.json`、`exceptions.json` 和支持项的 WAV。20 条旧样音作为参考，追加 10 个常用词和 2 条当前完整例句。旧 Kokoro 的声线、量化、语速及前端不同，只用于个人听感对照，不作为受控模型质量实验。页面每次仅允许一条音频播放，不自动播放下一项。

生成报告同时记录前端、生成器和预览工具的哈希；工具改变后旧样音不能被当作新实现的验证。`--check` 是离线、只读的哈希／波形文件验证，不加载模型，不重新生成资源。需要评估冻结词库的前端支持范围时，可单独执行：

```powershell
python -B tools/evaluate_melo_tts.py --audit
```

`--audit` 只进行词语／例句的拼音转换，输出 `frontend-coverage.json`；不合成全库声音，不证明全部读音正确或可在手机播放。

## 短词反馈与针对性对照

用户试听初版后反馈语速快、语流不自然，以及“得／地／的／银行／行为／音乐”丢读；随后确认首尾边界＋0.7 倍显著更好，已据此进入 App 接入。初版波形非空不代表这些词可作为学习发音。

```powershell
python -B tools/evaluate_melo_tts.py --probes
```

该命令给上述六词各生成四个明确标注的方案，输出 `.gradle/melo-short-word-probes/index.html` 并核验报告：原转换示例 1.0 倍、仅减速 0.7 倍、原 Melo 首尾边界 0.7 倍、原 Melo 首尾边界＋句末停顿 0.7 倍。采样率和 noise 参数不变；减速由 `length_scale=1/speed` 控制，不用播放器变速。

原 Melo G2P 在 phone 序列首尾加入 `_`，再进行 `add_blank`；sherpa 示例则对裸 lexicon phone 加 blank。补齐原边界是在输入中增加音素／语言位置，不是拼接静音 WAV。原 API 的 `sdp_ratio=0.2`、导出图固定 `0` 及全零 BERT 特征也与完整版不同；这两项不能通过当前七个推理输入恢复。它们是已确认的协议差异，不等于漏读根因已经确诊。

近乎静音的输出（RMS ≤ 0.0001 或峰值 ≤ 0.001）单独标记，记录原始能量及耗时，不计为可用样音，也不通过放大音量来掩盖。输入边界对照通过文件检查仍需要逐词试听；若仍漏读或语流怪异，停止这条转换模型路线，不继续接入 App。

## 发音处理与边界

读取当前冻结 CSV，哈希必须保持 `21b2dbf3103513c8b1749a5f2cfc17a94f7df3f9ca63f89748d4f9718a9388a7`。中文读音来自指定拼音，例句优先利用已有分块；连写拼音只有在合法音节表、分块和字数约束下得到唯一划分才接受。支持不同汉字码点区域，不沿用上游删掉生僻字的预处理。

词库拼音保留明确轻声和多音字；直接使用原 Melo 一／不／三声变调规则处理表层调值。原前端猜测轻声的词典规则被禁用，避免将“东西”的方位义等强改为轻声；修改只存在于发音前端，不写回 CSV。jieba 词界和长串三声韵律仍是推断，列于样音提示中，待核听。

不支持的鼻音、儿化、歧义连写、标点错位、混合数字／Latin 输入或无法绑定的重复文本会拒绝整条样音。异常报告不能被解读为运行时系统回退已经实现。本阶段没有 App 回退行为改动。

WAV 保留模型原生采样率和完整输出，以 PCM16 保存；记录浮点峰值、RMS、超过 PCM16 范围的比例、时长、推理耗时和 RTF。近乎静音或异常输出不发布为成功样音。模型内部随机采样可能使再次推理的波形不同，输出哈希用来校验本次文件，而非要求跨次 WAV 字节相同。

实际执行结果以缓存报告与版本日志为准。用户确认的是这组桌面样音的听感；桌面波形、前端检查、APK 构建及安装启动不能替代手机音质、首声延迟、后台取消、内存／耗电或完整设备验收。实现阶段不升级版本；后续用户授权打包安装，按规则统一升级到 `1.4.1 / 7`，完成 Debug 包校验、覆盖安装与启动。本轮未新增／执行测试用例，未提交或推送。
