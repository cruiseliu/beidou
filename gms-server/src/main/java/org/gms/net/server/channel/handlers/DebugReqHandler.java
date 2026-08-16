package org.gms.net.server.channel.handlers;

import com.alibaba.fastjson2.JSONObject;
import org.gms.client.Client;
import org.gms.net.AbstractPacketHandler;
import org.gms.net.packet.InPacket;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调试：客户端发送 DEBUG_REQ，服务端返回角色全属性 JSON。
 * 用于伪客户端测试，不参与正式游戏逻辑。
 */
public final class DebugReqHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(DebugReqHandler.class);

    @Override
    public void handlePacket(InPacket p, Client c) {
        byte reqType = p.readByte();
        JSONObject json = new JSONObject();
        var chr = c.getPlayer();
        json.put("type", reqType);
        json.put("level", chr.getLevel());
        json.put("job", chr.getJob().getId());
        json.put("str", chr.getStr());
        json.put("dex", chr.getDex());
        json.put("int", chr.getInt());
        json.put("luk", chr.getLuk());
        json.put("hp", chr.getHp());
        json.put("maxhp", chr.getClientMaxHp());
        json.put("mp", chr.getMp());
        json.put("maxmp", chr.getClientMaxMp());
        json.put("exp", chr.getExp());
        json.put("ap", chr.getRemainingAp());
        json.put("sp", chr.getRemainingSp());
        json.put("map", chr.getMapId());
        json.put("fame", chr.getFame());
        // 派生属性（服务端有的）
        json.put("patk", chr.getTotalWatk());
        json.put("matk", chr.getTotalMagic());
        json.put("totalStr", chr.getTotalStr());
        json.put("totalDex", chr.getTotalDex());
        json.put("totalInt", chr.getTotalInt());
        json.put("totalLuk", chr.getTotalLuk());

        c.sendPacket(PacketCreator.getDebugRes(reqType, json.toJSONString()));
        log.debug("DEBUG_REQ type={} → {} 字段", reqType, json.size());
    }
}
