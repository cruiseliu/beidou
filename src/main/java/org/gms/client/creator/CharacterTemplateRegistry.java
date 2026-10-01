package org.gms.client.creator;

import com.alibaba.fastjson2.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色创建模板注册表（对齐 {@link org.gms.client.job.JobRegistry}）：
 * 加载 data/character_template/*.json，结构性问题启动期 fail-fast
 * （id 重复、入口职业码冲突、novice 缺性别候选集）。
 */
public final class CharacterTemplateRegistry {
    private static final Logger log = LoggerFactory.getLogger(CharacterTemplateRegistry.class);

    private static final Map<String, CharacterTemplate> BY_ID = new HashMap<>();
    private static final Map<Integer, CharacterTemplate> BY_NOVICE_JOB_CODE = new HashMap<>();
    private static final List<CharacterTemplate> TEMPLATES = new ArrayList<>();

    static {
        loadFromJsonDir();
    }

    private CharacterTemplateRegistry() {
    }

    private static void loadFromJsonDir() {
        File dir = new File("data/character_template");
        File[] files = dir.isDirectory() ? dir.listFiles((d, name) -> name.endsWith(".json")) : null;
        if (files == null || files.length == 0) {
            log.error("data/character_template 目录不存在或无 JSON 文件，角色创建模板为空");
            return;
        }

        List<String> problems = new ArrayList<>();
        for (File file : files) {
            try {
                String json = Files.readString(file.toPath());
                CharacterTemplate def = JSON.parseObject(json, CharacterTemplate.class);
                if (def == null) {
                    problems.add(file.getName() + ": 解析结果为空");
                    continue;
                }
                if (BY_ID.containsKey(def.id())) {
                    problems.add(file.getName() + ": 模板 id 重复定义 " + def.id());
                    continue;
                }
                if (def.novice() && (def.candidates() == null || def.candidates().male() == null || def.candidates().female() == null)) {
                    problems.add(file.getName() + ": novice 模板缺性别候选集路径");
                }
                BY_ID.put(def.id(), def);
                TEMPLATES.add(def);
                log.info("加载角色创建模板 {} (jobId={} level={} mapId={})", file.getName(), def.jobId(), def.level(), def.mapId());
            } catch (IOException | RuntimeException e) {
                problems.add(file.getName() + ": " + e.getMessage());
            }
        }

        // 入口职业码唯一性：novice 码全局唯一；mapleLife 码允许男女模板成对共享（性别分文件），
        // 但同一 (码, 性别) 只允许一个模板提供候选集
        for (CharacterTemplate def : TEMPLATES) {
            if (def.noviceJobCode() >= 0) {
                CharacterTemplate prev = BY_NOVICE_JOB_CODE.put(def.noviceJobCode(), def);
                if (prev != null) {
                    problems.add("noviceJobCode " + def.noviceJobCode() + " 冲突: " + prev.id() + " / " + def.id());
                }
            }
        }
        Map<String, String> seenMapleLife = new HashMap<>();
        for (CharacterTemplate def : TEMPLATES) {
            if (def.mapleLifeJobCode() < 0) {
                continue;
            }
            for (String gender : new String[]{"male", "female"}) {
                if (def.candidates() == null || ("male".equals(gender) ? def.candidates().male() : def.candidates().female()) == null) {
                    continue;
                }
                String key = def.mapleLifeJobCode() + ":" + gender;
                String prev = seenMapleLife.put(key, def.id());
                if (prev != null) {
                    problems.add("mapleLifeJobCode " + def.mapleLifeJobCode() + " 的 " + gender + " 候选冲突: " + prev + " / " + def.id());
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("角色创建模板存在问题: " + String.join("; ", problems));
        }
    }

    /** 选角界面职业码 → novice 模板；无此入口或模板非 novice 返回 null */
    public static CharacterTemplate byNoviceJobCode(int code) {
        CharacterTemplate t = BY_NOVICE_JOB_CODE.get(code);
        return t != null && t.novice() ? t : null;
    }

    /** 老兵卡职业码 + 性别 → 模板（男女成对共享码，按性别取候选集非空者） */
    public static CharacterTemplate byMapleLifeJobCode(int code, boolean female) {
        return TEMPLATES.stream()
                .filter(t -> t.mapleLifeJobCode() == code && t.candidatesFor(female) != null)
                .findFirst()
                .orElse(null);
    }

    public static CharacterTemplate byId(String id) {
        return BY_ID.get(id);
    }
}
