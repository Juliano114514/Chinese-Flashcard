# CSV 词库与导入静态验收记录

日期：2026-10-04。证据范围为最终数据文件、资源及源码检查。**没有新增测试代码，也没有运行测试、Gradle、lint、编译、打包、安装或设备操作。App 运行行为仍未验收。**

## 最终产物

| 项目 | 结果 |
| --- | --- |
| 原表 | 6,698 行；原样备份 `wordlist.original.csv` |
| 最终词库 | **6,648 词，16 列，5,268,363 字节** |
| 罕度分布 | 0：2,665；1：996；2：2,987；不是 HSK 等级 |
| 排序与身份 | 原罕度不改，同级保留原始次序；稳定 ID 不因排序、删除而重编号 |
| 删除清单 | **50 行**，原始行号、稳定 ID、原词、原拼音和原因完整 |
| 占位核实 | 25 条：保留 3 条，删除 22 条；逐条依据见 `wordlist-placeholder-audit.json` |
| 示例与答题字段 | 每词两条不同的完整例句及拼音／英文；一个符合上下文的主义项、词性、部件、使用提示和来源；三个有效干扰词 ID |
| 原有演示词 | 20 个保留；15 个与 CSV 重复，5 个为演示库独有 |
| 首次导入数量推导 | 新增 6,633，总库 6,653；此为最终数据与别名规则的静态推导 |

删除构成为：28 个缺资源字影响的 27 行＋22 个无法确认的占位词－其中重合的 1 行＋2 个其他无效条目。其他两行为原始第 4,087 行「呃呃」（给定读音与笑声用法无法可靠确认）及第 5,693 行「氘元素」（把氢的同位素当作独立元素）。没有因为 CC-CEDICT 未收完整词，就自动删除可确认的组合词、古语或地域用词。

原罕度和汉字逐条反查原始行；所有保留行均有审校记录。保留同字不同读音；字典候选读音不会自动覆盖作者审核的原读音，仅明确修正项改读音。句中「一」的正常变调与词条基本声调可不同。词条、两句拼音均完成逐字位置核对，并排除占位内容与缺失必填字段。

干扰项从已审定的常用语义锚点选择，排除同义／领域重叠候选；全量检查三个不同引用、非本词、四个英文答案不同。再按实际保留的演示词 ID／英文释义映射复核，6,648 行均有四个不同的实际选项。此检查不意味着 App 能自动判定任意自制 CSV 的词义正确性。

## 笔顺

| 项目 | 结果 |
| --- | --- |
| CSV 不同用字 | **6,323** |
| CSV 字符位置 | **13,078，全部有真实轮廓和中线** |
| 独立资源 | 6,324 字：6,254 Make Me a Hanzi＋70 AnimCJK 简体资源 |
| 额外保留字 | 琫；原词琫珌因珌缺资源而删除，琫自身真实资源仍保留 |
| 缺字／受影响保留词 | **0／0，覆盖率 100%** |
| 分包 | **50 包；每包最多 128 字；最大 638,903 字节，低于 1 MiB** |
| 真实几何 | 67,023 条轮廓，381,716 个中线点；与固定源逐字逐坐标核对一致 |
| 演示写字数据 | 原有 35 字内容与原资源保持一致；原 demo 文件字节未改 |

Make Me a Hanzi 固定版本 `bddc96d41bef78427ed0e034e9f7e31d71fd1b92`；AnimCJK 固定版本 `ec5e17cca76c87587790bcbce5ea0b4d4fb753d6`。只使用简体中文资源，没有采用日文字形、合成轮廓或坐标翻转。index、所有分包、来源和许可 SHA-256 一致；几何数据保留 Arphic Public License。词典数据保留 CC BY-SA 4.0 与修改说明。来源版本、原始下载哈希和完整许可随 assets 分发，并接入 Data & licenses。

## 源码路径核对

| 路径 | 已核对的源码行为 |
| --- | --- |
| `app/.../FlashcardApp.kt` | Profile 的系统 OpenDocument；URI／ContentResolver 只在应用层；没有新增存储或网络权限 |
| `feature/profile/.../ProfileScreen.kt`、`ProfileViewModel.kt` | 读取、预览新增／重复／错误数量、确认／取消；错误阻止确认；取消读取先等待收尾再清理；成功刷新统计，设置草稿保留 |
| `core/domain/.../CsvImport.kt` | 与 Android URI 无关的 `CsvSource` 和 Repository `preview`／`commit`／`discard` 契约 |
| `core/data/.../CsvParser.kt` | 严格 UTF-8、BOM、引号内逗号／换行、双引号转义、CRLF／LF；限 64 列／32 KiB 单记录；错误按原始物理行与字段定位 |
| `core/data/.../CsvDecoder.kt` | 必填表头／字段、稳定 ID、罕度、两例句、拼音字符格式、部件还原、三个不同引用；JSON 字符串、转义、控制字符、缺失项／尾逗号及深度限制 |
| `core/data/.../CsvStrokeResources.kt` | 限额与索引一致性；逐包解码；验证真实路径、中线、每字符资源，不同时装入全库几何 |
| `core/data/.../CsvImporter.kt` | 32 MiB 私有临时快照；预览和确认同一文件；确认时重校验；已有 ID／身份跳过、ID 不同词拒绝；批内别名及实际干扰词映射；单次／库内 10,000 词上限 |
| `core/data/.../CsvImporter.kt` | 复用既有串行锁与单个 Room 事务；分包笔顺和每 100 词写入；不覆盖已有学习进度；失败／事务提交前协程取消整批回滚；完成／取消／失败清理快照，启动清理遗留文件 |
| `core/data/.../FlashcardDatabase.kt`、`FlashcardRepositories.kt` | Room v2，注册保留数据的 1→2 `ALTER TABLE` 迁移；原数据库文件名不改；按罕度／顺序／ID 选新词；不重建数据库 |
| 原有 `ensureToday`、答题与写字流程 | 当日已有计划不重新选词；新词参与下一份日计划；继续使用原义项、逐字 position 关联及完整写字流程 |

表中 `...` 均指 `src/main/kotlin/com/example/chinese_flashcard/` 下对应模块包目录。JSON 加了字段词法约束，因为 [Android JSONTokener](https://developer.android.com/reference/org/json/JSONTokener) 本身支持宽松输入。对极端内容的限制没有移除业务逻辑或关闭校验。

取消语义以事务提交为界：提交前失败／取消会回滚；事务已提交后取消回传，不能撤销已提交数据。界面在确认写入期间禁用关闭。静态路径审查不替代真实迁移、取消和故障注入运行验收。

## 执行结果与修改范围

实际执行的只读产物检查：

```text
python tools/complete_wordlist.py --check
words=6648, characters=6323, demoDuplicates=15
firstImportNewWords=6633, resultingWordbookCount=6653

python -B tools/build_wordlist_strokes.py --check
COMPLETE: 6648 CSV rows; 6324 genuine glyphs;
6254 Make Me a Hanzi + 70 AnimCJK; 50 shards;
0 missing glyphs affecting 0 rows.
```

另已检查两份数据工具的 Python AST、10 份现有 XML、备份／删除分区、原始 ID／罕度／次序、全部引用、每个写字位置、资源文件集合与哈希。完整数字见 `wordlist-validation.json`。

本轮以开始时的 43 文件快照对照：7 个现有文件改变——CSV、README、Profile 两个文件、Room／Repository 两个文件、App 路由文件；新增 5 个 Kotlin 导入文件、词库备份／删除清单、审校来源、两个数据工具、独立资源和说明文档。没有删除原有文件；Gradle 配置、Manifest、演示资源、study／writing／media、Application／MainActivity 以及 v1 schema 文件均未改。实现验收时工作区尚未初始化 Git，该阶段未执行提交／推送；后续按用户要求拆分发布的提交见仓库历史。

未执行 Kotlin 编译、Room 迁移运行、文件选择器／TTS／学习／跨日计划／写字／取消回滚的运行验收。v2 schema 需后续允许 KSP 构建时由 Room 生成，本轮未手工伪造生成文件。先前文档中的 lint 结果属于旧版本，不能证明此次 CSV 改动。

安全自查：新增 App 入口仅读取系统选定文件，使用私有快照、输入与资源限额、字段校验及事务；没有将 CSV 内容作为命令、网络请求或可执行代码，也未新增敏感日志。

## 文件指纹

```text
wordlist.original.csv
f2d70fd87f82fd3ef9542715b87905639376a67d830ebf68e8237633fa8160a3

wordlist.csv
8cc63533b692ad99e580bd74cf4b8ee9bcf52ded2bb35981ccfeccdb572349af

wordlist-removals.csv
271724a8a80a35855341b33e28868728bbf9c9adb92b21bc55a1fbb7a2b186ff
```
