package org.gms.client.character;

import org.gms.client.Stat;
import org.gms.util.PacketCreator;
import org.gms.util.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CharacterListener implements AbstractCharacterListener {
    private final Character character;
    public CharacterListener(Character character) {
        this.character = character;
    }

    @Override
    public void onHpChanged(int oldHp) {
        character.hpChangeAction(oldHp);
    }

    @Override
    public void onHpMpPoolUpdate() {
        List<Pair<Stat, Integer>> hpmpupdate = character.recalcLocalStats();
        for (Pair<Stat, Integer> p : hpmpupdate) {
            character.statUpdates.put(p.getLeft(), p.getRight());
        }

        if (character.stats.hp > character.stats.localMaxHp) {
            character.setHp(character.stats.localMaxHp);
            character.statUpdates.put(Stat.HP, character.stats.hp);
        }

        if (character.stats.mp > character.stats.localMaxMp) {
            character.setMp(character.stats.localMaxMp);
            character.statUpdates.put(Stat.MP, character.stats.mp);
        }
    }

    @Override
    public void onStatUpdate() {
        character.recalcLocalStats();
    }

    @Override
    public void onAnnounceStatPoolUpdate() {
        List<Pair<Stat, Integer>> statup = new ArrayList<>(8);
        for (Map.Entry<Stat, Integer> s : character.statUpdates.entrySet()) {
            statup.add(new Pair<>(s.getKey(), s.getValue()));
        }

        character.sendPacket(PacketCreator.updatePlayerStats(statup, true, character));
    }
}
