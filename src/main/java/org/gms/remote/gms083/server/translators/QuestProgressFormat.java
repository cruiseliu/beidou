package org.gms.remote.gms083.server.translators;

import java.util.Map;

/**
 * 翻译 util：语义进度表（mobId → 进度值，插入序）→ v83 任务进度 wire 串
 * （SHOW_STATUS_INFO quest 体 / SET_FIELD quests 段：各 mob 进度值按表序逐个串接）。
 * 输入输出均为基础类型——不落地字节（字节写入归 packet 层）。
 */
public final class QuestProgressFormat {

    private QuestProgressFormat() {
    }

    public static String toWire(Map<Integer, String> progress) {
        StringBuilder sb = new StringBuilder();
        for (String value : progress.values()) {
            sb.append(value);
        }
        return sb.toString();
    }
}
