package org.gms.client.pet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.gms.provider.Data;
import org.gms.provider.DataProvider;
import org.gms.provider.DataProviderFactory;
import org.gms.provider.DataTool;
import org.gms.provider.wz.WZFiles;

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
}
