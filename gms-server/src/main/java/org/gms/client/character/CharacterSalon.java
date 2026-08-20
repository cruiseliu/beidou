package org.gms.client.character;

import org.gms.client.SkinColor;
import org.gms.net.server.Server;
import org.gms.server.quest.medal.DynamicHairMedal;
import org.gms.util.PacketCreator;

/**
 * 美容/外观模块组件：发型（hair）+ 脸型（face）+ 面部表情（lastExpression）+ 肤色（skinColor）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getHair/setHair/changeFaceExpression/... 对外转发）。
 *
 * 边界：只承载外观美容语义——发型/脸型/肤色与表情广播。
 * 依赖经 owner 门面调用（getMap/...）。
 */
class CharacterSalon {
    private final Character owner;

    /** 发型 */
    private int hair;
    /** 脸型 */
    private int face;
    /** 最近表情时间（限频 1.5s） */
    private long lastExpression = 0;
    /** 肤色 */
    private SkinColor skinColor = SkinColor.NORMAL;

    CharacterSalon(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    int getHair() {
        return hair;
    }

    void setHair(int hair) {
        int oldHair = this.hair;
        this.hair = hair;
        DynamicHairMedal.onHairChanged(owner, oldHair, hair);
    }

    int getFace() {
        return face;
    }

    void setFace(int face) {
        this.face = face;
    }

    SkinColor getSkinColor() {
        return skinColor;
    }

    void setSkinColor(SkinColor skinColor) {
        this.skinColor = skinColor;
    }

    // ── 表情 ──

    void changeFaceExpression(int emote) {
        long timeNow = Server.getInstance().getCurrentTime();
        // Client allows changing every 2 seconds. Give it a little bit of overhead for packet delays.
        if (timeNow - lastExpression > 1500) {
            lastExpression = timeNow;
            owner.getMap().broadcastMessage(owner, PacketCreator.facialExpression(owner, emote), false);
        }
    }
}
