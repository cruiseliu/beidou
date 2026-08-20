package org.gms.client.character;

import org.gms.client.EffectType;
import org.gms.client.Skill;
import org.gms.client.SkillFactory;
import org.gms.config.GameConfig;
import org.gms.constants.id.ItemId;
import org.gms.constants.skills.DarkKnight;
import org.gms.server.BuffEffectData;
import org.gms.server.TimerManager;
import org.gms.server.maps.Summon;
import org.gms.util.Locks;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.concurrent.TimeUnit.SECONDS;

/** L3 激活处理层：每槽位（BuffStat）当前生效的效果值表 + 激活 buff 的实际处理。
 *
 *  职责：
 *  - 激活表存储与查询（getBuffedValue/getBuffSource/hasActiveBuff 等）——游戏逻辑只查本层，
 *    看不到被覆盖（未部署）的 buff；
 *  - 消费 {@link EffectChangeReport} 应用激活表变更（applyChanges，函数式重建快照）；
 *  - 对外发布（publish：发包 / 召唤物清理 / 伴随任务停启）与伴随任务启动（startCompanions）。
 *
 *  激活表以**不可变快照**形式发布（volatile effects 字段）：applyChanges 在持锁状态下
 *  函数式地计算新表并整体替换；查询（含属性重算 recalc）**无锁读取快照**——recalc 只需要
 *  effects，不再需要 buff 锁，hp/mp 写入因此不必持有 effLock。
 *  本层完全不依赖 L1 在册表——重发 updateBuffEffect 所需槽位列表取自效果自身的 statups。 */
class ActiveBuffs {
    private final Character owner;

    /** 槽位 → 激活条目（值 + 溯源）的不可变快照；由 applyChanges/clear 整体替换发布 */
    volatile Map<EffectType, EffectStatus> effects = Collections.emptyMap();

    ActiveBuffs(Character owner) {
        this.owner = owner;
    }

    // ── 查询（无锁读快照） ──

    Long getBuffedStarttime(EffectType effectType) {
        EffectStatus effect = effects.get(effectType);
        return effect == null ? null : effect.buff.startTime;
    }

    Integer getBuffedValue(EffectType effectType) {
        EffectStatus effect = effects.get(effectType);
        return effect == null ? null : effect.value;
    }

    int getBuffSource(EffectType effectType) {
        EffectStatus effect = effects.get(effectType);
        return effect == null ? -1 : effect.getData().getSourceId();
    }

    BuffEffectData getBuffEffect(EffectType stat) {
        EffectStatus mbsvh = effects.get(stat);
        return mbsvh == null ? null : mbsvh.getData();
    }

    boolean isBuffFrom(EffectType stat, Skill skill) {
        EffectStatus mbsvh = effects.get(stat);
        return mbsvh != null && mbsvh.getData().isSkill() && mbsvh.getData().getSourceId() == skill.getId();
    }

    /** 就地修改槽位值（如能量条 ENERGY_CHARGE），值字段 volatile 安全发布给无锁读者 */
    void setBuffedValue(EffectType effect, int value) {
        EffectStatus mbsvh = effects.get(effect);
        if (mbsvh != null) {
            mbsvh.value = value;
        }
    }

    /** 溯源查询：某源的效果是否正处于激活态 */
    boolean hasActiveBuff(int sourceid) {
        for (EffectStatus mbsvh : effects.values()) {   // 快照不可变，可直接迭代
            if (mbsvh.getData().getBuffSourceId() == sourceid) {
                return true;
            }
        }
        return false;
    }

    // ── L3 变更应用 ──

    /** 消费报告的 L3 部分（三合一）：应用激活表变更 → 发布对外副作用 → 启动伴随任务。
     *  顺序固定且有依赖：publish 先停掉被摘除者（deactivated）的伴随任务，
     *  startCompanions 再启动接替者，避免"新任务刚起就被旧任务清理误杀"。调用方持锁。 */
    void applyReport(EffectChangeReport report) {
        applyChanges(report);
        publish(report);
        startCompanions(report.deploy);
    }

    /** 函数式应用激活表变更：以当前快照为基础计算新表（按类型移除 → 摘除 deactivated → 部署 deploy），
     *  整体发布为新快照。调用方持锁（串行化所有写者）。 */
    private void applyChanges(EffectChangeReport report) {
        // 注意：EnumMap 构造器不接受空源 map（抛 "Specified map is empty"），先建空表再 putAll
        EnumMap<EffectType, EffectStatus> next = new EnumMap<>(EffectType.class);
        next.putAll(effects);
        for (EffectType type : report.removeActiveTypes) {
            next.remove(type);
        }
        for (EffectStatus es : report.deactivated) {
            next.remove(es.type);
        }
        for (EffectStatus es : report.deploy) {
            next.put(es.type, es);
        }
        effects = Collections.unmodifiableMap(next);
    }

    /** 清空激活表（cancelAllBuffs 软取消）：整体替换为空快照 */
    void clear() {
        effects = Collections.emptyMap();
    }

    // ── L3 发布 ──

    /** 按变化清单推送对外副作用（发包/召唤物清理/伴随任务停启）。调用方持锁。 */
    void publish(EffectChangeReport report) {
        for (EffectStatus es : report.deactivated) {
            if (es.type == EffectType.RECOVERY) {
                if (owner.recoveryTask != null) {
                    owner.recoveryTask.cancel(false);
                    owner.recoveryTask = null;
                }
            } else if (es.type == EffectType.SUMMON || es.type == EffectType.PUPPET) {
                Summon summon = owner.summons.get(es.getData().getSourceId());
                if (summon != null) {
                    owner.removeSummonAndPuppet(summon);
                }
            } else if (es.type == EffectType.DRAGONBLOOD) {
                owner.dragonBloodSchedule.cancel(false);
                owner.dragonBloodSchedule = null;
            } else if (es.type == EffectType.HPREC || es.type == EffectType.MPREC) {
                if (es.type == EffectType.HPREC) {
                    owner.extraHpRec = 0;
                } else {
                    owner.extraMpRec = 0;
                }

                if (owner.extraRecoveryTask != null) {
                    owner.extraRecoveryTask.cancel(false);
                    owner.extraRecoveryTask = null;
                }

                if (owner.extraHpRec != 0 || owner.extraMpRec != 0) {
                    owner.startExtraTaskInternal(owner.extraHpRec, owner.extraMpRec, owner.extraRecInterval);
                }
            }
        }
        if (report.stopChairTask) {
            owner.stopChairTask();
        }
        if (report.unregisterMountHunger) {
            owner.getClient().getWorldServer().unregisterMountHunger(owner);
            owner.getMapleMount().setActive(false);
        }
        if (!report.lostSlots.isEmpty()) {
            cancelPlayerBuffs(new ArrayList<>(report.lostSlots));
        }
        for (Pair<BuffEffectData, Long> mse : report.reannounced) {
            mse.getLeft().updateBuffEffect(owner, getActiveEffectTypesAndValues(mse.getLeft()), mse.getRight());
        }
        if (report.battleshipRefresh && owner.isRidingBattleship()) {
            List<Pair<EffectType, Integer>> statups = new ArrayList<>(1);
            statups.add(new Pair<>(EffectType.MONSTER_RIDING, 0));
            owner.sendPacket(PacketCreator.giveBuff(ItemId.BATTLESHIP, 5221006, statups));
            owner.announceBattleshipHp();
        }
    }

    /** 激活表变更后刷新派生属性（本地 maxHp/maxMp/watk 等，池变化时顺带发 STAT_CHANGED）。
     *  必须在管线释放 buff 锁后调用：updateLocalStats 内部按 prtLock→effLock→stats.wLock 取锁，
     *  且持 wLock 期间经 recalcLocalStats 再取 chrLock——若在管线持锁（eff-only 或 chr）时调用，
     *  会分别形成 eff→prt 逆序与 chr→wLock 环，与定时器线程的锁序交叉死锁。 */
    void refreshLocalStats() {
        owner.stats.updateLocalStats();
    }

    /** 通知客户端取消指定槽位（含对外广播）。调用方持锁；cancelBuffStats 取消整槽后也会调用。 */
    void cancelPlayerBuffs(List<EffectType> effectTypes) {
        if (owner.client.getChannelServer().getPlayerStorage().getCharacterById(owner.getId()) != null) {
            owner.sendPacket(PacketCreator.cancelBuff(effectTypes));
            if (!effectTypes.isEmpty()) {
                owner.getMap().broadcastMessage(owner, PacketCreator.cancelForeignBuff(owner.getId(), effectTypes), false);
            }
        }
    }

    /** 已在调用方持锁内调用：取效果自身的槽位列表，值取激活表当前生效者（被其他源压制时显示压制者值） */
    private List<Pair<EffectType, Integer>> getActiveEffectTypesAndValues(BuffEffectData data) {
        List<Pair<EffectType, Integer>> ret = new ArrayList<>();
        List<Pair<EffectType, Integer>> singletonStatups = new ArrayList<>();
        for (Pair<EffectType, Integer> ps : data.getStatups()) {
            EffectType type = ps.getLeft();
            EffectStatus active = effects.get(type);

            Pair<EffectType, Integer> p;
            if (active != null) {
                p = new Pair<>(type, active.value);
            } else {
                p = new Pair<>(type, 0);
            }

            if (!BuffSelector.isSingletonStatup(type)) {   // thanks resinate, Daddy Egg for pointing out morph issues when updating it along with other statups
                ret.add(p);
            } else {
                singletonStatups.add(p);
            }
        }

        ret.sort(Comparator.comparing(Pair::getLeft));

        if (!singletonStatups.isEmpty()) {
            singletonStatups.sort(Comparator.comparing(Pair::getLeft));

            ret.addAll(singletonStatups);
        }

        return ret;
    }

    // ── 伴随任务 ──

    /** 对本次最终部署（report.deploy）的持有者启动伴随任务（龙血/狂暴/小灵/恢复跳/额外回复/椅子）。
     *  必须在 publish 之后调用：publish 会先停掉被摘除者（deactivated）的伴随任务，
     *  这里再启动接替者，避免"新任务刚起就被旧任务清理误杀"。
     *  同源多槽位持有者去重，一个 buff 源只启动一次。 */
    void startCompanions(List<EffectStatus> deploy) {
        Set<BuffEffectData> started = new LinkedHashSet<>();
        for (EffectStatus es : deploy) {
            if (started.add(es.getData())) {
                startCompanionTasks(es.getData());
            }
        }
    }

    /** 伴随任务启动（龙血/狂暴/小灵/恢复跳/额外回复/椅子）。
     *  仅由 startCompanions 在部署完成后调用——被压制（未部署）的 buff 不再启动任务，
     *  修复原"注册前无条件启动"导致的龙血双调度：第二个被压制的龙血不再误杀第一个的 HP 扣减调度。 */
    private void startCompanionTasks(BuffEffectData effect) {
        if (effect.isDragonBlood()) {
            owner.prepareDragonBlood(effect);
        } else if (effect.isBerserk()) {
            owner.checkBerserk(owner.isHidden());
        } else if (effect.isBeholder()) {
            final int beholder = DarkKnight.BEHOLDER;
            if (owner.beholderHealingSchedule != null) {
                owner.beholderHealingSchedule.cancel(false);
            }
            if (owner.beholderBuffSchedule != null) {
                owner.beholderBuffSchedule.cancel(false);
            }
            Skill bHealing = SkillFactory.getSkill(DarkKnight.AURA_OF_BEHOLDER);
            int bHealingLvl = owner.getSkillLevel(bHealing);
            if (bHealingLvl > 0) {
                final BuffEffectData healEffect = bHealing.getEffect(bHealingLvl);
                int healInterval = (int) SECONDS.toMillis(healEffect.getX());
                owner.beholderHealingSchedule = TimerManager.getInstance().register(() -> {
                    if (owner.awayFromWorld.get()) {
                        return;
                    }

                    owner.addHP(healEffect.getHp());
                    owner.sendPacket(PacketCreator.showOwnBuffEffect(beholder, 2));
                    owner.getMap().broadcastMessage(owner, PacketCreator.summonSkill(owner.getId(), beholder, 5), true);
                    owner.getMap().broadcastMessage(owner, PacketCreator.showOwnBuffEffect(beholder, 2), false);
                }, healInterval, healInterval);
            }
            Skill bBuff = SkillFactory.getSkill(DarkKnight.HEX_OF_BEHOLDER);
            if (owner.getSkillLevel(bBuff) > 0) {
                final BuffEffectData buffEffect = bBuff.getEffect(owner.getSkillLevel(bBuff));
                int buffInterval = (int) SECONDS.toMillis(buffEffect.getX());
                owner.beholderBuffSchedule = TimerManager.getInstance().register(() -> {
                    if (owner.awayFromWorld.get()) {
                        return;
                    }

                    buffEffect.applyTo(owner);
                    owner.sendPacket(PacketCreator.showOwnBuffEffect(beholder, 2));
                    owner.getMap().broadcastMessage(owner, PacketCreator.summonSkill(owner.getId(), beholder, (int) (Math.random() * 3) + 6), true);
                    owner.getMap().broadcastMessage(owner, PacketCreator.showBuffEffect(owner.getId(), beholder, 2), false);
                }, buffInterval, buffInterval);
            }
        } else if (effect.isRecovery()) {
            int healInterval = (GameConfig.getServerBoolean("use_ultra_recovery")) ? 2000 : 5000;
            final byte heal = (byte) effect.getX();

            try (var ignored = Locks.acquire(owner.chrLock)) {
                if (owner.recoveryTask != null) {
                    owner.recoveryTask.cancel(false);
                }

                owner.recoveryTask = TimerManager.getInstance().register(() -> {
                    if (getBuffSource(EffectType.RECOVERY) == -1) {
                        try (var ignored2 = Locks.acquire(owner.chrLock)) {
                            if (owner.recoveryTask != null) {
                                owner.recoveryTask.cancel(false);
                                owner.recoveryTask = null;
                            }
                        }

                        return;
                    }

                    owner.addHP(heal);
                    owner.sendPacket(PacketCreator.showOwnRecovery(heal));
                    owner.getMap().broadcastMessage(owner, PacketCreator.showRecovery(owner.id, heal), false);
                }, healInterval, healInterval);
            }
        } else if (effect.getHpRRate() > 0 || effect.getMpRRate() > 0) {
            if (effect.getHpRRate() > 0) {
                owner.extraHpRec = effect.getHpR();
                owner.extraRecInterval = effect.getHpRRate();
            }

            if (effect.getMpRRate() > 0) {
                owner.extraMpRec = effect.getMpR();
                owner.extraRecInterval = effect.getMpRRate();
            }

            try (var ignored = Locks.acquire(owner.chrLock)) {
                owner.stopExtraTask();
                owner.startExtraTask(owner.extraHpRec, owner.extraMpRec, owner.extraRecInterval);   // HP & MP sharing the same task holder
            }

        } else if (effect.isMapChair()) {
            owner.startChairTask();
        }
    }
}
