"""Prepare the 2026-10-27 headword editorial overlay, never write the live CSV.

Use pinned CC-CEDICT entries and attributed Tatoeba bilingual contexts.  The
preview is a candidate review aid, not a claim of human dictionary certification.
"""
from __future__ import annotations

import argparse
import bz2
import collections
import csv
import hashlib
import json
import re
import sys
import difflib
from pathlib import Path

import expand_vocabulary_v2 as g
import jieba
import regrade_vocabulary as pedagogical

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.gradle/wordlist-refinement-20261027'
SOURCE = WORK / 'lexical-sources'
DATA = ROOT / 'tools/vocabulary_v2'
HAN = re.compile(r'[\u4e00-\u9fff]+')
PUNCT = '。，！？：；、,.!?;: “”（）()"「」『』…—-'
BASE_SHA = '71d0a021e789e1892a12f5732bba0b704662d2d3639681ed5c087558b692028b'
CORE = set('''我你他她它您谁这那哪几一二三四五六七八九十零百千万亿两是有在不没也都很还就才更最和但而或把被比从给向让用要想会能该得了的地着过上下里外前后中东西南北人天年月日个本张只条次岁来去吃喝走跑坐站买卖读写看听说做拿放开关爱好大小多少长短高低冷热快慢新旧真假对错男女老早晚水火山海家手脚头脸嘴心血雨雪风云花草树米肉鱼鸟猫狗牛羊马鸡鸭猪钱车门床书纸笔茶盐糖油饭菜汤蛋奶酒土石光电路身耳眼牙鼻毛皮足气口刀力生死病药字名国城村春夏秋冬白黑红黄蓝绿紫金银千克斤米分秒片块根双把支件座台辆间户层瓶杯碗盘盒袋包顿回遍种名位第每各全半再又并且与因于由为到跟按同共其自另别勿请需必均仍正很太更最虽若非无未已曾常总均亦才仅曾够敢愿怕懂应能可就便真挺甚何怎啥兮'''.split()[0])
# Sentence-final particles/interjections are deliberately excluded from CORE.
CORE -= set('吧吗呢呀啊啦嘛喔哦噢呐呦哟哎嗨嘘咦哼咯喏兮')
BAD_HEADS = set('''五音之一 大写三 又好 账账 斜行延伸 唵嘛 嗨嗨 嗯嗯 好啦 好啊 青盉 三小 我靠 得了 付方 作人 我人 以律 威士 好时 比亚'''.split())
BAD_HEADS.update({'跛', '悝', '焘', '挝'})
# Only exclude these candidate strings from automatic selection; do not replace
# an existing valid word merely because its boundary is ambiguous in a corpus.
AMBIGUOUS_CANDIDATES = set('''他用 人从 人保 人相 上第 点见 家去 早来 护着 片中 代指 他性 对美 对日 对工 对生 清人 湿衣 起小 点大 点发 供过 神入 语者 讨人 色长 中的 中女 中人 家小 家子 头一 了知 得直 得看 到了 成了 结了 绝了 一言'''.split())
REPLACE_EXTRA = BAD_HEADS | set('''哎呀 哎哟 天哪 咪咪 噫嘻 呜呼 呜呼哀哉 咕咕 嘤嘤 嘻嘻 哈哈 呵呵 啧啧 哞哞 咩咩 喔喔叫 冬冬 哒哒 啪啪 啦啦 啰啰 你呢'''.split())
REPLACE_EXTRA.update(set('喔嘘噢哇哦吗哼咯咦喏呔啵嘞欤兮嘿呦啊吧呢呀啦嗨嘛耶哈哎唔哟呐咩矣哉乎唵噫咄'))
BAD_SENSE = re.compile(r'\b(?:surname|variant|phonetic|particle|interj|interjection|onom|onomatopoeia|vulgar|derog|slang|Cantonese|Taiwanese|Hokkien|transliteration|aboriginal|archaic|literary|ancient|classical|bound form|old word|old name|county|prefecture|province|district|city|dynasty)\b', re.I)
STOP = set('the and for with something somebody person thing used after before have from into without about which this that one two may can also very more most not only'.split())
ORIGINALS = r'''网线|network cable; Ethernet cable|noun|路由器和电脑之间连着一根网线。|A network cable connects the router to the computer.|这根网线太短，够不到书桌。|This network cable is too short to reach the desk.
触摸屏|touchscreen|noun|这台售票机的触摸屏没有反应。|The ticket machine's touchscreen is not responding.|请用干净的软布擦触摸屏。|Please wipe the touchscreen with a clean soft cloth.
手机壳|phone case|noun|她给新手机买了一个透明手机壳。|She bought a clear case for her new phone.|这个手机壳可以保护手机的边角。|This case protects the phone's corners.
钢化膜|tempered-glass screen protector|noun|手机摔了一下，钢化膜裂了。|The phone was dropped and its glass screen protector cracked.|贴钢化膜之前，要先擦干净屏幕。|Clean the screen before applying the glass protector.
充电头|power adapter for charging|noun|我带了数据线，却忘带充电头。|I brought the cable but forgot the power adapter.|这个充电头可以给平板电脑充电。|This power adapter can charge a tablet.
插电|to plug into a power supply|verb|电饭锅插电后，指示灯亮了。|The rice cooker's indicator lit up when it was plugged in.|这台风扇必须插电才能使用。|This fan needs to be plugged in to work.
掉线|to lose a network connection|verb|视频会议中途掉线了。|The connection dropped during the video meeting.|信号不好，游戏总是掉线。|The signal is poor and the game keeps disconnecting.
断网|to lose internet access; internet outage|verb; noun|家里断网了，我只好用手机热点。|The home internet is down, so I am using my phone's hotspot.|维修期间，这栋楼会短暂断网。|The building will briefly lose internet access during maintenance.
自动续费|automatic subscription renewal|noun|我在付款前关闭了自动续费。|I turned off automatic renewal before paying.|会员到期前，请检查自动续费设置。|Check the automatic renewal settings before the membership expires.
免密支付|payment without entering a password|noun|我给小额免密支付设置了限额。|I set a limit for small payments without a password.|开通免密支付前，请先看清使用规则。|Read the rules before enabling payments without a password.
取消订阅|to cancel a subscription|verb phrase|我不再看这份杂志，准备取消订阅。|I no longer read this magazine and plan to cancel my subscription.|取消订阅后，这个月已经付的费用不退。|The fee already paid for this month is not refunded after cancellation.
流量包|mobile data package|noun|出门前，我买了一个流量包。|I bought a mobile data package before going out.|这个流量包月底就到期了。|This data package expires at the end of the month.
热点|hotspot; point of widespread interest|noun|电脑连上了手机热点。|The computer connected to the phone's hotspot.|这件事成了大家讨论的热点。|This matter became a hot topic of discussion.
护发素|hair conditioner|noun|洗完头后，她在发梢抹了护发素。|After washing her hair, she applied conditioner to the ends.|这瓶护发素快用完了。|This bottle of conditioner is almost empty.
衣物柔顺剂|fabric softener|noun|洗毛巾时，她加了少量衣物柔顺剂。|She added a little fabric softener when washing the towels.|有些运动服不适合使用衣物柔顺剂。|Some sportswear is unsuitable for washing with fabric softener.
晾衣服|to hang clothes out to dry|verb phrase|雨停了，我到阳台上晾衣服。|The rain stopped, and I went to the balcony to hang the clothes out.|她每天洗完衣服就去晾衣服。|She hangs the clothes out after washing them each day.
叠衣服|to fold clothes|verb phrase|我们一边聊天，一边叠衣服。|We chatted while folding clothes.|孩子学会了自己叠衣服。|The child learned to fold clothes without help.
换床单|to change the bedsheet|verb phrase|周末打扫房间时，我会换床单。|I change the bedsheet when cleaning the room at the weekend.|客人退房后，服务员马上换床单。|The attendant changes the bedsheet as soon as the guest checks out.
被套|duvet cover; quilt cover|noun|这个被套有拉链，拆洗很方便。|This duvet cover has a zipper and is easy to remove for washing.|她把洗好的被套晾在阳台上。|She hung the washed duvet cover on the balcony.
沥水篮|draining basket; colander|noun|洗好的青菜放在沥水篮里。|The washed vegetables are in the draining basket.|这个沥水篮可以架在水槽上。|This draining basket can rest across the sink.
厨余垃圾|food waste; kitchen waste|noun|剩饭和菜叶属于厨余垃圾。|Leftover rice and vegetable leaves are food waste.|倒厨余垃圾时，请把塑料袋单独拿出来。|Separate the plastic bag when disposing of food waste.
外卖盒|takeaway food container|noun|吃完午饭后，他把外卖盒洗干净了。|After lunch, he washed the takeaway container.|这个外卖盒盖不紧，汤漏出来了。|This takeaway container does not close tightly, and the soup leaked.
自取|to collect an order oneself|verb|这家店支持到店自取。|This shop offers in-store pickup.|我选了自取，不需要送货上门。|I chose to collect the order myself and do not need home delivery.
起步价|starting fare; minimum charge|noun|这座城市的出租车起步价是多少？|What is the starting taxi fare in this city?|夜间打车的起步价比白天高。|The starting taxi fare is higher at night than during the day.
水电费|water and electricity bills|noun|房租不包括水电费。|The rent does not include water and electricity bills.|我们每个月按时交水电费。|We pay our water and electricity bills on time each month.
免邮|free shipping; free of postage|verb; adjective|这家网店满一百元免邮。|This online shop offers free shipping on orders over one hundred yuan.|偏远地区可能不参加免邮活动。|Remote areas may be excluded from the free-shipping offer.
差评|negative review|noun|因为送错了货，买家给了差评。|The buyer left a negative review because the wrong item was delivered.|店员认真回复了这条差评。|The staff member carefully replied to this negative review.
好评|positive review|noun|这家小店的服务得到了很多好评。|This little shop has received many positive reviews for its service.|她收到商品后，写了一条好评。|She wrote a positive review after receiving the item.
订单号|order number|noun|联系客服时，请提供订单号。|Please provide the order number when contacting customer service.|我把订单号复制到了备忘录里。|I copied the order number into my notes.
包装盒|packaging box|noun|退货时，请保留原来的包装盒。|Please keep the original packaging box for a return.|这个包装盒可以用来收纳小东西。|This packaging box can be used to store small items.
水杯|drinking cup; water bottle|noun|上学前，孩子把水杯装进书包。|The child put the water bottle in the schoolbag before school.|我的水杯落在会议室了。|I left my drinking cup in the meeting room.
烧开水|to boil water|verb phrase|泡茶之前，要先烧开水。|Boil some water before making tea.|他正在厨房里烧开水。|He is boiling water in the kitchen.
擦桌子|to wipe a table|verb phrase|吃完饭后，我负责擦桌子。|I am responsible for wiping the table after meals.|请用这块干净的抹布擦桌子。|Please wipe the table with this clean cloth.
蚊帐|mosquito net|noun|夏天睡觉时，我们把蚊帐放下来。|We lower the mosquito net when sleeping in summer.|这顶蚊帐有一个小洞。|This mosquito net has a small hole.
灭蚊器|mosquito-killing device|noun|夏天店里会打开灭蚊器。|The shop turns on its mosquito-killing device in summer.|清理灭蚊器之前，请先断电。|Disconnect the power before cleaning the mosquito-killing device.
冲牙器|oral irrigator; water flosser|noun|她给冲牙器的水箱加了温水。|She filled the water flosser's tank with warm water.|这个冲牙器可以调节水压。|The water pressure of this oral irrigator is adjustable.
刮胡子|to shave one's beard|verb phrase|他每天早上洗脸后刮胡子。|He shaves every morning after washing his face.|出门前，他花了几分钟刮胡子。|He spent a few minutes shaving before going out.
开窗|to open a window|verb phrase|屋里有点闷，请开窗通风。|It is a little stuffy inside; please open a window for fresh air.|外面正在下大雨，先别开窗。|It is raining heavily outside, so do not open the window yet.
防潮|to protect against moisture; moisture-resistant|verb; adjective|饼干盒里放了干燥剂，可以防潮。|There is a desiccant in the biscuit tin to keep out moisture.|地下室存放书籍要注意防潮。|Books stored in the basement need protection from moisture.
防霉|to prevent mold; mold-resistant|verb; adjective|浴室通风有助于防霉。|Ventilating the bathroom helps prevent mold.|这款涂料有防霉功能。|This paint is mold-resistant.
油渍|grease stain; oil stain|noun|围裙上有一块油渍。|There is a grease stain on the apron.|这瓶清洁剂可以去除厨房油渍。|This cleaner removes greasy stains in the kitchen.
水渍|water stain; water mark|noun|杯子下面留下了一圈水渍。|The cup left a ring-shaped water mark underneath it.|用软布把镜子上的水渍擦掉。|Wipe the water marks off the mirror with a soft cloth.
皮筋|elastic band; hair tie|noun|她用皮筋把头发扎起来。|She tied her hair up with a hair tie.|这根皮筋太松，扎不紧头发。|This hair tie is too loose to hold the hair firmly.
发夹|hair clip|noun|她用发夹夹住了额前的碎发。|She held her fringe back with a hair clip.|我在沙发下面找到了丢失的发夹。|I found the missing hair clip under the sofa.
透明胶|transparent adhesive tape|noun|请用透明胶把纸粘好。|Please stick the paper together with transparent tape.|这卷透明胶快用完了。|This roll of transparent tape is almost finished.
卷尺|tape measure|noun|他用卷尺量了门框的宽度。|He measured the doorframe's width with a tape measure.|买窗帘前，先找一把卷尺量尺寸。|Find a tape measure and take measurements before buying curtains.
扎带|cable tie|noun|他用扎带把几根电线捆在一起。|He bundled several wires together with a cable tie.|拆开这个扎带需要用剪刀。|Scissors are needed to cut this cable tie open.
安全别针|safety pin|noun|她用安全别针临时固定了裙边。|She temporarily secured the skirt's hem with a safety pin.|这个安全别针的尖头藏在扣里。|The sharp point of this safety pin is enclosed by its clasp.
指甲剪|nail clippers|noun|这个指甲剪放在抽屉里。|The nail clippers are in the drawer.|请把用过的指甲剪擦干净。|Please clean the nail clippers after using them.
削皮器|vegetable peeler|noun|她用削皮器给土豆削皮。|She peeled the potatoes with a vegetable peeler.|这个削皮器的刀片已经钝了。|The blade of this vegetable peeler has become blunt.
橡胶手套|rubber gloves|noun|洗碗时，她戴着橡胶手套。|She wears rubber gloves when washing dishes.|这副橡胶手套破了一个洞。|There is a hole in this pair of rubber gloves.
吊柜|wall-mounted cupboard|noun|常用的杯子放在厨房吊柜里。|The cups used every day are in the kitchen wall cupboard.|安装吊柜时，要固定好墙上的支架。|Secure the wall brackets when installing the wall cupboard.
沙发套|sofa cover|noun|这个沙发套可以拆下来清洗。|This sofa cover can be removed for washing.|她换上了一个浅灰色的沙发套。|She put a light gray cover on the sofa.
开锁|to unlock; to open a lock|verb|我找不到钥匙，只好请师傅开锁。|I could not find the key and had to call a locksmith.|这把钥匙只能开锁，不能启动汽车。|This key can unlock the car but cannot start it.
换锁|to replace a lock|verb|搬进新家后，我们决定换锁。|We decided to replace the lock after moving into our new home.|房东明天会来给大门换锁。|The landlord will come tomorrow to replace the front-door lock.
快递站|parcel collection point|noun|我的包裹已经送到小区快递站了。|My parcel has arrived at the neighborhood collection point.|取件前，请先查看快递站的营业时间。|Check the parcel collection point's opening hours before collecting your delivery.
早餐店|breakfast shop|noun|这家早餐店早上六点就开门。|This breakfast shop opens at six in the morning.|我在楼下的早餐店买了豆浆和包子。|I bought soy milk and steamed buns at the breakfast shop downstairs.
小吃摊|street-food stall|noun|夜市里有很多小吃摊。|There are many street-food stalls at the night market.|这家小吃摊的烤红薯很受欢迎。|The baked sweet potatoes at this food stall are very popular.
续杯|to refill a drink; refill|verb; noun|这杯咖啡可以免费续杯。|This coffee comes with free refills.|想要续杯的话，请把杯子递给店员。|If you would like a refill, hand your cup to the staff member.
打饭|to get a serving of food at a canteen|verb|同学们在食堂排队打饭。|The students line up to get food at the canteen.|打饭时，请告诉师傅你要多少。|Tell the server how much food you want when getting your meal.
盛饭|to serve rice; to fill a bowl with rice|verb|她给孩子盛饭，自己还没有吃。|She served the child rice before eating herself.|请用这个饭勺盛饭。|Please use this rice paddle to serve the rice.
半糖|half the usual amount of sugar in a drink|noun|这杯奶茶我要半糖。|I would like this milk tea with half the usual sugar.|她点了一杯半糖的柠檬茶。|She ordered a lemon tea with half the usual sugar.
少冰|less ice in a drink|noun phrase|我的奶茶要少冰，谢谢。|Less ice in my milk tea, please.|天气有点凉，她选了少冰。|The weather was a little cool, so she chose less ice.
信用额度|credit limit|noun|这张信用卡的信用额度不高。|This credit card does not have a high credit limit.|临时提高信用额度之前，要考虑还款能力。|Consider your ability to repay before raising your credit limit temporarily.
还款日|repayment due date|noun|请在还款日之前还清账单。|Please pay the bill in full before the repayment due date.|我把每个月的还款日记在日历上。|I note each month's repayment due date on the calendar.
账单日|statement date; billing date|noun|这张信用卡的账单日是每月五号。|The statement date for this credit card is the fifth of each month.|账单日之后的消费会计入下期账单。|Purchases after the statement date appear on the next statement.
余额不足|insufficient balance|expression|付款时，手机提示余额不足。|When I tried to pay, my phone showed an insufficient-balance message.|因为余额不足，这笔转账没有成功。|This transfer failed because the balance was insufficient.
充话费|to top up mobile phone credit|verb phrase|手机快停机了，我得先充话费。|My mobile service will soon be suspended; I need to top up my phone credit.|她每个月都会给父母充话费。|She tops up her parents' mobile phone credit each month.
补办卡|to obtain a replacement card|verb phrase|银行卡丢了，我要去银行补办卡。|I lost my bank card and need to get a replacement from the bank.|补办卡时，请带上身份证。|Bring your identity card when applying for a replacement card.'''
AUTHORED_DAILY_WORDS = {line.split('|', 1)[0] for line in ORIGINALS.splitlines()}
CORRECTED_EXAMPLES = r'''一阵子|a while; a period of time|time expression|雨太大，我们等一阵子再走。|The rain is too heavy; let us wait a while before leaving.|她在乡下住了一阵子。|She lived in the countryside for a while.
老城|old town; old part of a city|noun|这座老城保留着许多传统民居。|This old town retains many traditional houses.|周末，我们到老城的小巷里散步。|We walked through the old town's lanes at the weekend.
强烈反对|to strongly oppose|verb phrase|居民强烈反对在公园里修建停车场。|The residents strongly oppose building a car park in the park.|她强烈反对这种浪费食物的做法。|She strongly opposes this wasteful treatment of food.
雇员|employee|noun|这家公司有二十名雇员。|This company has twenty employees.|老板认真听取了雇员的意见。|The boss listened carefully to the employees' views.
只读|read-only, in computing|adjective|这个文件是只读的，不能直接修改。|This file is read-only and cannot be edited directly.|请先取消只读属性，再保存修改。|Remove the read-only attribute before saving the changes.
和衣|with one's clothes still on|adverbial phrase|他太累了，和衣躺在床上睡着了。|He was so tired that he lay on the bed and fell asleep with his clothes on.|旅途劳累，她回到房间就和衣而卧。|Tired from the journey, she lay down without undressing as soon as she returned to her room.
来意|purpose of one's visit|noun|客人说明了自己的来意。|The visitor explained the purpose of his visit.|请先告诉我你的来意。|Please tell me why you have come first.
用不了|to be unable to use; to need less than a stated amount|verb phrase|这个软件太旧，新电脑用不了。|This software is too old to run on the new computer.|从这里走到车站，用不了十分钟。|It takes less than ten minutes to walk from here to the station.
记不住|to be unable to remember|verb phrase|这个号码太长，我记不住。|This number is too long for me to remember.|如果记不住地址，就先写下来。|If you cannot remember the address, write it down first.
遥不可及|far out of reach; unattainable|adjective|对当时的他来说，大学似乎遥不可及。|For him at that time, university seemed far out of reach.|山顶看似遥不可及，我们还是决定继续攀登。|The summit seemed far out of reach, but we decided to keep climbing.
独裁|dictatorship; dictatorial rule|noun|这本书分析了独裁对社会的影响。|This book analyzes the effects of dictatorship on society.|人民希望结束独裁，建立民主制度。|The people hoped to end dictatorial rule and establish a democratic system.
反锁|to lock a door from the inside|verb|他把房门反锁，一个人在里面休息。|He locked the room from the inside and rested alone.|浴室的门反锁了，你得等一会儿。|The bathroom door is locked from the inside, so you will have to wait a moment.
赤字|deficit|noun|今年的财政赤字比去年小。|This year's fiscal deficit is smaller than last year's.|政府正在讨论如何减少预算赤字。|The government is discussing how to reduce the budget deficit.
永恒|eternal; everlasting; eternity|adjective; noun|诗人把爱情描写成永恒的主题。|The poet describes love as an eternal theme.|这座雕像寄托着人们对和平的永恒追求。|This statue represents people's eternal pursuit of peace.
活下来|to survive; to stay alive|verb phrase|受伤的小鸟在大家的照顾下活下来了。|The injured bird survived with everyone's care.|在那次事故中，他幸运地活下来了。|He was fortunate to survive that accident.'''
CORRECTED_EXAMPLES += r'''
一条龙|one-stop; fully integrated service|adjective|这家公司提供从设计到安装的一条龙服务。|This company provides a complete service from design to installation.|这里可以办完所有手续，享受一条龙服务。|All the formalities can be completed here through a one-stop service.
爱人|spouse; husband or wife|noun|我的爱人在医院工作。|My spouse works at a hospital.|他陪爱人一起去看望父母。|He went with his spouse to visit their parents.
读数|reading on a measuring instrument|noun|请记录温度计上的读数。|Please record the reading on the thermometer.|关掉水龙头后，水表的读数不再变化。|The water meter's reading stopped changing after the tap was turned off.
倒霉|unlucky; to have bad luck|adjective; verb|今天真倒霉，刚出门就下大雨。|I had bad luck today; it started pouring as soon as I went out.|他最近很倒霉，先丢钱包又丢钥匙。|He has been unlucky lately, first losing his wallet and then his keys.'''
CORRECTED_EXAMPLES += r'''
杏树|apricot tree|noun|院子里的杏树开花了。|The apricot tree in the yard has blossomed.|我们在杏树下休息。|We are resting under the apricot tree.
环顾|to look around|verb|她环顾了一下四周。|She looked all around.|她环顾教室，发现同学们都到齐了。|She looked around the classroom and saw that all her classmates had arrived.'''
GLOSS_REVIEW = {
    '赤字': 'deficit', '永恒': 'eternal; everlasting; eternity',
    '反锁': 'to lock a door from the inside', '一句话': 'a sentence; a single remark',
    '正当': 'just as; at the very time when', '金融': 'finance; banking',
    '一条龙': 'one-stop; fully integrated service', '纸质': 'paper-based; made of paper',
    '皇室': 'royal family; royal household', '水准': 'standard; level of quality or ability',
    '保龄球': 'bowling; bowling ball', '危及': 'to endanger; to jeopardize',
    '旗袍': 'cheongsam; qipao; a close-fitting traditional Chinese dress',
    '小聪明': 'petty cleverness; superficial cunning',
    '时差': 'time difference between time zones; jet lag',
    '经典': 'classic; classics; classical', '共和制': 'republican system; republican form of government',
    '布谷鸟': 'cuckoo', '夜来香': 'night-blooming jasmine', '考试卷': 'exam paper',
    '恐龙': 'dinosaur', '卷心菜': 'cabbage', '高丽菜': 'cabbage (Taiwan usage)',
    '望远镜': 'telescope; binoculars', '航空母舰': 'aircraft carrier',
    '魔法': 'magic; enchantment', '同胞': 'fellow countryman; compatriot; sibling',
    '动力': 'motive power; motivation; impetus', '回报': 'repayment; return; to repay',
    '代名词': 'synonym; byword; a symbol for something',
    '放射性': 'radioactivity; radioactive', '海平面': 'sea level',
    '嗓门': 'voice; volume of the voice', '空话': 'empty talk',
    '负有责任': 'to bear responsibility; to be accountable',
    '正餐': 'full meal; main meal', '生字': 'new or unfamiliar written character or word',
    '超级': 'super; extremely', '说不定': 'perhaps; maybe; not certain',
    '干脆': 'simply; might as well; straightforward; decisive',
    '阴谋': 'conspiracy; plot', '非得': 'must; to have to',
    '传入': 'to be introduced into; to spread into; to be transmitted into',
}
READING_REVIEW = {'还款日': 'huán kuǎn rì', '晾衣服': 'liàng yī fu', '叠衣服': 'dié yī fu'}
SOURCE_TEXT_REVIEW = {
    '这个男孩把收音机折散架了。': '这个男孩把收音机拆散架了。',
    '所有史蒂夫的工作不是无聊就是非高薪或是两者兼而有之。': '史蒂夫做过的工作要么无聊，要么薪水不高，要么两者都有。',
    '在北京，胡同是由四合院的传统居所排形成。': '在北京，胡同是由一排排四合院围成的小巷。',
    '学校的樱花开的烂漫，吸引了大量市民前来观赏。': '学校的樱花开得烂漫，吸引了大量市民前来观赏。',
    '如果你想变瘦，就应该少点在正餐以外吃零食。': '如果你想变瘦，就应该在正餐之外少吃零食。',
    '连一个云朵都没有。': '看不到云朵。',
    '他们主要输出的产品是纺织品，尤其以丝绸和绵为主。': '他们主要输出的产品是纺织品，尤其以丝绸和棉为主。',
}


def calibrate_rarities(overlay, originals, authored_daily):
    hsk, frequency, lexical, source_hashes, core_characters = pedagogical.read_signals()
    basic_daily = set('手机壳 网线 水杯 晾衣服 叠衣服 换床单 被套 外卖盒 自取 水电费 包装盒 烧开水 擦桌子 刮胡子 开窗 发夹 皮筋 开锁 换锁 早餐店 小吃摊 续杯 打饭 盛饭 半糖 少冰 充话费 云朵'.split())
    specific = {'婴儿车': 1, '冷气': 1, '反锁': 1, '各式各样': 1, '云朵': 0}
    original_by_id = {row['词条ID']: row for row in originals}
    for wid, record in overlay.items():
        row = dict(record['fields'], 词条ID=wid)
        value, rule, reason, signals = pedagogical.decide(row, hsk, frequency, lexical, {}, core_characters, {})
        new_value = max(value - 1, 0)
        word = record['newHanzi']
        if word in authored_daily:
            new_value = record['suggestedRarity']
            rule, reason = 'daily-editorial', '日常实际使用与书写复杂度逐词修订'
        if word in basic_daily:
            new_value = 0
            rule, reason = 'basic-daily-editorial', '基础物品、动作或生活场景，要求能背能写'
        if word in specific:
            new_value = specific[word]
            rule, reason = 'sense-daily-editorial', '按实际日用场景和识写难度修订，不由字幕缺频上抬'
        if int(original_by_id[wid]['罕度']) == 0:
            new_value = 0
            rule, reason = 'preserve-original-zero', '按用户要求，原0的稳定ID保持0'
        record['suggestedRarity'] = new_value
        record['rarityReason'] = reason + '；六档内容规则/HSK/现代分词词频重新评估后映回五档，未使用旧ID可选/编辑名单'
        record['rarityRule'] = rule
        record['raritySignals'] = signals
    return source_hashes


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8')


def tokenize_english(s):
    result = set()
    for w in re.findall('[a-z]{3,}', s.lower()):
        if w in STOP:
            continue
        if w.endswith('ies'):
            w = w[:-3] + 'y'
        elif w.endswith('ing'):
            w = w[:-3].rstrip('e')
        elif w.endswith('ed'):
            w = w[:-2].rstrip('e')
        elif w.endswith('s'):
            w = w[:-1]
        result.add(w)
    return result


def prepare():
    current = ROOT / 'wordlist.csv'
    rows = list(csv.DictReader(current.open(encoding='utf-8-sig')))
    if hashlib.sha256(current.read_bytes()).hexdigest() != BASE_SHA:
        backup = WORK / 'input.csv'
        if not backup.exists() or hashlib.sha256(backup.read_bytes()).hexdigest() != BASE_SHA:
            raise ValueError('Headword input changed; provide the exact pre-edit snapshot.')
        rows = list(csv.DictReader(backup.open(encoding='utf-8-sig')))
    _, freq, lex = g.read_sources()
    existing = {r['组词'] for r in rows}
    proper = set()
    for line in (g.CACHE / 'cedict.txt').read_text(encoding='utf-8-sig').splitlines():
        m = re.match(r'^(\S+) (\S+) \[([^\]]+)\]', line)
        if m and any(x[0].isupper() for x in m[3].split()):
            proper.add(m[2])
    candidates = {}
    for word, entries in lex.items():
        if not 2 <= len(word) <= 6 or word in existing or word in proper or word in BAD_HEADS or word in AMBIGUOUS_CANDIDATES:
            continue
        senses = [s for s in entries[0]['senses'] if not BAD_SENSE.search(s)]
        if not senses or re.search(r'\b(?:see|CL|abbr|abbr\.|same as|used in)\b', senses[0]):
            continue
        candidates[word] = dict(entries[0], senses=senses)
    raw = collections.defaultdict(dict)
    chunks = {}
    for r in rows:
        for p in json.loads(r['部件JSON']):
            chunks.setdefault(p['hanzi'], (p['pinyin'], p['gloss']))
        chunks[r['组词']] = (r['拼音'], r['英文释义'])
    for word, entries in lex.items():
        chunks.setdefault(word, (entries[0]['pinyin'], entries[0]['senses'][0]))
    for word, value in g.GRAMMAR.items():
        chunks[word] = value[:2]

    def add(text, english, source, example=None, source_meta=None):
        if not 5 <= len(text) <= 64 or len(english) > 240:
            return
        if not re.fullmatch(r'[\u4e00-\u9fff。，！？：；、,.!?;: “”（）()「」『』…—\-]+', text):
            return
        found = set()
        for i in range(len(text)):
            for n in range(2, min(6, len(text) - i) + 1):
                word = text[i:i+n]
                if word in candidates:
                    found.add(word)
        for word in found:
            if len(raw[word]) < 24:
                raw[word][text] = {'hanzi': text, 'english': english, 'source': source,
                                   'example': example, 'sourceMeta': source_meta}

    provenance = json.loads((DATA / 'example-provenance.json').read_text(encoding='utf8'))
    for r in rows:
        for ex in json.loads(r['例句JSON']):
            matching = next((p for p in provenance.get(r['词条ID'], []) if p['hanzi'] == ex['hanzi']), None)
            source = matching['source'] if matching else 'legacy:' + r['词条ID']
            original = matching.get('originalHanzi') if matching else None
            add(ex['hanzi'], ex['english'], source, ex,
                {'originalHanzi': original} if original else None)
    convert = g.simplified_context_converter()
    for line in (g.CACHE / 'cmn.txt').read_text(encoding='utf-8-sig').splitlines():
        fields = line.split('\t')
        if len(fields) < 3:
            continue
        en, zh, attribution = fields[:3]
        add(convert(zh), en, attribution)
    if not QUICK:
        lock = json.loads((DATA / 'source-lock-refinement.json').read_text(encoding='utf8'))
        for item in lock['sources']:
            p = ROOT / item['file']
            if p.stat().st_size != item['bytes'] or hashlib.sha256(p.read_bytes()).hexdigest() != item['sha256']:
                raise ValueError('Pinned source changed: ' + str(p))
    links = collections.defaultdict(list)
    for line in bz2.open(SOURCE / 'cmn-eng_links.tsv.bz2', 'rt', encoding='utf8'):
        a, b = line.rstrip('\n').split('\t')
        links[a].append(b)
    wanted = {x for xs in links.values() for x in xs}
    english_rows = {}
    if QUICK:
        for line in (g.CACHE / 'cmn.txt').read_text(encoding='utf-8-sig').splitlines():
            bits = line.split('\t')
            match = re.search(r'tatoeba.org #(\d+) \(([^)]+)\)', bits[2])
            if match:
                english_rows[match[1]] = [match[1], 'eng', bits[0], match[2]]
    else:
        english_file = SOURCE / 'eng_sentences_detailed.parallel.tsv.bz2'
        if not english_file.exists():
            english_file = SOURCE / 'eng_sentences_detailed.tsv.bz2'
        for line in bz2.open(english_file, 'rt', encoding='utf8'):
            bits = line.rstrip('\n').split('\t')
            if bits[0] in wanted:
                english_rows[bits[0]] = bits
    corpus_pairs = 0
    for line in bz2.open(SOURCE / 'cmn_sentences_detailed.tsv.bz2', 'rt', encoding='utf8'):
        bits = line.rstrip('\n').split('\t')
        if bits[0] not in links:
            continue
        for eid in links[bits[0]]:
            en = english_rows.get(eid)
            if not en:
                continue
            source = f'CC-BY 2.0 (France) Attribution: tatoeba.org #{en[0]} ({en[3]}) & #{bits[0]} ({bits[3]})'
            simplified = convert(bits[2])
            meta = {'chineseId': bits[0], 'chineseAuthor': bits[3],
                    'englishId': en[0], 'englishAuthor': en[3], 'export': '2026-10-24'}
            if simplified != bits[2]:
                meta['originalHanzi'] = bits[2]
            add(simplified, en[2], source, source_meta=meta)
            corpus_pairs += 1
    eligible = []
    for word, entry in candidates.items():
        if len(raw[word]) < 2:
            continue
        gloss = '; '.join(entry['senses'][:3])
        tokens = tokenize_english(gloss)
        values = sorted(raw[word].values(), key=lambda v: (-len(tokens & tokenize_english(v['english'])), len(v['hanzi'])))
        overlap = sum(bool(tokens & tokenize_english(v['english'])) for v in values)
        eligible.append({'word': word, 'pinyin': entry['pinyin'], 'senses': entry['senses'],
                         'frequencyRank': freq.get(word, (None, 0))[0], 'contextCount': len(values),
                         'overlapCount': overlap, 'contexts': values})
    eligible.sort(key=lambda v: (v['frequencyRank'] is None, v['frequencyRank'] or 999999, len(v['word']), v['word']))
    dump(WORK / 'headword-candidate-preview.json', {'corpusPairs': corpus_pairs, 'eligible': eligible})
    print(json.dumps({'corpusPairs': corpus_pairs, 'eligible': len(eligible), 'overlapAtLeastTwo': sum(x['overlapCount'] >= 2 for x in eligible)}, ensure_ascii=False))
    print(' '.join(x['word'] for x in eligible))


def build():
    rows = list(csv.DictReader((WORK / 'input.csv').open(encoding='utf-8-sig')))
    existing = {r['组词'] for r in rows}
    _, freq, lex = g.read_sources()
    segmenter = jieba.Tokenizer()
    segmenter.cache_file = str(WORK / 'jieba-headword-review.cache')
    segmenter.initialize()
    fixed_pos = {}
    for line in (ROOT / '.gradle/chinese-resource-source/python/jieba/dict.txt').read_text(encoding='utf8').splitlines():
        bits = line.split()
        if len(bits) == 3:
            fixed_pos[bits[0]] = bits[2]
    chunks = {}
    char_entries = collections.defaultdict(dict)
    for word, entries in lex.items():
        if word in BAD_HEADS:
            continue
        chunks[word] = (entries[0]['pinyin'], entries[0]['senses'][0])
        if len(word) == 1:
            for entry in entries:
                char_entries[word][entry['pinyin']] = entry['senses'][0]
    for row in rows:
        if row['组词'] not in BAD_HEADS:
            chunks[row['组词']] = (row['拼音'], row['英文释义'])
    for word, value in g.GRAMMAR.items():
        chunks[word] = value[:2]

    def parts(text, force=None):
        result = []
        offset = 0
        prefix = ''
        while offset < len(text):
            if not HAN.fullmatch(text[offset]):
                if result:
                    result[-1]['hanzi'] += text[offset]
                    result[-1]['pinyin'] += {'。': '.', '，': ',', '！': '!', '？': '?', '：': ':', '；': ';', '、': ','}.get(text[offset], '')
                else:
                    prefix += text[offset]
                offset += 1
                continue
            if force and text.startswith(force, offset):
                word = force
            else:
                following = text.find(force, offset+1) if force else -1
                limit = min(8, len(text)-offset, following-offset if following >= 0 else 8)
                word = next((text[offset:offset+n] for n in range(limit, 0, -1)
                             if text[offset:offset+n] in chunks and HAN.fullmatch(text[offset:offset+n])), text[offset])
            reading, gloss = chunks.get(word, (' '.join(g.lazy_pinyin(word, style=g.Style.TONE)), ''))
            following = text[offset+len(word):]
            if word == '量' and re.match(r'(?:了|过|着|尺寸|身高|体温|一)', following):
                reading, gloss = 'liáng', 'to measure'
            if word == '得' and offset > 0 and text[offset-1] in '我你他她咱您都还':
                reading, gloss = 'děi', 'must; to have to'
            if word == '衣服':
                reading = 'yī fu'
            result.append({'hanzi': prefix + word, 'pinyin': reading, 'gloss': g.clean_definition(gloss)[:100] or 'character in this expression'})
            prefix = ''
            offset += len(word)
        return result

    def exact_boundary(word, context):
        text, english = context['hanzi'], context['english']
        if '什幺' in text:
            return False
        if '走' in text and re.search(r'\bwait\b', english, re.I) and not re.search(r'\b(?:walk|go|went|leave|left|run|step|travel)\b', english, re.I):
            return False
        if re.search(r'[一二三四五六七八九十百]+分之[一二三四五六七八九十百]+', text):
            # The legacy "two thirds"/二分之三 pair is reversed; do not import
            # corpus fraction examples without a separate numerical review.
            return False
        starts, ends, offset = set(), set(), 0
        for token in segmenter.lcut(text, HMM=False):
            starts.add(offset)
            offset += len(token)
            ends.add(offset)
        if not any(m.start() in starts and m.end() in ends for m in re.finditer(re.escape(word), text)):
            return False
        original = context.get('example')
        context_parts = original['chunks'] if original else parts(text)
        return any(p['hanzi'].strip(PUNCT) == word for p in context_parts)

    def pos_for(word, gloss):
        special = {'因此': 'conjunction', '于是': 'conjunction', '以及': 'conjunction', '此外': 'conjunction',
                   '之所以': 'conjunction', '不但': 'conjunction', '要不然': 'conjunction', '哪怕': 'conjunction',
                   '事实上': 'adverbial phrase', '基本上': 'adverbial phrase', '总之': 'conjunction',
                   '没错': 'expression', '早上好': 'greeting', '早安': 'greeting', '晚上好': 'greeting',
                   '一会儿': 'time expression', '会儿': 'time expression', '一周': 'time expression',
                   '一阵子': 'time expression', '星期几': 'interrogative phrase', '用不着': 'verb phrase',
                   '如约而至': 'idiom', '对我来说': 'prepositional phrase', '不管怎样': 'adverbial phrase',
                   '一代': 'noun', '有点儿': 'adverbial phrase', '自我介绍': 'verb phrase'}
        if word in special:
            return special[word]
        flag = fixed_pos.get(word, '')
        if gloss.startswith('to '):
            return 'verb phrase' if len(word) >= 4 else 'verb'
        if '(idiom)' in gloss or '(proverb)' in gloss:
            return 'idiom'
        if flag:
            if flag.startswith(('nr', 'ns', 'nt', 'nz')):
                return 'proper noun' if flag.startswith(('nr', 'ns')) else 'noun'
            if flag.startswith('n'):
                return 'noun'
            if flag.startswith('v'):
                return 'verb'
            if flag.startswith(('a', 'z')):
                return 'adjective'
            if flag == 'd':
                return 'adverb'
            if flag == 'c':
                return 'conjunction'
            if flag == 'p':
                return 'preposition'
            if flag.startswith('r'):
                return 'pronoun'
            if flag == 'm':
                return 'numeral'
            if flag == 'q':
                return 'classifier'
            if flag == 't':
                return 'time expression'
            if flag in {'s', 'f'}:
                return 'localizer'
            if flag in {'i', 'l'}:
                return 'idiom' if flag == 'i' else 'expression'
        if re.match(r'(?:very|extremely|often|always|usually|already|suddenly|gradually|generally|mostly|mainly|particularly|especially|almost|perhaps|probably|merely|only|nearly|moreover|thus|therefore|consequently|instead|finally|originally|previously|immediately|currently|frequently|occasionally|meanwhile|hence|simply|clearly|surely|really|rather|quite)\b', gloss, re.I):
            return 'adverb'
        if re.match(r'(?:not |no |unable|impossible|unnecessary|useful|useless|difficult|easy|common|usual|unusual|rare|strange|ordinary|normal|abnormal|private|public|modern|ancient|traditional|beautiful|ugly|expensive|cheap|different|same|similar|possible|likely|unlikely|strong|weak|rich|poor|happy|sad|kind|friendly|clever|smart|intelligent|foolish|honest|warm|cold|hot|cool|new|old|good|bad|long|short|deep|shallow|wide|narrow|large|small|big|little|high|low|tall|short|light|heavy|bright|dark|quiet|loud|clean|dirty|safe|dangerous|important|significant|necessary|essential|sufficient|insufficient|available|free|busy|full|empty|thick|thin|hard|soft|dry|wet|fresh|stale|loose|tight|flat|round|straight|crooked)\b', gloss, re.I):
            return 'adjective'
        if word.startswith(('不', '没', '有')) and len(word) >= 3:
            return 'verb phrase'
        if len(word) >= 4 and any(x in gloss for x in ('(fig.)', '(fig)', 'fig.')):
            return 'idiom'
        return 'noun'

    # These strings are lexically attested but unsuitable for automatic reuse:
    # substring ambiguities, conversational particles, insults, and a heavy
    # concentration of violent subtitle vocabulary.
    rejected = set('''他妈的 该死 混蛋 老天 算了 见鬼 疯子 做爱 搞砸 废话 呵呵 变态 完蛋 杂种 性交 胡扯 坏蛋 胡说八道 放屁 拉里 阴茎 下流 裸体 色情 拉屎 小便 鸡巴 口交 妓院 性生活 性关系 瘾君子 鸡鸡 自慰 小屁孩 丁字裤 炮友 女同 你妈 处男 天啊 奶奶的 我的天 咕咕叫 不是吗 哗啦一声 只得 总能 各国 国界 我国 他国 本国 两样 另一 头儿 味儿 份儿 今儿 玩儿 活儿 一员 有种 会长 一号 一号 上将 加点 机关 在内 的士 命中 说好 了结 此地 人手 没准 要钱 此时 一一 却是 一发 头上 眼中 怀里 片时 的款 多士 该当 说上 上覆 次经 次长 二元 体形 前摆 后感 居经 力工 人种 血刃 释手 观止 错综 鼎沸 鳞伤 美石 骒马 骣骑 补裰 贝多 銮驾 神魂 纹丝 级联 统建 续增 起小 走你 走相 跟人 取水 与门 具格 格勒 静点 顶盘 长三 长开 过生 家公 了当 了局 得可以 神入 业者 视作 以北 平局 顶上 族群 课室 便是 到头 报上 出新 生就 望见 来神 国小 名记 西西 这就 那是 在下 这不 看上 对了 小的 上文 上月 下半 上分 上心 为上 耐烦 绝地 要不 要有 要看 要得 要着 上场 力士 岔子 语支 语族 语系 语流 岁月 花子 法兰 阿尔卑斯 拉夫 大雄 萨斯 萨克斯 法拉 哈拉 巴巴 会门 作下 作手 田方 移来 内能 位能 能事 力保 上新 国菜 百兽 自相 自始 自言 自语 了无 碑记 洋装 话亭 先自 次于 荡荡 细细 许下 出处 下令 开枪 杀手 子弹 炸弹 杀害 谋杀案 射杀 枪击 步枪 手枪 原子弹 世界大战 大战 炸药 武力 海军 军人 部下 军事 长官 帝国 嫌疑犯 绑匪 强奸犯 杀人犯 杀伤 迫害 血腥 虐杀 上吊 吊死 战死 仇杀 炮火 通缉 指挥官 共产主义 社会主义 资本主义 法西斯 无期徒刑 奴隶制 同性恋者 性行为 同性恋 跨性别 女权主义 自由意志主义 毒害 口角 哈里发'''.split())
    preview = json.loads((WORK / 'headword-candidate-preview.json').read_text(encoding='utf8'))
    selected = []
    filtered = []
    single_overlap_review = []
    for item in preview['eligible']:
        word = item['word']
        if word in rejected or word in BAD_HEADS or word in AMBIGUOUS_CANDIDATES:
            continue
        senses = item['senses'][:3]
        # A shared English token is only a conservative candidate gate; exact
        # Chinese chunk boundaries are independently required below.
        sense_scores = []
        for sense in senses:
            tokens = tokenize_english(sense)
            contexts = [c for c in item['contexts'] if exact_boundary(word, c)]
            contexts.sort(key=lambda c: (-len(tokens & tokenize_english(c['english'])), len(c['hanzi'])))
            scored = [(c, len(tokens & tokenize_english(c['english']))) for c in contexts]
            hits = [c for c, n in scored if n > 0]
            if len(hits) >= 2:
                first = hits[0]
                second = next((c for c in hits[1:] if difflib.SequenceMatcher(None,
                    ''.join(HAN.findall(first['hanzi'])), ''.join(HAN.findall(c['hanzi']))).ratio() < .80), None)
                if second:
                    sense_scores.append((sum(n for _, n in scored[:2]), sense, [first, second]))
        if not sense_scores:
            filtered.append(word)
            contexts = [c for c in item['contexts'] if exact_boundary(word, c)]
            if len(contexts) >= 2:
                tokens = tokenize_english('; '.join(senses))
                contexts.sort(key=lambda c: (-len(tokens & tokenize_english(c['english'])), len(c['hanzi'])))
                if tokens & tokenize_english(contexts[0]['english']):
                    single_overlap_review.append({'word': word, 'pinyin': item['pinyin'], 'senses': senses, 'contexts': contexts, 'frequencyRank': item['frequencyRank']})
            continue
        sense_scores.sort(key=lambda x: (-x[0], len(x[1])))
        _, gloss, contexts = sense_scores[0]
        selected.append({'word': word, 'pinyin': item['pinyin'], 'gloss': gloss[:160],
                         'pos': pos_for(word, gloss), 'contexts': contexts, 'dictionary': True,
                         'frequencyRank': item['frequencyRank']})
    for line in (ORIGINALS + '\n' + CORRECTED_EXAMPLES).splitlines():
        word, gloss, pos, zh1, en1, zh2, en2 = line.split('|')
        if word in existing:
            continue
        source = 'Project-authored AI-assisted editorial example, 2026-10-27'
        contexts = [{'hanzi': zh1, 'english': en1, 'source': source}, {'hanzi': zh2, 'english': en2, 'source': source}]
        reading = lex[word][0]['pinyin'] if word in lex else ' '.join(g.lazy_pinyin(word, style=g.Style.TONE))
        selected = [x for x in selected if x['word'] != word]
        selected.insert(0, {'word': word, 'pinyin': reading, 'gloss': gloss, 'pos': pos,
                            'contexts': contexts, 'dictionary': word in lex, 'editorial': True, 'frequencyRank': freq.get(word, (None,))[0]})

    manual_corpus = json.loads((DATA / 'headword-corpus-manual-review-20261027.json').read_text(encoding='utf8'))['rows']
    already = {x['word'] for x in selected}
    for word, entry in manual_corpus.items():
        if word in existing or any(x['word'] == word and x.get('editorial') for x in selected):
            continue
        a, b = entry['contexts']
        if difflib.SequenceMatcher(None, ''.join(HAN.findall(a['hanzi'])), ''.join(HAN.findall(b['hanzi']))).ratio() >= .80:
            continue
        selected = [x for x in selected if x['word'] != word]
        selected.append({**entry, 'pos': pos_for(word, entry['gloss'])})

    claimed = set(json.loads((WORK / 'supplement-claimed-ids.json').read_text(encoding='utf8')))
    reserved_heads = {x['newHanzi'] for x in json.loads((WORK / 'rare-compound-selected.json').read_text(encoding='utf8'))}
    reserved_heads.update({'奥运会', '胆固醇'})
    selected = [x for x in selected if x['word'] not in reserved_heads and x['word'] not in {'四条', '到得', '活下'}]

    selected.sort(key=lambda x: (not x.get('editorial'), x['frequencyRank'] is None,
                                 x['frequencyRank'] or 999999, len(x['word']), x['word']))
    target = [r for r in rows if r['词条ID'] not in claimed and ((len(r['组词']) == 1 and r['组词'] not in CORE) or r['组词'] in REPLACE_EXTRA)]
    # Retire isolated surnames, bound forms and rare symbols before independently
    # useful everyday verbs/adjectives if the safe replacement pool is smaller.
    target.sort(key=lambda r: (r['组词'] not in REPLACE_EXTRA and not ('particle' in r['词性'] or 'interjection' in r['词性']),
                               not any(x in r['英文释义'].lower() for x in ['bound form', 'surname', 'classical', 'archaic']),
                               -(freq.get(r['组词'], (100000,))[0]), r['词条ID']))
    assigned = {}
    available = {x['word']: x for x in selected}
    # Maximise genuine original-character word formation first.
    for row in sorted(target, key=lambda r: int(r['罕度']) != 0):
        old = row['组词']
        same = [x for x in available.values() if len(old) == 1 and old in x['word']]
        if same:
            entry = min(same, key=lambda x: (not x.get('editorial'), x['frequencyRank'] is None, x['frequencyRank'] or 999999, len(x['word']), x['word']))
            assigned[row['词条ID']] = entry
            del available[entry['word']]
    for row in target:
        if row['词条ID'] in assigned or not available:
            continue
        word = next(iter(available))
        assigned[row['词条ID']] = available.pop(word)
    overlay = {}
    retained = []
    reviewed_pos = json.loads((DATA / 'headword-pos-review-20261027.json').read_text(encoding='utf8'))['byHeadword']
    editorial_stage = {word: 1 for word in AUTHORED_DAILY_WORDS}
    for word in '手机壳 网线 水杯 晾衣服 叠衣服 换床单 被套 外卖盒 自取 水电费 包装盒 烧开水 擦桌子 刮胡子 开窗 发夹 皮筋 开锁 换锁 早餐店 小吃摊 续杯 打饭 盛饭 半糖 少冰 充话费'.split():
        editorial_stage[word] = 0
    for word in '免密支付 自动续费 取消订阅 信用额度 还款日 账单日'.split():
        editorial_stage[word] = 2
    for row in rows:
        wid, old = row['词条ID'], row['组词']
        if wid not in assigned:
            if len(old) == 1:
                retained.append({'id': wid, 'word': old, 'reason': '核心独立常用字、基础语法或常用数词/量词' if old in CORE else '本轮未找到两句义项和词界均可靠的唯一自然替换词，保留待逐词补充'})
            continue
        entry = assigned[wid]
        word = entry['word']
        reading = READING_REVIEW.get(word, entry['pinyin'])
        gloss = GLOSS_REVIEW.get(word, entry['gloss'])
        gloss = re.sub(r'\([^()]*?(?:\baka\b|a\.k\.a\.)\s*\)', '', gloss)
        gloss = re.sub(r'\(\s*\)', '', gloss)
        gloss = re.sub(r'\s+', ' ', gloss).strip(' ;,')
        pos_review = reviewed_pos.get(word)
        if pos_review:
            entry['pos'] = pos_review['pos'] if isinstance(pos_review, dict) else pos_review
        chunks[word] = (reading, gloss)
        examples = []
        example_provenance = []
        for context in entry['contexts']:
            old_text = context['hanzi']
            context = dict(context)
            context['hanzi'] = SOURCE_TEXT_REVIEW.get(context['hanzi'], context['hanzi'])
            for wrong, right in {'什幺': '什么', '甚幺': '什么', '怎幺': '怎么', '这幺': '这么', '那幺': '那么', '甚么': '什么'}.items():
                context['hanzi'] = context['hanzi'].replace(wrong, right)
            for verb in '看 想 穿 戴 拿 站 跑 读 写 说 听 笑 哭 唱 守 拖 拉 推 抱 背 躺 握 沿 跟 盯 朝 忍 系 绑 瞪 向'.split():
                context['hanzi'] = context['hanzi'].replace(verb + '著', verb + '着')
            if old_text != context['hanzi']:
                meta = dict(context.get('sourceMeta') or {})
                meta.setdefault('originalHanzi', old_text)
                meta['modification'] = 'Editorial standard simplified spelling and demonstrative/pronoun typo correction, 2026-10-27'
                context['sourceMeta'] = meta
            pieces = parts(context['hanzi'], word)
            assert ''.join(p['hanzi'] for p in pieces) == context['hanzi']
            examples.append({'hanzi': context['hanzi'], 'pinyin': ' '.join(p['pinyin'] for p in pieces), 'english': context['english'], 'chunks': pieces})
            prov = {'hanzi': context['hanzi'], 'source': context['source']}
            if context.get('sourceMeta'):
                prov.update(context['sourceMeta'])
            if prov.get('originalHanzi') == prov['hanzi']:
                prov.pop('originalHanzi')
            if prov.get('originalEnglish') == context['english']:
                prov.pop('originalEnglish')
            example_provenance.append(prov)
        breakdown = []
        for ch, py in zip(word, reading.split()):
            hint = char_entries.get(ch, {}).get(py)
            if hint:
                hint = re.sub(r'^\((?:bound form|literary|archaic)\)\s*', '', hint)
            breakdown.append({'hanzi': ch, 'pinyin': py, 'gloss': hint[:100] if hint else ''})
        if any(not p['gloss'] for p in breakdown):
            breakdown = [{'hanzi': word, 'pinyin': reading, 'gloss': gloss}]
        fields = {'组词': word, '拼音': reading, '英文释义': gloss, '词性': entry['pos'],
                  '例句JSON': examples, '部件JSON': breakdown, '本义解释JSON': [gloss], '引申义解释JSON': []}
        overlay[wid] = {'oldHanzi': old, 'newHanzi': word, 'fields': fields,
                        'exampleProvenance': example_provenance,
                        'reason': ('原字自然组词；' if len(old) == 1 and old in word else '用日常或专题缺口自然词替换低教学价值孤字/语气组合；') +
                                  ('项目逐词原创双例句' if entry.get('editorial') else '固定词典收录＋双自然语料的词界与同义项核对'),
                        'dictionaryAttested': entry['dictionary'], 'frequencyRank': entry['frequencyRank']}
        rank = entry['frequencyRank']
        suggested = 0 if rank is not None and rank <= 2500 else 1 if rank is not None and rank <= 12000 else 2 if rank is None or rank <= 25000 else 3
        if word in editorial_stage:
            suggested = editorial_stage[word]
        if int(row['罕度']) == 0:
            suggested = 0
        overlay[wid]['suggestedRarity'] = suggested
        overlay[wid]['rarityReason'] = ('原0保留0；' if int(row['罕度']) == 0 else '') + ('日常实际使用与书写复杂度逐词修订' if word in editorial_stage else '新词的通用语料词频、现定义教学难度；缺少词频不推入最高档')
    rarity_sources = calibrate_rarities(overlay, rows, AUTHORED_DAILY_WORDS)
    dump(DATA / 'headword-refinement-20261027.json', {'schemaVersion': 1, 'sourceCsvSha256': BASE_SHA,
          'rows': overlay, 'retainedSingles': retained,
          'raritySourceFingerprints': rarity_sources,
          'authoredDailyWords': [v['newHanzi'] for v in overlay.values() if v['newHanzi'] in AUTHORED_DAILY_WORDS],
          'audit': {'candidateWords': len(preview['eligible']), 'eligibleAfterBoundaryAndSenseGates': len(selected),
                    'manualCorpusPairsReviewed': len(manual_corpus),
                    'replacedSingles': sum(len(v['oldHanzi']) == 1 for v in overlay.values()),
                    'replacedCompounds': sum(len(v['oldHanzi']) > 1 for v in overlay.values()),
                    'sameCharacterCompounds': sum(len(v['oldHanzi']) == 1 and v['oldHanzi'] in v['newHanzi'] for v in overlay.values()),
                    'remainingSingles': len(retained), 'coreSingles': sum(r['word'] in CORE for r in retained),
                    'editorialDailyGaps': sum(v['newHanzi'] in AUTHORED_DAILY_WORDS for v in overlay.values()),
                    'reviewBoundary': 'Source-assisted editorial pass; corpus translations/reading and composed POS still need linguistic review. No human certification.'}})
    dump(WORK / 'headword-filtered-candidates.json', filtered)
    dump(WORK / 'headword-single-overlap-review.json', single_overlap_review)
    print(json.dumps({'replacements': len(overlay), 'retainedSingles': len(retained), 'available': len(available)}, ensure_ascii=False))
    print(' '.join(v['oldHanzi'] + '→' + v['newHanzi'] for v in overlay.values()))


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf8')
    QUICK = '--quick-preview' in sys.argv
    if '--build' in sys.argv:
        build()
    else:
        prepare()
