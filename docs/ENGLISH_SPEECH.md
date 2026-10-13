# 英语离线发音与音标资源

当前实现复用 `wordlist.csv` 的英文释义、本义／引申义、部件解释、英语例句与语块解释。CSV 字节、ID、顺序及导入契约保持原样。英语语音采用 Microsoft ONNX Runtime Android **1.31.0** 和 **Kitten TTS Nano 0.8 INT8**，固定 Bella 声线。模型随 APK 内置，第一次使用英语发音无需联网或下载模型。

## 来源与许可

| 输入 | 固定来源 | 处理与许可 |
| --- | --- | --- |
| Kitten 模型、声音及模型卡 | [KittenML 官方模型仓库](https://huggingface.co/KittenML/kitten-tts-nano-0.8-int8/tree/84781d74e29ee25217551556398b42f80593a813)，revision `84781d74e29ee25217551556398b42f80593a813` | 模型声明 Apache-2.0；ONNX 原字节内置，只把 Bella 的声音矩阵转换为小端 float32，不训练或克隆声音 |
| Kitten 文本预处理／模型输入参考 | [KittenML 官方源码](https://github.com/KittenML/KittenTTS/tree/8ed679c08e0976b236bd5d0dc4ec9ddd98b0da98)，revision `8ed679c08e0976b236bd5d0dc4ec9ddd98b0da98` | Apache-2.0；预处理源码仅放在构建缓存，模型符号表及推理输入遵循此版本 |
| ONNX Runtime Android | [官方安装说明](https://onnxruntime.ai/docs/install/#install-on-android)、[v1.31.0 许可](https://github.com/microsoft/onnxruntime/blob/v1.31.0/LICENSE) | MIT；应用内保存完整许可证，实际 AAR 的传递组件声明仍以制品为准 |
| 美国英语 IPA | [ipa-dict](https://github.com/open-dict-data/ipa-dict/tree/43c3570eb3553bdd19fccd2bd0091534889af023)，revision `43c3570eb3553bdd19fccd2bd0091534889af023` | en_US 数据来自 cmudict-ipa／CMUdict 与 syllabify；保留 MIT、CMU BSD 及原始署名，不套用其他语言分支的许可 |
| 构建期 eSpeak NG | [espeakng-loader 0.2.4 的 PyPI 制品](https://pypi.org/project/espeakng-loader/0.2.4/)，Windows x64 wheel SHA-256 `41f1e08ac9deda2efd1ea9de0b81dab9f5ae3c4b24284f76533d0a7b1dd7abd7` | 解包后仅加载 eSpeak NG 1.52.0 DLL，使用已核验 C API 生成语音输入；GPL 工具、语言数据和 loader 代码均不进入 APK |

原始下载 URL、固定版本、字节数和 SHA-256 均记录于 `tools/english_resources/source-lock.json`。运行时资源的模型、声音及每片发音数据哈希记录在 `english-speech/manifest.json`。模型卡中的许可声明是已核验的分发依据，不能代替未公开训练语料及声音权利的独立核实，也不表示零版权风险。

发音 sidecar 中的英语文本继续遵循现有混合词库的来源与许可。开放模型、合成发音或新增音标不会消除原词库已记录的授权待核实项；公开分发仍按 `THIRD_PARTY_NOTICES.md` 和 `docs/OPEN_SOURCE_COMPLIANCE.md` 的实际证据处理。

## 运行时资源契约

`core/media/src/main/assets/english-speech/manifest.json` 的 `schemaVersion` 为 1：

- `modelPath` 指向原始 ONNX，`modelSha256` 校验复制进应用缓存的模型。
- `stylePath` 指向 `bella.f32`，`styleSha256` 校验原始声音字节；小端 float32、400 行、256 列、无文件头，顺序为 row-major。
- `sampleRate=24000`、`speedPrior=0.8`、`trimTailSamples=5000`；语速先验只应用一次，不再叠乘旧中文语速。
- `hashPrefixChars=2`，`shardPattern=english-speech/shard_{prefix}.json`；前缀是下述 SHA-256 的前两个十六进制字符。
- `maxTokenCount=510`；当前最长实际输入为 237 个 token。

查找键是 `SHA256(text.trim().encode("UTF-8"))`，保留大小写。每片 JSON 格式为 `{"schemaVersion":1,"entries":{"完整hash":{"text":"原文","chunks":[{"tokenIds":[0,...,10,0],"styleRow":35}]}}}`。分片已经完成英文数字／缩写等文本预处理与分句；运行时不需要 phonemizer。

每个 chunk 的 ONNX 输入为 `input_ids: int64[1,N]`、`style: float32[1,256]` 和 `speed: float32[1]`。`styleRow` 已按预处理后的 chunk 长度计算 `min(length,399)`，播放器按此值读取矩阵的一行。模型输出展开为 float32 PCM，逐 chunk 去除末尾 5000 个样本后顺序播放；样本不足时报告失败，不补造音频。该规则来自固定 Kitten 官方 `onnx_model.py`。

NPZ 仅为下载缓存中的声音来源，生成器使用 `numpy.load(..., allow_pickle=False)` 读取并验证所有浮点数有限，运行时只携带原始 float32 文件。APK 资源中无 `.npz`、`.npy`、Python、eSpeak DLL、eSpeak 语言数据或 sherpa native 库。

## 词库覆盖与音标

2026-10-13 的正式 CSV 哈希为 `21b2dbf3103513c8b1749a5f2cfc17a94f7df3f9ca63f89748d4f9718a9388a7`。此次真实 eSpeak 预处理覆盖 13,223 个中文词条的 **65,722 条唯一英语文本、66,027 个发音 chunk**；覆盖报告见 `english-speech/coverage.json`。

原完整释义与顶层分号片段都保留可朗读索引，括号中的分号不拆分。纯符号 `☷` 没有英语发音，不生成虚假 token；缩写括号造成的纯标点尾 chunk 同样不调用模型。括号、排版引号等不属于 Kitten 模型符号表的字符按其官方 TextCleaner 规则忽略，并在报告中列出。词库中的注释和多义词发音并未因此获得人工语言审校认证。

`core/data/src/main/assets/english-teaching/ipa.json` 采用 `{"schemaVersion":1,"dialect":"en-US","entries":{"east":"/ˈist/"}}`。键为原文 `trim().lowercase(Locale.ROOT)`，当前有 **7,719 条精确词典命中**。多个读音按原数据保留，不自行选择语境读音；未命中时留空，不把构建期 TTS 转音结果伪装成词典 IPA。长释义和整句通常没有对应词典条目。

同目录 `word-index.json` 以 13,223 个原 `wordId` 关联原主释义及其顶层分号义项，保留文本、顺序和重复项。每个义项记录 `speechHash` 与命中时的 `ipaKey`；无可朗读文字或无词典命中时对应值为 null。它只提供数据溯源，不改变词义或学习状态，运行时无需新增加载这一索引。`LICENSES.txt` 聚合 IPA／CMU 的四份完整许可；这三个生成文件的字节数、SHA-256 与 CSV 快照记录在 `sources.json`。

## 维护与验证

```powershell
python tools/build_english_resources.py --prepare
python tools/build_english_resources.py --build
python tools/build_english_resources.py --check
```

只补词条溯源索引／教学许可时可执行 `python tools/build_english_resources.py --refresh-teaching`，复用已生成的发音分片和 IPA，不联网、不加载 DLL 或模型、不重建发音数据，然后执行同一只读检查。

`--prepare` 明确联网下载，仅写任务隔离目录 `.gradle/english-resource-source/`；来源和校验值来自固定清单，下载有超时／大小限制，解包检查路径、符号链接、条目数量及总大小。它不进行全局安装，也不运行 wheel 的安装脚本或 Python loader。`--build` 要求来源哈希正确，加载已核验的构建期 DLL 和固定文本预处理模块，输出模型、Bella 矩阵、256 个发音分片、IPA 与声明；Windows x64 和 NumPy 是当前生成入口的环境要求。

`--check` 不联网、不加载 NumPy 或 DLL、不写文件。它检查 CSV 快照、原模型哈希、声音有限值及矩阵大小、分片哈希、全量英语文本覆盖、token 范围／起止标记／510 上限、声音行、IPA 格式、许可证和运行时资源中禁用格式。它不是 Android 推理、音质、延迟或设备播放证明。

现有或用户导入的英语文本不在冻结 sidecar 时，运行时可使用设备已安装的离线英语声音；无可用声音时显示非阻塞提示。模型解码失败、缓存校验失败及播放被取消均不得改变答题或学习进度。更换词库快照需要重新生成和检查 sidecar；`--check` 不会自行修改 CSV 或下载补丁。
