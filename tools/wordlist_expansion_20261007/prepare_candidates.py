"""Prepare source-backed expansion candidates; this is not a semantic sign-off.

Reads the current CSV and pinned local caches. Writes only candidates.json and
coverage-audit.json next to this script. It never modifies the teaching CSV,
rebuild inputs, frozen reviews, application code, or stroke assets.
"""
from __future__ import annotations

import collections
import csv
import hashlib
import io
import json
import math
import re
import sys
import unicodedata
import zipfile
from pathlib import Path

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
DATA = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / "tools"))
from complete_wordlist import marked_pinyin, glosses

# Exclusively assigned to the family author; other author/retained-content
# shards must exclude these word forms before allocating their entries.
FAMILY_RESERVED_WORDS = """
牙刷 洗发水 沐浴露 洗衣液 洗衣粉 洗衣机 洗碗机 微波炉 水龙头 下水道 马桶 浴缸 淋浴 浴巾 牙线 牙签 湿巾 餐巾 洗手液 拖地 抹布 地毯 插头 插座 衣柜 衣架 被单 床垫 枕套 天花板 厨具 锅铲 平底锅 汤匙 饭碗 保鲜 冷藏 过期 变质 剩菜 清扫 收纳 洗衣 开水 冷水 下厨 煎蛋 蛋炒饭 食醋 香油 菜油 食用油 调料 味精 胡椒 甜味 苦味 咸菜 生姜 蒜头 绿豆 黄豆 红豆 扁豆 豆奶 蛋白 蛋黄 皮蛋 奶粉 奶油 芝士 奶酪 黄油 鸡腿 鸡胸 鱼肉 排骨 肉馅 肉丸 肉片 火腿 香肠 豆腐 豆腐干 豆腐皮 豆芽 零食 白饭 白粥 小米 燕麦 麦片 菜谱 甜瓜 木瓜 杏子 樱桃 番茄 花菜 青椒
空心菜 香菜 茄子 南瓜 冬瓜 苦瓜 丝瓜 木耳 海带 紫菜 山药 绿茶 红茶 奶茶 凉茶 豆乳 饮用水 汽水 苏打水 热饮 冷饮 冰块 甜点 甜食 主食 副食 素菜 凉菜 汤面 拌面 炸鸡 烤鸭 炖肉 油炸 红烧 凉拌 炒锅 蒸锅 蒸笼 电饭锅 高压锅 烤箱 榨汁机 烤面包机 抽油烟机 打火机 菜刀 餐具 食材 饭量 食量 食欲 饿肚子 夜宵 便饭 便当 饮酒 酒量 白酒 红酒 料酒 开瓶器 保温瓶 热水瓶 罐子 玻璃杯 保鲜膜 头痛 牙痛 腹痛 胃痛 发热 流感 过敏 恶心 鼻炎 咽炎 肺炎 胃病 血压 血糖 体温 体重 心跳 脉搏 胸口 腰部 后背 肩头 嗓子 脚趾 指头 头皮 毛孔 汗液 关节 伤痕 擦伤 扭伤 出血
止血 创可贴 消毒 药片 处方 药方 药房 服药 吃药 打针 输液 退烧药 止痛药 消炎药 感冒药 护理 看病 就医 挂号 门诊 急诊 出院 患者 病史 病因 病房 病假 体检 化验 内科 外科 儿科 眼科 牙科 麻醉 康复 保健 营养 维生素 热量 热身 健身 慢跑 拉伸 按摩 午睡 抽烟 戒烟 戒酒 护肤 防晒 防晒霜 护手霜 面霜 乳液 香皂 梳子 洗面奶 剃须 剃须刀 电吹风 吹风机 口臭 近视 远视 视力 镜框 隐形眼镜 助听器 耳塞 棉签 棉球 鼻塞 流鼻涕 咳痰 焦虑 关怀 喂养 养育 看护 护工 月嫂 托儿所 幼儿园 奶瓶 奶嘴 纸尿裤 尿布 童装 儿女 小孩子 亲子 母女 母子 父子 父女 夫妇 姐妹 侄女 外甥女
公公 岳母 晚辈 辈分 家务 养家 宠物 猫粮 狗粮 鱼缸 狗窝 猫砂 羽绒服 棉衣 风衣 大衣 夹克 长袖 短裤 连衣裙 内衣 内裤 凉鞋 皮鞋 布鞋 鞋垫 领口 帽檐 纽扣 拉链 毛线 熨烫 漂洗 脱水 手洗 干洗 晾衣架 试衣 抱枕 靠垫 坐垫 扶手 床头柜 杂物 客房 屋外 花束 花肥 浇花 盆栽 盆景 种菜 园艺 肥料 通风 换气 暖气 空调 电扇 风扇 台灯 电灯 灯罩 电池 遥控器 手电筒 电源 插线板 排插 电表 水表 燃气 煤气 热水器 漏电 停水 断电 水箱 门把手 房门 后门 纱窗 防盗门 防盗 报警器 灭火器 灭火 消防 逃生 门框 垃圾箱 垃圾车 纸盒 纸袋 废纸 废品 回收 厨余 生活垃圾 棉布
鸡爪 鸡翅 猪蹄 牛排 羊排 猪排 鸡排 鱼排 鱼刺 生鱼片 鱼头 肉汤 烤串 串烧 凉粉 凉皮 酸辣汤 烧卖 春卷 煎饼 葱油饼 馅料 酱料 豆瓣酱 番茄酱 沙拉酱 花生酱 果酱 味觉 鲜味 卤肉 卤蛋 冰糖 砂糖 白糖 红糖 糖浆 糖粉 花生 腰果 核桃 开心果 瓜子 葵花子 豆瓣 小白菜 大白菜 腌肉 腊肉 肉丝 蜜枣 枣泥 桂圆 奶昔 乳酸菌 酸奶油 冰棍 冰棒 雪糕 冰淇淋 冰沙 冰水 温热 泡茶 煮沸 加热 熟食 生食 生肉 半熟 可口 软烂 肉皮 饮食 用餐 就餐 进食 禁食 节食 食疗 偏食 挑食 素食 素食者 健康食品 绿色食品 免疫 抗体 传染病 慢性病 外伤 急救箱 瘀青 淤青 水肿 红肿 流血 头疼 睡衣
睡袋
""".split()

SCENE_CORE = {
    "home_food_care": """
    垃圾桶 垃圾袋 洗衣 晾衣 擦地 擦桌 拖地 洗发 淋浴 牙刷 洗发水
    沐浴露 洗衣液 洗衣粉 烧水 开水 热水 冷水 水壶 水杯 锅盖 锅铲
    平底锅 微波炉 洗碗机 洗衣机 洗手液 牙线 牙签 纸巾 餐巾 湿巾
    饭碗 饭盒 碗筷 盘子 碟子 筷子 勺子 汤匙 炒菜 煮饭 蒸饭 煮面
    煮粥 煎蛋 炒饭 蛋炒饭 鸡蛋 牛奶 酸奶 豆奶 豆浆 牛肉 猪肉 羊肉
    鸡肉 鸡胸 鸡腿 鱼肉 虾仁 黄瓜 番茄 西红柿 西兰花 胡萝卜 白菜
    生菜 青菜 菠菜 土豆 红薯 洋葱 大蒜 生姜 酱油 醋 食醋 香油 菜油
    食用油 辣椒 胡椒 味精 调料 调味 咸味 甜味 酸味 苦味 咸菜
    香肠 火腿 面包 蛋糕 饼干 点心 零食 水果 苹果 香蕉 橙子 梨子
    葡萄 柚子 草莓 西瓜 冰箱 冰柜 冷藏 冷冻室 保鲜 过期 保质期
    变质 剩饭 剩菜 剩余 食谱 菜谱 菜单 做菜 下厨 厨具 厨房
    早饭 午饭 夜宵 饱腹 吃饱 喝醉 口渴 饿肚子 饭量 食量
    被子 被单 床单 床垫 枕头 枕套 衣架 衣柜 鞋柜 书架 书柜 窗帘
    窗户 门锁 门铃 地板 天花板 墙壁 水龙头 下水道 马桶 浴缸 浴室
    浴巾 地毯 拖把 抹布 扫帚 清扫 清洗 清理 整理 收拾 收纳
    插头 插座 电灯 灯泡 台灯 电池 空调 风扇 电扇 暖气 暖风
    发热 嗓子 头痛 肚疼 腹痛 过敏 食欲 血压 血糖 体检 化验
    挂号 门诊 急诊 药片 退烧药 消炎药 止痛药 创可贴 消毒 护理
    耐心 焦虑 失眠 恶心 感冒 流感 打喷嚏 发炎 伤口 受伤 擦伤
    扭伤 骨折 烫伤 牙疼 牙痛 牙医 看病 就医 病人 患者 病历
    病假 病房 住院 出院 打针 输液 服药 吃药 用药 处方 药方
    药店 药房 健康 保健 康复 恢复 照料 照看 儿科 内科 外科
    眼科 牙科 胃病 肺炎 咽炎 鼻炎 眼镜 近视 远视 血液 体温
    体重 体力 营养 锻炼 运动 散步 睡眠 午睡 入睡 起床 早睡
    呼吸 心跳 胸口 腰部 后背 脚踝 脚趾 手腕 手指 指甲 指头
    肩膀 肩头 脖子 膝盖 肌肉 头皮 毛孔 汗水 汗液 洗脚 足浴
    """.split(),
    "travel_shopping_services": """
    账户 账号 付款 扫码 转账 退款 订单 外卖 快递 收件 寄件
    地铁 公交 公交车 班车 站台 转车 进站 出站 刷卡 打车 网约车
    导航 堵车 驾照 驾驶证 身份证 护照 签证 排队 取号 叫号
    预约 预订 表格 签字 收费 门票 机票 手提箱 安检 登机
    售票 买票 购票 退票 检票 月票 年票 单程 往返 车厢 车门
    车轮 轮胎 车牌 车灯 车库 车位 车主 车道 车流 车速 车费
    路口 路灯 路线 路程 路况 过街 人行道 斑马线 红绿灯 高速公路
    出租车 自行车 电动车 摩托车 货车 卡车 火车 轮船 公路
    铁路 机场 航班 航空 飞机 候机 候车 换乘站 终点 起点 起飞
    降落 到站 到达 出发 返程 返乡 回国 出国 入境 出境 海关
    旅途 旅客 游客 导游 旅行 出行 出游 行程 景点 旅馆 酒店
    宾馆 客房 住宿 入住 退房 前台 订房 房卡 房价 房费 押金
    租房 房租 租金 房东 租客 物业 房产 楼层 单元 电梯 楼梯
    楼道 地址 门牌 邮编 街道 街口 商店 店铺 店员 柜台 专柜
    营业 开店 关门 开门 购物 买卖 出售 销售 销量 价格 标价
    定价 涨价 降价 打折 折扣 优惠 优惠券 促销 便宜 贵重
    现金 零钱 硬币 纸币 钞票 找钱 找零 收银 收银台 收据 小票
    发票 开票 银行 银行卡 信用卡 借记卡 储蓄 存款 取款 存钱
    取钱 借钱 还钱 贷款 利息 利率 分期 分期付款 余额 账单
    对账 欠款 欠费 缴纳 交费 运费 邮费 包邮 邮寄 速递 送货
    收货 发货 退货 换货 补货 库存 现货 缺货 断货 货物 商品
    产品 品牌 质量 售后 保修 维修 修理 修补 赔偿 赔付 保证
    客服 投诉 咨询 查询 确认 提交 受理 处理 办事 证件 手续
    有效期 到期 续费 续签 办公室 服务中心 通知 公告 窗口
    会员 会员卡 积分 礼品 礼物 赠品 赠送 包装 打包 拆包
    盒子 纸盒 纸箱 箱子 尺码 尺寸 大小 长度 宽度 高度 重量
    合身 合适 试穿 试用 试吃 换算 数量 件数 单价 总价
    """.split(),
    "study_work_digital_social": """
    登录 注册 验证码 充电 充电器 耳机 蓝牙 无线 信号 流量 截图
    软件 更新 下载 上传 保存 点赞 复制 输入 输出 输入法
    文件 文件夹 文档 文本 文本框 网页 网站 网址 浏览器 浏览
    点击 双击 单击 鼠标 光标 光盘 硬盘 内存 存储 储存 储存卡
    键盘 按键 回车 空格 屏保 壁纸 显示器 电脑 笔记本 台式机
    打印 打印机 扫描 扫描仪 复印 复印机 摄像头 麦克风 音量
    静音 录音 录屏 视频 音频 相机 拍照 照片 图片 相册
    通话 接听 接电话 打电话 来电 未接来电 语音 短信 消息
    邮件 邮箱 电子邮件 发送 接收 收到 收发 转发 回复 回信
    备份 恢复 还原 退出 注销 关机 开机 重启 联网 连接 断开
    数据 数据库 设置 配置 系统 系统更新 版本 功能 界面
    链接 网络安全 隐私 信息 个人信息 用户 用户名 用户名片
    加密 解密 认证 授权 权限 签到 在线 离线 缓存 无线网
    开学 放学 上学 上课 下课 课间 课后 课堂 课程 课表
    作业 作文 写作 默写 听写 朗读 阅读 预习 复习 练习
    试卷 考卷 考题 答题 做题 答案 答卷 答辩 测验 考试
    考场 监考 成绩 分数 得分 扣分 满分 及格 不及格
    语文 数学 外语 英语 汉语 中文 拼音 词语 字词 组词
    句子 句号 逗号 标点 语法 词典 字典 注音 发音 口音
    口语 书面语 听力 词汇 词义 段落 章节 标题 主题
    知识 内容 要点 重点 难点 方法 步骤 过程 原因 结果
    目标 计划 安排 进度 完成 结束 开始 继续 暂停 中断
    上班 下班 加班 请假 休假 放假 假期 周末 月底 年底
    同事 同学 同桌 同伴 团队 合作 讨论 交流 沟通 发言
    会议 会场 开会 例会 面试 应聘 招聘 入职 离职 工作证
    职位 岗位 职责 任务 方案 报告 总结 记录 纪要 笔记
    通讯 联系 联系人 联系方式 约定 约会 见面 相处 交往
    礼貌 尊重 理解 关心 帮忙 帮助 支持 鼓励 安慰 祝福
    祝愿 道歉 抱歉 感谢 感激 拒绝 接受 同意 不同意
    表达 解释 说明 介绍 评价 建议 意见 看法 想法
    感受 情感 情绪 心情 开心 高兴 快乐 难过 伤心
    生气 愤怒 紧张 放松 担心 放心 满意 不满
    """.split(),
}
SCENE_SEEDS = {
    "home_food_care": "食餐饭菜米面油盐甜酸洗刷擦晾扫拖住床被枕穿脱医药病痛牙眼耳鼻脸腿饿饱冷热厨锅碗肉蛋奶茶汤果蔬花草睡家亲爸妈幼护伤胃肺血汗",
    "travel_shopping_services": "买卖钱款付收寄取存借退票站车路房租价贵费邮运货购商店银账证旅游航船机街桥市客服务营售办签约等轮包箱卡锁座停行出入城乡",
    "study_work_digital_social": "电屏网码键聊问答早晚今明周假书读学写教课考校字词句文语声听讲说想知信资讯软录音影图数算科业职工会谈友同心情理感意愿思忆乐忧喜愁怒爱笑",
}
SCENE_ENGLISH = {
    "home_food_care": set("food meal cook kitchen rice noodle soup vegetable fruit drink eat bread cake sweet salt sugar meat fish chicken pork milk tea coffee wash clean bath shower laundry clothes bed pillow blanket sleep rest sick illness disease health healthy medical medicine doctor patient nurse hospital body blood stomach skin tooth hair care child family parent mother father baby plant flower garden animal home household furniture oven fridge stove towel room floor wall window toilet pain wound fever nutrition taste".split()),
    "travel_shopping_services": set("buy sell shop shopping store market money cash price pay payment bill fee bank account loan interest credit debit refund ticket passenger train bus subway station stop travel trip journey tour tourist hotel airport flight fly road street traffic car truck bicycle boat ship transport vehicle drive driver rent rental house property apartment accommodation room booking reserve parcel package delivery post postal mail address service customer client order product goods sale discount receipt invoice baggage luggage customs border passport visa identity document repair maintenance insurance commercial trade import export".split()),
    "study_work_digital_social": set("study learn school lesson class student teacher homework examination exam question answer read write book dictionary word language text document letter report note paper office work job task meeting discuss discussion conversation talk speak listen sound thought think idea opinion plan goal project information data computer digital software hardware network internet website page screen keyboard mouse phone call message email video audio camera photo image music memory store storage file folder save delete copy paste login logon password user register online download upload connection signal friend social communicate communication feel feeling emotion happy sad angry agree refuse explain suggest advice express relationship hope wish respect polite encourage support help attention reason result process method knowledge science technology number".split()),
}
REJECT_WORDS = set("好啊 好呀 好啦 好哇 好嘛 好呗 嗯嗯 嗯哼 哈哈 嘻嘻 呵呵 嘿嘿 哎呀 哎哟 哎呦 啊哈 阿哈 哦哦 唉呀 唉哟 噫嘻 呜呼 呜呼哀哉 天哪 天啊 他妈的 他娘的 妈的 混蛋 王八蛋 傻逼 傻屄 干吗 干嘛 呢喃 哈哈大笑".split())
REJECT_SENSE = re.compile(
    r"\b(?:surname|given name|personal name|place name|county|prefecture|township|province|dynasty|emperor|kingdom|archaic|obsolete|classical|literary|dialect|onomatopoeia|onomatopoeic|interjection|exclamation|vulgar|offensive|swear|fuck|fucking|shit)\b"
    r"|sentence[- ]final|modal particle|grammatical particle|sound of|used in names|name of a|capital of|city in|district in|county in|river in|mountain in|island in"
    r"|^(?:variant|old variant|see |abbr\.|old name|ancient name|Japanese|Korean)|\(idiom\)",
    re.I,
)
READING = re.compile(r"(?:[a-zü:]+[0-5])(?: (?:[a-zü:]+[0-5]))*", re.I)
HANZI = re.compile(r"[\u3400-\u9fff\U00020000-\U000323af]")
REFERENCE = re.compile(r"(?:[\u3400-\u9fff]+\|)?[\u3400-\u9fff]+\[([^\]]+)\]")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def reading_key(value):
    return "".join(unicodedata.normalize("NFC", value).lower().split())


def frequency_tables(path):
    if digest(path) != "cced9cb382914b93956a24fd06c10de709d351ff4501ac94c9ab136004421f07":
        raise ValueError("Pinned SUBTLEX source hash differs")
    with zipfile.ZipFile(path) as archive:
        def read(name, key):
            lines = archive.read(name).decode("gb18030").splitlines()[2:]
            return {r[key]: r for r in csv.DictReader(io.StringIO("\n".join(lines)), delimiter="\t")}
        return read("SUBTLEX-CH-WF", "Word"), read("SUBTLEX-CH-CHR", "Character")


def ordinary_readings(word, entries, rejected, index):
    result = []
    for entry_number, entry in enumerate(entries):
        numeric = entry["pinyin"]
        if any(c.isupper() for c in numeric) or not READING.fullmatch(numeric):
            rejected["name_or_unsupported_reading_entries"] += 1
            continue
        if len(numeric.split()) != len(word):
            rejected["reading_alignment_entries"] += 1
            continue
        raw_senses = [s for s in entry["senses"] if not s.startswith(("CL:", "Taiwan pr.", "also pr.", "old pr."))]
        senses = [s for s in raw_senses if not REJECT_SENSE.search(s)]
        if not senses and any(s.startswith(("see ", "abbr. for ")) for s in raw_senses):
            senses = [s for s in glosses(entry, index) if not REJECT_SENSE.search(s)]
        senses = [s for s in senses if re.search(r"[A-Za-z]", s)]
        if not senses:
            rejected["name_noise_register_or_reference_only_entries"] += 1
            continue
        # References retain their attested reading; lexical authors must select
        # the applicable sense rather than infer it from this cleanup.
        cleaned = [REFERENCE.sub(lambda m: "'" + marked_pinyin(m[1]) + "'", s) for s in senses]
        cleaned = [re.sub(r"\s*\(CL:[^)]*\)", "", s).strip() for s in cleaned]
        result.append({"pinyin": marked_pinyin(numeric), "numericPinyin": numeric,
                       "glosses": cleaned, "sourceKey": f"dictionary-index.json:{word}#{entry_number}",
                       "traditional": entry["traditional"], "simplified": entry["simplified"],
                       "rawSourceSenses": raw_senses})
    return result


def scene_for(word, readings):
    english = set(re.findall(r"[a-z]+", " ".join(g for r in readings for g in r["glosses"]).lower()))
    scores = {}
    for scene in SCENE_CORE:
        scores[scene] = (1000 if word in SCENE_CORE[scene] else 0)
        scores[scene] += 8 * len(english & SCENE_ENGLISH[scene])
        scores[scene] += 1.5 * len(set(word) & set(SCENE_SEEDS[scene]))
    scene = max(scores, key=scores.get)
    return scene, scores[scene], [c for c in word if c in SCENE_SEEDS[scene]]


def retained_examples(rows, pool):
    sentences = []
    seen = {}
    for row in rows:
        for n, example in enumerate(json.loads(row["例句JSON"])):
            key = (example["hanzi"], reading_key(example["pinyin"]), example["english"])
            if key not in seen:
                seen[key] = len(sentences)
                sentences.append({"example": example, "references": []})
            sentences[seen[key]]["references"].append({"wordId": row["词条ID"], "exampleIndex": n})
    occurrences = collections.defaultdict(set)
    whole = collections.defaultdict(set)
    boundary = collections.defaultdict(set)
    boundary_readings = collections.defaultdict(lambda: collections.Counter())
    chunk_readings = collections.defaultdict(lambda: collections.defaultdict(lambda: {"sentences": set(), "glosses": set()}))
    for sentence_id, item in enumerate(sentences):
        example = item["example"]
        hanzi = example["hanzi"]
        for start in range(len(hanzi)):
            for size in range(2, 7):
                word = hanzi[start:start + size]
                if word in pool:
                    occurrences[word].add(sentence_id)
        for chunk in example["chunks"]:
            word = "".join(HANZI.findall(chunk["hanzi"]))
            if word not in pool:
                continue
            pinyin = re.sub(r"[^a-züêāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜńňǹḿ\s]", "", chunk["pinyin"].lower()).strip()
            if len(pinyin.split()) != len(word):
                continue
            whole[word].add(sentence_id)
            bank = chunk_readings[word][pinyin]
            bank["sentences"].add(sentence_id)
            bank["glosses"].add(chunk["gloss"])
        chunks = example["chunks"]
        for start in range(len(chunks)):
            raw_word, pinyin = "", ""
            for end in range(start, len(chunks)):
                raw_word += chunks[end]["hanzi"]
                word = raw_word.strip(".,!?;:，。！？；：、…—–-\"'“”‘’()（）[] ")
                pinyin += " " + chunks[end]["pinyin"]
                if len(word) > 6:
                    break
                if word not in pool:
                    continue
                pinyin = re.sub(r"[^a-züêāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜńňǹḿ\s]", "", pinyin.lower()).strip()
                if reading_key(pinyin) in {reading_key(r["pinyin"]) for r in pool[word]}:
                    boundary[word].add(sentence_id)
                    boundary_readings[word][pinyin] += 1
    return sentences, occurrences, whole, chunk_readings, boundary, boundary_readings


def cache_example_inventory(candidate_words):
    base = ROOT / ".gradle/wordlist-refinement/mapull"
    source = json.loads((base / "source.json").read_text(encoding="utf-8"))
    items = json.loads((base / "word.json").read_text(encoding="utf-8"))
    if digest(base / "word.json") != source["files"]["word.json"]["sha256"]:
        raise ValueError("Pinned mapull word source hash differs")
    fields = collections.Counter(k for item in items for k in item)
    direct_examples = sum(bool(item.get("example") or item.get("examples")) for item in items)
    relevant = [item for item in items if item["word"] in candidate_words]
    return {
        "source": source["source"], "commit": source["commit"], "wordEntries": len(items),
        "wordFields": dict(fields), "directExampleFieldEntries": direct_examples,
        "candidateWordExplanationMatches": len({item["word"] for item in relevant}),
        "sampleCandidateEntries": relevant[:6],
        "pairedNaturalEnglishExampleAvailability": "No paired natural Hanzi/pinyin/English sentence field in cached word.json; no bilingual sentence bank is assumed.",
        "otherCaches": {
            "char_detail.json": "Comma-separated character objects with nested explanations and examples; character-level examples often use placeholders and do not provide complete bilingual word examples.",
            "idiom.json": "Chinese idiom explanations and quotations with book metadata; mainly literary material, not a daily-life bilingual sentence bank.",
        },
        "boundary": "MIT repository metadata is retained with its upstream provenance caveat. Chinese explanations are reference material, not audited modern bilingual examples.",
    }


def main():
    csv_path = ROOT / "wordlist.csv"
    rows = list(csv.DictReader(csv_path.open(encoding="utf-8-sig", newline="")))
    old_words = {row["组词"] for row in rows}
    glyph_ids = collections.defaultdict(list)
    for row in rows:
        for glyph in sorted(set(row["组词"])):
            glyph_ids[glyph].append(row["词条ID"])
    glyphs = set(glyph_ids)
    index_path = ROOT / ".gradle/wordlist-implementation/dictionary-index.json"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    word_freq, char_freq = frequency_tables(ROOT / ".gradle/wordlist-difficulty/subtlex-ch-2010.zip")
    rejected = collections.Counter()
    pool = {}
    for word, entries in index.items():
        if word in old_words:
            rejected["existing_word_forms"] += 1
            continue
        if not 2 <= len(word) <= 6 or not all(c in glyphs for c in word):
            rejected["new_glyph_or_length_outside_2_to_6"] += 1
            continue
        if word in REJECT_WORDS:
            rejected["explicit_particle_exclamation_or_noise"] += 1
            continue
        readings = ordinary_readings(word, entries, rejected, index)
        if readings:
            pool[word] = readings
    sentences, examples, whole, chunk_readings, boundary, boundary_readings = retained_examples(rows, pool)
    grouped = collections.defaultdict(list)
    for word, readings in pool.items():
        scene, scene_score, seed = scene_for(word, readings)
        observed = word_freq.get(word, {})
        count, contexts = int(observed.get("WCount", 0)), int(observed.get("W-CD", 0))
        referenced = sorted(examples.get(word, set()))
        chunk_refs = sorted(whole.get(word, set()))
        boundary_refs = sorted(boundary.get(word, set()))
        useful_readings = []
        for pinyin, content in chunk_readings.get(word, {}).items():
            if reading_key(pinyin) not in {reading_key(r["pinyin"]) for r in readings}:
                continue
            useful_readings.append({"pinyin": pinyin, "distinctExampleCount": len(content["sentences"]),
                                    "glosses": sorted(content["glosses"])})
        core = any(word in values for values in SCENE_CORE.values()) or word in FAMILY_RESERVED_WORDS
        if word in FAMILY_RESERVED_WORDS:
            scene = "home_food_care"
        score = (10000 if core else 0) + (900 if len(chunk_refs) >= 2 and useful_readings else 0)
        score += (400 if len(boundary_refs) >= 2 else 0) + (100 if useful_readings else 0)
        score += min(scene_score, 100) + 20 * math.log10(1 + contexts) + 5 * math.log10(1 + count)
        grouped[scene].append({
            "word": word, "scene": scene, "seed": seed, "coreDailyGap": core,
            "score": round(score, 3), "readings": readings,
            "frequency": {"wordCount": count, "wordContexts": contexts,
                          "minCharacterCount": min(int(char_freq.get(c, {}).get("CHRCount", 0)) for c in word)},
            "existingExampleCount": len(referenced), "existingWholeChunkCount": len(chunk_refs),
            "existingBoundaryExampleCount": len(boundary_refs),
            "retainedBoundaryReadings": [{"pinyin": pinyin, "occurrences": count}
                                         for pinyin, count in boundary_readings.get(word, collections.Counter()).most_common()],
            "retainedWholeChunkReadings": sorted(useful_readings, key=lambda r: (-r["distinctExampleCount"], r["pinyin"])),
            "exampleReferences": [sentences[n]["references"][0] for n in (boundary_refs + [x for x in referenced if x not in boundary.get(word, set())])[:6]],
        })
    selected = []
    scene_summary = {}
    for scene in SCENE_CORE:
        ordered = sorted(grouped[scene], key=lambda r: (-r["score"], r["word"]))
        chosen = ordered[:2400]
        if len(chosen) < 2000:
            raise ValueError("Not enough candidates in scene " + scene)
        selected.extend(chosen)
        scene_summary[scene] = {
            "pool": len(ordered), "selected": len(chosen),
            "coreDailyGaps": sum(r["coreDailyGap"] for r in chosen),
            "twoRetainedExamples": sum(r["existingExampleCount"] >= 2 for r in chosen),
            "twoRetainedWholeChunks": sum(r["existingWholeChunkCount"] >= 2 for r in chosen),
            "twoRetainedBoundaryExamples": sum(r["existingBoundaryExampleCount"] >= 2 for r in chosen),
        }
    if len({r["word"] for r in selected}) != len(selected) or len(selected) < 6000:
        raise ValueError("Candidate count or uniqueness invariant failed")
    if len(FAMILY_RESERVED_WORDS) != 500 or len(set(FAMILY_RESERVED_WORDS)) != 500:
        raise ValueError("Family reservation must contain exactly 500 distinct words")
    if any(word not in pool for word in FAMILY_RESERVED_WORDS):
        raise ValueError("Family word lacks an accepted source reading: " + ", ".join(w for w in FAMILY_RESERVED_WORDS if w not in pool))
    candidate_glyphs = collections.defaultdict(lambda: collections.Counter())
    for candidate in selected:
        for glyph in set(candidate["word"]):
            candidate_glyphs[glyph][candidate["scene"]] += 1
    char_order = sorted(char_freq, key=lambda c: (-int(char_freq[c]["CHRCount"]), c))
    char_rank = {glyph: n + 1 for n, glyph in enumerate(char_order)}
    core_status = {}
    selected_words = {r["word"] for r in selected}
    for scene, core_words in SCENE_CORE.items():
        core_status[scene] = {
            "existing": [w for w in core_words if w in old_words],
            "selected": [w for w in core_words if w in selected_words],
            "notSelected": [{"word": w, "reason": "new glyph" if any(c not in glyphs for c in w) else "no accepted pinned lexical reading" if w not in pool else "scene selection budget"}
                            for w in core_words if w not in old_words and w not in selected_words],
        }
    baseline = {"rows": len(rows), "uniqueWordForms": len(old_words), "wordGlyphs": len(glyphs),
                "sha256": digest(csv_path), "bytes": csv_path.stat().st_size}
    sources = {"dictionaryIndexSha256": digest(index_path),
               "cedict": json.loads((ROOT / ".gradle/wordlist-implementation/cedict-source.json").read_text(encoding="utf-8")),
               "subtlex": {"year": 2010, "sha256": "cced9cb382914b93956a24fd06c10de709d351ff4501ac94c9ab136004421f07", "use": "Candidate ranking only; subtitles are not a daily-life syllabus or stage grading authority."}}
    review_boundary = "Source-backed candidates and automated screening only; no completed semantic language review, certified pronunciation/sense choice, new teaching content, build, or device proof. All recorded reading alternatives require contextual selection."
    save(DATA / "candidates.json", {"date": "2026-10-07", "baseline": baseline, "sources": sources,
        "summary": {"candidatePool": len(pool), "selectedCandidates": len(selected), "uniqueRetainedSentences": len(sentences),
                    "twoRetainedExamplesInPool": sum(len(v) >= 2 for v in examples.values()),
                    "twoRetainedWholeChunksInPool": sum(len(v) >= 2 for v in whole.values()),
                    "twoRetainedBoundaryExamplesInPool": sum(len(v) >= 2 for v in boundary.values()), "rejected": dict(rejected)},
        "scenes": scene_summary, "familyReservedWords": FAMILY_RESERVED_WORDS,
        "entries": selected, "boundary": review_boundary})
    save(DATA / "coverage-audit.json", {"date": "2026-10-07", "baseline": baseline, "scenes": scene_summary,
        "coreDailyGapStatus": core_status, "exampleSourceInventory": cache_example_inventory(selected_words),
        "characters": [{"glyph": glyph, "existingWordCount": len(glyph_ids[glyph]), "existingWordIds": glyph_ids[glyph],
                        "subtlexCharacterCount": int(char_freq.get(glyph, {}).get("CHRCount", 0)),
                        "subtlexCharacterRank": char_rank.get(glyph), "candidateScenes": dict(candidate_glyphs[glyph])}
                       for glyph in sorted(glyphs)], "boundary": review_boundary})
    print(json.dumps({"baseline": baseline, "selected": len(selected), "scenes": scene_summary,
                      "pool": len(pool), "sentences": len(sentences), "boundary": review_boundary}, ensure_ascii=False))


if __name__ == "__main__":
    main()
