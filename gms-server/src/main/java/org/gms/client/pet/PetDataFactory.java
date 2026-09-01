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
package org.gms.client.pet;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Danny (Leifde)
 */
public class PetDataFactory {
    private static final DataProvider dataRoot = DataProviderFactory.getDataProvider(WZFiles.ITEM);
    private static final Map<String, PetCommand> petCommands = new HashMap<>();
    private static final Map<Integer, Integer> petHunger = new HashMap<>();

    public static PetCommand getPetCommand(int petId, int skillId) {
        PetCommand ret = petCommands.get(petId + "" + skillId);
        if (ret != null) {
            return ret;
        }
        synchronized (petCommands) {
            ret = petCommands.get(petId + "" + skillId);
            if (ret == null) {
                Data skillData = dataRoot.getData("Pet/" + petId + ".img");
                int prob = 0;
                int inc = 0;
                if (skillData != null) {
                    prob = DataTool.getInt("interact/" + skillId + "/prob", skillData, 0);
                    inc = DataTool.getInt("interact/" + skillId + "/inc", skillData, 0);
                }
                ret = new PetCommand(petId, skillId, prob, inc);
                petCommands.put(petId + "" + skillId, ret);
            }
            return ret;
        }
    }

    public static int getHunger(int petId) {
        Integer ret = petHunger.get(petId);
        if (ret != null) {
            return ret;
        }
        synchronized (petHunger) {
            ret = petHunger.get(petId);
            if (ret == null) {
                ret = DataTool.getInt(dataRoot.getData("Pet/" + petId + ".img").getChildByPath("info/hungry"), 1);
            }
            return ret;
        }
    }

    /**
     * 蛋类判别（wz info/hungry 缺失 = 无宠物行为、不可召唤的蛋）：进化龙 5000028 / 机器蛋 5000047。
     * 注意"有 evol1"不构成判据——可召唤宠物（宝贝龙等）同样携带任务进化目标。
     */
    public static boolean isHatchling(int petId) {
        Data root = dataRoot.getData("Pet/" + petId + ".img");
        return root != null && root.getChildByPath("info/hungry") == null;
    }

    /**
     * 宠物持续时间（wz info/life，单位天）：发放时计算 expiresAt 的真值来源；无字段返回 0（永久）。
     */
    public static int getLife(int petId) {
        Data root = dataRoot.getData("Pet/" + petId + ".img");
        if (root == null) {
            return 0;
        }
        if (DataTool.getInt("info/permanent", root, 0) == 1) {
            return 0;
        }
        return DataTool.getInt("info/life", root, 0);
    }

    public static boolean canRevive(int petId) {
        Data root = dataRoot.getData("Pet/" + petId + ".img");
        int noRevive = DataTool.getInt("info/noRevive", root, 0);
        return noRevive != 1;
    }

    /**
     * 孵化/进化目标（wz info/evol1）：蛋类道具使用时换宿主的去向 id；无进化定义返回 0。
     */
    public static int getEvolution(int petId) {
        Data root = dataRoot.getData("Pet/" + petId + ".img");
        if (root == null || root.getChildByPath("info/evol1") == null) {
            return 0;
        }
        return DataTool.getInt("info/evol1", root, 0);
    }
}
