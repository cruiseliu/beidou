package org.gms.client.inventory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 道具定义注册表：从 gms-server/data/item/*.json 加载（只加载有补充信息的道具）。
 *
 * 与 SkillRegistry 同构：不是所有道具都有 definition（道具主数据源是 wz），
 * {@link #of(int)} 对未登记道具返回 null（而非抛异常）。
 * 装载失败（目录缺失/解析失败/id 重复）直接抛异常——数据错误在启动期就要炸出来。
 */
public final class ItemRegistry {
    private static final Map<Integer, ItemDefinition> DEFS = new HashMap<>();

    static {
        loadFromJsonDir();
    }

    private ItemRegistry() {
    }

    private static void loadFromJsonDir() {
        File dir = new File("data/item");
        for (File file : dir.listFiles((d, name) -> name.endsWith(".json"))) {
            try {
                // 单文件多条目数组（类比 wz 一 img 多条目）；非数组输入不检测，转换自然抛异常
                for (Object entry : (List<?>) JSON.parse(Files.readString(file.toPath()))) {
                    ItemDefinition def = ((JSONObject) entry).to(ItemDefinition.class);
                    ItemDefinition prev = DEFS.put(def.itemId(), def);
                    if (prev != null) {
                        throw new IllegalStateException("道具 id 重复定义: " + def.itemId() + "（" + prev + " / " + def + "）");
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("读取道具定义失败: " + file, e);
            }
        }
    }

    /** 按道具 id 取定义；未登记返回 null（不是所有道具都有补充定义） */
    public static ItemDefinition of(int itemId) {
        return DEFS.get(itemId);
    }

    /** 全部已加载定义（只读视图） */
    public static Map<Integer, ItemDefinition> all() {
        return Map.copyOf(DEFS);
    }
}
