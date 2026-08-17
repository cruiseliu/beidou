/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick@huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.gms.net.server;

import org.gms.client.Disease;
import org.gms.server.life.MobSkill;
import org.gms.util.Pair;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 跨会话 debuff 中转站（一次性存取）。buff 域已改为对象驻留冻结
 * （CharacterBuffs.freeze/resume），不再经由本类。
 *
 * @author Danny//changed to map :3
 * @author Ronan//debuffs to storage as well
 */
public class PlayerBuffStorage {
    private final Lock lock = new ReentrantLock(true);
    private final Map<Integer, Map<Disease, Pair<Long, MobSkill>>> diseases = new HashMap<>();

    public void addDiseasesToStorage(int chrid, Map<Disease, Pair<Long, MobSkill>> toStore) {
        lock.lock();
        try {
            diseases.put(chrid, toStore);
        } finally {
            lock.unlock();
        }
    }

    public Map<Disease, Pair<Long, MobSkill>> getDiseasesFromStorage(int chrid) {
        lock.lock();
        try {
            return diseases.remove(chrid);
        } finally {
            lock.unlock();
        }
    }
}
