package org.gms.remote.gms083;

import java.nio.charset.Charset;

import org.gms.remote.gms083.server.translators.CooldownTranslator;
import org.gms.remote.gms083.server.translators.InventoryTranslator;
import org.gms.remote.gms083.server.translators.PetTranslator;
import org.gms.remote.gms083.server.translators.SkillsTranslator;
import org.gms.remote.gms083.server.translators.StatsTranslator;

public class Gms083Translators {
    public CooldownTranslator cooldownT = new CooldownTranslator();
    public InventoryTranslator inventoryT = new InventoryTranslator();
    public PetTranslator petT;
    public SkillsTranslator skillsT = new SkillsTranslator();
    public StatsTranslator statsT = new StatsTranslator();

    public Gms083Translators(Charset charset) {
        this.petT = new PetTranslator(charset);
    }
}
