package org.gms.net.packet.logging;

import io.netty.buffer.Unpooled;
import org.gms.constants.net.OpcodeConstants;
import org.gms.util.HexTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingUtil {
    private static final Logger log = LoggerFactory.getLogger(LoggingUtil.class);
    private static final int LOG_CONTENT_THRESHOLD = 3_000;

    public static short readFirstShort(byte[] bytes) {
        return Unpooled.wrappedBuffer(bytes).readShortLE();
    }

    /**
     * legacy 收向包 hex 行（分派点输出，吃 readShort 之前的全帧快照）。
     * remote 接管的 opcode 不走此路径——由 {@code [remote-in]}（AbstractInRouter）结构化
     * 单源记录，判定源自分发表，无平行 opcode 清单。
     */
    public static void logClientSend(byte[] content) {
        final int packetLength = content.length;

        if (packetLength > LOG_CONTENT_THRESHOLD) {
            log.info("<OversizedPacket> ... {}", HexTool.toHexString(new byte[]{content[0], content[1]}));
            return;
        }

        final short opcode = readFirstShort(content);
        final String opcodeHex = Integer.toHexString(opcode).toUpperCase();
        final String opcodeName = OpcodeConstants.recvOpcodeNames.get((int) opcode);
        final String prefix = opcodeName == null ? "<UnknownPacket> " : "";
        log.info("{}ClientSend:{} [{}] ({}) <HEX> {} <TEXT> {}", prefix, opcodeName, opcodeHex, packetLength,
                HexTool.toHexString(content), HexTool.toStringFromCharset(content));
    }
}
