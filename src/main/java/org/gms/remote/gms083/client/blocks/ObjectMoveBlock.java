package org.gms.remote.gms083.client.blocks;

import java.util.ArrayList;
import java.util.List;

import org.gms.remote.gms083.utils.ByteBufBuilder;
import org.gms.remote.gms083.utils.ByteBufReader;
import org.gms.util.AssertUtil;

public record ObjectMoveBlock(List<G83MoveElement> elements) {
    public interface G83MoveElement {}

    private interface MoveElementInternal extends G83MoveElement {
        void encode(ByteBufBuilder p);
    }

    public void encode(ByteBufBuilder p) {
        p.writeByte(elements.size());
        for (G83MoveElement element : elements) {
            ((MoveElementInternal) element).encode(p);
        }
    }

    public static ObjectMoveBlock decode(ByteBufReader p) {
        int length = p.readByte();
        List<G83MoveElement> elements = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            elements.add(decodeElement(p));
        }
        return new ObjectMoveBlock(elements);
    }

    private static G83MoveElement decodeElement(ByteBufReader p) {
        int command = p.readByte();
        switch (command) {
            case 0 -> {
                return G83AbsoluteMove.decode(command, p);
            }
            case 2 -> {
                return G83RelativeMove.decode(command, p);
            }
            default -> throw AssertUtil.never("Unexpected move command " + command);
        }
    }

    public record G83AbsoluteMove(
        int command,
        int x,
        int y,
        int ppsX,
        int ppsY,
        int fh,
        int stance,
        int duration
    ) implements MoveElementInternal {

        public void encode(ByteBufBuilder p) {
            p.writeByte(command);
            p.writeShort(x);
            p.writeShort(y);
            p.writeShort(ppsX);
            p.writeShort(ppsY);
            p.writeShort(fh);
            p.writeByte(stance);
            p.writeShort(duration);
        }

        static G83AbsoluteMove decode(int command, ByteBufReader p) {
            return new G83AbsoluteMove(
                command,
                p.readShort(),
                p.readShort(),
                p.readShort(),
                p.readShort(),
                p.readShort(),
                p.readByte(),
                p.readShort()
            );
        }
    }

    public record G83RelativeMove(
        int command,
        int x,
        int y,
        int stance,
        int duration
    ) implements MoveElementInternal {

        public void encode(ByteBufBuilder p) {
            p.writeByte(command);
            p.writeShort(x);
            p.writeShort(y);
            p.writeByte(stance);
            p.writeShort(duration);
        }

        static G83RelativeMove decode(int command, ByteBufReader p) {
            return new G83RelativeMove(
                command,
                p.readShort(),
                p.readShort(),
                p.readByte(),
                p.readShort()
            );
        }
    }
}
