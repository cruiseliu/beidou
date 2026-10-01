package org.gms.remote.gms083.utils;

import org.gms.constants.string.CharsetConstants;
import org.gms.net.packet.InPacket;

import java.awt.*;
import java.nio.charset.Charset;

/**
 * gms083 收包读侧（与 {@link ByteBufBuilder} 逐方法对称）：小端序、readString = short
 * 长度前缀 + 字符集内容。字符集构造期固定（对称 ByteBufBuilder，不读 ThreadLocal）——
 * 收侧由 shim 按会话语言显式注入；无参构造默认客户端语言 0。
 *
 * <p>实现 InPacket 以兼容既有读入口（对称 ByteBufBuilder 实现 OutPacket 的兼容策略）；
 * 但 net 层类型不越过 Gms083ShimHandler——版本 codec 的 decode 只见本类。
 */
public final class ByteBufReader implements InPacket {

    private final io.netty.buffer.ByteBuf buf;
    private final Charset charset;

    public ByteBufReader(byte[] bytes) {
        this(bytes, CharsetConstants.getCharset(0));
    }

    public ByteBufReader(byte[] bytes, Charset charset) {
        this.buf = io.netty.buffer.Unpooled.wrappedBuffer(bytes);
        this.charset = charset;
    }

    @Override
    public byte readByte() {
        return buf.readByte();
    }

    @Override
    public short readUnsignedByte() {
        return (short) buf.readUnsignedByte();
    }

    @Override
    public short readShort() {
        return buf.readShortLE();
    }

    @Override
    public int readInt() {
        return buf.readIntLE();
    }

    @Override
    public long readLong() {
        return buf.readLongLE();
    }

    @Override
    public Point readPos() {
        short x = buf.readShortLE();
        short y = buf.readShortLE();
        return new Point(x, y);
    }

    @Override
    public String readString() {
        short length = readShort();
        return new String(readBytes(length), charset);
    }

    @Override
    public byte[] readBytes(int numberOfBytes) {
        byte[] bytes = new byte[numberOfBytes];
        buf.readBytes(bytes);
        return bytes;
    }

    @Override
    public void skip(int numberOfBytes) {
        buf.skipBytes(numberOfBytes);
    }

    @Override
    public int available() {
        return buf.readableBytes();
    }

    @Override
    public void seek(int byteOffset) {
        buf.readerIndex(byteOffset);
    }

    @Override
    public int getPosition() {
        return buf.readerIndex();
    }

    @Override
    public byte[] getBytes() {
        return io.netty.buffer.ByteBufUtil.getBytes(buf);
    }
}
