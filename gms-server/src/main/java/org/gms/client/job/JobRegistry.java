package org.gms.client.job;

import com.alibaba.fastjson2.JSON;
import org.gms.client.JobEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 职业定义注册表：从 gms-server/data/job/*.json 加载（服务端/客户端双份，打包工具 portable 格式）。
 *
 * 每个职业一个 JSON（文件名即职业名，内容含 jobId 作键）；缺失 = 启动校验失败（防漏）。
 */
public final class JobRegistry {
    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    private static final Map<Integer, JobDefinition> DEFS = new HashMap<>();

    static {
        loadFromJsonDir();
    }

    private JobRegistry() {
    }

    private static void loadFromJsonDir() {
        File dir = new File("data/job");
        File[] files = dir.isDirectory() ? dir.listFiles((d, name) -> name.endsWith(".json")) : null;
        if (files == null || files.length == 0) {
            log.warn("data/job 目录不存在或无 JSON 文件，职业定义注册表为空");
            return;
        }

        for (File file : files) {
            try {
                String json = Files.readString(file.toPath());
                JobDefinition def = JSON.parseObject(json, JobDefinition.class);
                if (def == null) {
                    log.warn("解析失败: {}", file.getName());
                    continue;
                }
                JobDefinition prev = DEFS.put(def.jobId(), def);
                if (prev != null) {
                    log.warn("职业 id {} 重复定义: {}", def.jobId(), file.getName());
                }
                log.info("加载职业定义 {} (id={})", file.getName(), def.jobId());
            } catch (IOException | RuntimeException e) {
                log.error("加载职业定义失败: {}", file.getName(), e);
            }
        }
    }

    /** 启动校验：所有 JobEnum 均已登记（缺失抛异常，防漏） */
    public static void validate() {
        for (JobEnum job : JobEnum.values()) {
            if (!DEFS.containsKey(job.getId())) {
                throw new IllegalStateException("未登记的职业定义: " + job);
            }
        }
    }

    public static JobDefinition of(JobEnum job) {
        return of(job.getId());
    }

    public static JobDefinition of(int jobId) {
        JobDefinition def = DEFS.get(jobId);
        if (def == null) {
            throw new IllegalStateException("未登记的职业定义 jobId=" + jobId + "（data/job 缺失该职业 JSON）");
        }
        return def;
    }
}
