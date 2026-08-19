package org.gms.client.character;

import org.gms.client.Disease;
import org.gms.constants.skills.Bishop;
import org.gms.net.server.Server;
import org.gms.server.life.MobSkill;
import org.gms.server.life.MobSkillId;
import org.gms.util.Locks;
import org.gms.util.DatabaseConnection;
import org.gms.util.PacketCreator;
import org.gms.util.TimeoutHelper;import org.gms.util.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 角色 debuff/疾病模块组件：疾病表（debuffs + 到期时刻）+ 到期定时 + 施加/解除/净化 + 持久化。
 * 仿照 CharacterBuffs/CharacterPets 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * 自有锁（lock）串行化（原实现用 chrLock），Character 保留公开具名门面。
 *
 * 锁说明：原 debuffs 由 chrLock 保护，重构时收敛为本类自有的 lock——
 * 与 chrLock 无交互（施加/解除只读 buff 快照与发包），无新锁序。
 */
class CharacterDebuffs {
    private final Character owner;

    /** 疾病 → (生效时刻+剩余时长, 来源技能) */
    private final EnumMap<Disease, DebuffStatus> debuffs = new EnumMap<>(Disease.class);

    /** 疾病模块锁：串行化疾病表/到期定时 */
    private final Lock lock = new ReentrantLock(true);

    /** debuff 到期定时器（id = disease.ordinal()，timestamp = 到期时刻），替代原 1.5s 周期扫描 */
    private final TimeoutHelper debuffExpireTimer = new TimeoutHelper();

    /** 冻结时刻，-1 = 未冻结（离开世界：换频道/商城/MTS） */
    private long frozenAt = -1;

    CharacterDebuffs(Character owner) {
        this.owner = owner;
        debuffExpireTimer.setListener((id, timestamp) -> {
            dispelDebuff(Disease.values()[id]);
        });
    }

    // ── 查询 ──

    boolean hasDebuff(final Disease dis) {
        try (var ignored = Locks.acquire(lock)) {
            return debuffs.containsKey(dis);
        }
    }

    int getDebuffsSize() {
        try (var ignored = Locks.acquire(lock)) {
            return debuffs.size();
        }
    }

    Map<Disease, Pair<Long, MobSkill>> getAllDebuffs() {
        try (var ignored = Locks.acquire(lock)) {
            long curtime = Server.getInstance().getCurrentTime();
            Map<Disease, Pair<Long, MobSkill>> ret = new LinkedHashMap<>();

            for (Entry<Disease, DebuffStatus> de : debuffs.entrySet()) {
                DebuffStatus status = de.getValue();

                ret.put(de.getKey(), new Pair<>(status.length - (curtime - status.startTime), status.source));
            }

            return ret;
        }
    }

    // ── 施加/恢复 ──

    void silentApplyDebuffs(Map<Disease, Pair<Long, MobSkill>> debuffMap) {
        try (var ignored = Locks.acquire(lock)) {
            long curTime = Server.getInstance().getCurrentTime();

            for (Entry<Disease, Pair<Long, MobSkill>> di : debuffMap.entrySet()) {
                long expTime = curTime + di.getValue().getLeft();

                debuffs.put(di.getKey(), new DebuffStatus(di.getKey(), di.getValue().getRight(), curTime, di.getValue().getLeft()));
                debuffExpireTimer.schedule(di.getKey().ordinal(), curTime + di.getValue().getLeft());
            }
        }
    }

    void giveDebuff(final Disease debuff, MobSkill skill) {
        if (!hasDebuff(debuff) && getDebuffsSize() < 2) {
            if (!(debuff == Disease.SEDUCE || debuff == Disease.STUN)) {
                if (owner.hasActiveBuff(Bishop.HOLY_SHIELD)) {
                    return;
                }
            }

            long curTime;
            try (var ignored = Locks.acquire(lock)) {
                curTime = Server.getInstance().getCurrentTime();
                debuffs.put(debuff, new DebuffStatus(debuff, skill, curTime, skill.getDuration()));
            }
            debuffExpireTimer.schedule(debuff.ordinal(), curTime + skill.getDuration());

            if (debuff == Disease.SEDUCE && owner.getChair() < 0) {
                owner.sitChair(-1);
            }

            final List<Pair<Disease, Integer>> debuffList = Collections.singletonList(new Pair<>(debuff, Integer.valueOf(skill.getX())));
            owner.sendPacket(PacketCreator.giveDebuff(debuffList, skill));

            if (debuff != Disease.SLOW) {
                owner.getMap().broadcastMessage(owner, PacketCreator.giveForeignDebuff(owner.getId(), debuffList, skill), false);
            } else {
                owner.getMap().broadcastMessage(owner, PacketCreator.giveForeignSlowDebuff(owner.getId(), debuffList, skill), false);
            }
        }
    }

    // ── 解除/净化 ──

    void dispelDebuff(Disease debuff) {
        if (hasDebuff(debuff)) {
            long mask = debuff.getValue();
            owner.sendPacket(PacketCreator.cancelDebuff(mask));

            if (debuff != Disease.SLOW) {
                owner.getMap().broadcastMessage(owner, PacketCreator.cancelForeignDebuff(owner.getId(), mask), false);
            } else {
                owner.getMap().broadcastMessage(owner, PacketCreator.cancelForeignSlowDebuff(owner.getId()), false);
            }

            try (var ignored = Locks.acquire(lock)) {
                debuffs.remove(debuff);
            }
        }
    }

    void dispelDebuffs() {
        dispelDebuff(Disease.CURSE);
        dispelDebuff(Disease.DARKNESS);
        dispelDebuff(Disease.POISON);
        dispelDebuff(Disease.SEAL);
        dispelDebuff(Disease.WEAKEN);
        dispelDebuff(Disease.SLOW);    // thanks Conrad for noticing ZOMBIFY isn't dispellable
    }

    void purgeDebuffs() {
        dispelDebuff(Disease.SEDUCE);
        dispelDebuff(Disease.ZOMBIFY);
        dispelDebuff(Disease.CONFUSE);
        dispelDebuffs();
    }

    void cancelAllDebuffs() {
        try (var ignored = Locks.acquire(lock)) {
            for (Disease debuff : debuffs.keySet()) {
                debuffExpireTimer.cancel(debuff.ordinal());
            }
            debuffs.clear();
        }
    }

    // ── 跨图/全图通告 ──

    /** 疾病可见性跨地图过渡延续（换图/进商城重入时向全图广播本角色疾病） */
    void announceDebuffs() {
        Set<Entry<Disease, DebuffStatus>> chrDebuffs;

        try (var ignored = Locks.acquire(lock)) {
            // Poison damage visibility and debuffs status visibility, extended through map transitions thanks to Ronan
            if (!owner.isLoggedInWorld()) {
                return;
            }

            chrDebuffs = new LinkedHashSet<>(debuffs.entrySet());
        }

        for (Entry<Disease, DebuffStatus> di : chrDebuffs) {
            Disease debuff = di.getKey();
            MobSkill skill = di.getValue().source;
            final List<Pair<Disease, Integer>> debuffList = Collections.singletonList(new Pair<>(debuff, Integer.valueOf(skill.getX())));

            if (debuff != Disease.SLOW) {
                owner.getMap().broadcastMessage(PacketCreator.giveForeignDebuff(owner.getId(), debuffList, skill));
            } else {
                owner.getMap().broadcastMessage(PacketCreator.giveForeignSlowDebuff(owner.getId(), debuffList, skill));
            }
        }
    }

    /** 把全图所有玩家的疾病广播给本客户端（进图时同步他人 debuff 显示） */
    void collectDebuffs() {
        for (Character chr : owner.getMap().getAllPlayers()) {
            int cid = chr.getId();

            for (Entry<Disease, Pair<Long, MobSkill>> di : chr.debuffs.getAllDebuffs().entrySet()) {
                Disease debuff = di.getKey();
                MobSkill skill = di.getValue().getRight();
                final List<Pair<Disease, Integer>> debuffList = Collections.singletonList(new Pair<>(debuff, Integer.valueOf(skill.getX())));

                if (debuff != Disease.SLOW) {
                    owner.sendPacket(PacketCreator.giveForeignDebuff(cid, debuffList, skill));
                } else {
                    owner.sendPacket(PacketCreator.giveForeignSlowDebuff(cid, debuffList, skill));
                }
            }
        }
    }

    // ── 到期定时 ──

    // ── 定时器生命周期（仿 CharacterBuffs.expireTimer） ──

    /** 启动定时器并补排全部在册 debuff（登录恢复在 start 前 schedule 被丢弃，此处统一补排；已到期的立即触发） */
    void startExpireTimer() {
        debuffExpireTimer.start();
        List<DebuffStatus> all;
        try (var ignored = Locks.acquire(lock)) {
            all = new ArrayList<>(debuffs.values());
        }
        for (DebuffStatus status : all) {
            // 到期时刻 = startTime + length，随 startTime 推移自动成立
            debuffExpireTimer.scheduleOrTrigger(status.type.ordinal(), status.startTime + status.length);
        }
    }

    void stopExpireTimer() {
        debuffExpireTimer.stop();
    }

    /** 冻结全部 debuff 计时（离开世界：换频道/进商城/进MTS）。状态保留在本对象上。 */
    void freeze() {
        frozenAt = Server.getInstance().getCurrentTime();
        stopExpireTimer();
    }

    /** 恢复 debuff 计时：推移冻结期间跳过的时间并重启定时器。未冻结时空操作。 */
    void resume() {
        if (frozenAt < 0) {
            return;
        }
        long skipped = Server.getInstance().getCurrentTime() - frozenAt;
        frozenAt = -1;
        if (skipped > 0) {
            try (var ignored = Locks.acquire(lock)) {
                // 到期时刻 = startTime + length，随 startTime 推移自动成立
                for (DebuffStatus status : debuffs.values()) {
                    status.startTime += skipped;
                }
            }
        }
        startExpireTimer();
    }

    // ── 持久化 ──

    /** 落库全部疾病（断开保存时调用；先删后插，保持 playerdebuffs 与内存一致） */
    void saveDebuffs() {
        Map<Disease, Pair<Long, MobSkill>> listds = getAllDebuffs();
        if (!listds.isEmpty()) {
            try (Connection con = DatabaseConnection.getConnection()) {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM playerdebuffs WHERE charid = ?")) {
                    ps.setInt(1, owner.getId());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO playerdebuffs (charid, debuff, mobskillid, mobskilllv, length) VALUES (?, ?, ?, ?, ?)")) {
                    ps.setInt(1, owner.getId());

                    for (Entry<Disease, Pair<Long, MobSkill>> e : listds.entrySet()) {
                        ps.setInt(2, e.getKey().ordinal());

                        MobSkill ms = e.getValue().getRight();
                        MobSkillId msId = ms.getId();
                        ps.setInt(3, msId.type().getId());
                        ps.setInt(4, msId.level());
                        ps.setInt(5, e.getValue().getLeft().intValue());
                        ps.addBatch();
                    }

                    ps.executeBatch();
                }
            } catch (SQLException se) {
                se.printStackTrace();
            }
        }
    }
}
