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
 * 道具定义注册表：从 gms-server/data/item/*.json[c] 加载（只加载有补充信息的道具）。
 *
 * 与 SkillRegistry 同构：不是所有道具都有 definition（道具主数据源是 wz），
 * {@link #of(int)} 对未登记道具返回 null（而非抛异常）。
 * 装载失败（目录缺失/解析失败/id 重复）直接抛异常——数据错误在启动期就要炸出来。
 *
 * 双视图：{@link #of(int)} 返回 Java 消费字段的 record（itemId/hooks）；
 * {@link #rawJson(int)} 返回条目原文（树往返序列化）给脚本——文件里的全部字段
 * （含 record 未声明的脚本载荷）原样透传，注释/排版在装载期规范化掉（fastjson2
 * 解析吃 jsonc，输出干净 JSON；JS 侧 JSON.parse 严格，恰好衔接）。schema = 数据文件本身。
 *
 * jsonc 注释：fastjson2 2.0.47 的注释容忍是解析路径相关的（根数组元素对象内部
 * 的成员间注释会抛 "character /"，官方文档"2.x 默认支持注释"未披露此限定），
 * 2.0.65 起上游已修复、任意位置可用（work/testjsonc.jar 可复测）。
 */
public final class ItemRegistry {
    private static final Map<Integer, ItemDefinition> DEFS = new HashMap<>();
    private static final Map<Integer, String> RAW = new HashMap<>();

    static {
        loadFromJsonDir();
    }

    private ItemRegistry() {
    }

    private static void loadFromJsonDir() {
        File dir = new File("data/item");
        for (File file : dir.listFiles((d, name) -> name.endsWith(".json") || name.endsWith(".jsonc"))) {
            try {
                // 单文件多条目数组（类比 wz 一 img 多条目）；非数组输入不检测，转换自然抛异常
                for (Object entry : (List<?>) JSON.parse(Files.readString(file.toPath()))) {
                    JSONObject json = (JSONObject) entry;
                    ItemDefinition def = json.to(ItemDefinition.class);
                    ItemDefinition prev = DEFS.put(def.itemId(), def);
                    if (prev != null) {
                        throw new IllegalStateException("道具 id 重复定义: " + def.itemId() + "（" + prev + " / " + def + "）");
                    }
                    RAW.put(def.itemId(), json.toString());
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

    /** 条目原文（装载期树往返序列化，注释/排版已规范化）；未登记返回 null。脚本侧经 bind_player.js JSON.parse 消费 */
    public static String rawJson(int itemId) {
        return RAW.get(itemId);
    }

    /** 全部已加载定义（只读视图） */
    public static Map<Integer, ItemDefinition> all() {
        return Map.copyOf(DEFS);
    }
}
