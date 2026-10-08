package com.xy2407.kingdom.common.npc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xy2407.kingdom.common.persistence.NpcSchema;

/**
 * 市民数据POJO，dirty标记驱动增量落库
 */
public final class NpcData {
    private static final Gson GSON = new Gson();
    public static final double HUNGER_MAX = 20.0;

    private final UUID id;
    private UUID entityUuid;
    private String name = "";
    private String nation = "";
    private String gender = "";
    private UUID familyId;
    private UUID fatherId;
    private UUID motherId;
    private UUID spouseId;
    private String job = "";
    private String workplaceId;
    private String homeId;
    private int age;
    private String lifecycle = "BORN";
    private List<String> hobbies = new ArrayList<>();
    private List<String> likedItems = new ArrayList<>();
    private String personality = "";
    private String inventoryJson = "";
    private String skin = "";
    private int strength;
    private int intelligence;
    private int agility;
    private double health = 100.0;
    private double mood = 100.0;
    private double hunger = 20.0;
    private double stamina = 100.0;
    private long wealth;
    private boolean dead;
    private int birthDay;
    private int deathDay;
    private long updatedAt;
    private boolean dirty;

    private String dimension = "";
    private double x;
    private double y;
    private double z;
    private boolean positionDirty;
    private int homeX;
    private int homeY;
    private int homeZ;
    private int workX;
    private int workY;
    private int workZ;
    private NpcBrain brain;
    private boolean restoreDone;
    private NpcState state = NpcState.DAY;
    private NpcSubState subState = NpcSubState.RESTING;
    private NpcDetail detail = NpcDetail.WANDERING;
    private NpcShift shift = NpcShift.DAY_SHIFT;
    private long dayTime;

    public NpcData(UUID id) {
        this.id = id;
    }

    NpcData(UUID id, UUID entityUuid, String name, String nation, String gender, UUID familyId, UUID fatherId,
            UUID motherId, UUID spouseId, String job, String workplaceId, String homeId, int age, String lifecycle,
            String hobbiesJson, String likedItemsJson, String personality, int strength, int intelligence,
            int agility, double health, double mood, double hunger, double stamina, long wealth, boolean dead,
            int birthDay, int deathDay, long updatedAt, String dimension, double x, double y, double z,
            int homeX, int homeY, int homeZ, int workX, int workY, int workZ) {
        this.id = id;
        this.entityUuid = entityUuid;
        this.name = name;
        this.nation = nation;
        this.gender = gender;
        this.familyId = familyId;
        this.fatherId = fatherId;
        this.motherId = motherId;
        this.spouseId = spouseId;
        this.job = job;
        this.workplaceId = workplaceId;
        this.homeId = homeId;
        this.age = age;
        this.lifecycle = lifecycle;
        this.hobbies = parseList(hobbiesJson);
        this.likedItems = parseList(likedItemsJson);
        this.personality = personality;
        this.strength = strength;
        this.intelligence = intelligence;
        this.agility = agility;
        this.health = health;
        this.mood = mood;
        this.hunger = hunger;
        this.stamina = stamina;
        this.wealth = wealth;
        this.dead = dead;
        this.birthDay = birthDay;
        this.deathDay = deathDay;
        this.updatedAt = updatedAt;
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.homeX = homeX;
        this.homeY = homeY;
        this.homeZ = homeZ;
        this.workX = workX;
        this.workY = workY;
        this.workZ = workZ;
    }

    private static List<String> parseList(String json) {
        List<String> list = GSON.fromJson(json, new TypeToken<List<String>>() {
        }.getType());
        return list != null ? list : new ArrayList<>();
    }

    public UUID getId() {
        return id;
    }

    public UUID getEntityUuid() {
        return entityUuid;
    }

    public void setEntityUuid(UUID entityUuid) {
        this.entityUuid = entityUuid;
        markDirty();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        markDirty();
    }

    public String getNation() {
        return nation;
    }

    public void setNation(String nation) {
        this.nation = nation;
        markDirty();
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
        markDirty();
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public void setFamilyId(UUID familyId) {
        this.familyId = familyId;
        markDirty();
    }

    public UUID getFatherId() {
        return fatherId;
    }

    public void setFatherId(UUID fatherId) {
        this.fatherId = fatherId;
        markDirty();
    }

    public UUID getMotherId() {
        return motherId;
    }

    public void setMotherId(UUID motherId) {
        this.motherId = motherId;
        markDirty();
    }

    public UUID getSpouseId() {
        return spouseId;
    }

    public void setSpouseId(UUID spouseId) {
        this.spouseId = spouseId;
        markDirty();
    }

    public String getJob() {
        return job;
    }

    public void setJob(String job) {
        this.job = job;
        markDirty();
    }

    public String getWorkplaceId() {
        return workplaceId;
    }

    public void setWorkplaceId(String workplaceId) {
        this.workplaceId = workplaceId;
        markDirty();
    }

    public String getHomeId() {
        return homeId;
    }

    public void setHomeId(String homeId) {
        this.homeId = homeId;
        markDirty();
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
        markDirty();
    }

    public String getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(String lifecycle) {
        this.lifecycle = lifecycle;
        markDirty();
    }

    public List<String> getHobbies() {
        return hobbies;
    }

    public void setHobbies(List<String> hobbies) {
        this.hobbies = hobbies != null ? hobbies : new ArrayList<>();
        markDirty();
    }

    public List<String> getLikedItems() {
        return likedItems;
    }

    public void setLikedItems(List<String> likedItems) {
        this.likedItems = likedItems != null ? likedItems : new ArrayList<>();
        markDirty();
    }

    public String getInventoryJson() {
        return inventoryJson == null ? "" : inventoryJson;
    }

    public void setInventoryJson(String inventoryJson) {
        this.inventoryJson = inventoryJson == null ? "" : inventoryJson;
    }

    public String getSkin() {
        return skin == null ? "" : skin;
    }

    public void setSkin(String skin) {
        this.skin = skin == null ? "" : skin;
        markDirty();
    }

    public String getPersonality() {
        return personality;
    }

    public void setPersonality(String personality) {
        this.personality = personality;
        markDirty();
    }

    public int getStrength() {
        return strength;
    }

    public void setStrength(int strength) {
        this.strength = strength;
        markDirty();
    }

    public int getIntelligence() {
        return intelligence;
    }

    public void setIntelligence(int intelligence) {
        this.intelligence = intelligence;
        markDirty();
    }

    public int getAgility() {
        return agility;
    }

    public void setAgility(int agility) {
        this.agility = agility;
        markDirty();
    }

    public double getHealth() {
        return health;
    }

    public void setHealth(double health) {
        this.health = health;
        markDirty();
    }

    public double getMood() {
        return mood;
    }

    public void setMood(double mood) {
        this.mood = mood;
        markDirty();
    }

    public double getHunger() {
        return hunger;
    }

    public void setHunger(double hunger) {
        this.hunger = Math.max(0.0, Math.min(HUNGER_MAX, hunger));
        markDirty();
    }

    public double getStamina() {
        return stamina;
    }

    public void setStamina(double stamina) {
        this.stamina = stamina;
        markDirty();
    }

    public long getWealth() {
        return wealth;
    }

    public void setWealth(long wealth) {
        this.wealth = wealth;
        markDirty();
    }

    public boolean isDead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
        markDirty();
    }

    public int getBirthDay() {
        return birthDay;
    }

    public void setBirthDay(int birthDay) {
        this.birthDay = birthDay;
        markDirty();
    }

    public int getDeathDay() {
        return deathDay;
    }

    public void setDeathDay(int deathDay) {
        this.deathDay = deathDay;
        markDirty();
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        String value = dimension != null ? dimension : "";
        if (!value.equals(this.dimension)) {
            this.dimension = value;
            markDirty();
        }
    }

    public void setPosition(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        positionDirty = true;
    }

    public int getHomeX() {
        return homeX;
    }

    public void setHomeX(int homeX) {
        this.homeX = homeX;
        markDirty();
    }

    public int getHomeY() {
        return homeY;
    }

    public void setHomeY(int homeY) {
        this.homeY = homeY;
        markDirty();
    }

    public int getHomeZ() {
        return homeZ;
    }

    public void setHomeZ(int homeZ) {
        this.homeZ = homeZ;
        markDirty();
    }

    public int getWorkX() {
        return workX;
    }

    public void setWorkX(int workX) {
        this.workX = workX;
        markDirty();
    }

    public int getWorkY() {
        return workY;
    }

    public void setWorkY(int workY) {
        this.workY = workY;
        markDirty();
    }

    public int getWorkZ() {
        return workZ;
    }

    public void setWorkZ(int workZ) {
        this.workZ = workZ;
        markDirty();
    }

    public boolean isDirty() {
        return dirty;
    }

    public boolean isPositionDirty() {
        return positionDirty;
    }

    public void markDirty() {
        dirty = true;
        updatedAt = System.currentTimeMillis();
    }

    public void clearDirty() {
        dirty = false;
        positionDirty = false;
    }

    public NpcBrain getBrain() {
        if (brain == null) {
            brain = new NpcBrain(this);
        }
        return brain;
    }

    public boolean isRestoreDone() {
        return restoreDone;
    }

    public void markRestoreDone() {
        restoreDone = true;
    }

    public NpcState getState() {
        return state;
    }

    public void setState(NpcState state) {
        this.state = state;
        markDirty();
    }

    public NpcSubState getSubState() {
        return subState;
    }

    public void setSubState(NpcSubState subState) {
        this.subState = subState;
        markDirty();
    }

    public NpcDetail getDetail() {
        return detail;
    }

    public void setDetail(NpcDetail detail) {
        this.detail = detail;
        markDirty();
    }

    public NpcShift getShift() {
        return shift;
    }

    public void setShift(NpcShift shift) {
        this.shift = shift;
        markDirty();
    }

    public long getDayTime() {
        return dayTime;
    }

    public void setDayTime(long dayTime) {
        this.dayTime = dayTime;
    }

    public Map<String, Object> toValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(NpcSchema.COL_UUID, id.toString());
        values.put(NpcSchema.COL_ENTITY_UUID, entityUuid != null ? entityUuid.toString() : null);
        values.put(NpcSchema.COL_NAME, name);
        values.put(NpcSchema.COL_NATION, nation);
        values.put(NpcSchema.COL_GENDER, gender);
        values.put(NpcSchema.COL_FAMILY_ID, familyId != null ? familyId.toString() : null);
        values.put(NpcSchema.COL_FATHER_ID, fatherId != null ? fatherId.toString() : null);
        values.put(NpcSchema.COL_MOTHER_ID, motherId != null ? motherId.toString() : null);
        values.put(NpcSchema.COL_SPOUSE_ID, spouseId != null ? spouseId.toString() : null);
        values.put(NpcSchema.COL_JOB, job);
        values.put(NpcSchema.COL_WORKPLACE_ID, workplaceId);
        values.put(NpcSchema.COL_HOME_ID, homeId);
        values.put(NpcSchema.COL_AGE, age);
        values.put(NpcSchema.COL_LIFECYCLE, lifecycle);
        values.put(NpcSchema.COL_HOBBIES, GSON.toJson(hobbies));
        values.put(NpcSchema.COL_LIKED_ITEMS, GSON.toJson(likedItems));
        values.put(NpcSchema.COL_PERSONALITY, personality);
        values.put(NpcSchema.COL_INVENTORY, getInventoryJson());
        values.put(NpcSchema.COL_SKIN, getSkin());
        values.put(NpcSchema.COL_STRENGTH, strength);
        values.put(NpcSchema.COL_INTELLIGENCE, intelligence);
        values.put(NpcSchema.COL_AGILITY, agility);
        values.put(NpcSchema.COL_HEALTH, health);
        values.put(NpcSchema.COL_MOOD, mood);
        values.put(NpcSchema.COL_HUNGER, hunger);
        values.put(NpcSchema.COL_STAMINA, stamina);
        values.put(NpcSchema.COL_WEALTH, wealth);
        values.put(NpcSchema.COL_DEAD, dead ? 1 : 0);
        values.put(NpcSchema.COL_BIRTH_DAY, birthDay);
        values.put(NpcSchema.COL_DEATH_DAY, deathDay);
        values.put(NpcSchema.COL_UPDATED_AT, updatedAt);
        values.put(NpcSchema.COL_DIMENSION, dimension);
        values.put(NpcSchema.COL_POS_X, x);
        values.put(NpcSchema.COL_POS_Y, y);
        values.put(NpcSchema.COL_POS_Z, z);
        values.put(NpcSchema.COL_HOME_X, homeX);
        values.put(NpcSchema.COL_HOME_Y, homeY);
        values.put(NpcSchema.COL_HOME_Z, homeZ);
        values.put(NpcSchema.COL_WORK_X, workX);
        values.put(NpcSchema.COL_WORK_Y, workY);
        values.put(NpcSchema.COL_WORK_Z, workZ);
        values.put(NpcSchema.COL_STATE, state.name());
        values.put(NpcSchema.COL_SUB_STATE, subState.name());
        values.put(NpcSchema.COL_DETAIL, detail.name());
        values.put(NpcSchema.COL_SHIFT, shift.name());
        return values;
    }
}
