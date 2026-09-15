package org.gms.remote;

import org.gms.client.character.Character;
import org.gms.net.packet.Packet;
import org.gms.client.pet.Pet;
import org.gms.remote.modules.basic.BasicModule;
import org.gms.remote.modules.basic.server.BasicUpdate;
import org.gms.remote.modules.cooldown.CooldownModule;
import org.gms.remote.modules.inventory.InventoryModule;
import org.gms.remote.modules.map.client.MapModule;
import org.gms.remote.modules.npc.client.NpcModule;
import org.gms.remote.modules.map.client.movement.MoveElement;
import org.gms.remote.modules.inventory.server.SlotChange;
import org.gms.remote.modules.pet.PetModule;
import org.gms.remote.modules.skills.SkillsModule;
import org.gms.remote.modules.skills.server.SkillUpdate;
import org.gms.remote.modules.skills.server.SpUpdate;
import org.gms.remote.modules.stats.StatsModule;
import org.gms.remote.modules.stats.server.StatsUpdate;

import java.util.List;

/** 无连接实现：全部语义调用静默容忍（对齐 Character.sendPacket 对 client==null 的行为）；
 *  模块访问器自指（本类即全部模块面），机器钩子（flushAll/dispatch）为空操作。 */
final class DummyClient extends RemoteClientBase implements RemoteClient,
        StatsModule,
        SkillsModule,
        BasicModule,
        CooldownModule,
        InventoryModule,
        PetModule,
        MapModule,
        NpcModule {

    static final DummyClient INSTANCE = new DummyClient();

    private DummyClient() {
    }

    // ── 机器钩子：空操作 ──

    @Override
    public org.gms.net.PacketHandler resolveHandler(short opcode) {
        return null;   // 无连接：静默
    }

    @Override
    protected void flushAll() {
    }

    // ── 模块访问器：自指（本类即全部模块面，调用全部静默）──

    @Override
    public void initialize(Character chr) {
        // 无连接：静默
    }

    @Override
    public void updateMacros(org.gms.client.SkillMacro[] macros) {
        // 无连接：静默
    }

    @Override
    public BasicModule basic() {
        return this;
    }

    @Override
    public StatsModule stats() {
        return this;
    }

    @Override
    public SkillsModule skills() {
        return this;
    }

    @Override
    public CooldownModule cooldown() {
        return this;
    }

    @Override
    public InventoryModule inventory() {
        return this;
    }

    @Override
    public PetModule pet() {
        return this;
    }

    @Override
    public NpcModule npc() {
        return this;
    }

    @Override
    public MapModule map() {
        return this;
    }

    @Override
    public Packet movePlayer(int charId, List<MoveElement> elements) {
        return null;   // 无连接：静默
    }

    @Override
    public void ackMoveMonster(int oid, short moveid, int currentMp, boolean useSkills, int skillId, int skillLevel) {
        // 无连接：静默
    }

    @Override
    public Packet relayMoveMonster(int oid, boolean skillPossible, int skill, int skillId, int skillLevel,
                                   int pOption, java.awt.Point startPos, List<MoveElement> elements) {
        return null;   // 无连接：静默
    }

    @Override
    public void talk(int npc, int msgType, int speaker, String text, int... endBytes) {
        // 无连接：静默
    }

    @Override
    public void showInfo(String path) {
        // 无连接：静默
    }

    @Override
    public void dropMessage(int type, String message) {
        // 无连接：静默
    }

    // ── 模块面：全部静默 ──

    @Override
    public void updateStats(StatsUpdate update) {
    }

    @Override
    public void updateSp(SpUpdate update) {
    }

    @Override
    public void updateSkill(SkillUpdate update) {
    }

    @Override
    public void removeSkill(int skillId) {
    }

    @Override
    public void updateBasic(BasicUpdate update) {
    }

    @Override
    public void unlockActions() {
    }

    @Override
    public void clearSkillCooldown(int skillId) {
    }

    @Override
    public void updateInventory(List<SlotChange> changes) {
    }

    @Override
    public void announceInventoryFull() {
    }

    @Override
    public void summonPet(Pet pet, int fh) {
    }

    @Override
    public void dismissPet(Pet pet, boolean hunger) {
    }

    @Override
    public void expire(Pet pet) {
    }

    @Override
    public void revive(Pet pet) {
    }

    @Override
    public void updatePanel(Pet pet, boolean levelUp) {
    }

    @Override
    public void updateIgnoreList(Character chr) {
    }

    @Override
    public void petFoodResponse(Character chr, int slot, boolean enjoyed, boolean hasChatBalloon) {
    }

    @Override
    public void petNameChange(Character chr, String newName, int slot) {
    }
}
