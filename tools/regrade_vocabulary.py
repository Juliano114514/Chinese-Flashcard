"""Review six pedagogical bands; change only the first CSV field, never teaching content.

Stage 5 is an exact, sense-bound opt-in editorial list, not a frequency fallback.
--preview writes a draft; --write accepts the recorded 1.6.8 input once; --check
checks every decision and content fingerprint against the frozen review ledger.
"""
from __future__ import annotations

import argparse
import collections
import csv
import hashlib
import io
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'tools/vocabulary_v2'
BEFORE_SHA256 = '7b87cd834c93d22e60c617d2dabbdd42e1754534d084a4ede7812f4647e627f4'
JIEBA_SHA256 = '7197c3211ddd98962b036cdf40324d1ea2bfaa12bd028e68faa70111a88e12a8'
REVIEW = DATA / 'rarity-review.json'

# Explicit teaching scope. These are words, not substring or example matches.
FOUNDATION = set('''幼儿园 我 你 他 她 它 我们 你们 他们 她们 自己 谁 什么 哪 哪儿 哪里 这 那 这里 那里
爸爸 妈妈 爷爷 奶奶 外公 外婆 哥哥 姐姐 弟弟 妹妹 宝宝 小孩 孩子 朋友 老师 人 家 家人 男 女 男孩 女孩
一 二 三 四 五 六 七 八 九 十 零 百 两 个 只 本 张 条 口 头 天 年 月 日 点 分 早上 中午 晚上 今天 明天 昨天
是 有 没有 不 没 的 了 吗 呢 吧 啊 和 也 都 很 太 还 就 在 到 从 给 跟 比 让 要 想 会 能 可以 不能
大 小 多 少 好 坏 新 旧 长 短 高 矮 热 冷 快 慢 红 黄 蓝 绿 白 黑 红色 黄色 蓝色 绿色 白色 黑色
上 下 左 右 前 后 里 外 上面 下面 里面 外面 左边 右边 前面 后面
吃 喝 睡 睡觉 起床 走 跑 跳 坐 站 看 听 说 读 写 玩 笑 哭 来 去 回家 回来 出去 进来 拿 放 打 开 关 找 买 卖
水 饭 米饭 面包 牛奶 鸡蛋 苹果 香蕉 西瓜 菜 肉 鱼 糖 盐 汤 杯子 碗 筷子 勺子 桌子 椅子 床 门 窗 窗户 书 笔 纸 书包
手 脚 头 脸 嘴 嘴巴 眼睛 耳朵 鼻子 牙齿 头发 衣服 裤子 鞋 鞋子 帽子 球 车 汽车
猫 狗 鸟 鸡 鸭 牛 羊 马 兔子 老虎 大象 熊猫 太阳 月亮 星星 天空 云 雨 下雨 雪 风 花 草 树 石头 土 山 河 海
你好 谢谢 再见 对不起 没关系 不客气 请 请问 好的 不要 喜欢 爱 知道 不知道 明白 认识 多少 几 怎么 为什么
你呢 很好 好吧 好啊 好啦 数一数 去玩'''.split())

DAILY = set('''盐 糖 醋 酱油 蚝油 料酒 花椒 八角 淀粉 面粉 食用油 冰箱 冰柜 洗衣机 空调 电风扇
电磁炉 热水壶 空气炸锅 微波炉 电饭煲 电饭锅 电吹风 吹风机 吸尘器 洗碗机 烤箱 净水器 路由器 充电器 插座 插头
垃圾袋 卫生纸 纸巾 湿巾 洗衣液 洗洁精 牙膏 牙刷 毛巾 浴巾 拖鞋 晾衣架 衣架 马桶 水龙头 花洒
地铁 公交 公交车 高铁 车票 机票 打车 乘车 扫码 付款 支付 找零 外卖 快递 收货 退货 退款 网购 下单 取件
早餐 午餐 晚餐 夜宵 菜单 点菜 打包 少辣 不辣 香菜 葱 蒜 蒜头 洋葱 土豆 番茄 西红柿 黄瓜 胡萝卜 菠菜 白菜
面条 饺子 馒头 包子 豆腐 豆浆 酸奶 饼干 鸡肉 猪肉 牛肉 羊肉 虾 蔬菜 水果 米 面 茶 果汁 饮料 矿泉水
幼儿园 托儿所 小区 超市 便利店 药店 卫生间 洗手间 电梯 楼梯 阳台 厨房 餐厅 客厅 卧室 书房 沙发 茶几
系鞋带 饭团 梨子 果切 榨汁 水盆 护手霜 牙痛 牙疼 流鼻涕 发痒 吃药 过敏 口罩 体温 发烧 感冒 咳嗽 头疼 肚子
保温杯 纸杯 水杯 茶杯 饭碗 水瓶 瓶子 塑料袋 盒子 袋子 书桌 书架 台灯 灯 电视 电视机 电脑 手机 平板 耳机 音箱
打印机 计算器 相机 照相机 录音机 录像机 电池 开关 灯泡 洗脸 洗手 洗澡 刷牙 梳头 理发 剃头 擦桌子 洗碗 洗衣服 晒衣服
穿衣服 穿鞋 脱鞋 换衣服 戴帽子 系扣子 背书包 背诵 朗读 读书 写字 认字 作业 上学 放学 学校 小学 中学 大学
词典 字典 口语 汉语 中文 英语 英文 拼音 课本 书页 短文 文章 语文 数学 老师 学生 同学 校门 校舍 寒假 暑假 周末
预习 复习 考试 考试卷 试卷 成绩 分数 及格 认真 仔细 粗心 学习 上课 下课 请假 迟到 早退 黑板 粉笔 橡皮 尺子 铅笔 钢笔
红绿灯 站牌 公交站 地铁站 车站 火车站 机场 服务台 售票处 司机 快递员 收银员 门牌 排队 买票 换乘 进站 出站 上车 下车
银行卡 信用卡 借记卡 银行 现金 零钱 硬币 纸币 收款 取款机 水费 电费 房租 账单 结账 收据 价钱 价格 便宜 贵
爸爸妈妈 爸妈 兄弟 姐妹 叔叔 阿姨 姑姑 舅舅 伯伯 伯父 伯母 姑妈 舅妈 侄子 侄女 婶婶 孙子 孙女 丈夫 妻子 爱人
亲戚 邻居 客人 主人 服务员 大人 年轻人 老人 男生 女生 男人 女人 姓 名字 姓名 生日 年龄 结婚 生日快乐
早饭 午饭 晚饭 开饭 吃饭 喝水 好吃 好喝 好看 好听 漂亮 可爱 干净 脏 舒服 难受 生病 饿 渴 饱 累 困
起飞 到达 出发 回去 过去 过来 下去 上来 出来 进去 回头 拐弯 左转 右转 直走 过马路 马路 路口 街道 公路 人行道
星期 星期一 星期二 星期三 星期四 星期五 星期六 星期天 星期日 今年 去年 明年 上午 下午 傍晚 半夜 白天 黑夜 一会儿 马上
春天 夏天 秋天 冬天 春 夏 秋 冬 下雪 刮风 天气 温度 阴天 晴天 雨天 多云 大雨 小雨 雨伞 雨衣 手套 围巾 袜子 裙子 衬衫
桃子 葡萄 橘子 橙子 草莓 芒果 菠萝 樱桃 柠檬 柚子 哈密瓜 梨 菜花 西兰花 生菜 豌豆 扁豆 苦瓜 素菜 豆腐皮 鸡爪 馅料
乌龟 蝴蝶 蚂蚁 蜜蜂 蚊子 苍蝇 小鸟 小狗 小猫 兔 熊 猪 猴子 狮子 鹅 鸽子 麻雀 鲤鱼 金鱼 青蛙 刺猬 燕子
树叶 树枝 树根 树干 花瓣 树林 森林 草地 花园 花坛 花盆 盆花 桃树 松树 柳树 枫树 荷花 梅花 玫瑰 月季 菊花 莲花
石子 泥土 沙子 河水 小河 池塘 湖 湖水 海水 海边 沙滩 大海 山顶 山脚 山上 山下 爬山 游泳 运动 跑步 散步 唱歌 跳舞 画画
打球 篮球 足球 乒乓球 羽毛球 球拍 拍照 看懂 听懂 说清楚 不清楚 明白了 当然 没错 行了 算了 嗯嗯 天哪 等等 一起 一样
谢谢你 请进 请坐 别客气 没问题 不用了 慢走 小心 注意 当心 加油 休息 帮忙 送礼 买菜 做饭 炒菜 煮饭 烧水 烘干 饭菜
被子 褥子 被褥 床单 枕头 枕套 窗帘 地板 墙 墙壁 门口 门铃 门锁 钥匙 洗面奶 香皂 肥皂 洗发水 沐浴露 水壶 勺 漏勺
保鲜膜 保鲜袋 饭盒 厨具 菜刀 砧板 菜板 锅 平底锅 炒锅 蒸锅 压力锅 锅盖 抹布 海绵 垃圾桶 垃圾箱 扫帚 扫把 拖把 簸箕
剪刀 胶水 胶带 订书机 订书钉 文件夹 笔记本 练习本 信封 邮票 书签 橡皮擦 刨笔刀 转笔刀 纸盒 纸箱 纽扣 拉链 鞋带 钱包
手提包 挎包 背包 行李箱 旅行箱 皮箱 毛衣 外套 大衣 羽绒服 棉袄 棉被 衬衣 短裤 长裤 裙子 凉鞋 皮鞋 球鞋 运动鞋 雨鞋
电动车 自行车 摩托车 出租车 火车 飞机 轮船 小船 地图 路线 路程 地址 邮箱 电话 电话号码 号码 邮件 短信 通知 留言 回信
网上 上网 网络 微信 照片 图片 视频 音乐 电影 新闻 节目 玩具 积木 拼图 滑梯 秋千 气球 绳子 溜冰 跳绳 踢球 捉迷藏
洗菜 切菜 削皮 剥皮 搅拌 尝尝 咬 嚼 吞 吐 闻 摸 抱 推 拉 扔 捡 捡起 收拾 整理 打扫 搬家 修理 带来 带走 递给 还给
开门 关门 开灯 关灯 开水 热水 冷水 温水 冰水 凉水 汽水 可乐 咖啡 啤酒 红茶 绿茶 奶茶 冰淇淋 果酱 黄油 奶酪 芝士
面包店 小卖部 菜市场 商店 店铺 饭店 酒店 宾馆 前台 房间 屋子 楼房 房子 房屋 院子 公园 医院 邮局 图书馆 体育馆 操场
安全 出事 危险 害怕 开心 高兴 生气 伤心 难过 着急 担心 放心 放松 不好意思 抱歉 感谢 祝福 愿望 梦想 希望 期待 觉得 认为
相信 同意 反对 决定 选择 开始 结束 完成 准备 需要 应该 必须 可能 一定 经常 常常 有时 偶尔 总是 从来 已经 正在 终于
'''.split())

ACTIVE_HARDER = set('''医保 社保 报销 合同 押金 租金 物业 处方 抗生素 血压 数据线 蓝牙 挂号 预约 门诊 发票
复诊 补办 货到付款 闸机 导览 记账 理财 汇率 保险 养老金 社保卡 护照 签证 通行证 驾照 身份证 身份证号 户口 居住证
贷款 利息 本金 存款 定期 活期 利率 首付 分期 账单 对账 自动扣款 扣款 转账 汇款 汇入 汇出 余额 信用 透支 偿还 欠款
朦胧 蜈蚣 口腔 化合物 溶液 分子 原子 电子 质子 中子 钙 钠 锌 铁 铜 铝 氧 氢 氮 碳 硫 氯 磷 硅 镁 氦 氖 汞
圆锥 圆柱 直径 半径 周长 面积 体积 长方形 正方形 三角形 平行四边形 奇数 偶数 质数 分数 小数 百分数 乘法 除法 平方
乘积 立方 方程 函数 坐标 概率 统计 平均数 中位数 加减 算式 算术 等号 不等式 等式 单位 公里 千米 厘米 毫米 千克 克
液化 汽化 蒸发 凝固 凝结 冰雹 降雨 降雪 纬度 经度 热带 寒带 温带 赤道 北极 南极 地球 月球 行星 恒星 宇宙 轨道
光线 光波 声波 波长 频率 电流 电压 电阻 电路 串联 并联 电源 电量 充电 放电 导体 绝缘体 电磁 电磁波 电动机 发电机
论据 论点 论证 类比 概括 归纳 总结 分析 综合 推理 判断 假设 观点 结论 证据 事实 道理 理由 条件 原因 结果 目的
资料 信息 资源 材料 过程 步骤 方法 方式 意义 概念 内容 形式 性质 特点 特征 状态 现象 情况 变化 规律 原则 标准 规范
处理器 台式机 界面 工作站 快门 摄影 曝光 光圈 焦距 镜头 像素 分辨率 软件 硬件 程序 系统 操作系统 应用程序 文件 文件名
文件夹 输入法 光标 图标 鼠标 键盘 显示器 触摸屏 屏幕 网页 网站 浏览器 链接 复制 粘贴 保存 删除 恢复 下载 上传 安装
电商 物流 快递单 单号 包裹 运费 寄件 寄出 签收 拒收 退换货 退换 售后 保修 维修 质保 客服 保质期 生产日期 说明书
脱水 消炎 消毒 止痛 退烧 量体温 输液 疫苗 接种 体检 检查 化验 检验 血检 抽血 骨折 擦伤 烫伤 割伤 扭伤 皮肤 骨骼
肌肉 血管 心脏 肺 肝 胃 肠 肾 脾 胆 大脑 神经 细胞 组织 呼吸 消化 吸收 营养 维生素 蛋白质 脂肪 碳水化合物 贫血
脂肪肝 糖尿病 高血压 低血糖 湿疹 溃疡 心悸 牙龈 蛀牙 脱发 感染 炎症 细菌 病毒 传染病 传染 预防 隔离 急救 急诊
救护车 消防车 灭火器 报警 疏散 逃生 防火 防盗 防滑 防晒 防护 安全带 头盔 救生圈 救生衣 警察 民警 公安 消防员
水稻 小麦 玉米 高粱 大麦 燕麦 谷物 农作物 秧苗 种植 施肥 除草 浇水 灌溉 收割 收成 耕地 耕种 肥料 农药 害虫 果树
花蕾 花苞 花丛 花圃 花卉 草坪 芦苇 芹菜 芥菜 菠菜 木柴 木屑 树梢 花纹 鲫鱼 螃蟹 龙虾 贝壳 蛤蜊 章鱼 海豚 鲨鱼 鲸鱼
恭敬 从容 慈祥 朴素 淳朴 诚恳 诚实 勤劳 谦虚 谨慎 勇敢 坚定 团结 友好 热情 耐心 细心 专心 自信 自觉 自豪 独立
灵活 机灵 聪明 愚蠢 狡猾 懒惰 自私 粗鲁 幼稚 成熟 坚强 软弱 脆弱 顽强 倔强 固执 调皮 乖巧 淘气 严肃 严格 严厉
矗立 耸立 崎岖 浑浊 涟漪 凉爽 温暖 温和 湿润 干燥 炎热 寒冷 酷热 严寒 明媚 蔚蓝 辽阔 宽阔 狭窄 雄伟 壮观 美丽
均匀 整齐 杂乱 稀少 稠密 稀疏 丰富 缺乏 充足 充沛 浓郁 浓厚 清淡 淡薄 具体 抽象 复杂 简单 清楚 模糊 明确 详细
思考 思索 联想 想象 记忆 回忆 印象 感受 感觉 感想 体会 体验 领会 理解 解释 表达 描述 叙述 说明 议论 抒情 修辞 比喻
拟人 排比 夸张 对比 象征 反问 设问 寓言 童话 诗歌 诗词 散文 小说 故事 传说 日记 书信 作文 读后感 游记 笔记 记录
叮嘱 嘱咐 催促 鼓励 夸奖 批评 提醒 劝告 建议 请求 要求 命令 安慰 关怀 关心 尊敬 尊重 爱护 保护 维护 责任 义务 权利
得意扬扬 亡羊补牢 掩耳盗铃 刻舟求剑 守株待兔 画蛇添足 画龙点睛 井底之蛙 自相矛盾 狐假虎威 杯弓蛇影
一心一意 三心二意 七嘴八舌 五颜六色 四面八方 千方百计 惟妙惟肖 栩栩如生 彬彬有礼 兴高采烈 垂头丧气 心花怒放
东张西望 左顾右盼 半途而废 不约而同 不知不觉 异口同声 大惊小怪 争先恐后 无边无际 各种各样 多种多样
'''.split())

# Familiarity with these is useful in ordinary news/literature; no active recall demand.
READING = set('''仲裁 渎职 丞相 驿站 衙门 府邸 仕途 廉政 弹劾 弊端 废除 颁布 立法 司法 刑事 民事 行政 宪法 宪政
内讧 夙愿 轴承 匍匐 肃穆 鬃毛 笃定 纵横 怂恿 雕琢 驳船 淋漓 厮杀 歧途 咫尺 揣测 风靡 深邃 作祟 尼姑 帷幕
希冀 馈赠 匮乏 熙熙攘攘 孜孜不倦 趾高气扬 杞人忧天 含辛茹苦 越俎代庖 如火如荼 言简意赅 千钧一发 咄咄逼人
芸芸众生 胸有成竹 随机应变 墨守成规 仁至义尽 众志成城 同舟共济 赴汤蹈火 赏心悦目 背道而驰 风平浪静 车水马龙
会晤 枢纽 迂腐 毋庸 涣散 懈怠 阴霾 澎湃 蜕变 点缀 缔造 拘泥 栖息 端倪 媲美 感慨 颓废 依偎 璀璨 教诲 悖论
甄选 韧性 拮据 静谧 窘迫 茁壮 谦逊 凸显 迄今 蕴含 倔强 波澜 蹒跚 熏陶 陶冶 斟酌 精髓 荟萃 磅礴 磋商
秦朝 汉朝 唐朝 宋朝 元朝 明朝 清朝 春秋 战国 三国 晋朝 隋朝 商朝 周朝 夏朝 西汉 东汉 西晋 东晋 南宋 北宋
孔子 孟子 老子 庄子 韩非子 荀子 墨子 屈原 李白 杜甫 白居易 苏轼 辛弃疾 李清照 鲁迅 巴金 老舍 郭沫若 茅盾
《论语》 论语 史记 诗经 楚辞 孙子兵法 资治通鉴 红楼梦 西游记 水浒传 三国演义 莎士比亚 欧阳修 司马迁 司马光 曹操
'''.split())

ADVANCED = set('''训诂 校勘 音韵 词源 语源 同位素 拓扑 拓扑学 量子 量子力学 相对论 神经网络 深度学习 机器学习
聚合物 高分子 有机合成 离心力 加速度 角速度 角动量 矢量 标量 熵 焓 质谱 色谱 电解质 线粒体 叶绿体 核糖体 染色体
乾坤 乾 朕 寡人 卿 妾 诏 诏书 觐见 朝觐 薨 崩殂 社稷 庙堂 尚书 侍郎 太尉 丞 九卿 中书门下 枢密使 节度使
'''.split())


def dump(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8', newline='\n')


def content_hash(row: dict) -> str:
    return hashlib.sha256(json.dumps([(k, v) for k, v in row.items() if k != '罕度'], ensure_ascii=False,
                                     separators=(',', ':')).encode()).hexdigest()


def read_signals():
    lock = json.loads((DATA / 'source-lock.json').read_text(encoding='utf-8'))
    source_files = {}
    for record in lock['sources']:
        if record['id'].startswith(('frequencywords-', 'hsk-')):
            path = ROOT / '.gradle/vocabulary-v2' / record['file']
            if hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256']:
                raise ValueError('Pedagogical input changed: ' + record['file'])
            source_files[record['file']] = record['sha256']
    hsk = {}
    for level, words in json.loads((ROOT / '.gradle/vocabulary-v2/hsk-all-words.json').read_text(encoding='utf-8')).items():
        for word in words:
            hsk.setdefault(word, level)
    frequency = {line.split()[0]: rank for rank, line in enumerate(
        (ROOT / '.gradle/vocabulary-v2/frequency-zh-cn-2018-50k.txt').read_text(encoding='utf-8').splitlines(), 1)}
    dictionary = ROOT / '.gradle/chinese-resource-source/python/jieba/dict.txt'
    if hashlib.sha256(dictionary.read_bytes()).hexdigest() != JIEBA_SHA256:
        raise ValueError('Existing tokenizer dictionary changed')
    lexical = {fields[0]: int(fields[1]) for line in dictionary.read_text(encoding='utf-8').splitlines()
               if len(fields := line.split()) == 3}
    source_files['existing-jieba-dict.txt'] = JIEBA_SHA256
    # Recognition (Stage 3) includes advanced general reading, not just beginner characters.
    core_characters = set(''.join(hsk))
    return hsk, frequency, lexical, source_files, core_characters


def decide(row, hsk, frequency, lexical, optional, core_characters, editorial):
    word, pos, gloss = row['组词'], row['词性'], row['英文释义']
    rank, token_frequency, level = frequency.get(word), lexical.get(word, 0), hsk.get(word)
    less_familiar_graphs = ''.join(dict.fromkeys(char for char in word if char not in core_characters))
    signals = {'hsk': level, 'subtitleRank': rank, 'tokenizerFrequency': token_frequency,
               'outsideGradedCharacterSet': less_familiar_graphs}
    if row['词条ID'] in optional:
        record = optional[row['词条ID']]
        if (record['hanzi'], record['pinyin']) != (word, row['拼音']) or not record['reason'].strip():
            raise ValueError('Optional sense mismatch: ' + row['词条ID'])
        if record.get('glossSha256') != hashlib.sha256(gloss.encode()).hexdigest():
            raise ValueError('Optional teaching meaning changed: ' + row['词条ID'])
        if level is not None or (rank is not None and rank <= 14000):
            raise ValueError('A core/general-use signal conflicts with optional classification: ' + word)
        return 5, 'optional-reviewed', record['reason'], signals
    if row['词条ID'] in editorial:
        record = editorial[row['词条ID']]
        if ((record['word'], record['pinyin'], record['glossSha256']) !=
                (word, row['拼音'], hashlib.sha256(gloss.encode()).hexdigest())
                or type(record['desiredRarity']) is not int or not 0 <= record['desiredRarity'] <= 4):
            raise ValueError('Editorial teaching sense mismatch: ' + row['词条ID'])
        return record['desiredRarity'], 'sense-reviewed', record['reason'], signals
    for words, value, key, explanation in [
        (FOUNDATION, 0, 'foundation', '简单的基础认知与交流词，要求能背能写'),
        (DAILY, 1, 'daily-active', '日常生活、学校和公共交流的基本词，要求能背能写'),
        (ACTIVE_HARDER, 2, 'harder-active', '较难但应能认能写的通用学习、生活和科学基础词'),
        (READING, 3, 'general-reading-reviewed', '通用阅读、新闻、文学及文化常识中应见过并有印象'),
        (ADVANCED, 4, 'advanced-reviewed', '进阶知识或古典阅读词，暂不设掌握要求'),
    ]:
        if word in words:
            return value, key, explanation, signals
    restricted = bool(re.search(r'\b(?:archaic|obsolete|ancient|classical|literary|dynasty|emperor|imperial|feudal)\b|\(old\)', gloss, re.I))
    technical = bool(re.search(r'\((?:botany|zoology|chemistry|physics|math(?:ematics)?|anatomy|medicine|biology|astronomy|geology|linguistics|phonetics)\)|\b(?:genus|species|enzyme|isotope|alkaloid)\b', gloss, re.I))
    bound = 'bound' in pos
    proper = 'proper' in pos
    idiom = 'idiom' in pos
    if level:
        value = 1 if level in {'hsk1', 'hsk2'} else 2 if level in {'hsk3', 'hsk4'} else 3
        if not (restricted or bound or proper or technical or idiom):
            modern_value = 1 if rank is not None and rank <= 2200 else 2 if (
                (rank is not None and rank <= 18000) or token_frequency >= 800) else 3
            value = min(value, modern_value)
        if restricted or bound:
            value = max(3, value)
        if idiom:
            value = max(2, value)
        return value, 'graded-core', '分级通用词表收录并结合现代使用频次、词性与语体，不按HSK高等级机械上抬日常词', signals
    if proper:
        value = 3 if rank is not None and rank <= 14000 else 4
        return value, 'named-reference', '专名用于阅读识别；不将未核实的专名直接划为可跳过', signals
    if bound or technical:
        return 4, 'specialized-reading', '构词语素或明确窄领域义项，保留为可选进阶阅读', signals
    if restricted:
        value = 3 if (rank is not None and rank <= 28000) or token_frequency >= 300 else 4
        return value, 'marked-register', '有明确古典、历史或文学语体；通用阅读证据足够者为3，其余为4', signals
    if idiom:
        value = 4 if less_familiar_graphs and token_frequency < 300 and (rank is None or rank > 28000) else 3
        return value, 'idiom-reading', '一般成语作为阅读接触词；用字与现代使用证据都偏僻的成语为4，不因字幕缺频进入5', signals
    if rank is not None and rank <= 2200:
        return 1, 'frequent-active', '高频且无专业或古旧语体限制，作为日常主动词汇', signals
    if (rank is not None and rank <= 18000) or token_frequency >= 800:
        return 2, 'modern-active', '字幕或现代书面分词词频的正面通用证据，要求认写', signals
    if len(word) == 1 and token_frequency < 300 and (rank is None or rank > 28000):
        return 4, 'low-use-graph', '现代语料中独立使用较少的单字义；不设掌握要求，缺频本身不构成5', signals
    if less_familiar_graphs and token_frequency < 300 and (rank is None or rank > 28000):
        return 4, 'advanced-graphs', '用字超出分级通用词表字符范围且现代使用证据弱，列为不要求掌握的进阶词', signals
    return 3, 'modern-reading', '无窄领域或古旧语体限制的现代词义，纳入阅读接触而非缺频兜底5', signals


def make_review(rows):
    hsk, frequency, lexical, sources, core_characters = read_signals()
    optional_data = json.loads((DATA / 'rarity-optional-review.json').read_text(encoding='utf-8'))
    if optional_data['inputWordlistSha256'] != BEFORE_SHA256:
        raise ValueError('Optional review was made against another vocabulary snapshot')
    optional = optional_data['words']
    editorial_data = json.loads((DATA / 'rarity-editorial-overrides.json').read_text(encoding='utf-8'))
    if editorial_data['inputWordlistSha256'] != BEFORE_SHA256:
        raise ValueError('Editorial review was made against another vocabulary snapshot')
    editorial = editorial_data['overrides']
    if len(optional) > 2000 or set(optional) - {r['词条ID'] for r in rows}:
        raise ValueError('Optional entries exceed user cap or refer to absent IDs')
    records = {}
    for row in rows:
        value, rule, reason, signals = decide(row, hsk, frequency, lexical, optional, core_characters, editorial)
        records[row['词条ID']] = {'hanzi': row['组词'], 'pinyin': row['拼音'], 'previousRarity': int(row['罕度']),
            'rarity': value, 'rule': rule, 'reason': reason, 'signals': signals, 'contentSha256': content_hash(row)}
    return {'schemaVersion': 1, 'reviewDate': '2026-10-27', 'previousWordlistSha256': BEFORE_SHA256,
            'sourceFingerprints': sources, 'stage5Limit': 2000,
            'boundary': 'AI editorial decisions with locked corpus signals, not human certification or an official school syllabus.',
            'requirements': ['基础词，必须能背能写', '必须能背能写', '较难，必须能认能写，不要求背',
                             '必须见过，有印象', '不设要求', '可以不看'], 'words': records}


def apply_reviewed_rarities(rows):
    """Final expansion pass; never change existing question choices to regrade a word."""
    frozen = json.loads(REVIEW.read_text(encoding='utf-8'))
    review = frozen['words']
    if (frozen['schemaVersion'] != 1 or len(rows) != len(review)
            or any(type(v['rarity']) is not int or not 0 <= v['rarity'] <= 5 for v in review.values())
            or sum(v['rarity'] == 5 for v in review.values()) > 2000):
        raise ValueError('Invalid frozen rarity review or optional-stage limit')
    if set(review) != {r['词条ID'] for r in rows}:
        raise ValueError('Vocabulary membership changed; review pedagogical rarity explicitly')
    for row in rows:
        record = review[row['词条ID']]
        if record['contentSha256'] != content_hash(row):
            raise ValueError('Vocabulary content changed since rarity review: ' + row['词条ID'])
        row['罕度'] = str(record['rarity'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--preview', type=Path)
    mode.add_argument('--write', action='store_true')
    mode.add_argument('--check', action='store_true')
    args = parser.parse_args()
    path = ROOT / 'wordlist.csv'
    payload = path.read_bytes()
    rows = list(csv.DictReader(io.StringIO(payload.decode('utf-8-sig'), newline='')))
    if len(rows) != 18500 or len({r['词条ID'] for r in rows}) != len(rows):
        raise ValueError('The reviewed vocabulary must contain exactly 18,500 distinct stable IDs')
    if (DATA / 'wordlist-refinement-review.json').exists():
        if not args.check:
            raise ValueError('The six-band review is historical; maintain the explicit five-stage refinement overlay')
        from refine_wordlist import check
        check()
        return
    review = make_review(rows)
    if args.check:
        from wordlist_snapshot import verify_snapshot
        verify_snapshot(path)
        frozen = json.loads(REVIEW.read_text(encoding='utf-8'))
        review['finalWordlistSha256'] = hashlib.sha256(payload).hexdigest()
        for key, record in review['words'].items():
            record['previousRarity'] = frozen['words'][key]['previousRarity']
        if frozen != review or any(str(review['words'][r['词条ID']]['rarity']) != r['罕度'] for r in rows):
            raise ValueError('Current vocabulary differs from its reviewed pedagogical decisions')
    elif hashlib.sha256(payload).hexdigest() != BEFORE_SHA256:
        raise ValueError('Rarity-only edit requires the exact recorded 1.6.8 snapshot')
    counts = collections.Counter(str(x['rarity']) for x in review['words'].values())
    moved = sum(x['previousRarity'] == 5 and x['rarity'] < 5 for x in review['words'].values())
    summary = {'words': len(rows), 'previousRarity': dict(collections.Counter(r['罕度'] for r in rows)),
               'rarity': dict(sorted(counts.items())), 'movedFrom5': moved,
               'rules': dict(collections.Counter(r['rule'] for r in review['words'].values()))}
    if args.preview or args.write:
        lines = payload.splitlines(keepends=True)
        if len(lines) != len(rows) + 1 or any(not re.match(rb'^[0-5],', line) for line in lines[1:]):
            raise ValueError('CSV is no longer the expected one-record-per-line layout')
        updated = lines[0] + b''.join(str(review['words'][row['词条ID']]['rarity']).encode() + line[1:]
                                     for row, line in zip(rows, lines[1:]))
        after = list(csv.DictReader(io.StringIO(updated.decode('utf-8-sig'), newline='')))
        if len(payload) != len(updated) or any(content_hash(a) != content_hash(b) for a, b in zip(rows, after)):
            raise ValueError('A rarity-only edit changed teaching content')
        summary['sha256'] = hashlib.sha256(updated).hexdigest()
        summary['bytes'] = len(updated)
        review['finalWordlistSha256'] = summary['sha256']
        target = args.preview if args.preview else DATA
        target.mkdir(parents=True, exist_ok=True)
        dump(target / 'rarity-review.json', review)
        dump(target / 'rarity-rebalance-report.json', summary)
        stream = io.StringIO(newline='')
        writer = csv.writer(stream, lineterminator='\n')
        writer.writerow(['词条ID', '词汇', '拼音', '原罕度', '新罕度', '分级依据', 'HSK参考', '字幕排名', '现代分词频次'])
        for key, record in review['words'].items():
            signals = record['signals']
            writer.writerow([key, record['hanzi'], record['pinyin'], record['previousRarity'], record['rarity'],
                             record['reason'], signals['hsk'], signals['subtitleRank'], signals['tokenizerFrequency']])
        (target / 'rarity-full-review.csv').write_bytes(stream.getvalue().encode('utf-8-sig'))
        if args.preview:
            (target / 'wordlist.csv').write_bytes(updated)
        else:
            # Manifest acceptance is explicit, after membership/content and the cap were checked.
            snapshot_path = ROOT / 'docs/wordlist-validation.json'
            snapshot = json.loads(snapshot_path.read_text(encoding='utf-8'))
            if snapshot['sha256'] != BEFORE_SHA256:
                raise ValueError('Expected previous reviewed snapshot manifest')
            path.write_bytes(updated)
            snapshot.update(sha256=summary['sha256'], rarity=summary['rarity'])
            dump(snapshot_path, snapshot)
            expansion_path = DATA / 'expansion-report.json'
            expansion = json.loads(expansion_path.read_text(encoding='utf-8'))
            expansion.update(sha256=summary['sha256'], rarity=summary['rarity'])
            dump(expansion_path, expansion)
    print(json.dumps(summary, ensure_ascii=False))
    print('Rarity review: ' + ('check passed' if args.check else 'draft written' if args.preview else 'applied; other 11 columns unchanged'))


if __name__ == '__main__':
    main()
