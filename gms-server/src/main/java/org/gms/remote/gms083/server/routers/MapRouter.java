package org.gms.remote.gms083.server.routers;

import org.gms.net.packet.Packet;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.client.packets.MoveLifePacket;
import org.gms.remote.gms083.client.packets.MovePlayerPacket;
import org.gms.remote.modules.map.client.MapModule;
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
    public Packet movePlayer(int charId, List<MoveElement> elements) {
        return client.toLegacyPacket(MovePlayerPacket.relay(charId, elements));
    }

    @Override
    public void ackMoveMonster(int oid, short moveid, int currentMp, boolean useSkills, int skillId, int skillLevel) {
        client.send(new MoveLifePacket.Response(oid, moveid, currentMp, useSkills, skillId, skillLevel));
    }

    @Override
    public Packet relayMoveMonster(int oid, boolean skillPossible, int skill, int skillId, int skillLevel,
                                   int pOption, Point startPos, List<MoveElement> elements) {
        return client.toLegacyPacket(
                new MoveLifePacket.Relay(oid, skillPossible, skill, skillId, skillLevel, pOption, startPos, elements));
    }
}
