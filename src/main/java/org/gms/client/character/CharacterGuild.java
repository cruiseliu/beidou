package org.gms.client.character;

import org.gms.dao.entity.GuildsDO;
import org.gms.net.server.Server;
import org.gms.net.server.guild.Alliance;
import org.gms.net.server.guild.Guild;
import org.gms.net.server.guild.GuildCharacter;
import org.gms.net.server.guild.GuildPackets;
import org.gms.util.DatabaseConnection;
import org.gms.util.I18nUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 公会/联盟模块组件：公会 id/职位 + 公会成员视图（mgc）+ 公会/联盟查询与操作。
 * 仿照 CharacterBuffs/CharacterChair 模式：数据 + 领域逻辑内聚于此，持有 owner 反向引用，
 * Character 保留公开具名门面（getGuild/getAlliance/saveGuildStatus/... 对外转发）。
 *
 * 边界：只承载公会/联盟语义——guildId/guildRank/allianceRank、mgc、公会操作（解散/扩容/消息/等级更新）。
 * 依赖经 owner 门面调用（sendPacket/getWorld/getMeso/gainMeso/dropMessage/...）。
 */
class CharacterGuild {
    private final Character owner;

    private int guildId;
    private int guildRank;
    private int allianceRank;

    /** 公会成员视图（null 表示不在公会） */
    private GuildCharacter mgc = null;

    CharacterGuild(Character owner) {
        this.owner = owner;
    }

    // ── 查询 ──

    int getGuildId() {
        return guildId;
    }

    void setGuildId(int guildId) {
        this.guildId = guildId;
    }

    int getGuildRank() {
        return guildRank;
    }

    void setGuildRank(int guildRank) {
        this.guildRank = guildRank;
    }

    int getAllianceRank() {
        return allianceRank;
    }

    void setAllianceRank(int allianceRank) {
        this.allianceRank = allianceRank;
    }

    GuildCharacter getMGC() {
        return mgc;
    }

    void setMGC(GuildCharacter mgc) {
        this.mgc = mgc;
    }

    Guild getGuild() {
        try {
            return Server.getInstance().getGuild(getGuildId(), owner.getWorld(), owner);
        } catch (Exception ex) {
            ex.printStackTrace();
            return null;
        }
    }

    Alliance getAlliance() {
        if (mgc != null) {
            try {
                return Server.getInstance().getAlliance(getGuild().getAllianceId());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        return null;
    }

    boolean isGuildLeader() {    // true on guild master or jr. master
        return guildId > 0 && guildRank < 3;
    }

    // ── 操作 ──

    void deleteGuild(int guildId) {
        Character.characterService.deleteGuild(GuildsDO.builder().guildid((long) guildId).build());
    }

    void disbandGuild() {
        if (guildId < 1 || guildRank != 1) {
            return;
        }
        try {
            Server.getInstance().disbandGuild(guildId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void genericGuildMessage(int code) {
        owner.sendPacket(GuildPackets.genericGuildMessage((byte) code));
    }

    void guildUpdate() {
        mgc.setLevel(owner.getLevel());
        mgc.setJobId(owner.job.getId());

        if (this.guildId < 1) {
            return;
        }

        try {
            Server.getInstance().memberLevelJobUpdate(this.mgc);
            //Server.getInstance().getGuild(guildid, world, mgc).gainGP(40);
            int allianceId = getGuild().getAllianceId();
            if (allianceId > 0) {
                Server.getInstance().allianceMessage(allianceId, GuildPackets.updateAllianceJobLevel(owner), owner.getId(), -1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void increaseGuildCapacity() {
        int cost = Guild.getIncreaseGuildCost(getGuild().getCapacity());

        if (owner.getMeso() < cost) {
            owner.dropMessage(1, I18nUtil.getMessage("Character.increaseGuildCapacity.message1"));
            return;
        }

        if (Server.getInstance().increaseGuildCapacity(guildId)) {
            owner.gainMeso(-cost, true, false, true);
        } else {
            owner.dropMessage(1, I18nUtil.getMessage("Character.increaseGuildCapacity.message2"));
        }
    }

    void saveGuildStatus() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE characters SET guildid = ?, guildrank = ?, allianceRank = ? WHERE id = ?")) {
            ps.setInt(1, guildId);
            ps.setInt(2, guildRank);
            ps.setInt(3, allianceRank);
            ps.setInt(4, owner.getId());
            ps.executeUpdate();
        } catch (SQLException se) {
            se.printStackTrace();
        }
    }
}
