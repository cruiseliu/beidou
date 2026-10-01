package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.server.maps.Summon;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapObject;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.Collections;
import java.util.List;

/**
 * GM/隐身模块组件：GM 等级（gmLevel）+ 隐身状态（hidden）+ 白字聊天（whiteChat）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（isGM/hide/isHidden/... 对外转发）。
 *
 * 边界：只承载 GM 语义——GM 等级判定、隐身/现身、白字聊天。
 * 依赖经 owner 门面调用（sendPacket/getMap/getId/getSummonsValues/releaseControlledMonsters/enableActions/...）。
 */
class CharacterGm {
    private final Character owner;

    /** GM 等级（0-6，>1 视为 GM） */
    private int gmLevel;
    /** 隐身状态 */
    private boolean hidden;
    /** 白字聊天（GM 功能） */
    private boolean whiteChat = false;

    CharacterGm(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    int gmLevel() {
        return gmLevel;
    }

    boolean isGM() {
        return gmLevel > 1;
    }

    boolean isHidden() {
        return hidden;
    }

    boolean getWhiteChat() {
        return isGM() && whiteChat;
    }

    // ── 变更 ──

    void setGMLevel(int level) {
        this.gmLevel = Math.max(Math.min(level, 6), 0);
        whiteChat = gmLevel >= 4;   // thanks ozanrijen for suggesting default white chat
    }

    void setGM(int level) {
        this.gmLevel = level;
    }

    void toggleWhiteChat() {
        whiteChat = !whiteChat;
    }

    void hide(boolean hide, boolean login) {
        if (isGM() && hide != this.hidden) {
            if (!hide) {
                this.hidden = false;
                owner.sendPacket(PacketCreator.getGMEffect(0x10, (byte) 0));
                List<EffectType> dsstat = Collections.singletonList(EffectType.DARKSIGHT);
                owner.getMapRef().broadcastGMMessage(owner.ref(), PacketCreator.cancelForeignBuff(owner.getId(), dsstat), false);
                owner.getMapRef().broadcastSpawnPlayerMapObjectMessage(owner.ref(), owner.ref(), false);

                for (Summon ms : owner.getSummonsValues()) {
                    owner.getMapRef().broadcastNONGMMessage(owner.ref(), PacketCreator.spawnSummon(ms, false), false);
                }

                for (MapObject mo : owner.getMapRef().getMonsters()) {
                    Monster m = (Monster) mo;
                    m.aggroUpdateController();
                }
            } else {
                this.hidden = true;
                owner.sendPacket(PacketCreator.getGMEffect(0x10, (byte) 1));
                if (!login) {
                    owner.getMapRef().broadcastNONGMMessage(owner.ref(), PacketCreator.removePlayerFromMap(owner.getId()), false);
                }
                List<Pair<EffectType, Integer>> ldsstat = Collections.singletonList(new Pair<EffectType, Integer>(EffectType.DARKSIGHT, 0));
                owner.getMapRef().broadcastGMMessage(owner.ref(), PacketCreator.giveForeignBuff(owner.getId(), ldsstat), false);
                owner.releaseControlledMonsters();
            }
            owner.enableActions();
        }
    }

    void hide(boolean hide) {
        hide(hide, false);
    }

    void toggleHide(boolean login) {
        hide(!hidden, login);
    }
}
