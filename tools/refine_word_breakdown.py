"""Audit word/example breakdown and emit a guarded editorial overlay; never edit CSV.

Only explicit semantic splits are applied to word parts. Opaque/phonetic words stay
whole; sentence repairs replace placeholder glosses and join dictionary-attested
units while preserving source Hanzi/English and correcting verified readings
and the pronunciation line's punctuation when needed.
"""
from __future__ import annotations
import argparse, collections, copy, csv, hashlib, json, re, unicodedata
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'tools/vocabulary_v2'
HAN = re.compile(r'^[\u3400-\u9fff]+$')
PUNCT = '。，！？；：、,.!?;: \"“”‘’（）()《》'

# Semantic constituents, deliberately reviewed as a list rather than inferred
# from all dictionary substrings (加拿大 must not become 加 / 拿大).
SPLITS = '''
冰箱 冰/箱
家具 家/具
窗户 窗/户
知识 知/识
眼睛 眼/睛
头发 头/发
眉毛 眉/毛
月亮 月/亮
星星 星/星
姓名 姓/名
衣服 衣/服
回家 回/家
回来 回/来
出去 出/去
进来 进/来
走路 走/路
坐下 坐/下
跪下 跪/下
起来 起/来
几个 几/个
一个 一/个
这个 这/个
那个 那/个
后面 后/面
前面 前/面
里面 里/面
外面 外/面
上面 上/面
下面 下/面
多少 多/少
力量 力/量
休息 休/息
困难 困/难
指甲 指/甲
名字 名/字
薪水 薪/水
老人 老/人
垃圾袋 垃圾/袋
垃圾分类 垃圾/分类
葡萄酒 葡萄/酒
橄榄球 橄榄/球
卫生间 卫生/间
卫生纸 卫生/纸
抗生素 抗/生/素
吸尘器 吸/尘/器
停车位 停车/位
餐巾纸 餐巾/纸
电风扇 电/风扇
保鲜盒 保鲜/盒
保鲜袋 保鲜/袋
充电桩 充电/桩
充电线 充电/线
净水器 净/水/器
出租屋 出租/屋
医保卡 医保/卡
外卖员 外卖/员
快递柜 快递/柜
数据线 数据/线
晾衣杆 晾/衣/杆
洗发露 洗/发/露
洗洁精 洗/洁/精
消毒液 消毒/液
热水壶 热水/壶
电热毯 电/热/毯
电磁炉 电/磁/炉
路由器 路由/器
电动牙刷 电动/牙刷
空气炸锅 空气/炸/锅
有没有 有/没有
不在乎 不/在乎
实际上 实际/上
非常感谢 非常/感谢
俱乐部 俱乐部
意大利 意大利
加拿大 加拿大
了不起 了不起
全世界 全/世界
从来不 从来/不
大部分 大/部分
无所谓 无所谓
大多数 大/多数
圣诞节 圣诞/节
外星人 外星/人
第二次 第二/次
地下室 地下/室
救护车 救护/车
可能性 可能/性
第二天 第二/天
注意力 注意/力
不怎么 不/怎么
别无选择 别无/选择
小朋友 小/朋友
英国人 英国/人
尽可能 尽/可能
深呼吸 深/呼吸
圣诞老人 圣诞/老人
来不及 来不及
星期五 星期/五
听不懂 听/不/懂
星期六 星期/六
得不到 得/不/到
双胞胎 双/胞胎
星期天 星期/天
心脏病 心脏/病
到此为止 到此/为止
星期一 星期/一
目的地 目的/地
取决于 取决/于
听不见 听/不/见
收音机 收音/机
看不出 看/不/出
成年人 成年/人
游泳池 游泳/池
计算机 计算/机
一系列 一/系列
行不通 行/不/通
大自然 大/自然
打交道 打交道
犯罪现场 犯罪/现场
看一看 看/一/看
男孩子 男/孩子
大使馆 大使/馆
外国人 外国/人
老太太 老/太太
有钱人 有钱/人
音乐会 音乐/会
老人家 老人/家
口香糖 口/香/糖
演唱会 演唱/会
服务生 服务/生
殖民地 殖民/地
不在意 不/在意
建筑物 建筑/物
幽默感 幽默/感
看得见 看/得/见
影响力 影响/力
电影院 电影/院
魔术师 魔术/师
音乐家 音乐/家
兄弟姐妹 兄弟/姐妹
共同点 共同/点
健身房 健身/房
以至于 以至于
试一试 试/一/试
听得见 听/得/见
下星期 下/星期
纪念品 纪念/品
电视机 电视/机
情人节 情人/节
冰激凌 冰激凌
政治家 政治/家
添麻烦 添/麻烦
聚在一起 聚/在一起
看不起 看不起
前男友 前/男友
上星期 上/星期
百分之百 百分之/百
建筑师 建筑/师
高跟鞋 高/跟/鞋
爆米花 爆/米/花
本地人 本地/人
野生动物 野生/动物
时不时 时不时
糖尿病 糖/尿/病
买不起 买/不/起
曲棍球 曲/棍/球
赶不上 赶/不/上
看不清 看/不/清
时间表 时间/表
解决办法 解决/办法
代理人 代理/人
尽力而为 尽力/而/为
好意思 好意思
不自在 不/自在
搭便车 搭/便车
跟不上 跟/不/上
保险柜 保险/柜
想像力 想像/力
想象力 想象/力
星期日 星期/日
达成协议 达成/协议
不得已 不得已
作曲家 作曲/家
私生活 私/生活
洋娃娃 洋/娃娃
瞧不起 瞧不起
犹太教 犹太/教
公共汽车 公共/汽车
物理学 物理/学
放不下 放/不/下
理发师 理发/师
吹口哨 吹/口哨
玫瑰花 玫瑰/花
记忆力 记忆/力
外交官 外交/官
发牢骚 发/牢骚
生物学 生物/学
阿拉伯语 阿拉伯/语
吃不下 吃/不/下
十二月 十二/月
戴帽子 戴/帽子
一般来说 一般/来/说
自助餐 自助/餐
笔记本电脑 笔记本/电脑
指南针 指/南/针
小孩儿 小孩/儿
男孩儿 男孩/儿
有利于 有利/于
代表团 代表/团
旅行社 旅行/社
流鼻血 流/鼻血
市政府 市/政府
打瞌睡 打/瞌睡
钢琴家 钢琴/家
种族主义者 种族主义/者
吸取教训 吸取/教训
种族主义 种族/主义
必不可少 必/不可/少
烤面包 烤/面包
预防措施 预防/措施
救生衣 救生/衣
苹果派 苹果/派
天文学 天文/学
音乐厅 音乐/厅
上班族 上班/族
国际象棋 国际/象棋
收银员 收银/员
古典音乐 古典/音乐
哈佛大学 哈佛/大学
调查结果 调查/结果
高血压 高/血压
科学研究 科学/研究
程序员 程序/员
信号灯 信号/灯
技术员 技术/员
公事包 公事/包
安全帽 安全/帽
人民币 人民/币
遇难者 遇难/者
冥王星 冥王星
西班牙文 西班牙/文
放风筝 放/风筝
开眼界 开/眼界
金黄色 金黄/色
面包机 面包/机
方向感 方向/感
工艺品 工艺/品
交通枢纽 交通/枢纽
侏儒症 侏儒/症
做生意 做/生意
公共设施 公共/设施
博士学位 博士/学位
历史人物 历史/人物
原子序数 原子/序数
口腔溃疡 口腔/溃疡
合作伙伴 合作/伙伴
啮齿动物 啮齿/动物
天气预报 天气/预报
实际情况 实际/情况
工作人员 工作/人员
消息来源 消息/来源
消防员 消防/员
犯罪团伙 犯罪/团伙
犯罪者 犯罪/者
生日快乐 生日/快乐
生日礼物 生日/礼物
研究人员 研究/人员
研究报告 研究/报告
硕士学位 硕士/学位
经验丰富 经验/丰富
第一次 第一/次
第一轮 第一/轮
下个月 下/个/月
买东西 买/东西
买菜 买/菜
带孩子 带/孩子
放在心上 放/在/心上
放慢脚步 放慢/脚步
救援队 救援/队
有一些 有/一些
有礼貌 有/礼貌
每个人 每/个/人
毕业典礼 毕业/典礼
民间故事 民间/故事
滥用权力 滥用/权力
演奏者 演奏/者
烧水 烧/水
用完 用/完
看书 看/书
听清 听/清
吃完 吃/完
吃草 吃/草
做完 做/完
做错 做/错
守时 守/时
没电 没/电
称重 称/重
穿上 穿/上
结网 结/网
缺钱 缺/钱
水田 水/田
水鸟 水/鸟
家训 家/训
临水 临/水
旧名 旧/名
旧称 旧/称
新芽 新/芽
灾后 灾/后
睡眼惺忪 睡眼/惺忪
瑟瑟发抖 瑟瑟/发抖
步履蹒跚 步履/蹒跚
呶呶不休 呶呶/不休
玛丽 玛丽
爱玛 爱玛
呱呱 呱呱
喋喋 喋喋
娓娓 娓娓
孜孜 孜孜
眈眈 眈眈
'''
SEMANTIC_SPLITS = dict(line.split() for line in SPLITS.splitlines() if line.strip())
# Glosses apply to a constituent in a particular headword, not every occurrence
# of the same character. These are learning aids, not etymological derivations.
CONTEXT = {
 '多少': {'多':'many; much','少':'few; little'},
 '前面': {'前':'front','面':'side; aspect'}, '后面': {'后':'back; behind','面':'side; aspect'},
 '上面': {'上':'above; upper','面':'side; surface'}, '下面': {'下':'below; lower','面':'side; surface'},
 '里面': {'里':'inside','面':'side; aspect'}, '外面': {'外':'outside','面':'side; aspect'},
 '下个月': {'下':'next','月':'month'}, '前男友': {'前':'former; ex-'},
 '做生意': {'生意':'business; trade'}, '守时': {'守':'observe; keep','时':'time'},
 '放在心上': {'心上':'in mind; at heart'}, '流鼻血': {'鼻血':'blood from the nose'},
 '称重': {'称':'weigh','重':'weight'}, '国际象棋': {'象棋':'chess (in this term: international chess)'},
 '达成协议': {'协议':'agreement'},
 '原子序数': {'序数':'ordinal position; number'}, '高血压': {'高':'high'},
 '音乐厅': {'厅':'hall'}, '玫瑰花': {'花':'flower'}, '十二月': {'月':'month'},
 '星期日': {'日':'day; Sunday'}, '星期天': {'天':'day; Sunday'},
 '建筑师': {'师':'specialist; professional'},
 '冰箱': {'冰':'ice; cooling','箱':'box; cabinet'},
 '家具': {'家':'home; household','具':'utensil; equipment'},
 '窗户': {'窗':'window','户':'opening; window'},
 '知识': {'知':'know','识':'knowledge; understanding'},
 '眼睛': {'眼':'eye','睛':'eyeball'},
 '衣服': {'衣':'clothing','服':'clothing'},
 '头发': {'头':'head','发':'hair'},
 '眉毛': {'眉':'eyebrow','毛':'hair'},
 '月亮': {'月':'moon','亮':'bright'},
 '星星': {'星':'star (reduplicated noun)'},
 '休息': {'休':'rest','息':'rest; stop'},
 '力量': {'力':'strength','量':'amount; capacity'},
 '困难': {'困':'difficulty; hardship','难':'difficult'},
 '指甲': {'指':'finger','甲':'nail; hard covering'},
 '名字': {'名':'name','字':'name; written character'},
 '薪水': {'薪':'pay; wages','水':'bound element in the word for pay'},
 '吸尘器': {'吸':'suck in','尘':'dust','器':'device'},
 '停车位': {'位':'space; position'}, '垃圾袋': {'袋':'bag'},
 '保鲜袋': {'袋':'bag'}, '充电桩': {'桩':'post; charging stand'},
 '充电线': {'线':'cable'}, '数据线': {'线':'cable'},
 '抗生素': {'抗':'against','生':'living organisms','素':'substance'},
 '卫生间': {'间':'room'}, '卫生纸': {'卫生':'hygiene; sanitary use'},
 '电风扇': {'电':'electric'}, '净水器': {'净':'purify','器':'device'},
 '医保卡': {'医保':'medical insurance','卡':'card'},
 '外卖员': {'员':'worker'}, '洗发露': {'洗':'wash','发':'hair','露':'liquid preparation'},
 '洗洁精': {'洗':'wash','洁':'clean','精':'concentrated preparation'},
 '消毒液': {'液':'liquid'}, '电热毯': {'电':'electric','热':'heated'},
 '电磁炉': {'电':'electric','磁':'magnetic','炉':'stove'},
 '路由器': {'路由':'routing','器':'device'},
 '空气炸锅': {'空气':'air','炸':'fry','锅':'cooker; pot'},
 '实际上': {'上':'in the respect of; in practice'},
 '全世界': {'全':'whole; entire'}, '大部分': {'大':'large; major'},
 '大多数': {'大':'great; overwhelming'},
 '圣诞节': {'节':'festival'}, '外星人': {'人':'person; being'},
 '地下室': {'地下':'underground','室':'room'},
 '可能性': {'性':'possibility-forming suffix (-ity)'},
 '注意力': {'力':'ability; capacity'}, '尽可能': {'尽':'as much as possible'},
 '得不到': {'得':'obtain','到':'result complement: reach; obtain'},
 '双胞胎': {'双':'two; a pair','胞胎':'siblings born in the same pregnancy'},
 '目的地': {'地':'place'},
 '收音机': {'收音':'receive sound or broadcasts','机':'device'},
 '计算机': {'机':'machine'}, '行不通': {'行':'work; be practicable','通':'go through; work'},
 '大自然': {'大':'great; vast'}, '男孩子': {'男':'male'},
 '大使馆': {'馆':'official office; building'},
 '老太太': {'老':'elderly'}, '音乐会': {'会':'gathering; event'},
 '老人家': {'家':'respectful suffix for an older person'},
 '口香糖': {'口':'mouth','香':'fragrant; pleasant-tasting','糖':'sweet; candy'},
 '演唱会': {'会':'performance; event'}, '服务生': {'生':'worker; attendant'},
 '殖民地': {'地':'land; territory'}, '建筑物': {'物':'structure; object'},
 '幽默感': {'感':'sense'}, '影响力': {'力':'power; capacity'},
 '电影院': {'院':'public establishment; venue'},
 '音乐家': {'家':'specialist; professional'}, '魔术师': {'师':'specialist; performer'},
 '共同点': {'点':'point; aspect'}, '健身房': {'房':'room; venue'},
 '纪念品': {'品':'item; article'}, '电视机': {'机':'device'},
 '情人节': {'节':'festival; special day'}, '政治家': {'家':'specialist; professional'},
 '下星期': {'下':'next'}, '上星期': {'上':'last; previous'},
 '百分之百': {'百分之':'percent; out of a hundred'},
 '高跟鞋': {'跟':'heel'}, '爆米花': {'爆':'pop; burst','米':'grain; kernels','花':'flower-like popped shape'},
 '时不时': {'时不时':'from time to time (a fixed expression)'},
 '糖尿病': {'糖':'sugar','尿':'urine'},
 '买不起': {'起':'result complement: afford'},
 '曲棍球': {'曲':'curved','棍':'stick'},
 '时间表': {'表':'table; chart'}, '尽力而为': {'而':'and; linking manner to action','为':'act; do'},
 '搭便车': {'搭':'take; ride in','便车':'a lift; a ride in a passing vehicle'},
 '保险柜': {'保险':'secure; safe'},
 '作曲家': {'家':'specialist; professional'}, '私生活': {'私':'private'},
 '洋娃娃': {'洋':'foreign; Western-style'}, '犹太教': {'教':'religion'},
 '物理学': {'学':'field of study'}, '理发师': {'师':'specialist; worker'},
 '记忆力': {'力':'ability'}, '外交官': {'官':'official'},
 '发牢骚': {'发':'express; give vent to'}, '生物学': {'学':'field of study'},
 '自助餐': {'自助':'self-service','餐':'meal'}, '指南针': {'指':'point','针':'needle'},
 '小孩儿': {'儿':'diminutive or colloquial suffix'},
 '男孩儿': {'儿':'diminutive or colloquial suffix'},
 '代表团': {'团':'group; delegation'}, '旅行社': {'社':'agency'},
 '市政府': {'市':'city'}, '打瞌睡': {'打':'verb used in the fixed expression for dozing'},
 '钢琴家': {'家':'specialist; professional'},
 '种族主义者': {'者':'person who follows or practises something'},
 '必不可少': {'必':'necessarily','不可':'cannot','少':'be missing; be lacking'},
 '救生衣': {'救生':'life-saving'},
 '苹果派': {'派':'pie (phonetic loan)'}, '天文学': {'学':'field of study'},
 '上班族': {'族':'group of people with a shared occupation or lifestyle'},
 '收银员': {'员':'worker'}, '程序员': {'员':'worker; professional'},
 '技术员': {'员':'worker; specialist'},
 '人民币': {'币':'currency'}, '遇难者': {'者':'person'},
 '放风筝': {'放':'fly; let out'}, '开眼界': {'开':'broaden; open up'},
 '面包机': {'机':'machine'}, '方向感': {'感':'sense'},
 '工艺品': {'工艺':'craft; workmanship','品':'item; article'}, '侏儒症': {'症':'medical condition'},
 '消防员': {'员':'worker; professional'}, '犯罪者': {'者':'person'},
 '旧称': {'称':'name; designation'}, '睡眼惺忪': {'睡眼':'sleepy eyes','惺忪':'drowsy; not fully awake'},
 '呶呶不休': {'呶呶':'talk continuously','不休':'without stopping'},
}
# Opaque words have one lexical unit; breaking their spelling into independent
# meanings would falsely imply a semantic origin.
OPAQUE = set('''咖啡 沙发 巧克力 葡萄 蝴蝶 蜘蛛 蚂蚁 蝙蝠 柠檬 玻璃 垃圾 橄榄 骆驼 咳嗽 喉咙
玫瑰 茉莉 蔷薇 蘑菇 蜈蚣 蟋蟀 螃蟹 蝌蚪 蟑螂 鹦鹉 鸳鸯 凤凰 珊瑚 琥珀 玛瑙 芙蓉 琵琶
逻辑 巴士 引擎 雪茄 吗啡 喇叭 胳膊 尴尬 徘徊 匍匐 逍遥 伶俐 狼狈 慷慨 朦胧 囫囵 仿佛
邂逅 憧憬 踌躇 蹒跚 踉跄 窈窕 彷徨 坎坷 婆娑 参差 逶迤 叮咛 吩咐 唠叨 嘀咕 哆嗦
抗生素 曲奇 薪水 单调 歇斯底里 迪斯可 涅槃 呋喃 哌嗪 吡啶 瑜伽 伽蓝 喇嘛 佛陀 比萨 意大利 加拿大 阿拉伯 西班牙
俱乐部 冰激凌 冥王星 玛丽 爱玛 呱呱 喋喋 娓娓 孜孜 眈眈'''.split())
# These new daily words did not occur in the recorded six-stage CSV. Adding
# their transparent units must not change the frozen baseline overlay.
_NEW_DAILY_UNIT_ROWS = '''
水杯|水:water; drinking|杯:cup
水渍|水:water|渍:stain; mark
油渍|油:oil; grease|渍:stain
网线|网:network|线:cable
外卖盒|外卖:takeaway food|盒:container; box
水电费|水:water|电:electricity|费:charge; fee
半糖|半:half|糖:sugar
好评|好:good; positive|评:review
打饭|打:get a serving of|饭:food; cooked rice
热点|热:hot; popular|点:spot; topic
开窗|开:open|窗:window
换锁|换:replace|锁:lock
自动续费|自动:automatic|续费:renew a subscription or payment
包装盒|包装:packaging|盒:box
安全别针|安全:safety|别针:pin
差评|差:bad; negative|评:review
烧开水|烧开:bring to the boil|水:water
掉线|掉:lose|线:connection
开锁|开:open; unlock|锁:lock
自取|自:oneself|取:collect; pick up
信用额度|信用:credit|额度:limit; allowance
充话费|充:top up|话费:mobile phone credit
少冰|少:less|冰:ice
插电|插:plug in|电:electrical power
手机壳|手机:mobile phone|壳:case; outer cover
卷尺|卷:roll; coil|尺:measuring rule; tape
冲牙器|冲:flush; rinse|牙:teeth|器:device
换床单|换:change|床单:bedsheet
扎带|扎:fasten; bind|带:tie; strap
沙发套|沙发:sofa|套:cover
流量包|流量:mobile data|包:package; allowance
被套|被:quilt; duvet|套:cover
发夹|发:hair|夹:clip
订单号|订单:order|号:number
蚊帐|蚊:mosquito|帐:netting; canopy
透明胶|透明:transparent|胶:adhesive; tape
断网|断:disconnect|网:network; internet
还款日|还款:repayment|日:date
灭蚊器|灭:kill; eliminate|蚊:mosquito|器:device
刮胡子|刮:shave|胡子:beard
起步价|起步:initial distance or stage|价:fare; price
补办卡|补办:obtain a replacement|卡:card
衣物柔顺剂|衣物:clothes; laundry|柔顺:softening|剂:preparation; agent
免邮|免:waive; be free of|邮:postage
擦桌子|擦:wipe|桌子:table
免密支付|免:without|密:password|支付:payment
账单日|账单:billing statement|日:date
早餐店|早餐:breakfast|店:shop
厨余垃圾|厨余:kitchen scraps; food leftovers|垃圾:waste
余额不足|余额:remaining balance|不足:insufficient
防霉|防:prevent|霉:mold
晾衣服|晾:hang out to dry|衣服:clothes
取消订阅|取消:cancel|订阅:subscription
防潮|防:protect against|潮:moisture; dampness
快递站|快递:parcel delivery|站:station; collection point
削皮器|削皮:peel|器:tool; device
盛饭|盛:serve; scoop out|饭:cooked rice; food
小吃摊|小吃:snacks; street food|摊:stall
指甲剪|指甲:fingernails|剪:clippers
吊柜|吊:wall-mounted; hanging|柜:cabinet
钢化膜|钢化:toughened; tempered|膜:film; screen protector
护发素|护发:hair care|素:preparation; product
橡胶手套|橡胶:rubber|手套:gloves
充电头|充电:charging|头:adapter; plug head
叠衣服|叠:fold|衣服:clothes
沥水篮|沥水:drain water|篮:basket
续杯|续:refill; renew|杯:cup
触摸屏|触摸:touch|屏:screen
'''
for _unit_line in _NEW_DAILY_UNIT_ROWS.strip().splitlines():
 _word, *_unit_cells = _unit_line.split('|')
 _unit_pairs = [cell.split(':', 1) for cell in _unit_cells]
 assert ''.join(unit for unit, _ in _unit_pairs) == _word
 SEMANTIC_SPLITS[_word] = '/'.join(unit for unit, _ in _unit_pairs)
 CONTEXT[_word] = dict(_unit_pairs)
# 皮筋 is kept as one lexical unit: its spelling does not invite an independent
# "skin + tendon" explanation of an elastic band.
OPAQUE.add('皮筋')

# Unambiguous fixes to wrong-reading glosses, not a blanket replacement of every
# contextual sense with a dictionary's first definition.
POLYPHONIC = {
 ('发','fà'): ('hair', r'^(?!.*\bhair\b).*$'),
 ('乐','yuè'): ('music', r'happy|amused|joy|pleasure'),
 ('地','dì'): ('ground; place; land', r'particle|adverbial'),
 ('得','dé'): ('obtain; get', r'particle|linking'),
 ('得','děi'): ('must; have to', r'particle|linking|obtain'),
 ('为','wéi'): ('do; act as; be', r'for the sake|preposition'),
 ('种','zhǒng'): ('kind; species', r'^grow|^to grow|^plant|^to plant'),
 ('种','zhòng'): ('plant; grow', r'^kind|^species'),
 ('曲','qū'): ('curved; bent', r'song|tune'),
 ('曲','qǔ'): ('tune; musical composition', r'^bent|^curved'),
 ('行','háng'): ('line; trade; profession', r'walk|acceptable|okay|to go'),
 ('长','zhǎng'): ('grow; elder; head of a group', r'^long|length'),
 ('还','huán'): ('return; give back', r'still|also|even more'),
 ('好','hào'): ('be fond of', r'^good|fine|well'),
 ('数','shǔ'): ('count', r'^number|figure'),
 ('调','diào'): ('tune; transfer', r'^adjust'),
}
GRAMMAR = {
 ('玛','mǎ'):'phonetic character used in names', ('迹','jī'):'trace; track',
 ('栅','shān'):'fence; railing', ('筝','zhēng'):'zither; bound element in kite',
 ('桔','jú'):'mandarin orange; bound element in balloon flower',
 ('參','cān'):'take part in; visit', ('著','zhe'):'continuing-action or state particle',
 ('个','gè'):'general classifier', ('这','zhè'):'this', ('们','men'):'plural suffix for people',
 ('吗','ma'):'yes-no question particle', ('没','méi'):'not; have not',
 ('会','huì'):'can; will; know how to', ('为','wèi'):'for; for the sake of',
 ('里','lǐ'):'inside; in', ('过','guo'):'experiential aspect particle',
 ('过','guò'):'pass; go across', ('该','gāi'):'should; ought to',
 ('从','cóng'):'from; since', ('让','ràng'):'let; allow; make',
 ('别','bié'):'do not; other', ('应','yīng'):'should; ought to',
 ('许','xǔ'):'permit; allow', ('将','jiāng'):'will; be about to',
 ('谁','shuí'):'who; whom', ('两','liǎng'):'two; both',
 ('并','bìng'):'and; also', ('与','yǔ'):'and; with',
 ('儿','ér'):'child; son', ('麼','me'):'bound element in what or how',
 ('的','de'):'possessive or attributive particle', ('地','de'):'adverbial marker',
 ('得','de'):'complement marker', ('了','le'):'completion or change-of-state particle',
 ('呢','ne'):'follow-up question or ongoing-state particle',
 ('吧','ba'):'suggestion or tentative statement particle',
}

def normalized_py(value):
 return ' '.join(unicodedata.normalize('NFC', value).lower().replace('u:','ü').split())
def clean_gloss(value):
 value = re.sub(r'\[[^\]]*\]', '', value)
 value = re.sub(r'[^\s;/()]*[\u3400-\u9fff][^\s;/()]*', '', value)
 value = re.sub(r'\b(?:sb|sb\.)\b', 'somebody', value)
 value = re.sub(r'\b(?:sth|sth\.)\b', 'something', value)
 return re.sub(r'\s+', ' ', value).strip(' ;,')
def acceptable(value):
 return bool(value and re.search('[A-Za-z]',value) and not re.search(r'character in this|^surname\b|^variant of\b|^old variant|^see\b|^CL:|^used in\b|^\(phonetic\)$|abbr\.|Taiwan pr\.|also pr\.',value,re.I))
def make_lexicon(rows):
 lex = collections.defaultdict(list)
 sys_path = ROOT/'.gradle/chinese-resource-source/python'
 import sys
 sys.path.insert(0,str(sys_path))
 from pypinyin.contrib.tone_convert import to_tone
 for line in (ROOT/'.gradle/vocabulary-v2/cedict.txt').read_text(encoding='utf-8-sig').splitlines():
  m=re.match(r'^(\S+) (\S+) \[([^\]]+)\] /(.*)/$',line)
  if not m: continue
  _,w,py,senses=m.groups()
  py=normalized_py(' '.join(to_tone(x.lower().replace('u:','v').replace('5','')) for x in py.split()))
  glosses=[clean_gloss(s) for s in senses.split('/') if acceptable(s)]
  glosses=[s for s in glosses if acceptable(s)]
  if glosses: lex[(w,py)].extend(glosses)
 # Existing teaching meanings first; they are already short and learner-facing.
 for row in rows:
  key=row['组词'],normalized_py(row['拼音'])
  if acceptable(row['英文释义']):lex[key].insert(0,row['英文释义'])
 lex[('玛莉','mǎ lì')].insert(0,'Mary (name)')
 lex[('爹地','diē dì')].insert(0,'daddy')
 lex[('參加','cān jiā')].insert(0,'take part in; attend')
 lex[('參观','cān guān')].insert(0,'visit; look around')
 lex[('倚著','yǐ zhe')].insert(0,'lean against')
 lex[('谢谢','xiè xiè')].insert(0,'thank you')
 return lex

def build(csv_path,output,report,only_ids=None):
 content=csv_path.read_bytes();rows=list(csv.DictReader(content.decode('utf-8-sig').splitlines()))
 if only_ids is not None and set(only_ids)-{r['词条ID'] for r in rows}:
  raise ValueError('Unknown stable IDs in requested breakdown scope')
 lex=make_lexicon(rows)
 import sys, warnings
 sys.path.insert(0,str(ROOT/'.gradle/chinese-resource-source/python'))
 with warnings.catch_warnings():
  warnings.simplefilter('ignore',UserWarning)
  import jieba
 jieba.setLogLevel(40)
 for token in ['爹地','玛莉','參加','參观','倚著']:
  jieba.add_word(token,100000)
 if only_ids is not None:
  # These heads have already passed a separate sense/word-boundary review. Keep
  # their full teaching meaning (e.g. password-free payment) in the example line.
  for row in rows:
   if row['词条ID'] in only_ids:jieba.add_word(row['组词'],100000)
 lexreadings=collections.defaultdict(list)
 for (unit,reading) in lex:lexreadings[unit].append(reading)
 freq=collections.defaultdict(collections.Counter)
 for r in rows:
  for p in json.loads(r['部件JSON']):
   if acceptable(p['gloss']):freq[p['hanzi'],normalized_py(p['pinyin'])][p['gloss']]+=1
  for e in json.loads(r['例句JSON']):
   for p in e['chunks']:
    w=p['hanzi'].strip(PUNCT);py=normalized_py(p['pinyin'].strip(PUNCT))
    if HAN.fullmatch(w) and acceptable(p['gloss']):freq[w,py][p['gloss']]+=1
 def gloss(w,py,prefer_lex=True):
  key=w,normalized_py(py)
  if key in GRAMMAR:return GRAMMAR[key]
  if prefer_lex and key in lex:return lex[key][0][:290]
  if freq[key]:return freq[key].most_common(1)[0][0][:290]
  if key in lex:return lex[key][0][:290]
  return None
 def resegment(ex):
  old=ex['chunks'];text=ex['hanzi']
  # Use the saved pronunciation as input, not guessed single-character readings.
  py=[]
  for part in old:
   letters=re.findall(r'[^\W\d_]+',part['pinyin'],re.UNICODE)
   han=[c for c in part['hanzi'] if HAN.fullmatch(c)]
   if len(letters)!=len(han):return None
   py.extend(letters)
  han_positions=[i for i,c in enumerate(text) if HAN.fullmatch(c)]
  if len(py)!=len(han_positions):return None
  bypos=dict(zip(han_positions,py));out=[];offset=0
  def known_unit(unit,reading):
   key=unit,normalized_py(reading)
   if key in lex or key in GRAMMAR:
    return reading,gloss(unit,reading)
   if len(unit)>1:
    for candidate in lexreadings.get(unit,[]):
     cpy=candidate.split();opy=normalized_py(reading).split()
     def plain(py):
      return ''.join(c for c in unicodedata.normalize('NFD',py) if not unicodedata.combining(c))
     # Tone-neutralization differences are safe in an independently recognized
     # word; changed consonants/vowels require a known polyphonic character.
     if len(cpy)==len(opy) and all(a==b or plain(a)==plain(b) or unit[j] in '地乐行长好发数种调重还得藏处参血爹煞著' for j,(a,b) in enumerate(zip(cpy,opy))):
      return candidate,gloss(unit,candidate)
   if len(unit)==1:
    return reading,gloss(unit,reading)
   return reading,None
  def emit(unit,begin):
   reading=' '.join(bypos[j] for j in range(begin,begin+len(unit)) if j in bypos)
   reading,val=known_unit(unit,reading)
   if val is not None:
    out.append({'hanzi':unit,'pinyin':reading,'gloss':'; '.join(part.strip() for part in val.split(';')[:2])});return True
   # A segmenter token need not be a dictionary word; split it using verified
   # smaller units, never invent a word sense to satisfy a split.
   j=0
   while j<len(unit):
    found=None
    for n in range(min(8,len(unit)-j),0,-1):
     sub=unit[j:j+n];r=' '.join(bypos[k] for k in range(begin+j,begin+j+n))
     r,v=known_unit(sub,r)
     if v:found=(sub,r,v);break
    if not found:return False
    sub,r,v=found;out.append({'hanzi':sub,'pinyin':r,'gloss':'; '.join(part.strip() for part in v.split(';')[:2])});j+=len(sub)
   return True
  for token in jieba.cut(text,HMM=False):
   if HAN.fullmatch(token):
    if not emit(token,offset):return None
   else:
    # Keep source punctuation attached to its preceding lexical unit, as the CSV
    # contract and the explanation display already expect.
    if not out:return None
    if any(HAN.fullmatch(ch) for ch in token):return None
    out[-1]['hanzi']+=token
    out[-1]['pinyin']+=''.join({'。': '.', '，': ',', '？': '?', '！': '!', '：': ':', '；': ';', '、': ',', '（': '(', '）': ')', '「': '"', '」': '"', '『': '"', '』': '"', '《': '"', '》': '"', '〈': '"', '〉': '"', '【': '[', '】': ']', '〔': '[', '〕': ']'}.get(ch,ch) for ch in token).strip()
   offset+=len(token)
  if ''.join(p['hanzi'] for p in out)!=text:return None
  return out
 overlay={};stats=collections.Counter();unresolved=[]
 for r in rows:
  w=r['组词'];wid=r['词条ID']
  if only_ids is not None and wid not in only_ids:continue
  originalparts=json.loads(r['部件JSON']);parts=copy.deepcopy(originalparts)
  exs=json.loads(r['例句JSON']);newexs=copy.deepcopy(exs);reasons=[]
  if w in SEMANTIC_SPLITS or w in OPAQUE:
   units=SEMANTIC_SPLITS.get(w,w).split('/')
   if w in OPAQUE:units=[w]
   assert ''.join(units)==w
   syllables=r['拼音'].split();cursor=0;candidate=[]
   for unit in units:
    py=' '.join(syllables[cursor:cursor+len(unit)]);cursor+=len(unit)
    val=CONTEXT.get(w,{}).get(unit)
    if val is None:
     val=r['英文释义'] if unit==w else gloss(unit,py)
    if val is None:
     old=next((p['gloss'] for p in parts if p['hanzi']==unit and p['pinyin']==py and acceptable(p['gloss'])),None)
     val=old
    if val is None:
     unresolved.append({'id':wid,'word':w,'unit':unit,'pinyin':py});candidate=[];break
    candidate.append({'hanzi':unit,'pinyin':py,'gloss':val})
   if candidate and candidate!=parts:
    parts=candidate;reasons.append('reviewed_semantic_units' if len(units)>1 else 'opaque_lexical_unit');stats[reasons[-1]]+=1
  if any('character in this' in part['gloss'] for part in parts):
   # A missing character definition is not a license to invent an etymology.
   # Preserve the attested word as a lexical unit until its morphology is known.
   parts=[{'hanzi':w,'pinyin':r['拼音'],'gloss':r['英文释义']}]
   reasons.append('unknown_morpheme_kept_as_lexical_unit');stats['unknown_morpheme_kept_as_lexical_unit']+=1
  for p in parts:
   key=p['hanzi'],normalized_py(p['pinyin'])
   fix=POLYPHONIC.get(key)
   if fix and re.search(fix[1],p['gloss'],re.I):
    p['gloss']=fix[0];stats['reading_specific_part_gloss']+=1
    if 'reading_specific_part_gloss' not in reasons:reasons.append('reading_specific_part_gloss')
  for ex in newexs:
   oldchunks=ex['chunks'];i=0;chunks=[]
   reading_changed=False
   for q in oldchunks:
    val=q['pinyin']
    if '迹' in q['hanzi']:
     q['pinyin']=re.sub(r'(?<![A-Za-z])jī(?![A-Za-z])','jì',q['pinyin'])
    if '桔梗' in ex['hanzi'] and '桔' in q['hanzi']:
     q['pinyin']=re.sub(r'(?<![A-Za-z])jú(?![A-Za-z])','jié',q['pinyin'])
    if '栅栏' in ex['hanzi'] and '栅' in q['hanzi']:
     q['pinyin']=re.sub(r'(?<![A-Za-z])shān(?![A-Za-z])','zhà',q['pinyin'])
    reading_changed=reading_changed or val!=q['pinyin']
   if reading_changed:
    ex['pinyin']=' '.join(q['pinyin'] for q in oldchunks)
    stats['example_context_reading']+=1
    if 'example_context_reading' not in reasons:reasons.append('example_context_reading')
   while i<len(oldchunks):
    p=copy.deepcopy(oldchunks[i]);bad='character in this expression' in p['gloss']
    chosen=None
    # Repair only runs containing a placeholder. Preserve good sentence chunks.
    for n in range(min(5,len(oldchunks)-i),1,-1):
     run=oldchunks[i:i+n]
     if not any('character in this expression' in q['gloss'] for q in run):continue
     text=''.join(q['hanzi'] for q in run);unit=text.strip(PUNCT)
     if not HAN.fullmatch(unit) or len(unit)>8:continue
     # No join across punctuation; suffix punctuation belongs to last unit.
     if any(c in text[:len(text.rstrip(PUNCT))] for c in PUNCT):continue
     py=' '.join(q['pinyin'] for q in run);barepy=normalized_py(py.strip(PUNCT))
     if (unit,barepy) in lex:
      val=gloss(unit,barepy)
      if val:chosen=({'hanzi':text,'pinyin':py,'gloss':val},n);break
    if chosen:
     p,n=chosen;i+=n;stats['example_lexical_join']+=1
    else:
     i+=1
     if bad:
      unit=p['hanzi'].strip(PUNCT);py=p['pinyin'].strip(PUNCT)
      val=gloss(unit,py)
      if val:p['gloss']=val;stats['example_placeholder_gloss']+=1
      else:unresolved.append({'id':wid,'word':w,'example':ex['hanzi'],'unit':unit,'pinyin':py,'kind':'unresolved_example'})
    chunks.append(p)
   ex['chunks']=chunks
   if only_ids is not None or int(wid.removeprefix('wl_'))>=13224:
    improved=resegment(ex)
    if improved is not None and improved!=ex['chunks']:
     ex['chunks']=improved;chunks=improved
     ex['pinyin']=' '.join(p['pinyin'] for p in improved)
     stats['expanded_example_lexical_segmentation']+=1
     if 'expanded_example_lexical_segmentation' not in reasons:reasons.append('expanded_example_lexical_segmentation')
   assert ''.join(p['hanzi'] for p in chunks)==ex['hanzi']
   assert re.sub(r"[\s'’]+",'',normalized_py(' '.join(p['pinyin'] for p in chunks)))==re.sub(r"[\s'’]+",'',normalized_py(ex['pinyin'])), (wid,w)
  fields={}
  if parts!=originalparts:fields['部件JSON']=parts
  if newexs!=exs:
   fields['例句JSON']=newexs;reasons.append('example_placeholder_or_lexical_unit')
  if fields:
   assert ''.join(p['hanzi'] for p in parts)==w
   assert normalized_py(' '.join(p['pinyin'] for p in parts))==normalized_py(r['拼音'])
   overlay[wid]={'headword':w,'fields':fields,'reasons':reasons}
 result={'schemaVersion':1,'sourceCsvSha256':hashlib.sha256(content).hexdigest(),'reviewedRows':len(rows) if only_ids is None else len(only_ids),'changedRows':len(overlay),'statistics':dict(stats),'rows':overlay}
 if only_ids is not None:result['lexiconRows']=len(rows)
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 summary={k:v for k,v in result.items() if k!='rows'};summary['unresolved']=unresolved
 report.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
 print(json.dumps({k:v for k,v in summary.items() if k!='unresolved'},ensure_ascii=False));print('unresolved',len(unresolved))
 return result

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--csv',type=Path,default=ROOT/'wordlist.csv')
 p.add_argument('--output',type=Path,default=DATA/'breakdown-refinement-20261027.json')
 p.add_argument('--report',type=Path,default=DATA/'breakdown-audit-20261027.json')
 p.add_argument('--only-ids',type=Path,action='append',default=[],help='Process only IDs from this overlay or ID array; repeat to combine scopes')
 a=p.parse_args();only_ids=None
 if a.only_ids:
  only_ids=set()
  for path in a.only_ids:
   selected=json.loads(path.read_text(encoding='utf-8'))
   values=selected['rows'].keys() if isinstance(selected,dict) else selected
   if not isinstance(values,(list,type({}.keys()))) or any(not isinstance(value,str) for value in values):
    raise ValueError('Breakdown scope must be an overlay or a stable-ID array')
   only_ids.update(values)
 build(a.csv,a.output,a.report,only_ids=only_ids)
