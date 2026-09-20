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
package org.gms.client.quest.requirements;

import org.gms.client.character.Character;
import org.gms.client.quest.QuestWz;
import org.gms.client.quest.QuestRequirementType;
import org.gms.provider.Data;
import org.gms.provider.DataTool;

/**
 * @author Ronan
 */
public class ScriptRequirement extends AbstractQuestRequirement {
    private String scriptName;   // WZ Check.img 指定的入口函数名（startscript/endscript 值）

    public ScriptRequirement(QuestWz quest, Data data) {
        super(QuestRequirementType.SCRIPT);
        processData(data);
    }

    @Override
    public void processData(Data data) {
        scriptName = DataTool.getString(data, null);
    }

    @Override
    public boolean check(Character chr, Integer npcid) {
        return true;
    }

    /** WZ 指定的脚本入口名；未声明返回 null */
    public String get() {
        return scriptName;
    }
}
