package org.gms.server.life;

import org.gms.client.character.CharacterRef;

import org.gms.client.character.Character;

public interface MonsterListener {

    void monsterKilled(int aniTime);
    void monsterDamaged(CharacterRef from, int trueDmg);
    void monsterHealed(int trueHeal);
}
