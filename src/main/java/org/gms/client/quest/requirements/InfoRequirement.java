package org.gms.client.quest.requirements;

import org.gms.client.character.Character;
import org.gms.client.quest.QuestRequirementType;
import org.gms.client.quest.QuestWz;
import org.gms.provider.Data;
import org.gms.provider.DataTool;

public class InfoRequirement extends AbstractQuestRequirement {
    private String value;

    public InfoRequirement(QuestWz quest, Data data) {
        super(QuestRequirementType.INFO);
        processData(data);
    }

    @Override
    public void processData(Data data) {
        value = DataTool.getString("0", data, null);
    }

    @Override
    public boolean check(Character chr, Integer npcid) {
        return true;
    }

    public String getValue() {
        return value;
    }
}
