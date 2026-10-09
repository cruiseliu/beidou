package org.gms.client.character;

import org.gms.client.Client;
import org.gms.client.FamilyEntry;
import org.gms.client.JobEnum;
import org.gms.infra.StrictWindow;
import org.gms.infra.Strand;
import org.gms.infra.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.client.Player;
import org.gms.client.PlayerStrand;
import org.gms.client.quest.Quest;
import org.gms.infra.ActorMessage;
import org.gms.server.partyquest.PartyQuest;
import org.gms.net.server.world.Party;
import org.gms.net.server.world.PartyCharacter;
import org.gms.remote.RemoteClient;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.server.maps.MapObject;
import org.gms.server.maps.MapObjectType;
import org.gms.server.maps.MapleMap;
import org.gms.server.maps.PlayerShop;
import org.gms.server.maps.Portal;
import org.gms.server.maps.Summon;
import org.gms.server.life.Monster;
import org.gms.server.partyquest.MonsterCarnival;
import org.gms.client.EffectType;
import org.gms.server.BuffEffectData;
import org.gms.server.events.gm.Fitness;
import org.gms.server.events.gm.Ola;
import org.gms.util.AssertUtil;

import java.awt.Point;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * 角色的地图域侧句柄（doc/13 反向剥离）：MapleMap 只持 ref 不持 Character——
 * **规范唯一**：每 Character 构造自己的 ref（{@link #of(Character)}/`chr.ref()` 幂等），
 * ref identity 即角色 identity。接口 = MapleMap 用法并集（封闭集，1:1 照抄，编译驱动补齐）。
 *
 * <p><b>方法体当前为直调（P0）</b>——线程语义与迁移前一致；map 任务体直读直写角色
 * 的现状（chrWLock 等锁保护）保持。后续 post 化（写方法 post 回 player strand +
 * 读快照化）为独立批次，不在本轮。
 *
 * <p><b>strict canary</b>：本体的 strict 收包管线执行窗口内（按开窗线程判定，见
 * Character#inStrictOnThisThread(StrictWindow.STRAND)），一切经 ref 触达本体（直调委托与
 * {@link #unref()} 解包）即断言失败——命中的调用链即 map 域同步跨域点，post 化欠账的
 * 定位输出（doc/16 §4.1）。
 */
public final class CharacterRef implements MapObject {

    private static final Logger log = LoggerFactory.getLogger(CharacterRef.class);

    private final Character chr;
    /**
     * 本体 id（装载后不变）：构造期未定（id 由 DB 装载后赋值），首次读取时捕获——
     * 捕获后 getId 不再访问 Character（map 任务体直读安全；未装载角色不可达 map 域，
     * 首捕必然发生在装载后）。并发首捕同值幂等。
     */
    private int id;

    /** map 域只读视图快照（CharacterMapView，player actor 任务边界整体发布；见 publishView） */
    private volatile CharacterMapView view;
    /** 幽灵判定快照（方向 2 读快照化）：离场标志，player 侧翻转点回写；初值 true 对齐本体 AtomicBoolean */
    private volatile boolean awayFromWorld = true;
    /** 幽灵判定快照：会话断开（单向置位，player 断连收尾回写） */
    private volatile boolean clientDisconnected;
    /** 所属 player strand（跨 actor 类型化消息投递通道）：newClient 安装、登出收尾清除——与本体 strandSlot 同步点一致 */
    private volatile PlayerStrand strand;

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

    /** player 侧回写：会话 strand 安装/清除（与本体 strandSlot 同步点一致，见 newClient/登出收尾） */
    void syncStrand(PlayerStrand s) {
        strand = s;
    }

    /**
     * 跨 actor 类型化消息投递（map→player）：入队到本体 strand，接收域内经分发器执行。
     * 无会话静默丢弃；closed strand 的 IllegalStateException 捕获静默——对齐 Strand 契约
     * 「close 后迟到任务由调用侧静默」（登出竞态是常态而非异常）。
     */
    public void post(ActorMessage msg) {
        PlayerStrand s = strand;
        if (s == null) {
            return;
        }
        try {
            s.post(msg);
        } catch (IllegalStateException ignored) {
        }
    }

    /** 本域写回的便捷后投递（apply-visibility 类；同一 null/closed 容忍语义） */
    public void post(String taskName, Runnable body) {
        PlayerStrand s = strand;
        if (s == null) {
            return;
        }
        try {
            s.post(taskName, body);
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * strict 管线过渡桥（legacy 直发段 post 化）：把「经 ref 取 client 直发」的遗留发包段
     * 整体后投递到本 strand 执行——strict 窗口内经 ref 直触 client 即 canary 断言
     * （doc/16 §4.1），map 任务体/in-place 缝以本桥替代直发；body 执行时窗口按 strand
     * 串行必已收口，体内 getClient 合法。无会话静默丢弃（对齐 {@link #post(String, Runnable)}）。
     *
     * <p>过渡债务：body 捕获 map 域活对象（MapObject 等）跨界，延迟窗口内可能被 map actor
     * 并改——与被替代的直发段（本就无同步直读）同偿；对应收包流全量 post 化后消除。
     */
    public void postLegacyPacket(String taskName, Consumer<Client> body) {
        post(taskName, () -> {
            PipelineContext.clear();   // 上下文截断点：过渡桥的 legacy 直发合法（体内 getClient 免哨）
            Client c = chr.getClient();
            if (c != null) {
                body.accept(c);
            }
        });
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
     * strict 管线窗口断言（迁移 canary，全量）：管线线程窗口内经 ref 触达本体即抛
     * AssertionError，由 strand/shim fail-safe 记日志（定位用，不中断服务）。合法的
     * map actor 载荷任务不应携带/触达 CharacterRef——需要本体引用的（controller 移交等）
     * 由载荷直接携带 Character（identity/移交专用，见 MapleMap.onTransitionMobView）。
     * 断言按<b>开窗线程</b>判定（线程精确）：跨 actor 异步任务在窗口存续期触达 ref
     * （如 map shim 上的 transitionMobView）不属管线违规，不 fire。
     */
    private void notInStrictPipeline() {
        PipelineContext ctx = PipelineContext.current();
        if (ctx != null && ctx.kinds.contains(StrictWindow.STRAND)) {
            if (ctx.ownerType == PipelineContext.OwnerType.CHARACTER && ctx.ownerId == id) {
                return;   // 触 owner 自己 = 自访（owner actor 上的续段）
            }
            if (ctx.mode == StrictWindow.Mode.LOG) {
                log.error("strict 管线执行窗口内经 CharacterRef 触达本体 (cid={}) [log 模式]", id, new RuntimeException("call site"));
                return;
            }
            throw new AssertionError("strict 管线执行窗口内经 CharacterRef 触达本体 (cid=" + id + ")");
        }
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

    /**
     * map 域只读：等级视图（player actor 任务边界发布的快照）。
     * 免哨——视图读是跨域取数的被认可通道（本方法即 CharacterMapView 的第一个消费者）。
     */
    public int getLevel() {
        CharacterMapView v = view;
        return v != null ? v.level() : 0;
    }

    /** 视图发布（player actor 任务边界 / 入场绑定调用；本体现值 → 有变化才整体替换）。 */
    public void publishView() {
        // partyId 哨兵全域统一 -1 = 无队伍（组件域原值透传；Party id 恒正），消费端一律 > 0 判成员资格
        CharacterMapView next = new CharacterMapView(chr.getLevel(), chr.getPartyId());
        CharacterMapView cur = view;
        if (cur == null || cur.level() != next.level() || cur.partyId() != next.partyId()) {
            view = next;
        }
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

    public MapleMap getMap() {
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

    /** map 域只读：队伍 id 视图（-1 = 无队伍；发布语义同 getLevel） */
    public int getPartyId() {
        CharacterMapView v = view;
        return v != null ? v.partyId() : -1;
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

    public MonsterCarnival getMonsterCarnival() {
        notInStrictPipeline();
        return chr.getMonsterCarnival();
    }

    /**
     * strict 窗口标志读（免闸：plain volatile 读）——map 域逻辑用于跳过在窗角色
     * （如 Monster controller 选举：在窗角色本轮不参选，unref 候选即守卫触达）。
     */
    public float getExpRate() {
        notInStrictPipeline();
        return chr.getExpRate();
    }

    public float getMobExpRate() {
        notInStrictPipeline();
        return chr.getMobExpRate();
    }

    public void gainExp(int gain, int party, boolean show, boolean inChat, boolean white) {
        notInStrictPipeline();
        chr.gainExp(gain, party, show, inChat, white);
    }

    public void raiseQuestMobCount(int mobId) {
        notInStrictPipeline();
        chr.raiseQuestMobCount(mobId);
    }

    public void increaseEquipExp(int expGain) {
        notInStrictPipeline();
        chr.increaseEquipExp(expGain);
    }

    public void setPlayerAggro(int mobHash) {
        notInStrictPipeline();
        chr.setPlayerAggro(mobHash);
    }

    public Strand strand() {
        notInStrictPipeline();
        return chr.strand();
    }

    public PartyQuest getPartyQuest() {
        notInStrictPipeline();
        return chr.getPartyQuest();
    }

    public FamilyEntry getFamilyEntry() {
        notInStrictPipeline();
        return chr.getFamilyEntry();
    }

    public float getFamilyExp() {
        notInStrictPipeline();
        return chr.getFamilyExp();
    }

    public JobEnum getJob() {
        notInStrictPipeline();
        return chr.getJob();
    }

    public int getStr() {
        notInStrictPipeline();
        return chr.getStr();
    }

    public int getLuk() {
        notInStrictPipeline();
        return chr.getLuk();
    }

    public void showUnderLeveledInfo(Monster mob) {
        notInStrictPipeline();
        chr.showUnderLeveledInfo(mob);
    }

    public boolean strictMode() {
        return chr.strictMode();
    }

    public PlayerShop getPlayerShop() {
        notInStrictPipeline();
        return chr.getPlayerShop();
    }

    public int getSkillLevel(int skillId) {
        notInStrictPipeline();
        return chr.getSkillLevel(skillId);
    }

    public Quest getQuest(int questid) {
        notInStrictPipeline();
        return chr.getQuest(questid);
    }

    public boolean needQuestItem(int questid, int itemid) {
        notInStrictPipeline();
        return chr.needQuestItem(questid, itemid);
    }

    public RemoteClient getRemote() {
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

    public Ola getOla() {
        notInStrictPipeline();
        return chr.getOla();
    }

    public void setOla(Ola ola) {
        notInStrictPipeline();
        chr.setOla(ola);
    }

    public Fitness getFitness() {
        notInStrictPipeline();
        return chr.getFitness();
    }

    public void setFitness(Fitness fitness) {
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

    /** 可见视图差集判定（值读；登记/注销改走 MapObjectsViewMessage 回投，无写桥） */
    public boolean isMapObjectVisible(int oid) {
        notInStrictPipeline();
        return chr.isMapObjectVisible(oid);
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

    public boolean isLoggedInWorld() {
        notInStrictPipeline();
        return chr.isLoggedInWorld();
    }

    public BuffEffectData getBuffEffect(EffectType type) {
        notInStrictPipeline();
        return chr.getBuffEffect(type);
    }

    // ── mob 控制簿记（Monster.controller 的 ref 化镜像，P0 直调；post 化另批）──

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

    public void saveLocation(String type) {
        notInStrictPipeline();
        chr.saveLocation(type);
    }

    public void removeSandboxItems() {
        notInStrictPipeline();
        chr.removeSandboxItems();
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

    public void changeMap(MapleMap to) {
        notInStrictPipeline();
        chr.changeMap(to);
    }

    public void changeMap(MapleMap to, int portal) {
        notInStrictPipeline();
        chr.changeMap(to, portal);
    }

    public void changeMap(MapleMap to, Portal pto) {
        notInStrictPipeline();
        chr.changeMap(to, pto);
    }

    public void changeMap(MapleMap to, Point pos) {
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
    public MapObjectType getType() {
        return MapObjectType.PLAYER;
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
