package org.gms.remote.gms083.server.routers;

import org.gms.net.packet.Packet;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.client.packets.MoveLifePacket;
import org.gms.remote.gms083.client.packets.MovePlayerPacket;
import org.gms.remote.modules.map.client.MapModule;
import org.gms.remote.modules.map.client.MonsterMove;
import org.gms.remote.modules.map.client.movement.MoveElement;

import java.awt.*;
import java.util.List;

/** 地图域视图：移动中继编码（成品包，收播过滤归地图侧）+ mob 移动 ack 直发。 */
public final class MapRouter implements MapModule {

    private final Gms083 client;

    public MapRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    public void characterMove(int charId, List<MoveElement> movements) {
        client.send(MovePlayerPacket.relay(charId, movements));
    }

    @Override
    public void ackMoveMonster(int oid, short moveid, int currentMp, boolean useSkills, int skillId, int skillLevel) {
        client.send(new MoveLifePacket.Response(oid, moveid, currentMp, useSkills, skillId, skillLevel));
    }

    @Override
    public void monsterMove(MonsterMove move) {
        client.send(new MoveLifePacket.Relay(move.oid(), move.skillPossible(), move.skill(),
                move.skillId(), move.skillLevel(), move.pOption(), move.startPos(), move.elements()));
    }
}
