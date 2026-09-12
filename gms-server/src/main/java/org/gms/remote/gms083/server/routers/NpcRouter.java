package org.gms.remote.gms083.server.routers;

import org.gms.net.packet.Packet;
import org.gms.remote.gms083.Gms083;
import org.gms.remote.gms083.server.packets.NPCTalkPacket;
import org.gms.remote.gms083.server.packets.ServerMessagePacket;
import org.gms.remote.gms083.server.packets.ShowInfoPacket;
import org.gms.remote.modules.npc.client.NpcModule;

/** NPC 对话域视图：对话页编码 + 直发本连接。 */
public final class NpcRouter implements NpcModule {

    private final Gms083 client;

    public NpcRouter(Gms083 client) {
        this.client = client;
    }

    @Override
    public void talk(int npc, int msgType, int speaker, String text, int... endBytes) {
        client.send(new NPCTalkPacket(npc, msgType, speaker, text, endBytes));
    }

    @Override
    public void showInfo(String path) {
        client.send(new ShowInfoPacket(path));
    }

    @Override
    public void dropMessage(int type, String message) {
        client.send(new ServerMessagePacket(type, message));
    }
}
