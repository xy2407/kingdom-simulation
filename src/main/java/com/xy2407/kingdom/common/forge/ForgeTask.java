package com.xy2407.kingdom.common.forge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xy2407.kingdom.common.building.ItemCount;

/**
 * 锻造任务：记录委托目标、展开后的步骤、本任务自己的材料收支账本与工人分工
 */
public final class ForgeTask {

    public enum State {
        PREPARING,
        RUNNING,
        DONE
    }

    public static final String ROLE_SMELT = "SMELT";

    public static final String ROLE_CRAFT = "CRAFT";

    private final UUID buildingId;
    private final String dimension;
    private final String target;
    private final int requested;
    private final long createdDay;
    private final ForgePlan plan;
    private final Map<String, Integer> progress = new LinkedHashMap<>();
    private final Map<String, Integer> intake = new LinkedHashMap<>();
    private final Map<String, Integer> produced = new LinkedHashMap<>();
    private final Map<String, Integer> consumed = new LinkedHashMap<>();
    private final Map<UUID, String> roles = new LinkedHashMap<>();
    private State state;
    private long nextSupplyAt;

    public ForgeTask(UUID buildingId, String dimension, String target, int requested,
                     ForgePlan plan, long createdDay) {
        this.buildingId = buildingId;
        this.dimension = dimension == null ? "" : dimension;
        this.target = target == null ? "" : target;
        this.requested = Math.max(1, requested);
        this.createdDay = createdDay;
        this.plan = plan;
        this.state = State.PREPARING;
    }

    public UUID buildingId() {
        return buildingId;
    }

    public String dimension() {
        return dimension;
    }

    public String target() {
        return target;
    }

    public int requested() {
        return requested;
    }

    public long createdDay() {
        return createdDay;
    }

    public ForgePlan plan() {
        return plan;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state == null ? State.PREPARING : state;
    }

    public long nextSupplyAt() {
        return nextSupplyAt;
    }

    public void setNextSupplyAt(long nextSupplyAt) {
        this.nextSupplyAt = nextSupplyAt;
    }

    public int doneOf(String stepKey) {
        return progress.getOrDefault(stepKey, 0);
    }

    public void addProgress(String stepKey, int delta) {
        if (stepKey == null || delta == 0) {
            return;
        }
        progress.put(stepKey, Math.max(0, doneOf(stepKey) + delta));
    }

    public boolean finished() {
        if (plan.steps().isEmpty()) {
            return true;
        }
        for (ForgeStep step : plan.steps()) {
            if (doneOf(step.key()) < step.runs()) {
                return false;
            }
        }
        return targetOutput() <= 0 || producedOf(target) >= targetOutput();
    }

    public int remainingOf(ForgeStep step) {
        return Math.max(0, step.runs() - doneOf(step.key()));
    }

    public int targetOutput() {
        int total = 0;
        for (ForgeStep step : plan.steps()) {
            if (step.output().equals(target)) {
                total += step.outputCount() * step.runs();
            }
        }
        return total;
    }

    public void credit(String item, int count) {
        if (item == null || item.isBlank() || count <= 0) {
            return;
        }
        intake.merge(item, count, Integer::sum);
    }

    public void creditAll(Map<String, Integer> stock) {
        if (stock == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : stock.entrySet()) {
            credit(entry.getKey(), entry.getValue() == null ? 0 : entry.getValue());
        }
    }

    public void noteProduce(String item, int count) {
        if (item == null || item.isBlank() || count <= 0) {
            return;
        }
        produced.merge(item, count, Integer::sum);
    }

    public void noteConsume(String item, int count) {
        if (item == null || item.isBlank() || count <= 0) {
            return;
        }
        consumed.merge(item, count, Integer::sum);
    }

    public int producedOf(String item) {
        return item == null ? 0 : produced.getOrDefault(item, 0);
    }

    public int intakeOf(String item) {
        return item == null ? 0 : intake.getOrDefault(item, 0);
    }

    public int ledgerOf(String item) {
        if (item == null) {
            return 0;
        }
        return intakeOf(item) + producedOf(item) - consumed.getOrDefault(item, 0);
    }

    public Map<UUID, String> roles() {
        return roles;
    }

    public String roleOf(UUID npcId) {
        return npcId == null ? null : roles.get(npcId);
    }

    public void setRole(UUID npcId, String role) {
        if (npcId == null || role == null) {
            return;
        }
        roles.put(npcId, role);
    }

    public String encodePlan() {
        JsonObject root = new JsonObject();
        root.addProperty("target", plan.target());
        root.addProperty("requested", plan.requested());
        JsonArray steps = new JsonArray();
        for (ForgeStep step : plan.steps()) {
            JsonObject node = new JsonObject();
            node.addProperty("key", step.key());
            node.addProperty("kind", step.kind().name());
            node.addProperty("recipe", step.recipeId());
            node.addProperty("output", step.output());
            node.addProperty("outputCount", step.outputCount());
            node.addProperty("ticks", step.ticks());
            node.addProperty("depth", step.depth());
            node.addProperty("runs", step.runs());
            JsonArray inputs = new JsonArray();
            for (ItemCount input : step.inputs()) {
                JsonObject item = new JsonObject();
                item.addProperty("item", input.item());
                item.addProperty("count", input.count());
                inputs.add(item);
            }
            node.add("inputs", inputs);
            steps.add(node);
        }
        root.add("steps", steps);
        JsonArray bases = new JsonArray();
        for (ItemCount base : plan.baseInputs()) {
            JsonObject item = new JsonObject();
            item.addProperty("item", base.item());
            item.addProperty("count", base.count());
            bases.add(item);
        }
        root.add("base", bases);
        return root.toString();
    }

    public String encodeProgress() {
        JsonObject root = new JsonObject();
        root.add("steps", mapToJson(progress));
        root.add("intake", mapToJson(intake));
        root.add("produced", mapToJson(produced));
        root.add("consumed", mapToJson(consumed));
        JsonObject roleNode = new JsonObject();
        for (Map.Entry<UUID, String> entry : roles.entrySet()) {
            roleNode.addProperty(entry.getKey().toString(), entry.getValue());
        }
        root.add("roles", roleNode);
        return root.toString();
    }

    public void loadProgress(String json) {
        progress.clear();
        intake.clear();
        produced.clear();
        consumed.clear();
        roles.clear();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (root.has("steps") && root.get("steps").isJsonObject()) {
                readMap(root.getAsJsonObject("steps"), progress);
                readMap(root.getAsJsonObject("intake"), intake);
                readMap(root.getAsJsonObject("produced"), produced);
                readMap(root.getAsJsonObject("consumed"), consumed);
                if (root.has("roles") && root.get("roles").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry
                            : root.getAsJsonObject("roles").entrySet()) {
                        try {
                            roles.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
                        } catch (RuntimeException ignored) {
                        }
                    }
                }
                return;
            }
            readMap(root, progress);
        } catch (RuntimeException ignored) {
            progress.clear();
        }
    }

    private static JsonObject mapToJson(Map<String, Integer> source) {
        JsonObject node = new JsonObject();
        for (Map.Entry<String, Integer> entry : source.entrySet()) {
            node.addProperty(entry.getKey(), entry.getValue());
        }
        return node;
    }

    private static void readMap(JsonObject node, Map<String, Integer> target) {
        if (node == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
            target.put(entry.getKey(), entry.getValue().getAsInt());
        }
    }

    public static ForgePlan decodePlan(String json) {
        if (json == null || json.isBlank()) {
            return new ForgePlan("", 0, List.of(), List.of());
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String target = root.has("target") ? root.get("target").getAsString() : "";
            int requested = root.has("requested") ? root.get("requested").getAsInt() : 0;
            List<ForgeStep> steps = new ArrayList<>();
            if (root.has("steps")) {
                for (JsonElement element : root.getAsJsonArray("steps")) {
                    steps.add(readStep(element.getAsJsonObject()));
                }
            }
            List<ItemCount> bases = new ArrayList<>();
            if (root.has("base")) {
                for (JsonElement element : root.getAsJsonArray("base")) {
                    JsonObject item = element.getAsJsonObject();
                    bases.add(new ItemCount(item.get("item").getAsString(),
                            item.get("count").getAsInt()));
                }
            }
            return new ForgePlan(target, requested, steps, bases);
        } catch (RuntimeException ignored) {
            return new ForgePlan("", 0, List.of(), List.of());
        }
    }

    private static ForgeStep readStep(JsonObject node) {
        List<ItemCount> inputs = new ArrayList<>();
        if (node.has("inputs")) {
            for (JsonElement element : node.getAsJsonArray("inputs")) {
                JsonObject item = element.getAsJsonObject();
                inputs.add(new ItemCount(item.get("item").getAsString(),
                        item.get("count").getAsInt()));
            }
        }
        ForgeStep.Kind kind = "SMELT".equals(node.get("kind").getAsString())
                ? ForgeStep.Kind.SMELT : ForgeStep.Kind.CRAFT;
        return new ForgeStep(node.get("key").getAsString(), kind,
                node.get("recipe").getAsString(), node.get("output").getAsString(),
                node.get("outputCount").getAsInt(), inputs, node.get("ticks").getAsInt(),
                node.get("depth").getAsInt(), node.get("runs").getAsInt());
    }
}
