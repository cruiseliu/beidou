package org.gms.client.character;

import org.gms.client.autoban.AutobanManager;
import org.gms.constants.id.MapId;
import org.gms.dao.entity.AccountsDO;
import org.gms.model.json.CharacterAntiCheatData;
import org.gms.net.server.Server;
import org.gms.server.TimerManager;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * 反作弊模块组件：封禁（ban/autoBan/block/sendPolice）+ 监狱刑期（jail）+ 攻击间隔检测 + 移动距离检测。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（ban/autoBan/checkSkillWindow/... 对外转发）。
 *
 * 边界：只承载反作弊语义——封禁、监狱刑期、攻击频率滑动窗口判定、移动/瞬移距离误判修正。
 * 放逐（banish，城镇卷轴/怪物放逐）属地图语义，归 CharacterMap；
 * 依赖经 owner 门面调用（sendPacket/isGM/getWorld/...）。
 */
class CharacterAntiCheat {
    private static final Logger log = LoggerFactory.getLogger(CharacterAntiCheat.class);

    private final Character owner;

    private AutobanManager autoBan;
    private boolean banned = false;

    /** 监狱刑期到期时间戳（-1 表示从未入狱；持久化到 character_json 的 antiCheat 域） */
    private long jailExpiration = -1;

    // 记录最近一次“瞬移类位移”发生时间（单调时钟纳秒，用于短时间内的距离检测防误判）
    private volatile long lastTeleportLikeMoveTime = 0;
    // 传送距离误判修正上下文：用于“传送前坐标 + 当前坐标”双坐标校验
    private static final long TELEPORT_DISTANCE_CONTEXT_EXPIRE_NS = MILLISECONDS.toNanos(1200L); // 保护窗口，过长会增加可利用面
    private static final byte TELEPORT_DISTANCE_CONTEXT_MAX_ATTACK_CHECKS = 2; // 最多保护 2 次攻击包
    private static final double TELEPORT_DISTANCE_CONTEXT_MIN_SHIFT_SQ = 1600.0; // 至少 40px 位移才建立上下文
    private Point teleportBeforePos = null; // 传送前服务端坐标（用于双坐标距离复核）
    private Point teleportAfterPos = null; // 传送后服务端坐标（用于确认确实发生了传送位移）
    private int teleportContextMapId = MapId.NONE; // 传送上下文所属地图，跨图后自动失效
    private long teleportContextExpireTime = 0L; // 传送上下文过期时间戳（单调时钟纳秒）
    private byte teleportContextRemainingChecks = 0; // 传送上下文剩余可用攻击校验次数
    // 普通移动距离误判修正上下文：只覆盖“移动包后紧跟攻击包”的极短时间窗
    private static final long MOVEMENT_DISTANCE_CONTEXT_EXPIRE_NS = MILLISECONDS.toNanos(350L);
    private static final byte MOVEMENT_DISTANCE_CONTEXT_MAX_ATTACK_CHECKS = 1;
    private static final double MOVEMENT_DISTANCE_CONTEXT_MIN_SHIFT_SQ = 400.0; // 至少 20px 位移才建立上下文
    private Point movementBeforePos = null;
    private Point movementAfterPos = null;
    private int movementContextMapId = MapId.NONE;
    private long movementContextExpireTime = 0L;
    private byte movementContextRemainingChecks = 0;

    /** 窗口大小：最近 N 次攻击间隔 */
    static final int WINDOW_SIZE = 10;
    /** 变异系数阈值：CV < 此值判定为稳定高速 */
    static final double STABLE_CV = 0.3;
    /** 网络抖动透明上限：< 此值的间隔不更新状态、不入窗口 */
    static final long MIN_INTERVAL = 50;
    /** 平均阈值：窗口 avg >= 此值判定为正常频率 */
    static final long NORMAL_AVG = 250;
    /** 窗口自动过期时间：60s 无写入自动重置 */
    private static final long CLEANUP_MS = 60_000;

    /** 各技能攻击间隔滑动窗口 */
    private final ConcurrentHashMap<Integer, AttackWindow> skillWindows = new ConcurrentHashMap<>();

    /** 全局最后攻击时间戳，只被正常主动技能更新 */
    private volatile long globalAttackTime;

    CharacterAntiCheat(Character owner) {
        this.owner = owner;
    }

    // ── 封禁 ──

    void ban(String reason) {
        Character.accountService.ban(owner, reason);
    }

    static boolean ban(String id, String reason, boolean accountId) {
        try {
            Character.accountService.ban(id, reason, accountId);
            return true;
        } catch (Exception ex) {
            log.error(I18nUtil.getLogMessage("Character.ban.error1"), id, ex);
        }
        return false;
    }

    void autoBan(String reason) {
        if (owner.isGM() || this.banned) {  // thanks RedHat for noticing GM's being able to get banned
            return;
        }
        this.ban(reason);
        owner.sendPacket(PacketCreator.sendPolice(I18nUtil.getMessage("Character.autoBan.message1")));  //发送自动封禁提示
        TimerManager.getInstance().schedule(() -> owner.client.disconnect(false, false), 5000);

        Server.getInstance().broadcastGMMessage(owner.getWorld(), PacketCreator.serverNotice(6, Character.makeMapleReadable(owner.getName()) + " was autobanned for " + reason));
    }

    void block(int reason, int days, String desc) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, days);
        Character.accountService.update(AccountsDO.builder()
                .id(owner.getAccountId())
                .banreason(desc)
                .tempban(new Timestamp(cal.getTimeInMillis()))
                .greason(reason)
                .build());
    }

    void sendPolice(int greason, String reason, int duration) {
        owner.sendPacket(PacketCreator.sendPolice(String.format("You have been blocked by the#b %s Police for %s.#k", "Cosmic", reason)));
        this.banned = true;
        TimerManager.getInstance().schedule(() -> owner.client.disconnect(false, false), duration);
    }

    void sendPolice(String text) {
        final String message = owner.getName() + " received this - " + text;
        if (Server.getInstance().isGmOnline(owner.getWorld())) { //Alert and log if a GM is online
            Server.getInstance().broadcastGMMessage(owner.getWorld(), PacketCreator.sendYellowTip(message));
        } else { //Auto DC and log if no GM is online
            owner.client.disconnect(false, false);
        }
        log.info(message);
        //Server.getInstance().broadcastGMMessage(0, PacketCreator.serverNotice(1, getName() + " received this - " + text));
        //sendPacket(PacketCreator.sendPolice(text));
        //this.isbanned = true;
        //TimerManager.getInstance().schedule(new Runnable() {
        //    @Override
        //    public void run() {
        //        client.disconnect(false, false);
        //    }
        //}, 6000);
    }

    boolean isBanned() {
        return banned;
    }

    void setBanned(boolean banned) {
        this.banned = banned;
    }

    AutobanManager getAutoBanManager() {
        return autoBan;
    }

    void setAutoBanManager(AutobanManager autoBan) {
        this.autoBan = autoBan;
    }

    // ── 监狱（jail）──

    /** 剩余刑期（ms），未入狱时为负数 */
    long getJailExpirationTimeLeft() {
        return jailExpiration - System.currentTimeMillis();
    }

    private void setFutureJailExpiration(long time) {
        jailExpiration = System.currentTimeMillis() + time;
    }

    /** 累加刑期（GM 再次入狱时在原剩余刑期上追加） */
    void addJailExpirationTime(long time) {
        long timeLeft = getJailExpirationTimeLeft();

        if (timeLeft <= 0) {
            setFutureJailExpiration(time);
        } else {
            setFutureJailExpiration(timeLeft + time);
        }
    }

    /** 清空刑期（出狱） */
    void removeJailExpirationTime() {
        jailExpiration = 0;
    }

    // ── 持久化 ──

    CharacterAntiCheatData toData() {
        CharacterAntiCheatData data = new CharacterAntiCheatData();
        data.jailExpiration = jailExpiration;
        return data;
    }

    void applyData(CharacterAntiCheatData data) {
        if (data != null) {
            jailExpiration = data.jailExpiration;
        }
    }

    // ── 攻击间隔检测 ──

    /** 环形缓冲，保存最近 N 次攻击间隔，满后自动计算 avg + CV */
    static final class AttackWindow {
        private final long[] buf = new long[WINDOW_SIZE];
        private int idx;
        private int count;
        private long lastPush;

        AttackWindow() {
            this.lastPush = System.currentTimeMillis();
        }

        synchronized void push(long interval) {
            long now = System.currentTimeMillis();
            if (now - lastPush > CLEANUP_MS) {
                idx = 0;
                count = 0;
            }
            buf[idx] = interval;
            idx = (idx + 1) % WINDOW_SIZE;
            if (count < WINDOW_SIZE) count++;
            lastPush = now;
        }

        synchronized boolean isFull() {
            return count == WINDOW_SIZE;
        }

        synchronized double avg() {
            long sum = 0;
            for (int i = 0; i < count; i++) sum += buf[i];
            return (double) sum / count;
        }

        synchronized double stddev() {
            double a = avg();
            double sumSq = 0;
            for (int i = 0; i < count; i++) {
                double d = buf[i] - a;
                sumSq += d * d;
            }
            return Math.sqrt(sumSq / count);
        }
    }

    /**
     * 推入间隔到滑动窗口，返回窗口判定结果。
     * 窗口不满或 avg >= 250 返回 PASS；
     * avg < 250 且 CV < STABLE_CV 返回 STABLE_HACK；
     * avg < 250 但 CV >= STABLE_CV 返回 BURST。
     */
    Character.SkillWindowResult checkSkillWindow(int skillId, long interval) {
        AttackWindow w = skillWindows.computeIfAbsent(skillId, k -> new AttackWindow());
        w.push(interval);
        if (!w.isFull()) return Character.SkillWindowResult.PASS;
        double avg = w.avg();
        if (avg >= NORMAL_AVG) return Character.SkillWindowResult.PASS;
        double cv = w.stddev() / avg;
        return cv < STABLE_CV ? Character.SkillWindowResult.STABLE_HACK : Character.SkillWindowResult.BURST;
    }

    /** 获取指定技能的滑动窗口（外部只读 avg / isFull），无则返回 null */
    AttackWindow getSkillWindow(int skillId) {
        return skillWindows.get(skillId);
    }

    /** 获取全局攻击间隔。首次或未设时返回 Long.MAX_VALUE */
    long getGlobalInterval(long now) {
        long last = globalAttackTime;
        return last == 0 ? Long.MAX_VALUE : now - last;
    }

    /** 更新全局攻击时间戳，只被正常主动技能调用 */
    void updateGlobalTime(long now) {
        globalAttackTime = now;
    }

    // ── 移动距离检测 ──

    /**
     * 标记一次瞬移类位移，并记录传送前后坐标用于后续攻击距离双坐标校验。
     *
     * <p>只在位移明显时建立上下文，避免普通小步移动误入传送保护逻辑。</p>
     */
    synchronized void markTeleportLikeMove(Point beforePos, Point afterPos) {
        long now = monotonicNow();
        this.lastTeleportLikeMoveTime = now;

        if (!shouldBuildTeleportDistanceContext(beforePos, afterPos)) {
            clearTeleportDistanceContextLocked();
            return;
        }

        this.teleportBeforePos = copyPoint(beforePos);
        this.teleportAfterPos = copyPoint(afterPos);
        this.teleportContextMapId = owner.getMapId();
        this.teleportContextExpireTime = now + TELEPORT_DISTANCE_CONTEXT_EXPIRE_NS;
        this.teleportContextRemainingChecks = TELEPORT_DISTANCE_CONTEXT_MAX_ATTACK_CHECKS;
    }

    /**
     * 记录一次普通移动前后坐标，用于极短时间窗内的攻击距离双坐标校验。
     */
    synchronized void markRegularMove(Point beforePos, Point afterPos) {
        long now = monotonicNow();
        if (!shouldBuildMovementDistanceContext(beforePos, afterPos)) {
            clearMovementDistanceContextLocked();
            return;
        }

        this.movementBeforePos = copyPoint(beforePos);
        this.movementAfterPos = copyPoint(afterPos);
        this.movementContextMapId = owner.getMapId();
        this.movementContextExpireTime = now + MOVEMENT_DISTANCE_CONTEXT_EXPIRE_NS;
        this.movementContextRemainingChecks = MOVEMENT_DISTANCE_CONTEXT_MAX_ATTACK_CHECKS;
    }

    /**
     * 获取用于攻击距离校验的“传送前坐标”。
     *
     * <p>仅在上下文仍有效时返回，超时/跨图/次数耗尽会自动清理。</p>
     */
    synchronized Point getTeleportBeforePositionForDistanceCheck() {
        if (!isTeleportDistanceContextActiveLocked(monotonicNow())) {
            clearTeleportDistanceContextLocked();
            return null;
        }
        return copyPoint(teleportBeforePos);
    }

    /**
     * 获取用于攻击距离校验的“普通移动前坐标”。
     */
    synchronized Point getMovementBeforePositionForDistanceCheck() {
        if (!isMovementDistanceContextActiveLocked(monotonicNow())) {
            clearMovementDistanceContextLocked();
            return null;
        }
        return copyPoint(movementBeforePos);
    }

    /**
     * 消费一次传送距离保护校验次数（按攻击包维度消费）。
     */
    synchronized void consumeTeleportDistanceCheckContext() {
        if (!isTeleportDistanceContextActiveLocked(monotonicNow())) {
            clearTeleportDistanceContextLocked();
            return;
        }

        teleportContextRemainingChecks--;
        if (teleportContextRemainingChecks <= 0) {
            clearTeleportDistanceContextLocked();
        }
    }

    /**
     * 消费一次普通移动距离保护校验次数。
     */
    synchronized void consumeMovementDistanceCheckContext() {
        if (!isMovementDistanceContextActiveLocked(monotonicNow())) {
            clearMovementDistanceContextLocked();
            return;
        }

        movementContextRemainingChecks--;
        if (movementContextRemainingChecks <= 0) {
            clearMovementDistanceContextLocked();
        }
    }

    /**
     * 显式清空“传送距离校验上下文”。
     *
     * <p>用于跨图切换等关键状态变更点，确保不会携带旧地图上下文参与后续判定。</p>
     */
    synchronized void clearTeleportDistanceContext() {
        clearTeleportDistanceContextLocked();
        clearMovementDistanceContextLocked();
        lastTeleportLikeMoveTime = 0L;
    }

    private boolean isTeleportDistanceContextActiveLocked(long now) {
        return teleportBeforePos != null
                && teleportAfterPos != null
                && teleportContextRemainingChecks > 0
                && now <= teleportContextExpireTime
                && teleportContextMapId == owner.getMapId();
    }

    private boolean isMovementDistanceContextActiveLocked(long now) {
        return movementBeforePos != null
                && movementAfterPos != null
                && movementContextRemainingChecks > 0
                && now <= movementContextExpireTime
                && movementContextMapId == owner.getMapId();
    }

    private void clearTeleportDistanceContextLocked() {
        teleportBeforePos = null;
        teleportAfterPos = null;
        teleportContextMapId = MapId.NONE;
        teleportContextExpireTime = 0L;
        teleportContextRemainingChecks = 0;
    }

    private void clearMovementDistanceContextLocked() {
        movementBeforePos = null;
        movementAfterPos = null;
        movementContextMapId = MapId.NONE;
        movementContextExpireTime = 0L;
        movementContextRemainingChecks = 0;
    }

    /**
     * 仅当传送前后坐标有效且位移幅度足够大时，才建立距离校验上下文。
     */
    private static boolean shouldBuildTeleportDistanceContext(Point beforePos, Point afterPos) {
        return beforePos != null
                && afterPos != null
                && beforePos.distanceSq(afterPos) >= TELEPORT_DISTANCE_CONTEXT_MIN_SHIFT_SQ;
    }

    private static boolean shouldBuildMovementDistanceContext(Point beforePos, Point afterPos) {
        return beforePos != null
                && afterPos != null
                && beforePos.distanceSq(afterPos) >= MOVEMENT_DISTANCE_CONTEXT_MIN_SHIFT_SQ;
    }

    private static Point copyPoint(Point pos) {
        return pos == null ? null : new Point(pos);
    }

    private static long monotonicNow() {
        return System.nanoTime();
    }
}
