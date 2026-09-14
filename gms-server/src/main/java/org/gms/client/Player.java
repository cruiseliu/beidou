package org.gms.client;

import org.gms.client.character.Character;
import org.gms.client.keybind.KeyBinding;
import org.gms.client.inventory.Equip;
import org.gms.client.inventory.InventoryTab;
import org.gms.client.inventory.InventoryType;
import org.gms.client.inventory.ItemSlot;
import org.gms.client.pet.Pet;
import org.gms.client.Family;
import org.gms.client.FamilyEntry;
import org.gms.client.Mount;
import org.gms.client.SkillFactory;
import org.gms.client.BuddyList;
import org.gms.client.BuddylistEntry;
import org.gms.client.CharacterNameAndId;
import org.gms.config.GameConfig;
import org.gms.constants.game.GameConstants;
import org.gms.manager.ServerManager;
import org.gms.net.server.Server;
import org.gms.net.server.channel.Channel;
import org.gms.net.server.channel.CharacterIdChannelPair;
import org.gms.net.server.coordinator.world.EventRecallCoordinator;
import org.gms.net.server.guild.Alliance;
import org.gms.net.server.guild.Guild;
import org.gms.net.server.guild.GuildPackets;
import org.gms.net.server.world.PartyCharacter;
import org.gms.net.server.world.PartyOperation;
import org.gms.net.server.world.World;
import org.gms.scripting.event.EventInstanceManager;
import org.gms.service.HpMpAlertService;
import org.gms.service.NoteService;
import org.gms.util.DatabaseConnection;
import org.gms.util.I18nUtil;
import org.gms.util.PacketCreator;
import org.gms.util.packets.WeddingPackets;
import org.gms.server.maps.MapleMap;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.gms.remote.ClientEventHandlerRegistry;
import org.gms.remote.RemoteClient;

/**
 * 客户端 actor 的对内封装（词汇纪律：player = 客户端 actor，character = {@link Character}
 * 实体——新代码中"角色的"一律说 character，"这个 actor"说 player）。
 *
 * <p><b>获取纪律</b>：Player 只作为环境上下文存在——正在 player strand 上执行的代码经
 * {@link #current()} 零参数取得；strand 之外不可获取（无任何可导航出口：从 Client、从
 * strand 引用钻取均不可达）。跑在其他线程/strand 的代码放别的包，不得使用本类。
 *
 * <p><b>视图语义</b>：character/remote 为派生视图（从 {@link Client} 现取，不存槽）。
 * client 本身是可换绑的传输附件——换频道/出商城等过渡性重连时 strand 与本对象存活，
 * 由会话管理器（org.gms.net.server.coordinator.session.PlayerSession）在 actor 上
 * 执行 rebind 换绑到新 Client；派生视图零成本自动跟随，无双槽陈旧引用问题。
 *
 * <p><b>放置规则</b>：org.gms.client.** 的新代码执行期必须满足 {@code Player.current() != null}
 * （入口处 {@link #require} 断言）；{@link #current()} 的语义边界是"本线程正在作为该
 * actor 执行"，不是"本线程在为该 actor 等待"——阻塞在 strand.run 上的等待者拿到 null。
 */
public final class Player {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Player.class);


    private final PlayerStrand strand;
    private volatile Client client;
    /** 收包入口聚合（per-module Handler 槽位表）：actor 的收包插座，角色入场绑定时接插组件 */
    private final ClientEventHandlerRegistry clientEventHandlers = new ClientEventHandlerRegistry();

    Player(PlayerStrand strand) {
        this.strand = strand;
    }

    /** 当前线程正在作为的 actor；不在 player strand 上执行 → null */
    public static Player current() {
        PlayerStrand s = PlayerStrand.current();
        return s != null ? s.player() : null;
    }

    /** 同 {@link #current()}，但不在 player strand 上时抛出——新代码入口的放置断言（严格契约） */
    public static Player require(String what) {
        Player p = current();
        if (p == null) {
            throw new IllegalStateException("off-strand 访问 [" + what + "]：须在 player strand 上执行，当前线程 " + Thread.currentThread());
        }
        return p;
    }

    /** 本 actor 的执行队列（调度能力可经 Strand 基类跨线程使用；Player 上下文不可） */
    public PlayerStrand strand() {
        return strand;
    }

    /** 传输/会话所有者（netty handler、登录态、hwid 等的原住地） */
    public Client client() {
        return client;
    }

    /**
     * 收包 Handler 槽位表（remote 客户端事件的 actor 侧插座）。组件在角色入场绑定时
     * 接插（PlayerLoggedinHandler 入场任务，on strand）；会话与角色同寿命（换角色 =
     * 完整重登 = 新会话），会话期内不换插。
     */
    public ClientEventHandlerRegistry clientEventHandlers() {
        return clientEventHandlers;
    }

    /**
     * 换绑传输附件（过渡性重连时由会话管理器在本 strand 上调用——对 actor 的一切写
     * 必须走 actor 调度器，见 doc/12 权责语义）。
     */
    void rebind(Client c) {
        if (!strand.onStrand()) {
            throw new IllegalStateException("rebind 必须在本 actor strand 上执行（跨 actor 访问纪律）");
        }
        this.client = c;
    }


    /**
     * 角色实体槽位（doc/12 §21 追记：从属关系反转）——Character 从属于 Player（actor），
     * 入场编舞时在本 strand 上绑定，会话期内稳定（换角色 = 完整重登 = 新会话 = 新 Player）；
     * 过渡重连（换绑）不清槽。登录前/charlist 阶段为 null。
     */
    private volatile Character characterSlot;

    /**
     * 角色绑定（入场编舞调用；本 actor strand 上写，跨 actor 访问纪律同 rebind）。
     * 同步喂 Client.player legacy 镜像——真源在本槽位，镜像仅供存量消费者
     * （~900 处 .getPlayer()，批次迁移后随 Client 侧 Character 概念退役）。
     */
    public void bindCharacter(Character c) {
        if (!strand.onStrand()) {
            throw new IllegalStateException("bindCharacter 必须在本 actor strand 上执行");
        }
        this.characterSlot = c;
        client().setPlayer(c);
    }

    /** 角色实体（本 actor 的从属状态）；登录前/charlist 阶段为 null */
    public Character character() {
        return characterSlot;
    }

    /** 远端客户端语义层（世界域模块面视图；派生视图，惰性）。世界域代码获取语义层的唯一出口。 */
    public RemoteClient remote() {
        return client.remoteView();
    }




    /**
     * 入场流程（原 PlayerLoggedinHandler/PlayerSession verbatim 迁移）：仅在入场任务内
     * 调用——rebind 已完成（client()/character() 派生视图就绪），跑在本 actor strand 上。
     *
     * @param newcomer 全新入场（DB 装载定位）；false = 过渡重入（重绑出生点 + buff 恢复）
     */
    /**
     * 入场编舞（doc/12 §21 追记 4）：仅编排——服务端初始化与初始化数据发送都归
     * Character（initWorldEntry/sendWorldEntryData，保序拆分：spawn 包必须在
     * SET_FIELD 之后，编舞穿插随 send 原序 verbatim）。跑在本 actor strand 上。
     *
     * @param newcomer 全新入场（DB 装载定位）；false = 过渡重入（重绑出生点 + buff 恢复）
     */
    public void enterWorld(boolean newcomer) {
        final Client c = client();
        final Character player = character();
        player.initWorldEntry(c, newcomer);
        player.sendWorldEntryData(c, newcomer);
    }







    private static void showDueyNotification(Client c, Character player) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT Type FROM dueypackages WHERE ReceiverId = ? AND Checked = 1 ORDER BY Type DESC")) {
            ps.setInt(1, player.getId());

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement ps2 = con.prepareStatement("UPDATE dueypackages SET Checked = 0 WHERE ReceiverId = ?")) {
                        ps2.setInt(1, player.getId());
                        ps2.executeUpdate();

                        c.sendPacket(PacketCreator.sendDueyParcelNotification(rs.getInt("Type") == 1));
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String toString() {
        return "player[strand=" + strand + "]";
    }
}
