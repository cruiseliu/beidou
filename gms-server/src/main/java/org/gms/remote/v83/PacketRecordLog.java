package org.gms.remote.v83;

import com.alibaba.fastjson2.JSON;
import org.gms.config.GameConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 语义通道的 packet record 日志：encode 调用点传入 packet record，输出其 JSON。
 * BytesPacket 帧不经 OutPacketLogger（仅记录 OutPacket），语义通道以此补齐可观测性。
 * 受 use_debug_show_packet 开关控制。
 */
public final class PacketRecordLog {
    private static final Logger log = LoggerFactory.getLogger(PacketRecordLog.class);

    private PacketRecordLog() {
    }

    public static void debug(Object packetRecord) {
        if (packetRecord == null || !GameConfig.getServerBoolean("use_debug_show_packet")) {
            return;
        }
        log.info("[remote] {}", JSON.toJSONString(packetRecord));
    }
}
