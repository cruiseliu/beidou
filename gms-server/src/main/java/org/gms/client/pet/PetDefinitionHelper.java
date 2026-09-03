package org.gms.client.pet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;
import org.gms.util.Pair;

public class PetDefinitionHelper {
    private static final DataProvider itemWzFiles = DataProviderFactory.getDataProvider(WZFiles.ITEM);

    private static final Map<Integer, Data> petWzs = new ConcurrentHashMap<>();

    private static Data getPetWz(int petItemId) {
        Data data = petWzs.getOrDefault(petItemId, null);
        if (data == null) {
            data = itemWzFiles.getData("Pet/" + petItemId + ".img");
            petWzs.put(petItemId, data);
        }
        return data;
    }

    public static boolean canPetEatFood(int petItemId, int foodItemId) {
        return true;
    }

    private static int getHungrySpeed(int petItemId) {  // todo: cache this?
        return DataTool.getInt("info/hungry", getPetWz(petItemId), 1);
    }

    public static long getHungryInterval(int petItemId) {
        return Math.round(TimeUnit.MINUTES.toMillis(1) / getHungrySpeed(petItemId));
    }

    public static boolean isEgg(int petItemId) {
        // fixme: [refactor] not a good idea to inference this from hungry
        return getPetWz(petItemId).getChildByPath("info/hungry") == null;
    }

    public static boolean canEvolve(int petItemId) {
        return DataTool.getInt("info/evol", getPetWz(petItemId), 0) == 1;
    }

    /** Return pairs (evolveToPetItemId, weight) */
    public static List<Pair<Integer, Integer>> getEvolvePool(int petItemId) {
        Data wz = getPetWz(petItemId);
        List<Pair<Integer, Integer>> candidates = new ArrayList<>();
        int i = 0;
        while (true) {
            i += 1;
            int evolveItemId = DataTool.getInt("info/evol" + i, wz, -1);
            if (evolveItemId == -1) {
                break;
            }
            int prob = DataTool.getInt("info/evolProb" + i, wz);
            candidates.add(new Pair<>(evolveItemId, prob));
        }
        return candidates;
    }
}
