package org.gms.client.character;

import org.gms.client.JobEnum;
import org.gms.client.SkinColor;
import org.gms.client.inventory.ItemSlot;
import org.gms.model.json.CharacterData;
import org.gms.model.json.CharacterSpData;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * charlist 视图条目（{@link CharacterView} 的快照实现，仿 PetData 的按实体拆分）：
 * 不可变标量集 + 外观装备副本，不含组件图。两个生产入口（与 CharacterView javadoc 对应）：
 * <ul>
 *   <li>{@link #fromDb}：DB 装载档（charlist 预览 / 账号视图），只解析视图事实所需的列与
 *       character_json 域——auth 期装载不再构造全量 Character（组件 restore 的 strand 风险源头）；</li>
 *   <li>{@link #ofLive}：活角色快照（建角/删角/改名/转 world 后的视图登记）。</li>
 * </ul>
 * 派生规则与组件实现逐条对齐：clientMax = min(30000, base)、客户端可见 SP = 分桶规则、
 * isGM = gmLevel &gt; 1——漂移即 CHARLIST 字节漂移，改动需同步组件侧。
 */
public final class CharacterViewEntry implements CharacterView {
    // FIXME: [refactor] does not belong to player strand

    private final int accountId;
    private final int id;
    private final String name;
    private final int gender;
    private final SkinColor skin;
    private final int face;
    private final int hair;
    private final List<ItemSlot> equipped;
    private final boolean equippedChecked;
    private final int level;
    private final JobEnum job;
    private final int str;
    private final int dex;
    private final int int_;
    private final int luk;
    private final int hp;
    private final int mp;
    private final int maxHp;
    private final int maxMp;
    private final int remainingAp;
    private final int remainingSp;
    private final int[] spBuckets;
    private final int exp;
    private final int fame;
    private final int gachaExp;
    private final int mapId;
    private final int initialSpawnPoint;
    private final int gmLevel;
    private final int world;
    private final int rank;
    private final int rankMove;
    private final int jobRank;
    private final int jobRankMove;

    private CharacterViewEntry(int accountId, int id, String name, int gender, SkinColor skin,
                               int face, int hair, Collection<ItemSlot> equipped, boolean equippedChecked,
                               int level, JobEnum job, int str, int dex, int int_, int luk,
                               int hp, int mp, int maxHp, int maxMp, int remainingAp,
                               int remainingSp, int[] spBuckets, int exp, int fame, int gachaExp,
                               int mapId, int initialSpawnPoint, int gmLevel, int world,
                               int rank, int rankMove, int jobRank, int jobRankMove) {
        this.accountId = accountId;
        this.id = id;
        this.name = name;
        this.gender = gender;
        this.skin = skin;
        this.face = face;
        this.hair = hair;
        this.equipped = List.copyOf(equipped);
        this.equippedChecked = equippedChecked;
        this.level = level;
        this.job = job;
        this.str = str;
        this.dex = dex;
        this.int_ = int_;
        this.luk = luk;
        this.hp = hp;
        this.mp = mp;
        this.maxHp = maxHp;
        this.maxMp = maxMp;
        this.remainingAp = remainingAp;
        this.remainingSp = remainingSp;
        this.spBuckets = spBuckets;
        this.exp = exp;
        this.fame = fame;
        this.gachaExp = gachaExp;
        this.mapId = mapId;
        this.initialSpawnPoint = initialSpawnPoint;
        this.gmLevel = gmLevel;
        this.world = world;
        this.rank = rank;
        this.rankMove = rankMove;
        this.jobRank = jobRank;
        this.jobRankMove = jobRankMove;
    }

    /** DB 装载档（charlist 预览 / 账号视图；equipped = inventory 表预取的 EQUIPPED 槽条目）。 */
    public static CharacterViewEntry fromDb(ResultSet rs, List<ItemSlot> equipped) throws SQLException {
        CharacterData charData = CharacterData.deserialize(rs.getString("stats_json"));
        CharacterStatsDataView stats = CharacterStatsDataView.of(charData);
        int gmLevel = rs.getInt("gm");
        return new CharacterViewEntry(
                rs.getInt("accountid"),
                rs.getInt("id"),
                rs.getString("name"),
                rs.getInt("gender"),
                SkinColor.getById(rs.getInt("skincolor")),
                rs.getInt("face"),
                rs.getInt("hair"),
                equipped != null ? equipped : List.of(),
                false,   // DB 装载档未做穿戴校验
                rs.getInt("level"),
                JobEnum.getById(charData.jobId),
                stats.str(), stats.dex(), stats.int_(), stats.luk(),
                stats.hp(), stats.mp(), stats.maxHp(), stats.maxMp(),
                stats.remainingAp(),
                stats.remainingSp(),
                stats.spBuckets(),
                rs.getInt("exp"),
                rs.getInt("fame"),
                rs.getInt("gachaexp"),
                charData.mapId,
                rs.getInt("spawnpoint"),
                gmLevel,
                rs.getByte("world"),
                rs.getInt("rank"),
                rs.getInt("rankMove"),
                rs.getInt("jobRank"),
                rs.getInt("jobRankMove"));
    }

    /** 活角色快照（建角/删角/改名/转 world 后的视图登记）。 */
    public static CharacterViewEntry ofLive(Character chr) {
        return new CharacterViewEntry(
                chr.getAccountId(),
                chr.getId(),
                chr.getName(),
                chr.getGender(),
                chr.getSkinColor(),
                chr.getFace(),
                chr.getHair(),
                chr.getEquippedItems(),
                chr.isEquippedChecked(),
                chr.getLevel(),
                chr.getJob(),
                chr.getStr(),
                chr.getDex(),
                chr.getInt(),
                chr.getLuk(),
                chr.getHp(),
                chr.getMp(),
                chr.getClientMaxHp(),
                chr.getClientMaxMp(),
                chr.getRemainingAp(),
                chr.getRemainingSp(),
                chr.getRemainingSps(),
                chr.LEGACY_getExp(),
                chr.getFame(),
                chr.getGachaExp(),
                chr.getMapId(),
                chr.getInitialSpawnPoint(),
                chr.gmLevel(),
                chr.getWorld(),
                chr.getRank(),
                chr.getRankMove(),
                chr.getJobRank(),
                chr.getJobRankMove());
    }

    // ── CharacterView ──

    @Override
    public int getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getAccountId() {
        return accountId;
    }

    @Override
    public int getWorld() {
        return world;
    }

    @Override
    public int getGender() {
        return gender;
    }

    @Override
    public SkinColor getSkinColor() {
        return skin;
    }

    @Override
    public int getFace() {
        return face;
    }

    @Override
    public int getHair() {
        return hair;
    }

    @Override
    public Collection<ItemSlot> getEquippedItems() {
        return equipped;
    }

    @Override
    public boolean isEquippedChecked() {
        return equippedChecked;
    }

    @Override
    public int getLevel() {
        return level;
    }

    @Override
    public JobEnum getJob() {
        return job;
    }

    @Override
    public int getStr() {
        return str;
    }

    @Override
    public int getDex() {
        return dex;
    }

    @Override
    public int getInt() {
        return int_;
    }

    @Override
    public int getLuk() {
        return luk;
    }

    @Override
    public int getHp() {
        return hp;
    }

    @Override
    public int getClientMaxHp() {
        return Math.min(30000, maxHp);   // 与 CharacterStats.getClientMaxHp 同式
    }

    @Override
    public int getMp() {
        return mp;
    }

    @Override
    public int getClientMaxMp() {
        return Math.min(30000, maxMp);
    }

    @Override
    public int getRemainingAp() {
        return remainingAp;
    }

    @Override
    public int getRemainingSp() {
        return remainingSp;
    }

    @Override
    public int[] getRemainingSps() {
        return spBuckets;
    }

    @Override
    public int LEGACY_getExp() {
        return exp;
    }

    @Override
    public int getFame() {
        return fame;
    }

    @Override
    public int getGachaExp() {
        return gachaExp;
    }

    @Override
    public int getMapId() {
        return mapId;
    }

    @Override
    public int getInitialSpawnPoint() {
        return initialSpawnPoint;
    }

    @Override
    public long getPetId(int slot) {
        return 0;   // 视图条目装载跳过宠物
    }

    @Override
    public int getPetItemId(int slot) {
        return 0;
    }

    @Override
    public void markEquippedChecked() {
        // 不可变条目：无记忆化状态可标记（恒为未校验，DB 装载档语义）
    }

    @Override
    public boolean isGM() {
        return gmLevel > 1;   // 与 CharacterGm.isGM 同式
    }

    @Override
    public boolean isGmJob() {
        // 与 CharacterJob.isGmJob 同式：骑士团/GM 专属职业分位
        int jn = job.getJobNiche();
        return jn >= 8 && jn <= 9;
    }

    @Override
    public int gmLevel() {
        return gmLevel;
    }

    @Override
    public int getRank() {
        return rank;
    }

    @Override
    public int getRankMove() {
        return rankMove;
    }

    @Override
    public int getJobRank() {
        return jobRank;
    }

    @Override
    public int getJobRankMove() {
        return jobRankMove;
    }

    // ── 内部：character_json 标量域的视图抽取（避免为列表行 restore 组件图） ──

    private record CharacterStatsDataView(int str, int dex, int int_, int luk,
                                          int hp, int mp, int maxHp, int maxMp,
                                          int remainingAp, int remainingSp, int[] spBuckets) {

        static CharacterStatsDataView of(CharacterData charData) {
            CharacterStatsDataView v = null;
            if (charData.stats != null) {
                v = new CharacterStatsDataView(charData.stats.str, charData.stats.dex, charData.stats.int_,
                        charData.stats.luk, charData.stats.hp, charData.stats.mp,
                        charData.stats.maxHp, charData.stats.maxMp,
                        charData.ap != null ? charData.ap.remainingAp : 0,
                        clientVisibleSp(charData), spBuckets(charData));
            }
            if (v == null) {
                v = new CharacterStatsDataView(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, new int[0]);
            }
            return v;
        }

        /** 与 CharacterSp.getClientVisibleSp 同式：当前职业新手桶原值，否则非新手桶求和。 */
        private static int clientVisibleSp(CharacterData charData) {
            CharacterSpData sp = charData.sp;
            if (sp == null || sp.remainingSp == null) {
                return 0;
            }
            int cur = charData.jobId;
            JobEnum job = JobEnum.getById(cur);
            if (job != null && job.isBeginnerJob()) {
                return sp.remainingSp.getOrDefault(cur, 0);
            }
            int sum = 0;
            for (Map.Entry<Integer, Integer> e : sp.remainingSp.entrySet()) {
                JobEnum bucketJob = JobEnum.getById(e.getKey());
                if (bucketJob == null || !bucketJob.isBeginnerJob()) {
                    sum += e.getValue();
                }
            }
            return sum;
        }

        /** 与 CharacterSp.getSpBuckets 同式：桶按 jobId 升序取值。 */
        private static int[] spBuckets(CharacterData charData) {
            CharacterSpData sp = charData.sp;
            if (sp == null || sp.remainingSp == null) {
                return new int[0];
            }
            Map<Integer, Integer> sorted = new TreeMap<>(sp.remainingSp);
            int[] arr = new int[sorted.size()];
            int i = 0;
            for (int v : sorted.values()) {
                arr[i++] = v;
            }
            return arr;
        }
    }
}
