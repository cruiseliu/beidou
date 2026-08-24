package org.gms.client.weaponType;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 武器类型注册表：从 gms-server/data/weapon_type/*.json 加载（每个武器类型一个 JSON）。
 *
 * 查询方式：
 * - {@link #of(int itemId)}：按物品 id 匹配（匹配 itemIdRange）
 * - {@link #byTypeId(int)}：按段号（typeId）直接取
 * 徒手（BARE_HAND）不在注册表内，保留现有特判。
 */
public final class WeaponTypeRegistry {
    private static final Logger log = LoggerFactory.getLogger(WeaponTypeRegistry.class);

    private static final List<WeaponTypeDefinition> DEFS = new ArrayList<>();

    static {
        loadFromJsonDir();
    }

    private WeaponTypeRegistry() {
    }

    private static void loadFromJsonDir() {
        File dir = new File("data/weapon_type");
        File[] files = dir.isDirectory() ? dir.listFiles((d, name) -> name.endsWith(".json")) : null;
        if (files == null || files.length == 0) {
            log.warn("data/weapon_type 目录不存在或无 JSON 文件，武器类型注册表为空");
            return;
        }

        for (File file : files) {
            try {
                String json = Files.readString(file.toPath());
                WeaponTypeDefinition def = JSON.parseObject(json, WeaponTypeDefinition.class);
                if (def == null) {
                    log.warn("解析失败: {}", file.getName());
                    continue;
                }
                DEFS.add(def);
                log.info("加载武器定义 {} (typeId={})", file.getName(), def.typeId());
            } catch (IOException | RuntimeException e) {
                log.error("加载武器定义失败: {}", file.getName(), e);
            }
        }
    }

    /** 按物品 id 匹配武器类型；未登记抛异常（data/weapon_type 缺失该武器类型 JSON） */
    public static WeaponTypeDefinition of(int itemId) {    // fixme: [refactor] use bi-search
        for (WeaponTypeDefinition def : DEFS) {
            if (def.matches(itemId)) {
                return def;
            }
        }
        throw new IllegalStateException("未登记的武器类型 itemId=" + itemId + "（data/weapon_type 缺失该武器类型 JSON）");
    }

    /** 按段号取武器类型；未登记抛异常 */
    public static WeaponTypeDefinition byTypeId(int typeId) {
        for (WeaponTypeDefinition def : DEFS) {
            if (def.typeId() == typeId) {
                return def;
            }
        }
        throw new IllegalStateException("未登记的武器类型段号 typeId=" + typeId + "（data/weapon_type 缺失该武器类型 JSON）");
    }

    /** 全部已加载定义（只读视图） */
    public static List<WeaponTypeDefinition> all() {
        return List.copyOf(DEFS);
    }
}
