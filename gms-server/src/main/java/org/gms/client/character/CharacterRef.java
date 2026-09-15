package org.gms.client.character;

import org.gms.client.Client;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.Dragon;
import org.gms.server.maps.Summon;
import org.gms.client.EffectType;
import org.gms.server.BuffEffectData;
import org.gms.util.AssertUtil;

import java.awt.Point;

/**
 * 角色的地图域侧句柄（doc/13 反向剥离）：MapleMap 只持 ref 不持 Character——
 * **规范唯一**：每 Character 构造自己的 ref（{@link #of(Character)}/`chr.ref()` 幂等），
 * ref identity 即角色 identity。接口 = MapleMap 用法并集（封闭集，1:1 照抄，编译驱动补齐）。
 *
 * <p><b>方法体当前为直调（P0）</b>——线程语义与迁移前一致；map 任务体直读直写角色
 * 的现状（chrWLock 等锁保护）保持。后续 post 化（写方法 post 回 player strand +
 * 读快照化）为独立批次，不在本轮。
 *
 * <p><b>strict canary</b>：本体的 strict 收包管线执行窗口内（{@code chr.strictMode()}），
 * 一切经 ref 触达本体（直调委托与 {@link #unref()} 解包）即断言失败——命中的调用链
 * 即 map 域同步跨域点，post 化欠账的定位输出（doc/16 §4.1）。当前全部管线 strict=false，
 * 断言不激活。
 */
public final class CharacterRef implements org.gms.server.maps.MapObject {

    private final Character chr;
    /**
     * 本体 id（装载后不变）：构造期未定（id 由 DB 装载后赋值），首次读取时捕获——
     * 捕获后 getId 不再访问 Character（map 任务体直读安全；未装载角色不可达 map 域，
     * 首捕必然发生在装载后）。并发首捕同值幂等。
     */
    private int id;
    /** 幽灵判定快照（方向 2 读快照化）：离场标志，player 侧翻转点回写；初值 true 对齐本体 AtomicBoolean */
    private volatile boolean awayFromWorld = true;
    /** 幽灵判定快照：会话断开（单向置位，player 断连收尾回写） */
    private volatile boolean clientDisconnected;

    CharacterRef(Character chr) {
        this.chr = chr;
    }

    /** player 侧回写：离场标志翻转（setEnteredChannelWorld/setAwayFromChannelWorld） */
    void syncAwayFromWorld(boolean v) {
        awayFromWorld = v;
    }

    /** player 侧回写：会话断开置位（client 置空的收尾点） */
    void syncClientDisconnected() {
        clientDisconnected = true;
    }

    /** 幂等：角色实例的唯一 ref（null 透传） */
    public static CharacterRef of(Character chr) {
        return chr != null ? chr.ref() : null;
    }

    /** 还原本体（map 域公开出口用） */
    public Character unref() {
        notInStrictPipeline();
        return chr;
    }

    /**
     * strict 管线窗口断言（迁移 canary）：窗口内经 ref 触达本体即抛 AssertionError，
     * 由 strand/shim fail-safe 记日志（定位用，不中断服务）。
     */
    private void notInStrictPipeline() {
        AssertUtil.isTrue(!chr.strictMode(), "strict 管线执行窗口内经 CharacterRef 触达本体 (cid=" + id + ")");
    }

    /** 本体 id（首次读取捕获，见字段注；不访问 Character） */
    public int getId() {
        int i = id;
        if (i == 0) {
            i = chr.getId();
            if (i != 0) {
                id = i;
            }
        }
        return i;
    }

    public String getName() {
        notInStrictPipeline();
        return chr.getName();
    }

    public int getLevel() {
        notInStrictPipeline();
        return chr.getLevel();
    }

    public Client getClient() {
        notInStrictPipeline();
        return chr.getClient();
    }

    public int getMapId() {
        notInStrictPipeline();
        return chr.getMapId();
    }

    public void setMapId(int mapId) {
        notInStrictPipeline();
        chr.setMapId(mapId);
    }

    public org.gms.server.maps.MapleMap getMap() {
        notInStrictPipeline();
        return chr.getMap();
    }

    public boolean isHidden() {
        notInStrictPipeline();
        return chr.isHidden();
    }

    public boolean isGM() {
        notInStrictPipeline();
        return chr.isGM();
    }

    public int gmLevel() {
        notInStrictPipeline();
        return chr.gmLevel();
    }

    public byte getTeam() {
        notInStrictPipeline();
        return chr.getTeam();
    }

    public int getPartyId() {
        notInStrictPipeline();
        return chr.getPartyId();
    }

    public Party getParty() {
        notInStrictPipeline();
        return chr.getParty();
    }

    public PartyCharacter getMPC() {
        notInStrictPipeline();
        return chr.getMPC();
    }

    public EventInstanceManager getEventInstance() {
        notInStrictPipeline();
        return chr.getEventInstance();
    }

    public org.gms.server.partyquest.MonsterCarnival getMonsterCarnival() {
        notInStrictPipeline();
        return chr.getMonsterCarnival();
    }

    public Dragon getDragon() {
        notInStrictPipeline();
        return chr.getDragon();
    }

    public org.gms.server.maps.PlayerShop getPlayerShop() {
        notInStrictPipeline();
        return chr.getPlayerShop();
    }

    public int getSkillLevel(int skillId) {
        notInStrictPipeline();
        return chr.getSkillLevel(skillId);
    }

    public org.gms.client.QuestStatus getQuest(int questid) {
        notInStrictPipeline();
        return chr.getQuest(questid);
    }

    public boolean needQuestItem(int questid, int itemid) {
        notInStrictPipeline();
        return chr.needQuestItem(questid, itemid);
    }

    public org.gms.remote.RemoteClient getRemote() {
        notInStrictPipeline();
        return chr.getRemote();
    }

    public CharacterPets getPets() {
        notInStrictPipeline();
        return chr.getPets();
    }

    public Integer getBuffedValue(EffectType effect) {
        notInStrictPipeline();
        return chr.getBuffedValue(effect);
    }

    public BuffEffectData getStatForBuff(EffectType effect) {
        notInStrictPipeline();
        return chr.getStatForBuff(effect);
    }

    public void cancelBuffStats(EffectType stat) {
        notInStrictPipeline();
        chr.cancelBuffStats(stat);
    }

    public void cancelEffectFromBuffStat(EffectType stat) {
        notInStrictPipeline();
        chr.cancelEffectFromBuffStat(stat);
    }

    public void updateActiveEffects() {
        notInStrictPipeline();
        chr.updateActiveEffects();
    }

    public void unregisterChairBuff() {
        notInStrictPipeline();
        chr.unregisterChairBuff();
    }

    public void gainCP(int gain) {
        notInStrictPipeline();
        chr.gainCP(gain);
    }

    public int getTargetHpBarHash() {
        notInStrictPipeline();
        return chr.getTargetHpBarHash();
    }

    public boolean isFamilyBuff() {
        notInStrictPipeline();
        return chr.isFamilyBuff();
    }

    public org.gms.server.events.gm.Ola getOla() {
        notInStrictPipeline();
        return chr.getOla();
    }

    public void setOla(org.gms.server.events.gm.Ola ola) {
        notInStrictPipeline();
        chr.setOla(ola);
    }

    public org.gms.server.events.gm.Fitness getFitness() {
        notInStrictPipeline();
        return chr.getFitness();
    }

    public void setFitness(org.gms.server.events.gm.Fitness fitness) {
        notInStrictPipeline();
        chr.setFitness(fitness);
    }

    public Summon getSummonByKey(int id) {
        notInStrictPipeline();
        return chr.getSummonByKey(id);
    }

    public java.util.Collection<Summon> getSummonsValues() {
        notInStrictPipeline();
        return chr.getSummonsValues();
    }

    public boolean isSummonsEmpty() {
        notInStrictPipeline();
        return chr.isSummonsEmpty();
    }

    public boolean containsSummon(Summon summon) {
        notInStrictPipeline();
        return chr.containsSummon(summon);
    }

    public void addVisibleMapObject(org.gms.server.maps.MapObject mo) {
        notInStrictPipeline();
        chr.addVisibleMapObject(mo);
    }

    public void removeVisibleMapObject(org.gms.server.maps.MapObject mo) {
        notInStrictPipeline();
        chr.removeVisibleMapObject(mo);
    }

    public boolean isMapObjectVisible(org.gms.server.maps.MapObject mo) {
        notInStrictPipeline();
        return chr.isMapObjectVisible(mo);
    }

    public void sendPacket(org.gms.net.packet.Packet packet) {
        notInStrictPipeline();
        chr.sendPacket(packet);
    }

    public void dropMessage(int type, String message) {
        notInStrictPipeline();
        chr.dropMessage(type, message);
    }

    public void dropMessage(String message) {
        notInStrictPipeline();
        chr.dropMessage(message);
    }

    public void addMP(int delta) {
        notInStrictPipeline();
        chr.addMP(delta);
    }

    public int getMp() {
        notInStrictPipeline();
        return chr.getMp();
    }

    public String getChalkboard() {
        notInStrictPipeline();
        return chr.getChalkboard();
    }

    public void setChalkboard(String text) {
        notInStrictPipeline();
        chr.setChalkboard(text);
    }

    public void receivePartyMemberHP() {
        notInStrictPipeline();
        chr.receivePartyMemberHP();
    }

    public float getDropRate() {
        notInStrictPipeline();
        return chr.getDropRate();
    }

    public float getBossDropRate() {
        notInStrictPipeline();
        return chr.getBossDropRate();
    }

    public float getMesoRate() {
        notInStrictPipeline();
        return chr.getMesoRate();
    }

    public float getCardRate(int itemid) {
        notInStrictPipeline();
        return chr.getCardRate(itemid);
    }

    public float getFamilyDrop() {
        notInStrictPipeline();
        return chr.getFamilyDrop();
    }

    public boolean isAlive() {
        notInStrictPipeline();
        return chr.isAlive();
    }

    public void resetPlayerAggro() {
        notInStrictPipeline();
        chr.resetPlayerAggro();
    }

    /** 外观刷新广播包（接收方 client 决定编码，本体数据来自本角色） */
    public org.gms.net.packet.Packet updateCharLookPacket(Client targetClient) {
        notInStrictPipeline();
        return org.gms.util.PacketCreator.updateCharLook(targetClient, chr);
    }

    /** 幽灵判定快照读（map 域直读，不触本体；写点见 syncAwayFromWorld） */
    public boolean isAwayFromWorld() {
        return awayFromWorld;
    }

    /** 幽灵判定快照读（map 域直读，不触本体；写点见 syncClientDisconnected） */
    public boolean isClientDisconnected() {
        return clientDisconnected;
    }

    public void applyVisibleMapObjects(java.util.List<org.gms.server.maps.MapObject> addRefs,
                                       java.util.List<org.gms.server.maps.MapObject> removeRefs) {
        notInStrictPipeline();
        chr.applyVisibleMapObjects(addRefs, removeRefs);
    }

    public void saveLocation(String type) {
        notInStrictPipeline();
        chr.saveLocation(type);
    }

    public void removeSandboxItems() {
        notInStrictPipeline();
        chr.removeSandboxItems();
    }

    public void releaseControlledMonsters() {
        notInStrictPipeline();
        chr.releaseControlledMonsters();
    }

    public void leaveMap() {
        notInStrictPipeline();
        chr.leaveMap();
    }

    public void changeMap(int mapid) {
        notInStrictPipeline();
        chr.changeMap(mapid);
    }

    public void changeMap(int mapid, Object pt) {
        notInStrictPipeline();
        chr.changeMap(mapid, pt);
    }

    public void changeMap(org.gms.server.maps.MapleMap to) {
        notInStrictPipeline();
        chr.changeMap(to);
    }

    public void changeMap(org.gms.server.maps.MapleMap to, int portal) {
        notInStrictPipeline();
        chr.changeMap(to, portal);
    }

    public void changeMap(org.gms.server.maps.MapleMap to, org.gms.server.maps.Portal pto) {
        notInStrictPipeline();
        chr.changeMap(to, pto);
    }

    public void changeMap(org.gms.server.maps.MapleMap to, Point pos) {
        notInStrictPipeline();
        chr.changeMap(to, pos);
    }

    // ── MapObject 接口（Ref 即地图对象；全部委托本体）──

    @Override
    public int getObjectId() {
        notInStrictPipeline();
        return chr.getObjectId();
    }

    @Override
    public void setObjectId(int id) {
        notInStrictPipeline();
        chr.setObjectId(id);
    }

    @Override
    public org.gms.server.maps.MapObjectType getType() {
        return org.gms.server.maps.MapObjectType.PLAYER;
    }

    @Override
    public Point getPosition() {
        notInStrictPipeline();
        return chr.getPosition();
    }

    @Override
    public void setPosition(Point position) {
        notInStrictPipeline();
        chr.setPosition(position);
    }

    @Override
    public void sendSpawnData(Client client) {
        notInStrictPipeline();
        chr.sendSpawnData(client);
    }

    @Override
    public void sendDestroyData(Client client) {
        notInStrictPipeline();
        chr.sendDestroyData(client);
    }

    @Override
    public void nullifyPosition() {
        notInStrictPipeline();
        chr.nullifyPosition();
    }
}
