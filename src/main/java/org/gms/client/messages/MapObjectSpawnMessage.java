package org.gms.client.messages;

import org.gms.client.character.MapView;
import org.gms.infra.ActorMessage;
import org.gms.net.packet.Packet;

import java.util.List;

/**
 * map → player：视野型对象落地（monster/reactor/summon 等，包内容 viewer 无关故预构建）。
 * 全图玩家无条件投递，visible 判定（自身位置 × viewEntry 位置 × ranged 阈值）在 viewer
 * 域——可见才向 client 直发包并登记 MapView，不可见整体丢弃（move-diff 之后会按需重发）。
 * mapId 供接收方校验自身所在图（切图竞态丢弃）。
 */
public record MapObjectSpawnMessage(int mapId, MapView.Entry viewEntry, List<Packet> packets) implements ActorMessage {

    public MapObjectSpawnMessage {
        packets = List.copyOf(packets);
    }
}
