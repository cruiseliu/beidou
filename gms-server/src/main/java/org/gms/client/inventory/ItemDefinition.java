package org.gms.client.inventory;

/**
 * 道具定义（Java 视角，data/item/*.json[c] 条目）：只声明 Java 自己消费的字段。
 *
 * 道具的主要数据源是 wz（经脚本侧 getWzItemData 整树读取）；本 definition 只补充
 * wz 之外的服务端配置。除 itemId/hooks 外的条目字段（倍率 wz 路径、宠物食品配置等）
 * 是脚本载荷，Java 不解释也不声明——脚本经 {@link ItemRegistry#rawJson} 拿到完整
 * 条目原文（树往返序列化，注释/排版规范化，未知字段全保留）。schema = 数据文件本身，
 * 加脚本字段零 Java 改动。
 * <pre>
 * { "itemId": 5211000, "hooks": "item/coupon.js", "expRate": "info/rate" }
 * </pre>
 * hooks = scripts/ 相对路径的 ESM，导出道具钩子（onEnterInventory/onLeaveInventory/onUse 等）。
 */
public record ItemDefinition(
        int itemId,         // 道具 id（Item.wz 的键）
        String hooks        // 钩子脚本路径（scripts/ 相对）
) {
}
