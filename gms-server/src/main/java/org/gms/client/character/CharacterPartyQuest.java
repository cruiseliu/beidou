package org.gms.client.character;

import org.gms.constants.id.ItemId;
import org.gms.server.partyquest.AriantColiseum;
import org.gms.server.partyquest.MonsterCarnival;
import org.gms.server.partyquest.MonsterCarnivalParty;
import org.gms.server.partyquest.PartyQuest;
import org.gms.util.PacketCreator;

/**
 * 组队任务模块组件：组队任务实例（partyQuest）+ 斗兽场（AriantColiseum）+ 怪物嘉年华（MonsterCarnival）
 * + CP/队伍积分 + 组队任务物品标记（dataString）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getPartyQuest/getMonsterCarnival/gainCP/... 对外转发）。
 *
 * 边界：只承载组队任务语义——任务实例、Ariant/嘉年华状态、CP 与积分、组队任务物品标记。
 * 普通任务（quest）与组队（party）不属本组件，分别归 CharacterQuests/CharacterParty；
 * dataString 列（characters 表）仅作组队任务物品标记，随本组件读写；
 * 依赖经 owner 门面调用（sendPacket/getMap/getParty/...）。
 */
class CharacterPartyQuest {
    private final Character owner;

    private PartyQuest partyQuest = null;

    // MCPQ（怪物嘉年华）

    private AriantColiseum ariantColiseum;
    private MonsterCarnival monsterCarnival;
    private MonsterCarnivalParty monsterCarnivalParty = null;

    private int cp = 0;
    private int totCP = 0;
    private int FestivalPoints;
    private boolean challenged = false;

    /** 嘉年华队伍（-1 未分组） */
    private byte team = 0;

    /** 阿里安特斗兽场点数（持久化到 characters.ariantPoints） */
    private int ariantPoints;

    /** 组队任务物品标记字符串（持久化到 characters.dataString） */
    private String dataString;

    CharacterPartyQuest(Character owner) {
        this.owner = owner;
    }

    // ── 任务实例 ──

    PartyQuest getPartyQuest() {
        return partyQuest;
    }

    void setPartyQuest(PartyQuest partyQuest) {
        this.partyQuest = partyQuest;
    }

    AriantColiseum getAriantColiseum() {
        return ariantColiseum;
    }

    void setAriantColiseum(AriantColiseum ariantColiseum) {
        this.ariantColiseum = ariantColiseum;
    }

    MonsterCarnival getMonsterCarnival() {
        return monsterCarnival;
    }

    void setMonsterCarnival(MonsterCarnival monsterCarnival) {
        this.monsterCarnival = monsterCarnival;
    }

    MonsterCarnivalParty getMonsterCarnivalParty() {
        return monsterCarnivalParty;
    }

    void setMonsterCarnivalParty(MonsterCarnivalParty monsterCarnivalParty) {
        this.monsterCarnivalParty = monsterCarnivalParty;
    }

    byte getTeam() {
        return team;
    }

    void setTeam(int team) {
        this.team = (byte) team;
    }

    // ── 积分与点数 ──

    int getCP() {
        return cp;
    }

    void setCP(int a) {
        this.cp = a;
    }

    int getTotalCP() {
        return totCP;
    }

    void setTotalCP(int a) {
        this.totCP = a;
    }

    void gainCP(int gain) {
        if (this.getMonsterCarnival() != null) {
            if (gain > 0) {
                this.setTotalCP(this.getTotalCP() + gain);
            }
            this.setCP(this.getCP() + gain);
            if (owner.getParty() != null) {
                this.getMonsterCarnival().setCP(this.getMonsterCarnival().getCP(team) + gain, team);
                if (gain > 0) {
                    this.getMonsterCarnival().setTotalCP(this.getMonsterCarnival().getTotalCP(team) + gain, team);
                }
            }
            if (this.getCP() > this.getTotalCP()) {
                this.setTotalCP(this.getCP());
            }
            owner.sendPacket(PacketCreator.CPUpdate(false, this.getCP(), this.getTotalCP(), getTeam()));
            if (owner.getParty() != null && getTeam() != -1) {
                owner.getMapRef().broadcastMessage(PacketCreator.CPUpdate(true, this.getMonsterCarnival().getCP(team), this.getMonsterCarnival().getTotalCP(team), getTeam()));
            }
        }
    }

    void resetCP() {
        this.cp = 0;
        this.totCP = 0;
        this.monsterCarnival = null;
    }

    void gainFestivalPoints(int gain) {
        this.FestivalPoints += gain;
    }

    int getFestivalPoints() {
        return FestivalPoints;
    }

    void setFestivalPoints(int FestivalPoints) {
        this.FestivalPoints = FestivalPoints;
    }

    boolean isChallenged() {
        return challenged;
    }

    void setChallenged(boolean challenged) {
        this.challenged = challenged;
    }

    void gainAriantPoints(int points) {
        this.ariantPoints += points;
    }

    int getAriantPoints() {
        return ariantPoints;
    }

    void setAriantPoints(int ariantPoints) {
        this.ariantPoints = ariantPoints;
    }

    // ── 斗兽场得分 ──

    void updateAriantScore() {
        updateAriantScore(0);
    }

    void updateAriantScore(int dropQty) {
        AriantColiseum arena = this.getAriantColiseum();
        if (arena != null) {
            arena.updateAriantScore(owner, owner.countItem(ItemId.ARPQ_SPIRIT_JEWEL));

            if (dropQty > 0) {
                arena.addLostShards(dropQty);
            }
        }
    }

    /** 离开斗兽场（Character.leaveMap 调用） */
    void leaveArenaIfPresent() {
        AriantColiseum arena = this.getAriantColiseum();
        if (arena != null) {
            arena.leaveArena(owner);
        }
    }

    // ── 组队任务物品标记（dataString） ──

    String getDataString() {
        return dataString;
    }

    void setDataString(String dataString) {
        this.dataString = dataString;
    }

    boolean gotPartyQuestItem(String partyquestchar) {
        return dataString.contains(partyquestchar);
    }

    void removePartyQuestItem(String letter) {
        if (gotPartyQuestItem(letter)) {
            dataString = dataString.substring(0, dataString.indexOf(letter)) + dataString.substring(dataString.indexOf(letter) + letter.length());
        }
    }

    void setPartyQuestItemObtained(String partyquestchar) {
        if (!dataString.contains(partyquestchar)) {
            this.dataString += partyquestchar;
        }
    }
}
