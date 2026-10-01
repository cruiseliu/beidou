package org.gms.remote;

import org.gms.net.PacketHandler;
import org.gms.remote.modules.basic.BasicModule;
import org.gms.remote.modules.inventory.InventoryModule;
import org.gms.remote.modules.map.client.MapModule;
import org.gms.remote.modules.message.MessageModule;
import org.gms.remote.modules.npc.client.NpcModule;
import org.gms.remote.modules.quest.QuestModule;
import org.gms.remote.modules.pet.PetModule;
import org.gms.remote.modules.skills.SkillsModule;
import org.gms.remote.modules.stats.StatsModule;

/**
 * 无连接实现：模块访问器交付每域一个 emit 静默抛弃的匿名子类（全部语义调用静默容忍，
 * 对齐 Character.sendPacket 对 client==null 的行为）；机器钩子（flushAll/dispatch）为空操作。
 * DummyClient 不感知任何具体模块 API——新增模块方法无需回来补 no-op。
 */
final class DummyClient extends RemoteClientBase implements RemoteClient {

    static final DummyClient INSTANCE = new DummyClient();

    private final BasicModule basic = new BasicModule() {
        @Override
        protected void emit(ServerEventBase event) {
        }
    };

    private final StatsModule stats = new StatsModule() {
        @Override
        protected void emit(ServerEventBase event) {
        }
    };

    private final SkillsModule skills = new SkillsModule() {
        @Override
        protected void emit(ServerEventBase event) {
        }
    };

    private final InventoryModule inventory = new InventoryModule() {
        @Override
        protected void emit(ServerEventBase event) {
        }
    };

    private final PetModule pet = new PetModule() {
        @Override
        protected void emit(ServerEventBase event) {
        }
    };

    private final MapModule map = new MapModule() {
        @Override
        protected void emit(ServerEventBase event) {
        }
    };

    private final NpcModule npc = new NpcModule() {
        @Override
        protected void emit(ServerEventBase event) {
        }
    };

    private final QuestModule quest = new QuestModule() {
        @Override
        protected void emit(ServerEventBase event) {
        }
    };

    private final MessageModule message = new MessageModule() {
        @Override
        protected void emit(ServerEventBase event) {
        }
    };

    private DummyClient() {
    }

    // ── 机器钩子：空操作 ──

    @Override
    public PacketHandler resolveHandler(short opcode) {
        return null;
    }

    @Override
    protected void flushAll() {
    }

    // ── 模块访问器 ──

    @Override
    public BasicModule basic() {
        return basic;
    }

    @Override
    public StatsModule stats() {
        return stats;
    }

    @Override
    public SkillsModule skills() {
        return skills;
    }

    @Override
    public InventoryModule inventory() {
        return inventory;
    }

    @Override
    public PetModule pet() {
        return pet;
    }

    @Override
    public MapModule map() {
        return map;
    }

    @Override
    public NpcModule npc() {
        return npc;
    }

    @Override
    public QuestModule quest() {
        return quest;
    }

    @Override
    public MessageModule message() {
        return message;
    }
}
