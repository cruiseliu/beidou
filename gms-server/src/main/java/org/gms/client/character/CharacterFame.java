package org.gms.client.character;

import org.gms.client.Stat;
import org.gms.util.DatabaseConnection;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 人气模块组件：人气值（fame）+ 人气赠送记录（lastfametime/lastmonthfameids）+ 赠送日志（famelog 表）。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（gainFame/hasGivenFame/... 对外转发）。
 *
 * 边界：只承载人气语义——人气值读改写、赠送响应与 famelog 落库。
 * 依赖经 owner 门面调用（updateSingleStat/sendPacket/getName/getId/...）。
 */
class CharacterFame {
    private final Character owner;

    private int fame;
    private final Lock fameLock = new ReentrantLock(true);   // applyFame 的 fame 读改写专用（原误用 petLock）
    private long lastfametime;
    private List<Integer> lastmonthfameids;

    CharacterFame(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    int getFame() {
        return fame;
    }

    void setFame(int fame) {
        this.fame = fame;
    }

    long getLastfametime() {
        return lastfametime;
    }

    void setLastfametime(long lastfametime) {
        this.lastfametime = lastfametime;
    }

    List<Integer> getLastmonthfameids() {
        return lastmonthfameids;
    }

    void setLastmonthfameids(List<Integer> lastmonthfameids) {
        this.lastmonthfameids = lastmonthfameids;
    }

    // ── 赠送 ──

    private Pair<Integer, Integer> applyFame(int delta) {
        fameLock.lock();
        try {
            int newFame = fame + delta;
            if (newFame < -30000) {
                delta = -(30000 + fame);
            } else if (newFame > 30000) {
                delta = 30000 - fame;
            }

            fame += delta;
            return new Pair<>(fame, delta);
        } finally {
            fameLock.unlock();
        }
    }

    void gainFame(int delta) {
        gainFame(delta, null, 0);
    }

    boolean gainFame(int delta, Character fromPlayer, int mode) {
        Pair<Integer, Integer> fameRes = applyFame(delta);
        delta = fameRes.getRight();
        if (delta != 0) {
            int thisFame = fameRes.getLeft();
            owner.updateSingleStat(Stat.FAME, thisFame);

            if (fromPlayer != null) {
                fromPlayer.sendPacket(PacketCreator.giveFameResponse(mode, owner.getName(), thisFame));
                owner.sendPacket(PacketCreator.receiveFame(mode, fromPlayer.getName()));
            } else {
                owner.sendPacket(PacketCreator.getShowFameGain(delta));
            }
            return true;
        }

        return false;
    }

    void hasGivenFame(Character to) {
        lastfametime = System.currentTimeMillis();
        lastmonthfameids.add(to.getId());
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO famelog (characterid, characterid_to) VALUES (?, ?)")) {
            ps.setInt(1, owner.getId());
            ps.setInt(2, to.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}
