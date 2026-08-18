/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

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

import org.gms.net.server.Server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 登录会话 HWID 交接缓存，按 accountId 键控（每个账号一条）。
 *  原实现按 remoteHost(IP) 键控：同 IP 并发登录会互相消费对方条目（取出即删），
 *  导致后到者取到 null 被静默断连。改按账号键控后不再受同 IP 并发影响，
 *  代价是丢失了"该 IP 已知 hwid 在线则拒登"的同机检测（反作弊放宽，见 doc/TODO.md）。 */
class HostHwidCache {
    private final ConcurrentHashMap<Integer, HostHwid> hostHwidCache = new ConcurrentHashMap<>(); // Key: accountId

    void clearExpired() {
        SessionDAO.deleteExpiredHwidAccounts();

        Instant now = Instant.ofEpochMilli(Server.getInstance().getCurrentTime());
        List<Integer> accountIdsToRemove = new ArrayList<>();
        for (Map.Entry<Integer, HostHwid> entry : hostHwidCache.entrySet()) {
            if (now.isAfter(entry.getValue().expiry())) {
                accountIdsToRemove.add(entry.getKey());
            }
        }

        for (int accountId : accountIdsToRemove) {
            hostHwidCache.remove(accountId);
        }
    }

    void addEntry(int accountId, Hwid hwid) {
        hostHwidCache.put(accountId, HostHwid.createWithDefaultExpiry(hwid));
    }

    HostHwid getEntry(int accountId) {
        return hostHwidCache.get(accountId);
    }

    Hwid removeEntryAndGetItsHwid(int accountId) {
        HostHwid hostHwid = hostHwidCache.remove(accountId);
        return hostHwid == null ? null : hostHwid.hwid();
    }

    Hwid getEntryHwid(int accountId) {
        HostHwid hostHwid = hostHwidCache.get(accountId);
        return hostHwid == null ? null : hostHwid.hwid();
    }

}
