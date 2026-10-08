# 第三方来源、许可与分发边界

核对日期：2026-10-08。自有代码、工具代码和文档采用根目录 [MIT](LICENSE)，适用范围见 [NOTICE](NOTICE)。本文件不为第三方资源重新授权。当前完整仓库和 APK **尚未完成公开分发许可审查**，具体阻塞项见 [开源发布检查](docs/OPEN_SOURCE_COMPLIANCE.md)。

## 词库与教学数据

| 材料 | 来源及当前证据 | 许可与处理 |
| --- | --- | --- |
| CC-CEDICT 释义、读音及其改编 | CC-CEDICT contributors；CEDICT 最初由 Paul Denisowski 创建；MDBG 发布。固定版本 `2026-10-03T08:59:08Z`，原始 UTF-8 SHA-256 `1710773bbab29df832f5967bdfb62ca1281868e1240d184165a36c0d948f39e3` | CC BY-SA 4.0；保留作者、来源、许可及修改说明，同许可分发相关改编内容 |
| Mapull 中文参考及成语资料 | `mapull/chinese-dictionary@e804ada333b68afddfdccbe8dcc938a72da157a7`；上游 MIT 声明为 Copyright (c) 2021 码谱；部分收集材料原始来源未明 | 保留上游 MIT 全文；MIT 声明不证明所有被收集材料的权利链完整，需逐项排查或替换 |
| 原始词表、原例句与翻译 | `wordlist.original.csv`，现有元数据仅说明为用户提供，不能证明用户拥有全部公开再分发权 | 授权待核实；覆盖历史备份、基线、加工稿和派生产物，不授予 MIT |
| AI 辅助补充、改写、拆分及例句 | `wordlist_refined/`、`idiom_curated/`、`content_review_20261005/`、`wordlist_expansion_20261005/`、`wordlist_expansion_20261007/`、`wordlist_rebuild/` 等 | 制作记录和语言审校不证明版权独立；派生自 CC-CEDICT 的内容沿用 CC BY-SA 4.0，其他部分按原来源核实 |
| 机器辅助英译和拼音 | 已退役的历史脚本曾调用 Google 翻译接口，记录见 `tools/wordlist_refined/machine-translation-source.json` 及保留的生产资料 | 不能按接口可访问推定可批量分发；发布前核实当时服务条款、账号授权与原文权利，必要时独立重写 |
| SUBTLEX-CH 历史分级参考 | Cai Q, Brysbaert M (2010), *SUBTLEX-CH: Chinese Word and Character Frequencies Based on Film Subtitles*, PLoS ONE 5(6): e10729；历史记录见 `tools/wordlist_difficulty.json` | 原始语料缓存未作为当前词库许可依据；公开语料或摘录前核实对应附件的实际许可，保留论文署名。当前五档为 AI 编辑判断 |
| 其他词典、教材及网页核对材料 | 逐条引用和 `source-excerpts.json` 等审阅资料 | 事实核对链接不等于复制网页、释义、教材或例句的授权；公开稿件、缓存、附件和历史版本也需要审查 |

所有当前 13,223 行的 `来源说明` 均提到 CC-CEDICT，不能把整份混合 CSV 标成纯 MIT。对已获授权的 CC-CEDICT 改编部分及项目有权许可的相应贡献，沿用 CC BY-SA 4.0；**该声明不补足原始例句、其他来源或机器服务的缺失授权**。当前词库不能据此直接宣称已可公开分发。

CC-CEDICT 修改包括：语境择义、数字声调转声调符号、英文教学讲解、语块与部件、例句修订、追加和学习阶段标注。制作过程采用 AI 辅助；“作者稿”“新例句”描述制作过程，不等于独立版权或真人认证。逐条依据保留在 CSV 和冻结制作记录中。

- [MDBG 的 CC-CEDICT 分发说明](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)
- [CC BY-SA 4.0 法律文本](https://creativecommons.org/licenses/by-sa/4.0/legalcode.en)，本地完整文本见 [CC-BY-SA-4.0.txt](core/data/src/main/assets/wordlist-strokes/licenses/CC-BY-SA-4.0.txt)
- [Mapull 固定版本许可](https://github.com/mapull/chinese-dictionary/blob/e804ada333b68afddfdccbe8dcc938a72da157a7/LICENSE)，本地见 [MAPULL-MIT.txt](core/data/src/main/assets/wordlist-strokes/licenses/MAPULL-MIT.txt)
- [词库来源元数据](core/data/src/main/assets/wordlist-strokes/lexicon-sources.json) 和 [应用内词库声明](core/data/src/main/assets/wordlist-strokes/LEXICON_LICENSES.txt)

## 真实笔顺

Make Me a Hanzi 固定版本 `bddc96d41bef78427ed0e034e9f7e31d71fd1b92` 的 `graphics.txt` 提供 6,254 个正式字形；AnimCJK 固定版本 `ec5e17cca76c87587790bcbce5ea0b4d4fb753d6` 的 `graphicsZhHans.txt` 补充 70 个。正式包共 6,324 个字形，包括一个保留的当前未用补充字。资源依据当前 `sources.json` 核对。

字形源自 Arphic 字体及上述项目的加工，轮廓／中线采用 **Arphic Public License**，不授予 MIT。演示资源也保留独立 COPYING 和 Arphic 许可。处理包括字形选择、转为 App JSON、索引与分包；现有描述声明未变换坐标或生成合成笔顺。

分发须保留未改写的许可全文、原版权与上游声明，并按 Arphic 条款保留修改时间／内容及派生字形的可获取途径。当前分包的逐文件修改告知和公开派生数据地址仍需发布前复核，中央 NOTICE 不能自动替代逐文件要求。不能因上游同时含 LGPL 文件，就把所用字形误标为 LGPL，也不能将上游整个仓库一概标为 Arphic。

- [正式资源来源与哈希](core/data/src/main/assets/wordlist-strokes/sources.json)
- [完整笔顺声明](core/data/src/main/assets/wordlist-strokes/LICENSES.txt)
- [Make Me a Hanzi COPYING](core/data/src/main/assets/wordlist-strokes/licenses/MAKE_ME_A_HANZI_COPYING)
- [AnimCJK COPYING](core/data/src/main/assets/wordlist-strokes/licenses/ANIMCJK_COPYING.txt)
- [Arphic 许可](core/data/src/main/assets/wordlist-strokes/licenses/ARPHICPL.TXT) 及 [AnimCJK 随附副本](core/data/src/main/assets/wordlist-strokes/licenses/ANIMCJK_ARPHICPL.TXT)

当前使用的是 graphics 字形数据；引入 Make Me a Hanzi 的 `dictionary.txt`、AnimCJK 的其他文件或代码时，必须重新核对各自许可，不能套用本表。

## 头像、图形与名称

按用户明确要求，QQ 头像不纳入本次审查或处理，资源和现有 [头像声明](core/ui/src/main/assets/avatars/NOTICE.txt) 保持。它们不纳入项目 MIT 授权，本次文档更新也不作其权利已获确认的结论。

`design/app-icon.svg`、启动图标 XML、收藏星形 SVG 和程序绘制的主题纹样需由维护者确认原创或记录实际来源；普通几何外形本身不证明文件来源。未发现捆绑字体文件；设备系统字体及 TTS 引擎不由本项目再分发，新增字体／语音包须单独审查。

README 和历史 UI 文档中的产品与设计体系链接仅作设计参考说明，不表示授权或合作。不使用其他产品的名称、标志、截图或外观作项目官方背书。MIT 不授予第三方商标或肖像权。

## 软件依赖与构建工具

当前直接版本来自 `gradle/libs.versions.toml` 和 Gradle 配置：AndroidX Core 1.17.0、Activity 1.10.1、Lifecycle 2.9.2、Navigation 2.9.3、Room 2.8.5、Compose BOM 2025.08.01、Kotlin 2.2.10、Coroutines 1.10.2、AGP 9.2.1、KSP 2.3.12、Gradle 9.4.1，以及配置中的测试依赖和 Foojay 插件。

这是一份**直接配置清单，不是解析后的完整 SBOM 或许可证结论**。发布具体 APK／源码包前，以其实际依赖图和制品内 `META-INF`、POM、LICENSE／NOTICE 为准，核对传递依赖、插件及 `gradle/wrapper/gradle-wrapper.jar` 的再分发要求。BOM、版本目录或 Gradle 下载成功不证明许可审查通过。保留各依赖要求的版权与 NOTICE，不把二进制依赖纳入项目 MIT 授权。
