package com.xy2407.kingdom.common.faction;

/**
 * 一个国家（阵营）。
 *
 * <p>两种来源，用 {@link #permanent} 区分：</p>
 * <ul>
 *   <li><b>预置国</b>（瓦拉里安等 12 个）：id 形如 {@code valarian}，
 *       {@code permanent=false}、{@code founderUuid} 为空。
 *       它们<b>不进数据库</b>——由客户端页面硬编码，服务端只做 id 白名单校验。</li>
 *   <li><b>自建国</b>：id 形如 {@code realm-1}，{@code permanent=true}，
 *       记下 {@code founderUuid}。这类国家落在 {@code nation} 表里，
 *       <b>在该存档永久存在、不提供删除入口</b>，其它新玩家首登时能选到它。</li>
 * </ul>
 *
 * @param id          唯一标识
 * @param name        国号
 * @param ruler       君主名
 * @param motto       箴言
 * @param colorLeft   盾徽左半配色（hex，形如 #7a1414）
 * @param colorRight  盾徽右半配色
 * @param emblem      纹章名
 * @param emblemColor 纹章配色
 * @param founderUuid 建国者 UUID（预置国为空串）
 * @param permanent   是否永久保留（自建国为 true，不可删）
 * @param createdDay  建国时的世界天数
 */
public record Nation(String id, String name, String ruler, String motto,
                     String colorLeft, String colorRight,
                     String emblem, String emblemColor,
                     String founderUuid, boolean permanent, int createdDay) {

    public Nation {
        colorLeft = colorLeft == null ? "" : colorLeft;
        colorRight = colorRight == null ? "" : colorRight;
        emblem = emblem == null ? "" : emblem;
        emblemColor = emblemColor == null ? "" : emblemColor;
        founderUuid = founderUuid == null ? "" : founderUuid;
    }

    /** 是否由玩家建立（决定它能不能被删——答案是永远不能）。 */
    public boolean isPlayerFounded() {
        return permanent;
    }
}
