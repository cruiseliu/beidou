package org.gms.remote.v83;

import com.alibaba.fastjson2.JSON;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import org.gms.client.Client;
import org.gms.client.character.Character;
import org.gms.constants.string.CharsetConstants;
import org.gms.net.packet.Packet;
import org.gms.remote.BasicModule;
import org.gms.remote.BasicUpdate;
import org.gms.remote.CooldownModule;
import org.gms.remote.InventoryModule;
import org.gms.remote.PetModule;
import org.gms.remote.RemoteClient;
import org.gms.remote.RemoteUpdate;
import org.gms.remote.ScopeLog;
import org.gms.remote.SemanticEvent;
import org.gms.remote.SkillUpdate;
import org.gms.remote.SkillsModule;
import org.gms.remote.SpUpdate;
import org.gms.remote.StatsModule;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.EquipFlag;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.Item;
import org.gms.client.inventory.ItemFlag;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.pet.Pet;
import org.gms.remote.PetSnap;
import org.gms.remote.ScopeRecord;
import org.gms.remote.SlotChange;
import org.gms.remote.StatsUpdate;
import org.gms.remote.v83.packet.V83Packet;
import org.gms.remote.v83.translate.CooldownTranslator;
import org.gms.remote.v83.translate.SkillsTranslator;
import org.gms.remote.v83.translate.StatsTranslator;
import org.gms.remote.v83.translate.InventoryTranslator;
import org.gms.remote.v83.translate.PetTranslator;
import org.gms.util.HexTool;
import org.gms.util.ThreadLocalUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.util.List;

/**
 * route 层：语义模块调用 → 作用域段 → 按域分发 translate → packet record → 统一
 * encode + 日志 + 传输适配（BytesPacket）。
 * 对参数不做理解、只透传；固定冲刷序 stats → skills → cooldown → inventory。
 * 分层与原则见 gms-server/doc/package-client.md §4/§5；多对多落点注释保留在 deliver。
 */
public final class V83RemoteClient implements RemoteClient, StatsModule, SkillsModule,
        BasicModule, CooldownModule, InventoryModule, PetModule {

    private static final Logger log = LoggerFactory.getLogger(V83RemoteClient.class);

    /** v83 客户端旗标字的逐位拼装——legacy 旗标视图（Item.getLegacyFlags）的唯一组装点。 */
    public static int assembleClientFlagBits(Item item) {
        int bits = 0;
        boolean equipType = item.getInventoryTab() == InventoryType.EQUIP;
        for (ItemFlag f : ItemFlag.values()) {
            if (!item.hasFlag(f)) {
                continue;
            }
            if (f == ItemFlag.SCISSOR_USABLE) {
                continue;   // 服务端语义标签，客户端无此位
            }
            if (f == ItemFlag.TRADE_ONCE) {
                bits |= equipType ? ItemFlag.LEGACY_KARMA_EQP
                                  : ItemFlag.LEGACY_KARMA_USE;
                continue;
            }
            bits |= f.legacyValue();
        }
        Equip equipInfo = item.getEquipInfo();
        if (equipInfo != null) {
            for (EquipFlag f : EquipFlag.values()) {
                if (equipInfo.hasFlag(f)) {
                    bits |= f.legacyValue();
                }
            }
        }
        return bits;
    }

    private final Client client;
    private final StatsTranslator statsT = new StatsTranslator();
    private final SkillsTranslator skillsT = new SkillsTranslator();
    private final CooldownTranslator cooldownT;
    private final InventoryTranslator inventoryT;
    private final PetTranslator petT;

    /** 当前书写段（null = 无作用域）；嵌套经 parent 链接 */
    private ScopeLog active;

    public V83RemoteClient(Client client) {
        this.client = client;
        Charset charset = CharsetConstants.getCharset(ThreadLocalUtil.getClientLang());
        this.cooldownT = new CooldownTranslator();
        this.inventoryT = new InventoryTranslator(charset);
        this.petT = new PetTranslator(charset);
    }

    @Override
    public synchronized RemoteUpdate update() {
        active = new ScopeLog(active);
        return new Handle(active);
    }

    // ── 语义模块：写事件进当前段（无作用域则即时分发并冲刷）──

    @Override
    public synchronized void updateStats(StatsUpdate update) {
        dispatch(new SemanticEvent.Stats(update));
    }

    @Override
    public synchronized void updateSp(SpUpdate update) {
        dispatch(new SemanticEvent.Sp(update));
    }

    @Override
    public synchronized void updateBasic(BasicUpdate update) {
        dispatch(new SemanticEvent.Basic(update));
    }

    @Override
    public synchronized void unlockActions() {
        dispatch(new SemanticEvent.UnlockActions());
    }

    @Override
    public synchronized void updateSkill(SkillUpdate update) {
        dispatch(new SemanticEvent.Skill(update));
    }

    @Override
    public synchronized void removeSkill(int skillId) {
        dispatch(new SemanticEvent.SkillRemove(skillId));
    }

    @Override
    public synchronized void clearSkillCooldown(int skillId) {
        dispatch(new SemanticEvent.CooldownClear(skillId));
    }

    @Override
    public synchronized void updateInventory(java.util.List<org.gms.remote.SlotChange> changes) {
        dispatch(new SemanticEvent.InventoryMods(changes));
    }

    @Override
    public synchronized void announceInventoryFull() {
        dispatch(new SemanticEvent.InventoryFull());
    }

    private synchronized void dispatch(SemanticEvent e) {
        ScopeRecord r = freeze(e);
        if (active == null) {
            deliver(r);
            flushAll();
        } else {
            active.append(r);
        }
    }

    /**
     * peek/冻结：事件入域时由版本实现解析活引用——Inventory 对 pet 盲，宠物槽位的
     * body 数据在此补齐为 PetSnap 快照，翻译层只读快照（不受事务内后续 mutation 影响）。
     * 无活引用的事件原样透传。
     */
    private ScopeRecord freeze(SemanticEvent e) {
        if (!(e instanceof SemanticEvent.InventoryMods(var changes))) {
            return e;
        }
        boolean hasPet = changes.stream().anyMatch(c ->
                c instanceof SlotChange.Added a && a.item().getPetId() > -1);
        if (!hasPet) {
            return e;
        }
        List<FrozenInventoryEvent.Element> elements = changes.stream().map(c -> {
            if (c instanceof SlotChange.Added a && a.item().getPetId() > -1) {
                Pet pet = resolvePet(a.item().getPetId());
                if (pet == null) {
                    return (FrozenInventoryEvent.Element) new FrozenInventoryEvent.Element.Passthrough(c);
                }
                return (FrozenInventoryEvent.Element) new FrozenInventoryEvent.Element.PetBody(
                        (short) a.position(), a.item().getItemId(),
                        new PetSnap(pet.getPetId(), pet.getName(), pet.getLevel(),
                                pet.getTameness(), pet.getFullness(), pet.getFlags(),
                                pet.isAlive(), pet.getExpiration()));
            }
            return (FrozenInventoryEvent.Element) new FrozenInventoryEvent.Element.Passthrough(c);
        }).toList();
        return new FrozenInventoryEvent(elements);
    }

    /** 宠物解析（沿用 forceUpdateItem 的自愈语义：驻留位查无则 DB 兜底）；行缺失（desync）→ null，冻结侧按无宠物处理 */
    private Pet resolvePet(int petId) {
        Pet pet = client.getPlayer().getPetById(petId);
        if (pet != null) {
            return pet;
        }
        try {
            return Pet.load(petId);
        } catch (RuntimeException e) {
            log.warn("宠物 {} 行缺失，按无宠物处理（desync 容忍）", petId, e);
            return null;
        }
    }

    /** 语义事件/冻结记录 → 域翻译（多对多映射的唯一维护点） */
    private void deliver(ScopeRecord r) {
        if (r instanceof FrozenInventoryEvent f) {
            inventoryT.onFrozenInventory(f);
            return;
        }
        deliver((SemanticEvent) r);
    }

    private void deliver(SemanticEvent e) {
        if (e instanceof SemanticEvent.Stats(var u)) {
            statsT.onStats(u);
        } else if (e instanceof SemanticEvent.Sp(var u)) {
            statsT.onSp(u);
        } else if (e instanceof SemanticEvent.Basic(var u)) {
            statsT.onBasic(u);
        } else if (e instanceof SemanticEvent.UnlockActions) {
            statsT.onUnlockActions();
        } else if (e instanceof SemanticEvent.Skill(var u)) {
            skillsT.onSkill(u);
        } else if (e instanceof SemanticEvent.SkillRemove(int skillId)) {
            skillsT.onSkillRemove(skillId);
        } else if (e instanceof SemanticEvent.CooldownClear(int skillId)) {
            cooldownT.onCooldownClear(skillId);
        } else if (e instanceof SemanticEvent.InventoryMods(var changes)) {
            inventoryT.onInventoryMods(changes);
        } else if (e instanceof SemanticEvent.InventoryFull) {
            inventoryT.onInventoryFull();
        } else if (e instanceof SemanticEvent.PetIgnoreList l) {
            for (Pet pet : client.getPlayer().getSummonedPets()) {
                byte petIndex = client.getPlayer().getPetIndex(pet);
                send(petT.ignoreList(l.cid(), petIndex, pet.getPetId(), l.itemIds()));
            }
        } else if (e instanceof SemanticEvent.PetPanel snap) {
            inventoryT.onPetPanel(snap);
            if (snap.levelUp()) {
                // 升级演出：commit 时刻即时广播（先于 flushAll 的 inventory 帧 = legacy 演出→状态时序）。
                // TODO: rethink about map broadcast——地图广播尚未纳入事务模型（deliver 即发送，
                //       drop 撤不回；summon/desummon 等入口广播同样在事务保护之外），整体设计待重审。
                Character chr = client.getPlayer();
                chr.sendPacket(wire(petT.petLevelUpOwn(snap.petIndex())));
                chr.getMap().broadcastMessage(wire(petT.petLevelUpForeign(chr, snap.petIndex())));
            }
        }
    }

    /** 固定冲刷序：stats → skills → cooldown → inventory */
    private void flushAll() {
        for (V83Packet packet : statsT.flush()) {
            send(packet);
        }
        for (V83Packet packet : skillsT.flush()) {
            send(packet);
        }
        for (V83Packet packet : cooldownT.flush()) {
            send(packet);
        }
        for (V83Packet packet : inventoryT.flush()) {
            send(packet);
        }
    }

    private void send(V83Packet packet) {
        client.sendPacket(wire(packet));
    }

    /** debug 级 JSON + encode + trace 级 hex，产出传输帧；广播路径复用同一出口保证日志齐全 */
    private BytesPacket wire(V83Packet packet) {
        if (log.isDebugEnabled()) {
            log.debug("[remote] {} {}", packet.opcode(), JSON.toJSONString(packet));
        }
        ByteBuf frame = packet.encode();
        if (log.isTraceEnabled()) {
            log.trace("[remote] {} hex {}", packet.opcode(), HexTool.toHexString(ByteBufUtil.getBytes(frame)));
        }
        return new BytesPacket(frame);
    }

    /** 传输适配：帧 ByteBuf → Packet（复用既有加密/发送管线） */
    private record BytesPacket(ByteBuf frame) implements Packet {
        @Override
        public byte[] getBytes() {
            return ByteBufUtil.getBytes(frame);
        }
    }

    private final class Handle implements RemoteUpdate {
        private final ScopeLog mine;
        private boolean done = false;

        Handle(ScopeLog log) {
            this.mine = log;
        }

        /** 句柄只允许操作仍处于栈顶的段（过期句柄=编程错误，显式暴露而非静默错丢） */
        private ScopeLog popMine() {
            if (active != mine) {
                throw new IllegalStateException("事务作用域已终结或非栈顶");
            }
            ScopeLog closed = mine;
            active = closed.parent();
            return closed;
        }

        /** 丢弃本段全部事件并结束（P2：O(1) 弃段，无任何截断推理）。终态操作。 */
        @Override
        public synchronized void drop() {
            if (done) {
                throw new IllegalStateException("事务作用域已终结（drop/close 只允许一次）");
            }
            done = true;
            popMine();
        }

        @Override
        public void commit() {
            close();
        }

        @Override
        public synchronized void close() {
            if (done) {
                return;
            }
            done = true;
            ScopeLog closed = popMine();
            if (active == null) {
                closed.records().forEach(V83RemoteClient.this::deliver);
                flushAll();
            } else {
                active.adopt(closed);
            }
        }

        // 会话内书写与 RemoteClient 快捷通道是同一批模块单例（route 自身）：
        // 事件去向由作用域状态决定，句柄不参与路由。

        @Override public StatsModule stats() { return V83RemoteClient.this; }

        @Override public SkillsModule skills() { return V83RemoteClient.this; }

        @Override public BasicModule basic() { return V83RemoteClient.this; }

        @Override public CooldownModule cooldown() { return V83RemoteClient.this; }

        @Override public InventoryModule inventory() { return V83RemoteClient.this; }

        @Override public org.gms.remote.PetModule pet() { return V83RemoteClient.this; }
    }

    // ── 模块访问器（RemoteClient 面）：同上，返回自身 ──

    @Override public synchronized StatsModule stats() { return this; }

    @Override public synchronized SkillsModule skills() { return this; }

    @Override public synchronized BasicModule basic() { return this; }

    @Override public synchronized CooldownModule cooldown() { return this; }

    @Override public synchronized InventoryModule inventory() { return this; }

    @Override public synchronized org.gms.remote.PetModule pet() { return this; }

    // ── PetModule（org.gms.remote.PetModule）：PetTranslator 即时编码；广播机制属 map 模块（临时豁免）──

    @Override
    public synchronized void summonPet(Pet pet, int fh) {
        Character chr = pet.getOwner();
        chr.getMap().broadcastMessage(chr, wire(petT.spawnPet(chr, pet, false, false, fh)), true);
        send(petT.petStatUpdate(chr));
    }

    @Override
    public synchronized void dismissPet(Pet pet, boolean hunger) {
        Character chr = pet.getOwner();
        chr.getMap().broadcastMessage(chr, wire(petT.spawnPet(chr, pet, true, hunger, 0)), true);
        send(petT.petStatUpdate(chr));
    }

    /**
     * 面板推送入口：入口即冻结快照（pet 引用不出本方法），只记录不发送——
     * 一切 wire 后果（body 刷新与升级演出）都发生在 commit 边界之后，drop 才能整体弃段。
     */
    /** 到期/复活的 wire 同为面板物品体刷新（alive 位决定 EXPIRED/活态映射），故共用同一通路。 */
    @Override
    public void expire(Pet pet) {
        dispatchPetPanel(pet, false);
    }

    @Override
    public void revive(Pet pet) {
        dispatchPetPanel(pet, false);
    }

    @Override
    public synchronized void updatePanel(Pet pet, boolean levelUp) {
        dispatchPetPanel(pet, levelUp);
    }

    /** 面板/生命周期共同通路：入口冻结快照并 dispatch（PetPanel），发送时机由作用域决定。 */
    private synchronized void dispatchPetPanel(Pet pet, boolean levelUp) {
        Character chr = pet.getOwner();
        if (chr == null) {
            return;
        }
        ItemSlot host = chr.findPetItemSlot(pet.getPetId());
        if (host == null) {
            return;   // 无宿主物品（理论不可达）：面板无从承载
        }
        dispatch(new SemanticEvent.PetPanel(chr.getId(), chr.getPetIndex(pet),
                (short) host.getPosition(), pet.getPetId(), pet.getItemId(), pet.getName(),
                pet.getLevel(), pet.getTameness(), pet.getFullness(), pet.getFlags(), pet.isAlive(), pet.getExpiration(),
                levelUp));
    }

    public synchronized void petLevelUp(Character chr, int slot) {
        chr.sendPacket(wire(petT.petLevelUpOwn(slot)));
        chr.getMap().broadcastMessage(wire(petT.petLevelUpForeign(chr, slot)));
    }

    /**
     * 拾取过滤列表下发入口：入口即冻结快照（槽位随取随冻——左移会改变槽位）。
     * petIndex < 0（未召唤）时 wire 无从定位，静默跳过。commit 时刻本人下发。
     */
    @Override
    public void updateIgnoreList(Character chr) {
        dispatch(new SemanticEvent.PetIgnoreList(chr.getId(), List.copyOf(chr.getExcludedItems())));
    }

    @Override
    public synchronized void petFoodResponse(Character chr, int slot, boolean enjoyed, boolean hasChatBalloon) {
        chr.getMap().broadcastMessage(wire(petT.petFoodResponse(chr, slot, enjoyed, hasChatBalloon)));
    }

    @Override
    public synchronized void petNameChange(Character chr, String newName, int slot) {
        chr.getMap().broadcastMessage(chr, wire(petT.petNameChange(chr, newName, slot)), true);
    }
}
