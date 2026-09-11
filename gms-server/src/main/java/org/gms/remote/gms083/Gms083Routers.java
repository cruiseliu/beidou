package org.gms.remote.gms083;

import org.gms.remote.gms083.server.routers.BasicRouter;
import org.gms.remote.gms083.server.routers.CooldownRouter;
import org.gms.remote.gms083.server.routers.InventoryRouter;
import org.gms.remote.gms083.server.routers.MapRouter;
import org.gms.remote.gms083.server.routers.PetRouter;
import org.gms.remote.gms083.server.routers.SkillsRouter;
import org.gms.remote.gms083.server.routers.StatsRouter;

public class Gms083Routers {
    public BasicRouter basic;
    public CooldownRouter cooldown;
    public MapRouter map;
    public InventoryRouter inventory;
    public PetRouter pet;
    public SkillsRouter skills;
    public StatsRouter stats;

    public Gms083Routers(Gms083 client) {
        basic = new BasicRouter(client);
        cooldown = new CooldownRouter(client);
        map = new MapRouter(client);
        inventory = new InventoryRouter(client);
        pet = new PetRouter(client);
        skills = new SkillsRouter(client);
        stats = new StatsRouter(client);
    }
}
