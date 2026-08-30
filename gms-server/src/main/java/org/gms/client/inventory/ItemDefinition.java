package org.gms.client.inventory;

/**
 * 道具定义补充（数据驱动，data/item/*.json）。
 *
 * 道具的主要数据源是 wz（经脚本侧 getWzItemData 整树读取）；本 definition 只补充
 * wz 之外的服务端配置：钩子脚本路径、倍率字段映射。没有需要补充信息的道具不创建
 * JSON 文件；为 null 的字段在 JSON 中不写出。
 * <pre>
 * { "itemId": 5211000, "hooks": "item/coupon.js", "expRate": "info/rate" }
 * { "itemId": 5360000, "hooks": "item/coupon.js", "mesoRate": "info/rate", "dropRate": "info/rate" }
 * </pre>
 * hooks = scripts/ 相对路径的 ESM，导出道具钩子（onEnterInventory/onLeaveInventory，未来
 * onEquip 等）；rate 映射 = kind（exp/meso/drop）→ 该倍率真值在 wz 道具节点中的路径，
 * 由脚本按路径读取，Java 不解释。
 */
public record ItemDefinition(
        int itemId,         // 道具 id（Item.wz 的键）
        String hooks,       // 钩子脚本路径（scripts/ 相对）
        String expRate,     // 经验倍率真值的 wz 路径（无则 null）
        String mesoRate,    // 金币倍率真值的 wz 路径（无则 null）
        String dropRate     // 掉落倍率真值的 wz 路径（无则 null）
) {
}
