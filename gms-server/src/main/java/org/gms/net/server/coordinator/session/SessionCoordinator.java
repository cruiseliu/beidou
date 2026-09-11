/*
    This file is part of the HeavenMS MapleStory Server
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.net.server.coordinator.session;

import org.gms.client.character.Character;
import org.gms.client.Client;
import org.gms.config.GameConfig;
import org.gms.constants.id.NpcId;
import org.gms.net.server.Server;
import org.gms.net.server.coordinator.login.LoginStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gms.util.DatabaseConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * @author Ronan
 */
public class SessionCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SessionCoordinator.class);
    private static final SessionCoordinator instance = new SessionCoordinator();

    public static SessionCoordinator getInstance() {
        return instance;
    }

    public enum AntiMulticlientResult {
        SUCCESS,
        REMOTE_LOGGEDIN,
        REMOTE_REACHED_LIMIT,
        REMOTE_PROCESSING,
        REMOTE_NO_MATCH,
        MANY_ACCOUNT_ATTEMPTS,
        COORDINATOR_ERROR
    }

    private final SessionInitialization sessionInit = new SessionInitialization();
    private final LoginStorage loginStorage = new LoginStorage();
    /** 登录阶段（选角前）的会话条目，Key: account id —— 摘除按 sessionId 比对 */
    private final Map<Integer, Client> loginClients = new HashMap<>();
    /** 游戏会话 registry（doc/12），Key: account id —— 写入 attach、摘除 finalize */
    private final Map<Integer, PlayerSession> sessions = new HashMap<>();
    private final Object sessionLock = new Object();
    private final Set<Hwid> onlineRemoteHwids = new HashSet<>(); // Hwid/nibblehwid
    private final Map<String, Client> loginRemoteHosts = new ConcurrentHashMap<>(); // Key: Ip (+ nibblehwid)
    private final HostHwidCache hostHwidCache = new HostHwidCache();

    private SessionCoordinator() {
    }

    private static boolean attemptAccountAccess(int accountId, Hwid hwid, boolean routineCheck) {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<HwidRelevance> hwidRelevances = SessionDAO.getHwidRelevance(con, accountId);
            for (HwidRelevance hwidRelevance : hwidRelevances) {
                if (hwidRelevance.hwid().endsWith(hwid.hwid())) {
                    if (!routineCheck) {
                        // better update HWID relevance as soon as the login is authenticated
                        Instant expiry = HwidAssociationExpiry.getHwidAccountExpiry(hwidRelevance.relevance());
                        SessionDAO.updateAccountAccess(con, hwid, accountId, expiry, hwidRelevance.getIncrementedRelevance());
                    }

                    return true;
                }
            }

            if (hwidRelevances.size() < GameConfig.getServerInt("max_allowed_account_hwid")) {
                return true;
            }
        } catch (SQLException e) {
            log.warn("Failed to update account access. Account id: {}, nibbleHwid: {}", accountId, hwid, e);
        }

        return false;
    }

    public static String getSessionRemoteHost(Client client) {
        Hwid hwid = client.getHwid();

        if (hwid != null) {
            return client.getRemoteAddress() + "-" + hwid.hwid();
        } else {
            return client.getRemoteAddress();
        }
    }

    /**
     * 登录成功（updateLoginState(LOGIN_LOGGEDIN)）时的会话簿记：顶掉同账号的在玩旧会话
     * （新客户端进程登录 = 顶号），并登记登录阶段条目。游戏会话的 registry 写入归
     * {@link #attach}（PlayerLoggedinHandler），不走此路径。
     */
    public void updateOnlineClient(Client client) {
        if (client != null) {
            int accountId = client.getAccID();
            PlayerSession s = getSession(accountId);
            if (s != null) {
                Client ingameClient = s.client();
                // 同会话的重入（adopt 场景旧连接尚未 inactive）：不顶——过渡收尾不落到此连接
                if (ingameClient != null && ingameClient != client && ingameClient.getSession() == s) {
                    ingameClient.forceDisconnect();
                }
            }
            synchronized (sessionLock) {
                loginClients.put(accountId, client);
            }
        }
    }

    /**
     * 建立游戏会话（PlayerLoggedinHandler 前半段调用；调用线程为该连接的临时引导 strand）。
     * - registry 无会话/已终态 → fresh（新会话）；
     * - TRANSITION → adopt（同进程过渡重入，actor 状态存活）；
     * - ATTACHED（顶号）/CLOSING → forceDisconnect 旧传输并等待其终态后 fresh
     *   （顶号不遗传旧进程的 actor 状态）。
     * 等待发生在本连接的引导 strand 上：该 strand 是一次性登录队列，阻塞它即"登录等待
     * 旧登出"的语义本身，不违反跨 strand 互等纪律（doc/12 §3.10）。
     */
    public PlayerSession attach(Client client, int accountId) {
        while (true) {
            PlayerSession existing = getSession(accountId);
            if (existing == null || existing.state() == PlayerSession.State.CLOSED) {
                PlayerSession created = new PlayerSession(accountId);
                synchronized (sessionLock) {
                    PlayerSession race = sessions.get(accountId);
                    if (race != null && race.state() != PlayerSession.State.CLOSED) {
                        continue;   // 并发 attach 抢先，重查
                    }
                    sessions.put(accountId, created);
                }
                created.attachClient(client);
                return created;
            }

            if (existing.state() == PlayerSession.State.TRANSITION) {
                if (existing.adoptClient(client)) {
                    return existing;
                }
                continue;   // reaper 抢先终结，重查
            }

            // ATTACHED（顶号）/ CLOSING：终结旧会话 → 等终态 → fresh
            Client ingameClient = existing.client();
            if (ingameClient != null && ingameClient != client && ingameClient.getSession() == existing) {
                ingameClient.forceDisconnect();
            }
            existing.awaitClosed();
        }
    }

    /** 会话终结时从 registry 摘除（PlayerSession.finalize 调用） */
    void removeSession(PlayerSession session) {
        synchronized (sessionLock) {
            if (sessions.get(session.accountId()) == session) {
                sessions.remove(session.accountId());
            }
        }
    }

    private PlayerSession getSession(int accountId) {
        synchronized (sessionLock) {
            return sessions.get(accountId);
        }
    }

    public boolean canStartLoginSession(Client client) {
        if (!GameConfig.getServerBoolean("deterred_multi_client")) {
            return true;
        }

        String remoteHost = getSessionRemoteHost(client);
        final InitializationResult initResult = sessionInit.initialize(remoteHost);
        switch (initResult.getAntiMulticlientResult()) {
            case REMOTE_PROCESSING -> {
                return false;
            }
            case COORDINATOR_ERROR -> {
                return true;
            }
        }

        try {
            if (loginRemoteHosts.containsKey(remoteHost)) {
                return false;
            }

            loginRemoteHosts.put(remoteHost, client);
            return true;
        } finally {
            sessionInit.finalize(remoteHost);
        }
    }

    public void closeLoginSession(Client client) {
        clearLoginRemoteHost(client);

        Hwid nibbleHwid = client.getHwid();
        client.setHwid(null);
        if (nibbleHwid != null) {
            onlineRemoteHwids.remove(nibbleHwid);

            if (client != null) {
                Client loggedClient;
                synchronized (sessionLock) {
                    loggedClient = loginClients.get(client.getAccID());
                }

                // do not remove an online game session here, only login session
                if (loggedClient != null && loggedClient.getSessionId() == client.getSessionId()) {
                    synchronized (sessionLock) {
                        loginClients.remove(client.getAccID());
                    }
                }
            }
        }
    }

    private void clearLoginRemoteHost(Client client) {
        String remoteHost = getSessionRemoteHost(client);
        loginRemoteHosts.remove(client.getRemoteAddress());
        loginRemoteHosts.remove(remoteHost);
    }

    public AntiMulticlientResult attemptLoginSession(Client client, Hwid hwid, int accountId, boolean routineCheck) {
        if (!GameConfig.getServerBoolean("deterred_multi_client")) {
            client.setHwid(hwid);
            return AntiMulticlientResult.SUCCESS;
        }

        String remoteHost = getSessionRemoteHost(client);
        InitializationResult initResult = sessionInit.initialize(remoteHost);
        if (initResult != InitializationResult.SUCCESS) {
            return initResult.getAntiMulticlientResult();
        }

        try {
            if (!loginStorage.registerLogin(accountId)) {
                return AntiMulticlientResult.MANY_ACCOUNT_ATTEMPTS;
            } else if (routineCheck && !attemptAccountAccess(accountId, hwid, routineCheck)) {
                return AntiMulticlientResult.REMOTE_REACHED_LIMIT;
            } else if (onlineRemoteHwids.contains(hwid)) {
                return AntiMulticlientResult.REMOTE_LOGGEDIN;
            } else if (!attemptAccountAccess(accountId, hwid, routineCheck)) {
                return AntiMulticlientResult.REMOTE_REACHED_LIMIT;
            }

            client.setHwid(hwid);
            onlineRemoteHwids.add(hwid);

            return AntiMulticlientResult.SUCCESS;
        } finally {
            sessionInit.finalize(remoteHost);
        }
    }

    public AntiMulticlientResult attemptGameSession(Client client, int accountId, Hwid hwid) {
        final String remoteHost = getSessionRemoteHost(client);
        if (!GameConfig.getServerBoolean("deterred_multi_client")) {
            hostHwidCache.addEntry(accountId, hwid); // 按账号键控，供新角色进频道时拾取（原按 IP，同 IP 并发会互相挤掉）
            return AntiMulticlientResult.SUCCESS;
        }

        final InitializationResult initResult = sessionInit.initialize(remoteHost);
        if (initResult != InitializationResult.SUCCESS) {
            return initResult.getAntiMulticlientResult();
        }

        try {
            Hwid clientHwid = client.getHwid(); // thanks Paxum for noticing account stuck after PIC failure
            if (clientHwid == null) {
                return AntiMulticlientResult.REMOTE_NO_MATCH;
            }

            onlineRemoteHwids.remove(clientHwid);

            if (!hwid.equals(clientHwid)) {
                return AntiMulticlientResult.REMOTE_NO_MATCH;
            } else if (onlineRemoteHwids.contains(hwid)) {
                return AntiMulticlientResult.REMOTE_LOGGEDIN;
            }

            // assumption: after a SUCCESSFUL login attempt, the incoming client WILL receive a new IoSession from the game server

            // updated session CLIENT_HWID attribute will be set when the player log in the game
            onlineRemoteHwids.add(hwid);
            hostHwidCache.addEntry(accountId, hwid);
            associateHwidAccountIfAbsent(hwid, accountId);

            return AntiMulticlientResult.SUCCESS;
        } finally {
            sessionInit.finalize(remoteHost);
        }
    }

    private static void associateHwidAccountIfAbsent(Hwid hwid, int accountId) {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<Hwid> hwids = SessionDAO.getHwidsForAccount(con, accountId);

            boolean containsRemoteHwid = hwids.stream().anyMatch(accountHwid -> accountHwid.equals(hwid));
            if (containsRemoteHwid) {
                return;
            }

            if (hwids.size() < GameConfig.getServerInt("max_allowed_account_hwid")) {
                Instant expiry = HwidAssociationExpiry.getHwidAccountExpiry(0);
                SessionDAO.registerAccountAccess(con, accountId, hwid, expiry);
            }
        } catch (SQLException ex) {
            log.warn("Failed to associate hwid {} with account id {}", hwid, accountId, ex);
        }
    }

    private static Client fetchInTransitionSessionClient(Client client) {
        // 原实现按远程 IP 从 hostHwidCache 找回会话 hwid（getGameSessionHwid）；
        // hwid 缓存改按账号键控后此路径失去查询依据，且仅被 closeSession(null) 调用——
        // 实际调用方均传非空 client，此分支不可达且原实现即会 NPE，故直接返回 null。
        return null;
    }

    public void closeSession(Client client, Boolean immediately) {
        if (client == null) {
            client = fetchInTransitionSessionClient(client);
        }

        final Hwid hwid = client.getHwid();
        client.setHwid(null); // making sure to clean up calls to this function on login phase
        if (hwid != null) {
            onlineRemoteHwids.remove(hwid);
        }

        final boolean isGameSession = hwid != null;
        if (isGameSession) {
            // 游戏会话的 registry 摘除由 PlayerSession.finalize 负责（doc/12）
        } else {
            Client loggedClient;
            synchronized (sessionLock) {
                loggedClient = loginClients.get(client.getAccID());
            }

            // do not remove an online game session here, only login session
            if (loggedClient != null && loggedClient.getSessionId() == client.getSessionId()) {
                synchronized (sessionLock) {
                    loginClients.remove(client.getAccID());
                }
            }
        }

        if (immediately != null && immediately) {
            // 立即终结（封禁/协议异常/选角失败）：有会话走会话终结（登出收尾在会话 strand 上
            // 执行，含保存——修复旧路径"不保存直接断"的数据丢失）；无会话走旧异步路径
            client.terminateSession(false);
            client.closeSession();
        }
    }

    public Hwid pickLoginSessionHwid(int accountId) {
        // 按账号键控（原按远程 IP 并取出即删：同 IP 并发登录时后到者取到 null 被静默断连）
        return hostHwidCache.removeEntryAndGetItsHwid(accountId);
    }

    public Hwid getGameSessionHwid(int accountId) {
        return hostHwidCache.getEntryHwid(accountId);
    }

    public void clearExpiredHwidHistory() {
        hostHwidCache.clearExpired();
    }

    public void runUpdateLoginHistory() {
        loginStorage.clearExpiredAttempts();
    }

    public void printSessionTrace() {
        synchronized (sessionLock) {
            if (!sessions.isEmpty()) {
                List<Entry<Integer, PlayerSession>> elist = new ArrayList<>(sessions.entrySet());
                String commaSeparatedClients = elist.stream()
                        .map(Entry::getKey)
                        .sorted(Integer::compareTo)
                        .map(Object::toString)
                        .collect(Collectors.joining(", "));

                log.debug("Current game sessions: {}", commaSeparatedClients);
            }

            if (!loginClients.isEmpty()) {
                List<Entry<Integer, Client>> elist = new ArrayList<>(loginClients.entrySet());
                elist.sort(Entry.comparingByKey());
                String commaSeparatedClients = elist.stream()
                        .map(Entry::getKey)
                        .map(Object::toString)
                        .collect(Collectors.joining(", "));

                log.debug("Current login clients: {}", commaSeparatedClients);
            }
        }

        if (!onlineRemoteHwids.isEmpty()) {
            List<Hwid> hwids = new ArrayList<>(onlineRemoteHwids);
            hwids.sort(Comparator.comparing(Hwid::hwid));

            log.debug("Current online HWIDs: {}", hwids.stream()
                    .map(Hwid::hwid)
                    .collect(Collectors.joining(" ")));
        }

        if (!loginRemoteHosts.isEmpty()) {
            List<Entry<String, Client>> elist = new ArrayList<>(loginRemoteHosts.entrySet());
            elist.sort(Entry.comparingByKey());

            log.debug("Current login sessions: {}", loginRemoteHosts.entrySet().stream()
                    .sorted(Entry.comparingByKey())
                    .map(entry -> "(" + entry.getKey() + ", client: " + entry.getValue())
                    .collect(Collectors.joining(", ")));
        }
    }

    public void printSessionTrace(Client c) {
        String str = "Opened server sessions:\r\n\r\n";

        synchronized (sessionLock) {
            if (!sessions.isEmpty()) {
                List<Entry<Integer, PlayerSession>> elist = new ArrayList<>(sessions.entrySet());
                elist.sort(Entry.comparingByKey());

                str += ("Current game sessions:\r\n");
                for (Entry<Integer, PlayerSession> e : elist) {
                    str += ("  " + e.getKey() + " " + e.getValue().state() + "\r\n");
                }
            }

            if (!loginClients.isEmpty()) {
                List<Entry<Integer, Client>> elist = new ArrayList<>(loginClients.entrySet());
                elist.sort(Entry.comparingByKey());

                str += ("Current login clients:\r\n");
                for (Entry<Integer, Client> e : elist) {
                    str += ("  " + e.getKey() + "\r\n");
                }
            }
        }

        if (!onlineRemoteHwids.isEmpty()) {
            List<Hwid> hwids = new ArrayList<>(onlineRemoteHwids);
            hwids.sort(Comparator.comparing(Hwid::hwid));

            str += ("Current online HWIDs:\r\n");
            for (Hwid s : hwids) {
                str += ("  " + s + "\r\n");
            }
        }

        if (!loginRemoteHosts.isEmpty()) {
            List<Entry<String, Client>> elist = new ArrayList<>(loginRemoteHosts.entrySet());

            elist.sort((e1, e2) -> e1.getKey().compareTo(e2.getKey()));

            str += ("Current login sessions:\r\n");
            for (Entry<String, Client> e : elist) {
                str += ("  " + e.getKey() + ", IP: " + e.getValue().getRemoteAddress() + "\r\n");
            }
        }

        c.getAbstractPlayerInteraction().npcTalk(NpcId.TEMPLE_KEEPER, str);
    }
}
