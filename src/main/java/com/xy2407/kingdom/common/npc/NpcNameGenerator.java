package com.xy2407.kingdom.common.npc;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 市民姓名生成：百家姓单姓与复姓随机取姓，再按性别从名池随机组合。
 */
public final class NpcNameGenerator {

    private static final String[] SURNAMES;
    private static final String[] MALE;
    private static final String[] FEMALE;

    static {
        SURNAMES = split("赵 钱 孙 李 周 吴 郑 王 冯 陈 褚 卫 蒋 沈 韩 杨 朱 秦 尤 许 何 吕 施 张 孔 曹 严 华 金 魏 "
                + "陶 姜 戚 谢 邹 喻 柏 水 窦 章 云 苏 潘 葛 奚 范 彭 郎 鲁 韦 昌 马 苗 凤 花 方 俞 任 袁 柳 "
                + "酆 鲍 史 唐 费 廉 岑 薛 雷 贺 倪 汤 滕 殷 罗 毕 郝 邬 安 常 乐 于 时 傅 皮 卞 齐 康 伍 余 "
                + "元 卜 顾 孟 平 黄 和 穆 萧 尹 姚 邵 湛 汪 祁 毛 禹 狄 米 贝 明 臧 计 伏 成 戴 谈 宋 茅 庞 "
                + "熊 纪 舒 屈 项 祝 董 梁 杜 阮 蓝 闵 席 季 麻 强 贾 路 娄 危 江 童 颜 郭 梅 盛 林 刁 钟 徐 "
                + "邱 骆 高 夏 蔡 田 樊 胡 凌 霍 虞 万 支 柯 昝 管 卢 莫 经 房 裘 缪 干 解 应 宗 丁 宣 贲 邓 "
                + "郁 单 杭 洪 包 诸 左 石 崔 吉 钮 龚 程 嵇 邢 滑 裴 陆 荣 翁 荀 羊 於 惠 甄 曲 家 封 芮 羿 "
                + "储 靳 汲 邴 糜 松 井 段 富 巫 乌 焦 巴 弓 牧 隗 山 谷 车 侯 宓 蓬 全 郗 班 仰 秋 仲 伊 宫 "
                + "宁 仇 栾 暴 甘 钭 厉 戎 祖 武 符 刘 景 詹 束 龙 叶 幸 司 韶 郜 黎 蓟 薄 印 宿 白 怀 蒲 邰 "
                + "从 鄂 索 咸 籍 赖 卓 蔺 屠 蒙 池 乔 阴 郁 胥 能 苍 双 闻 莘 党 翟 谭 贡 劳 逄 姬 申 扶 堵 "
                + "冉 宰 郦 雍 郤 璩 桑 桂 濮 牛 寿 通 边 扈 燕 冀 郏 浦 尚 农 温 别 庄 晏 柴 瞿 阎 充 慕 连 "
                + "茹 习 宦 艾 鱼 容 向 古 易 慎 戈 廖 庾 终 暨 居 衡 步 都 耿 满 弘 匡 国 文 寇 广 禄 阙 东 "
                + "欧 殳 沃 利 蔚 越 夔 隆 师 巩 厍 聂 晁 勾 敖 融 冷 訾 辛 阚 那 简 饶 空 曾 毋 沙 乜 养 鞠 "
                + "须 丰 巢 关 蒯 相 查 后 荆 红 游 竺 权 逯 盖 益 桓 公 "
                + "万俟 司马 上官 欧阳 夏侯 诸葛 闻人 东方 赫连 皇甫 尉迟 公羊 澹台 公冶 宗政 濮阳 淳于 "
                + "单于 太叔 申屠 公孙 仲孙 轩辕 令狐 钟离 宇文 长孙 慕容 鲜于 闾丘 司徒 司空 亓官 司寇 "
                + "仉督 子车 颛孙 端木 巫马 公西 漆雕 乐正 壤驷 公良 拓跋 夹谷 宰父 穀梁 晋楚 闫法 汝鄢 "
                + "涂钦 段干 百里 东郭 南门 呼延 归海 羊舌 微生");
        MALE = split("承 景 明 昭 景承 知远 修言 书衡 川岳 泽宇 衡 远 谦 弘毅 安和 诚砚 朗川 启明 松远 承宇 "
                + "毅 卓 言 明昭 衡远 谦益 卓然 言川 知舟 书砚 知 书 砚 砚舟 屿岚 岳峰 海川 渊泽 瀚文 朗月 "
                + "舟 川 屿 启川 弘文 安宁 宁远 厚德 诚信 笃行 岚 岳 海 泰和 康宁 健行 修远 正行 恒川 竹清 "
                + "渊 泽 瀚 松风 辰宇 景行 明哲 昭华 衡山 远航 朗 启 弘 谦和 毅川 卓远 言溪 知言 书言 墨川 "
                + "安 宁 厚 舟远 川宁 屿川 岚峰 岳川 海宁 渊明 诚 信 笃 泽川 瀚海 朗然 启行 弘远 安川 宁川 "
                + "泰 康 健 厚川 诚川 信川 笃川 泰川 康川 健川 修 正 恒 修川 正川 恒远 竹川 松川 辰川 景川 "
                + "松 竹 辰 明川 昭川 衡川 远川 谦川 毅远 卓川");
        FEMALE = split("婉 清 柔 雅 清婉 知涵 若溪 芷宁 昕月 舒然 静 娴 舒 雅琴 思韵 书瑶 沁棠 静姝 云舒 婉清 "
                + "恬 悦 怡 柔嘉 雅静 静娴 舒宁 恬悦 怡然 若兰 然 若 芷 芷兰 昕曦 晗晴 晴月 星云 露雪 灵羽 "
                + "兰 昕 曦 薇蓉 萱菲 蕊桐 樱棠 溪澜 沁知 涵思 晗 晴 月 慧心 清雅 柔婉 静雅 舒雅 恬宁 悦心 "
                + "星 云 露 怡宁 然若 若芷 兰昕 曦月 晗月 晴岚 雪 灵 羽 星月 云溪 露荷 雪莹 灵溪 羽萱 薇宁 "
                + "薇 蓉 萱 蓉清 萱宁 菲儿 蕊心 桐月 樱宁 棠溪 菲 蕊 桐 澜清 沁心 知月 涵月 思宁 慧宁 清宁 "
                + "樱 棠 溪 清月 清溪 柔宁 雅宁 静宁 舒月 恬月 澜 沁 知 悦宁 怡月 然宁 若宁 芷月 兰宁 昕宁 "
                + "涵 思 慧 曦宁 晗宁 晴宁 星宁 云宁 露宁 清和");
    }

    private NpcNameGenerator() {
    }

    public static String generate(String gender) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String surname = SURNAMES[random.nextInt(SURNAMES.length)];
        String[] pool = "FEMALE".equalsIgnoreCase(gender) ? FEMALE : MALE;
        return surname + pool[random.nextInt(pool.length)];
    }

    private static String[] split(String raw) {
        return raw.trim().split("\\s+");
    }
}
