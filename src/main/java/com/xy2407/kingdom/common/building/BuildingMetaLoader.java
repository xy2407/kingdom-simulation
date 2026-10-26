package com.xy2407.kingdom.common.building;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xy2407.kingdom.ModMain;

/**
 * 建筑元数据加载：优先读与 nbt 同名的 json，缺失或字段缺省时逐项兜底，读坏也只回退不炸。
 */
public final class BuildingMetaLoader {

    private static final String JSON_EXTENSION = ".json";

    private static final java.util.Map<String, BuildingMeta> BY_BUILD_NAME =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.concurrent.atomic.AtomicBoolean scanned =
            new java.util.concurrent.atomic.AtomicBoolean();

    private BuildingMetaLoader() {
    }

    public static BuildingMeta load(Path nbtFile, String id) {
        Path jsonFile = jsonOf(nbtFile);
        if (!Files.isRegularFile(jsonFile)) {
            return BuildingMeta.fallback(id, null);
        }
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                ModMain.LOGGER.warn("建筑定义不是 JSON 对象，已忽略: {}", jsonFile);
                return BuildingMeta.fallback(id, null);
            }
            root = parsed.getAsJsonObject();
        } catch (IOException | RuntimeException exception) {
            ModMain.LOGGER.warn("读取建筑定义失败，已忽略: {}", jsonFile, exception);
            return BuildingMeta.fallback(id, null);
        }

        BuildingWork work = BuildingWork.parse(string(root, "work", null));
        BuildingType type = BuildingType.parse(string(root, "type", null));
        if (type == null && work != null) {
            type = work.type();
        }
        if (type == null) {
            ModMain.LOGGER.warn("建筑定义缺少 type 字段，已忽略: {}", jsonFile);
            return BuildingMeta.fallback(id, null);
        }
        if (work != null && work.type() != type) {
            ModMain.LOGGER.warn("建筑定义 {} 的 work={} 与 type={} 不符，按类型处理",
                    jsonFile.getFileName(), work, type);
            work = null;
        }
        if (work == null) {
            ModMain.LOGGER.warn("建筑定义 {} 缺少 work 字段", jsonFile.getFileName());
        }
        String name = string(root, "name", "");
        if (name == null || name.isBlank()) {
            name = BuildingMeta.shortName(id);
        }
        String buildName = string(root, "buildname", id);
        BuildingMeta shared = buildName == null ? null : BY_BUILD_NAME.get(buildName);
        if (shared != null) {
            return shared;
        }
        BuildingMeta meta = new BuildingMeta(
                buildName,
                buildName,
                string(root, "file", buildName),
                name,
                type,
                work,
                stringList(root, "tags"),
                integer(root, "level", 1),
                integer(root, "jobs", 0),
                hire(root),
                Math.max(0, integer(root, "farm_slots", 0)),
                Math.max(0, integer(root, "storage_slots", 0)),
                crops(root),
                animals(root),
                recipes(root),
                menu(root),
                troops(root),
                Math.max(0, integer(root, "patrol_radius", 0)),
                string(root, "nation", "")
        );
        BY_BUILD_NAME.putIfAbsent(meta.buildName(), meta);
        return meta;
    }

    public static BuildingMeta byBuildName(String buildName) {
        if (buildName == null || buildName.isBlank()) {
            return null;
        }
        BuildingMeta cached = BY_BUILD_NAME.get(buildName);
        if (cached != null) {
            return cached;
        }
        scan();
        return BY_BUILD_NAME.get(buildName);
    }

    public static java.util.List<String> allBuildNames() {
        scan();
        java.util.Set<String> names = new java.util.TreeSet<>(BY_BUILD_NAME.keySet());
        names.addAll(BuildingBlueprints.listFiles());
        return new java.util.ArrayList<>(names);
    }

    public static Path blueprintFile(String buildName) {
        BuildingMeta meta = byBuildName(buildName);
        return BuildingBlueprints.fileOf(meta == null ? buildName : meta.file());
    }

    public static void scan() {
        if (!scanned.compareAndSet(false, true)) {
            return;
        }
        Path dir = BuildingBlueprints.structuresDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.walk(dir, 3)) {
            for (Path jsonFile : files.filter(BuildingMetaLoader::isJsonFile).toList()) {
                String id = BuildingBlueprints.relativeId(dir, jsonFile);
                if (id.toLowerCase().endsWith(JSON_EXTENSION)) {
                    id = id.substring(0, id.length() - JSON_EXTENSION.length());
                }
                if (!hasBlueprint(id, jsonFile)) {
                    continue;
                }
                load(BuildingBlueprints.fileOf(id), id);
            }
        } catch (IOException exception) {
            ModMain.LOGGER.warn("扫描建筑定义失败: {}", dir, exception);
        }
    }

    public static void clearIndex() {
        BY_BUILD_NAME.clear();
        scanned.set(false);
    }

    private static boolean hasBlueprint(String id, Path jsonFile) {
        try (Reader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return false;
            }
            JsonObject root = parsed.getAsJsonObject();
            String file = string(root, "file", null);
            String build = string(root, "buildname", null);
            String target = file != null ? file : (build != null ? build : id);
            return Files.isRegularFile(BuildingBlueprints.fileOf(target));
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isJsonFile(Path file) {
        return file.getFileName().toString().toLowerCase().endsWith(JSON_EXTENSION);
    }

    private static List<Integer> hire(JsonObject root) {
        JsonElement element = root.get("hire");
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    result.add(item.getAsInt());
                }
            }
            return result;
        }
        if (element.isJsonPrimitive()) {
            result.add(element.getAsInt());
        }
        return result;
    }

    private static List<CropSpec> crops(JsonObject root) {
        JsonElement element = root.get("crops");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<CropSpec> specs = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null) {
                continue;
            }
            if (item.isJsonPrimitive()) {
                String id = item.getAsString();
                if (id != null && !id.isBlank()) {
                    specs.add(new CropSpec(id.trim(), ""));
                }
                continue;
            }
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String id = string(object, "item", "");
            if (id.isBlank()) {
                continue;
            }
            specs.add(new CropSpec(id, string(object, "name", "")));
        }
        return specs;
    }

    private static List<AnimalSpec> animals(JsonObject root) {
        JsonElement element = root.get("animals");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<AnimalSpec> specs = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String entity = string(object, "entity", "");
            if (entity.isBlank()) {
                continue;
            }
            String id = string(object, "id", "");
            if (id.isBlank()) {
                id = entity;
            }
            specs.add(new AnimalSpec(
                    id,
                    string(object, "name", id),
                    entity,
                    Math.max(0, integer(object, "capacity", 0)),
                    string(object, "feed", ""),
                    string(object, "mode", "collect"),
                    string(object, "output", "")
            ));
        }
        return specs;
    }

    private static List<RecipeSpec> recipes(JsonObject root) {
        JsonElement element = root.get("recipes");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<RecipeSpec> specs = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String id = string(object, "id", "");
            List<ItemCount> outputs = stacks(object, "outputs");
            if (id.isBlank() || outputs.isEmpty()) {
                continue;
            }
            specs.add(new RecipeSpec(
                    id,
                    string(object, "name", id),
                    stacks(object, "inputs"),
                    outputs,
                    Math.max(1, integer(object, "ticks", 200))
            ));
        }
        return specs;
    }

    private static List<ItemCount> stacks(JsonObject root, String key) {
        JsonElement element = root.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<ItemCount> specs = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String id = string(object, "item", "");
            if (id.isBlank()) {
                continue;
            }
            specs.add(new ItemCount(id, Math.max(1, integer(object, "count", 1))));
        }
        return specs;
    }

    private static List<MenuSpec> menu(JsonObject root) {
        JsonElement element = root.get("menu");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<MenuSpec> specs = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String id = string(object, "id", "");
            List<ItemCount> outputs = stacks(object, "outputs");
            if (id.isBlank() || outputs.isEmpty()) {
                continue;
            }
            specs.add(new MenuSpec(
                    id,
                    string(object, "name", id),
                    stacks(object, "inputs"),
                    outputs.get(0),
                    Math.max(0, integer(object, "price", 0))
            ));
        }
        return specs;
    }

    private static List<TroopSpec> troops(JsonObject root) {
        JsonElement element = root.get("troops");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<TroopSpec> specs = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String id = string(object, "id", "");
            if (id.isBlank()) {
                continue;
            }
            specs.add(new TroopSpec(
                    id,
                    string(object, "name", id),
                    Math.max(0, integer(object, "capacity", 0)),
                    string(object, "weapon", ""),
                    string(object, "armor", ""),
                    Math.max(0, integer(object, "wage", 0)),
                    bool(object, "ranged", false)
            ));
        }
        return specs;
    }

    private static Path jsonOf(Path nbtFile) {
        String fileName = nbtFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot < 0 ? fileName : fileName.substring(0, dot);
        Path parent = nbtFile.getParent();
        return parent == null ? Path.of(base + JSON_EXTENSION) : parent.resolve(base + JSON_EXTENSION);
    }

    private static String string(JsonObject object, String key, String defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException exception) {
            return defaultValue;
        }
    }

    private static int integer(JsonObject object, String key, int defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException exception) {
            return defaultValue;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean defaultValue) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }
        try {
            return element.getAsBoolean();
        } catch (RuntimeException exception) {
            return defaultValue;
        }
    }

    private static List<String> stringList(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        JsonArray array = element.getAsJsonArray();
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item != null && item.isJsonPrimitive()) {
                String value = item.getAsString();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        }
        return values;
    }
}
