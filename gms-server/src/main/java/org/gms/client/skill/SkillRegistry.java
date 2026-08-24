package org.gms.client.skill;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 技能定义注册表：从 gms-server/data/skill/*.json 加载（只加载有补充信息的技能）。
 *
 * 与 WeaponTypeRegistry 不同：不是所有技能都有 definition（技能主数据源是 wz），
 * 因此 {@link #of(int)} 对未登记技能返回 null（而非抛异常）。
 */
public final class SkillRegistry {
    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private static final Map<Integer, SkillDefinition> DEFS = new HashMap<>();

    static {
        loadFromJsonDir();
    }

    private SkillRegistry() {
    }

    private static void loadFromJsonDir() {
        File dir = new File("data/skill");
        File[] files = dir.isDirectory() ? dir.listFiles((d, name) -> name.endsWith(".json")) : null;
        if (files == null || files.length == 0) {
            log.warn("data/skill 目录不存在或无 JSON 文件，技能定义注册表为空");
            return;
        }

        for (File file : files) {
            try {
                String json = Files.readString(file.toPath());
                SkillDefinition def = JSON.parseObject(json, SkillDefinition.class);
                if (def == null) {
                    log.warn("解析失败: {}", file.getName());
                    continue;
                }
                SkillDefinition prev = DEFS.put(def.skillId(), def);
                if (prev != null) {
                    log.warn("技能 id {} 重复定义: {}", def.skillId(), file.getName());
                }
                log.info("加载技能定义 {} (skillId={})", file.getName(), def.skillId());
            } catch (IOException | RuntimeException e) {
                log.error("加载技能定义失败: {}", file.getName(), e);
            }
        }
    }

    /** 按技能 id 取定义；未登记返回 null（不是所有技能都有补充定义） */
    public static SkillDefinition of(int skillId) {
        return DEFS.get(skillId);
    }

    /** 全部已加载定义（只读视图） */
    public static Map<Integer, SkillDefinition> all() {
        return Map.copyOf(DEFS);
    }
}
