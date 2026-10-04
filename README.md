# Chinese Flashcard

面向中文初学者的离线 Android 词卡应用。围绕「理解词语 → 写一写 → 四选一回忆 → 当日通过 → 到期复习」重新组织学习。参考不背单词的情境学习和简洁布局，界面与教学文案独立实现。

界面与辅助讲解使用英文，学习内容使用简体汉字、声调拼音和英文释义。保留 **20 个原创演示词**，并支持从 Profile 直接导入本项目的 [wordlist.csv](wordlist.csv)。交付词库包含两个同义项例句、三个干扰词引用、部件讲解和使用说明；所需真实笔顺随 App 独立分包提供。

## 使用链路

- **Welcome**：名字／头像、每日词量、复习日期三个居中页面。每天新词默认 10，可选 5–50、每档 5 个；复习日期可从 1 / 3 / 5 / 7 / 14 / 30 天中多选。Profile 可修改连续正确轮次，默认 4，可选 2–8。
- **Today**：新学、到期复习、跨天未完成任务分别显示进度；可恢复词卡与写字草稿，显示词库总量、已通过和未开始数量。
- **Study**：一个词、四个英文释义选项。每组最多五词交错学习，前两轮显示不同的中文例句、拼音和英文翻译，后续隐藏例句。答错或选择不知道，当前词的连续正确轮次归零。
- **Explanation**：义项、例句、词语部件与使用提示。拆解作为记忆线索，不把词语联想当成字源。
- **Review**：到期词先做一次无例句回忆；答对完成本次复习，答错进入完整重学。重学通过后重新安排复习。
- **Profile**：修改学习设置、查看学习量、Import CSV 预览并确认追加词库、打开来源与许可。
- **Wordlist**：查看已导入词库的整体进度，按 All / Unlearned / Learning / Learned 和 Difficulty 0 / 1 / 2 筛选，搜索汉字、拼音或英文释义。点击词条可听音、查看讲解或手动书写，返回列表时保留筛选和位置。

当日目标和已有学习周期的设置固定；修改日词量用于下一份日计划，新学习／重学周期采用最新轮次和复习日期。第 N 天指通过日之后的 N 个日历日。错过多次到期节点的同一词，当天只出现一次复习；未完成的学习跨天轮次归零，以独立任务继续，不占新词额度。

底部导航为 Home / Profile / Wordlist。词库中的 Learned 表示曾完成学习轮次，复习答错不会减少累计已学数；Learning 表示已进入首次学习但尚未通过。浏览讲解和书写都不会增加学习轮次或标记掌握。旧日词量在重新确认设置前保留，新选择统一使用 5–50 的五词档位。

## 写一写

首次遇词在讲解后自动进入写字；复习错误的讲解后也自动进入一次写字，可选择稍后再写。同一重学周期重复出错不会重复自动打开。

使用田字格和真实笔画轮廓／中线，保留笔顺动画、暂停、逐笔示范、重播、描红／临写、撤销、重试与轨迹反馈。词语和拼音在画布上方，当前字标红，其他字为黑色；深色模式下纸面仍保持浅色。最后一笔接受后自动推进到下一个字／词，全部完成后显示 Done。

每个字形独立存储在 Room 的 `tracing_items`，`word_tracing` 按词语与字符位置关联。重复字如「谢谢」共享同一份字形数据，但两个位置分别练习。写字草稿和完成记录独立保存，不增加词卡轮次，也不等同于词汇掌握。

当日所有新学／复习／续学任务完成后，若有今日首次通过的新词，显示一次柔性邀请：「是否去写一写今天新学的词汇？」可以开始、拒绝或关闭。完整词语写字包含之前自动练过的字。

## 工程

| 模块 | 职责 |
| --- | --- |
| `app` | 启动、导航、手动依赖装配、离线语音与许可入口 |
| `core:domain` | 纯 Kotlin 模型、Repository 契约、轨迹匹配 |
| `core:data` | Room、演示内容初始化、CSV 校验／追加、笔顺分包、事务化学习与写字状态 |
| `core:ui` | 浅／深色 Compose 主题、统一错误文案 |
| `core:media` | 系统离线普通话 TTS 与音频焦点 |
| `feature:study` | 欢迎、今日、词卡、讲解、完成页，MVI |
| `feature:writing` | 独立写字页面，MVI |
| `feature:profile` | 设置与统计，轻量 MVVM |
| `feature:wordlist` | 只读词库、状态／难度筛选、独立词条详情与手动书写入口 |

保留 Kotlin / Jetpack Compose、Repository / Flow、MVI 与轻量 MVVM。新增 Room **2.8.5**、KSP **2.3.12**；现有 AGP 9.2.1、Gradle 9.4.1、Kotlin 2.2.10、Compose BOM 2025.08.01、Coroutines 1.10.2 保持。compileSdk 36.1、targetSdk 36、minSdk 28，JVM 21、字节码 11。

Gradle 项目名为 `Chinese_Flashcard`，应用名为 `Chinese Flashcard`，applicationId / 包名为 `com.example.chinese_flashcard`。物理工程目录保留原路径。新应用有独立数据空间，不迁移旧课程或旧学习记录。

## 词库、演示数据与来源

[CSV 格式与导入说明](docs/CSV_IMPORT.md) 记录全部字段、限制、追加规则、迁移和取消语义。原文件保存在 [wordlist.original.csv](wordlist.original.csv)，删除理由见 [wordlist-removals.csv](wordlist-removals.csv)。CSV 不随启动自动写入数据库；在 Profile 中自行选择并确认导入。

词库释义优先采用 [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)，保留 CC BY-SA 4.0 来源和许可。笔顺沿用 Make Me a Hanzi，并采用 [AnimCJK 简体中文固定版本](https://github.com/parsimonhi/animCJK/tree/ec5e17cca76c87587790bcbce5ea0b4d4fb753d6) 补充其缺字。完整来源、版本、修改说明、资源哈希和许可位于 `core/data/src/main/assets/wordlist-strokes/`，可在 App 的 Data & licenses 查看。

`tools/build_demo.py` 保存原创词语、例句、拆解和干扰义项，只从已缓存的固定版本笔顺数据提取所需字形：

```powershell
python tools/build_demo.py --check
```

`--check` 核对生成内容与已有 assets 完全一致。重新生成使用 `python tools/build_demo.py`；需要已有 `.gradle/stroke-source` 缓存和版本标记，脚本不下载字库。

笔顺来自 [Make Me a Hanzi](https://github.com/skishore/makemeahanzi/tree/bddc96d41bef78427ed0e034e9f7e31d71fd1b92)，固定提交 `bddc96d41bef78427ed0e034e9f7e31d71fd1b92`。保留原始轮廓、中线、Arphic Public License 与 COPYING；不分发其 dictionary 数据。完整许可可在应用中查看。

仅调用系统报告为已安装、无需联网的普通话 TTS。缺少语音或引擎失败时提示并允许继续学习；没有账号、联网权限、录音或上传。

## 交付与验证

2026-10-04 的 Wordlist、五词档位和字典图标改动已通过 `lintDebug assembleDebug`，八个 Android 模块共 0 错误、22 项依赖／SDK／已有头像资源告警；APK 的 v2 签名验证通过。没有新增测试代码或执行设备验收，离线语音、触摸书写与页面返回的真机表现仍需运行验证。构建日志位于 `.gradle/wordlist-build-final.log`。

早期 CSV 词库与直接导入改动**仅做数据和源码静态检查**，详见 [CSV 静态交付记录](docs/CSV_STATIC_REVIEW.md)。该记录保留当时的验证边界，本轮构建已覆盖当前导入代码；文件选择器、迁移和取消回滚的设备行为尚未验收。

[此前实施与验证记录](docs/IMPLEMENTATION.md) 中的 `lintDebug` 属于导入功能加入之前的历史验证，不能证明本次改动通过编译或运行。

实施前源码、资源和配置备份在同级 `Chinese_Self_Study_Tutor-backup-20261004-012404`，含 SHA-256 清单；退出使用的旧模块和模板资源保存在其中的 `retired-tree`。当前工程已使用 Git 管理，提交和推送状态以实际仓库为准。
