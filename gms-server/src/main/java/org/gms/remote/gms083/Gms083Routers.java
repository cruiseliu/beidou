package org.gms.remote.gms083;

import org.gms.remote.gms083.server.routers.BasicRouter;
import org.gms.remote.gms083.server.routers.InventoryRouter;
import org.gms.remote.gms083.server.routers.MapRouter;
import org.gms.remote.gms083.server.routers.NpcRouter;
import org.gms.remote.gms083.server.routers.PetRouter;
import org.gms.remote.gms083.server.routers.SkillsRouter;
import org.gms.remote.gms083.server.routers.StatsRouter;

public class Gms083Routers {
    public BasicRouter basic;
    public MapRouter map;
    public NpcRouter npc;
    public InventoryRouter inventory;
    public PetRouter pet;
    public SkillsRouter skills;
    public StatsRouter stats;

    public Gms083Routers(Gms083 client) {
        basic = new BasicRouter(client);
        map = new MapRouter(client);
        npc = new NpcRouter(client);
        inventory = new InventoryRouter(client);
        pet = new PetRouter(client);
        skills = new SkillsRouter(client);
        stats = new StatsRouter(client);
    }
}
