package org.gms.remote.gms083.utils;

import org.gms.constants.string.CharsetConstants;
import org.gms.net.packet.OutPacket;

import java.awt.*;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * gms083 packet record 的 wire 组装器：write 系列与 {@link OutPacket} 同面（小端序、
 * writeString = short 长度前缀 + 字符集内容），实现该接口以兼容既有编码入口
 * （PacketCreator.addItemInfo 等接受 OutPacket 的方法可直接传入）。
 *
 * <p>与 ByteBufOutPacket 的差异：字符集在构造时固定（默认客户端语言 0），不读
 * ThreadLocal——语义层编码多发生在会话 strand 任务内，与既有 queued 路径行为一致。
 * 远端 record 的 encode 不再依赖 net.packet 层的 OutPacket 具体实现。
 */
public final class ByteBufBuilder implements OutPacket {

    private final io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
    private final Charset charset;

    public ByteBufBuilder() {
        this(CharsetConstants.getCharset(0));
    }

    public ByteBufBuilder(Charset charset) {
        this.charset = charset;
    }

    @Override
    public void writeByte(byte value) {
        buf.writeByte(value);
    }

    @Override
    public void writeByte(int value) {
        buf.writeByte(value);
    }

    @Override
    public void writeBytes(byte[] value) {
        buf.writeBytes(value);
    }

    @Override
    public void writeShort(int value) {
        buf.writeShortLE(value);
    }

    @Override
    public void writeInt(int value) {
        buf.writeIntLE(value);
    }

    @Override
    public void writeLong(long value) {
        buf.writeLongLE(value);
    }

    @Override
    public void writeBool(boolean value) {
        buf.writeByte(value ? 1 : 0);
    }

    @Override
    public void writeString(String value) {
        byte[] bytes = value.getBytes(charset);
        writeShort(bytes.length);
        writeBytes(bytes);
    }

    @Override
    public void writeFixedString(String value) {
        writeFixedString(value, 13);
    }

    @Override
    public void writeFixedString(String value, int fixed) {
        writeBytes(Arrays.copyOf(value.getBytes(charset), fixed));
    }

    @Override
    public void writePos(Point value) {
        writeShort((short) value.getX());
        writeShort((short) value.getY());
    }

    @Override
    public void skip(int numberOfBytes) {
        buf.writeZero(numberOfBytes);
    }

    @Override
    public byte[] getBytes() {
        return io.netty.buffer.ByteBufUtil.getBytes(buf);
    }

    /** 组装结果（netty 视图；opcode 前缀由调用方按包型写入） */
    public io.netty.buffer.ByteBuf build() {
        return buf;
    }
}
