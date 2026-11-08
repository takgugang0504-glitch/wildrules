package kr.wildrules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.Scoreboard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 야생 규칙 모드 v0.1 - 신호기 시스템 (서버 전용 로직, Fabric API 만 사용, 믹스인 없음)
 */
public class WildRulesMod implements ModInitializer {
    // ---- 설정값 ----
    static final int MAX_BEACONS = 3;            // 한 사람당 설치 가능 횟수
    static final double DETECT_RANGE = 100.0;       // 플레이어 발광/알림 범위
    static final double SPAWN_BLOCK_R = 200.0;      // 몬스터 생성 금지: 가로 반경
    static final double SPAWN_BLOCK_H = 100.0;      // 몬스터 생성 금지: 높이     // 감지 범위(칸)
    static final int MIN_STREAK = 2;              // 이 연속킬 이상이면 감지 대상
    static final double BUFF_RANGE = 50.0;        // 주인 버프 범위(칸)
    static final String STREAK_OBJECTIVE = "wr_streak"; // 데이터팩의 연속킬 점수판

    // ---- 저장 데이터 ----
    static class Rec {
        String dim; int x, y, z; String owner; String ownerName;
        String effect;
        int level = 1;
    }
    static class Data {
        Map<String, Integer> placed = new HashMap<>();
        List<Rec> beacons = new ArrayList<>();
        Map<String, Long> bans = new HashMap<>();
        boolean dragonKilled;
    }
    static class Pending { String dim; BlockPos pos; UUID uuid; String name; int age; }

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Data data = new Data();
    static Path file;
    static MinecraftServer server;
    static final List<Pending> pending = new ArrayList<>();
    static final Map<String, Set<UUID>> flagged = new HashMap<>();
    static final Map<UUID, Double> macePending = new HashMap<>();
    static final Map<UUID, Integer> crystalHits = new HashMap<>();
    static final Map<UUID, Long> crystalLast = new HashMap<>();
    static final Map<UUID, Integer> shieldPending = new HashMap<>();
    static final Map<UUID, Long> lastNotice = new HashMap<>();
    static final Set<UUID> banNotified = new HashSet<>();
    record Rm(String dim, BlockPos pos) { }
    record Orb(ServerLevel level, double x, double y, double z, int value) { }
    record ArrowTrack(Entity arrow, double x, double y, double z) { }
    record LastPos(String dim, double x, double y, double z) { }
    static class Boost { Entity e; double fly = -1, mov = -1; }
    static final Map<UUID, LastPos> lastPos = new HashMap<>();
    static final Set<UUID> returning = new HashSet<>();
    static final Map<UUID, Boost> ghastBoost = new HashMap<>();
    record Delayed(ServerLevel level, double x, double y, double z, int value, int due) { }
    static final List<ArrowTrack> arrowTracks = new ArrayList<>();
    static final List<Delayed> delayedOrbs = new ArrayList<>();
    static final List<Entity> dragonQueue = new ArrayList<>();
    static final Map<UUID, Boolean> zState = new HashMap<>();
    static final Map<UUID, Long> totemMsg = new HashMap<>();
    static final Map<UUID, Long> dragonReady = new HashMap<>();
    static final Map<UUID, Long> dragonActive = new HashMap<>();
    static final Map<UUID, Entity> hookedMap = new HashMap<>();
    static final Set<UUID> handledBottles = new HashSet<>();
    static final List<Entity> discardQueue = new ArrayList<>();
    static final List<Orb> orbQueue = new ArrayList<>();
    // ---- 전투(PvP) 모드 ----
    static class Combat {
        UUID a, b; boolean active; int hitsA, hitsB;
        String dim; double x, y, z; long endMs, lastMs; long outA = -1, outB = -1;
    }
    static final List<Combat> combats = new ArrayList<>();
    static final long COMBAT_MS = 10L * 60L * 1000L;   // 전투 모드 지속(마지막 타격 후 10분)
    static final int HITS_TO_START = 3;                // 이 횟수 이상 때리면 전투 모드
    static final long HIT_WINDOW_MS = 10_000L;         // 마지막 타격 후 10초 지나면 타격 횟수 초기화
    static final double ZONE_R = 50.0, ZONE_H = 10.0;  // 구역 반경/높이
    static final int OUT_TICKS = 60;                   // 구역 밖 3초
    static final List<Rm> removeQueue = new ArrayList<>();
    static final Map<String, Integer> AXE_SECONDS = Map.of("minecraft:wooden_axe", 2, "minecraft:golden_axe", 2,
        "minecraft:stone_axe", 4, "minecraft:iron_axe", 8, "minecraft:diamond_axe", 12, "minecraft:netherite_axe", 20);
    /** 인챈트 최대 레벨 (EnchantmentMixin 이 사용) */
    public static final Map<String, Integer> MAX_LEVELS = new HashMap<>();
    static {
        String[][] a = {{"sharpness","15"},{"smite","10"},{"protection","10"},{"fire_protection","10"},
            {"blast_protection","10"},{"projectile_protection","10"},{"unbreaking","5"},{"fire_aspect","5"},
            {"knockback","10"},{"thorns","5"},{"depth_strider","5"},{"feather_falling","8"},{"fortune","5"},
            {"efficiency","10"},{"looting","5"},{"power","10"},{"punch","4"},{"sweeping_edge","5"}};
        for (String[] e : a) MAX_LEVELS.put("enchantment.minecraft." + e[0], Integer.parseInt(e[1]));
    }

    @Override
    public void onInitialize() {
        file = FabricLoader.getInstance().getConfigDir().resolve("wildrules-beacons.json");
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { server = s; load(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { save(); restoreGhasts(); });
        UseBlockCallback.EVENT.register(WildRulesMod::onUse);
        ServerTickEvents.END_SERVER_TICK.register(WildRulesMod::onTick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(WildRulesMod::allowDamage);
        ServerLivingEntityEvents.AFTER_DEATH.register(WildRulesMod::afterDeath);
        PlayerBlockBreakEvents.BEFORE.register(WildRulesMod::beforeBreak);
        AttackEntityCallback.EVENT.register(WildRulesMod::onAttack);
        AttackBlockCallback.EVENT.register((pl, lvl, hand, pos, dir) -> {      // 신호기는 주인만 부술 수 있음 (채굴 시작 단계에서 차단)
            if (lvl.isClientSide() || !lvl.getBlockState(pos).is(Blocks.BEACON)) return InteractionResult.PASS;
            Rec r = find(dimKey(lvl), pos);
            if (r != null && !r.owner.equals(pl.getUUID().toString())) {
                if (pl instanceof ServerPlayer sp) notice(sp, "다른 사람의 신호기는 부술 수 없습니다", ChatFormatting.RED);
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> onEntityLoad(entity, world));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) -> {
            if (!srv.isRunning()) return;
            try { onDisconnect(handler.getPlayer()); } catch (Exception ex) { System.err.println("[wildrules] disconnect: " + ex); }
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, world) -> {
            if (be.getBlockState().is(Blocks.ENDER_CHEST)) removeQueue.add(new Rm(dimKey(world), be.getBlockPos().immutable()));
        });
    }

    // ---------- 저장/불러오기 ----------
    static void load() {
        try {
            if (Files.exists(file)) {
                Data d = GSON.fromJson(Files.readString(file), Data.class);
                if (d != null) data = d;
                if (data.bans == null) data.bans = new HashMap<>();
            }
        } catch (Exception e) { System.err.println("[wildrules] load failed: " + e); }
    }
    static void save() {
        try { Files.writeString(file, GSON.toJson(data)); }
        catch (Exception e) { System.err.println("[wildrules] save failed: " + e); }
    }

    // ---------- 유틸 ----------
    static String dimKey(Level l) { return l.dimension().toString(); }
    static ServerLevel levelOf(MinecraftServer s, String dim) {
        for (ServerLevel l : s.getAllLevels()) if (dimKey(l).equals(dim)) return l;
        return null;
    }
    static int countOwned(String owner) {
        int n = 0;
        for (Rec q : data.beacons) if (q.owner.equals(owner)) n++;
        for (Pending q : pending) if (q.uuid.toString().equals(owner)) n++;
        return n;
    }
    static Rec find(String dim, BlockPos p) {
        for (Rec r : data.beacons) if (r.dim.equals(dim) && r.x == p.getX() && r.y == p.getY() && r.z == p.getZ()) return r;
        return null;
    }
    static void say(ServerPlayer p, String text, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(text).withStyle(color));
    }
    static int streak(MinecraftServer s, ServerPlayer p) {
        Scoreboard sb = s.getScoreboard();
        Objective obj = sb.getObjective(STREAK_OBJECTIVE);
        if (obj == null) return 0;
        ReadOnlyScoreInfo info = sb.getPlayerScoreInfo(p, obj);
        return info == null ? 0 : info.value();
    }
    static Holder<MobEffect> effectOf(String key) {
        return switch (key) {
            case "iron" -> MobEffects.SPEED;
            case "gold" -> MobEffects.HASTE;
            case "emerald" -> MobEffects.RESISTANCE;
            case "diamond" -> MobEffects.STRENGTH;
            case "netherite" -> MobEffects.REGENERATION;
            default -> null;
        };
    }
    static String ingotKey(ItemStack s) {
        if (s.is(Items.IRON_INGOT)) return "iron";
        if (s.is(Items.GOLD_INGOT)) return "gold";
        if (s.is(Items.EMERALD)) return "emerald";
        if (s.is(Items.DIAMOND)) return "diamond";
        if (s.is(Items.NETHERITE_INGOT)) return "netherite";
        return null;
    }
    static String blockKey(ItemStack s) {
        if (s.is(Items.IRON_BLOCK)) return "iron";
        if (s.is(Items.GOLD_BLOCK)) return "gold";
        if (s.is(Items.EMERALD_BLOCK)) return "emerald";
        if (s.is(Items.DIAMOND_BLOCK)) return "diamond";
        if (s.is(Items.NETHERITE_BLOCK)) return "netherite";
        return null;
    }
    static boolean inCombat(UUID id) {
        for (Combat c : combats) if (c.active && (c.a.equals(id) || c.b.equals(id))) return true;
        return false;
    }
    static String ingotLabel(String k) {
        return switch (k) {
            case "iron" -> "철: 신속"; case "gold" -> "금: 성급함"; case "emerald" -> "에메랄드: 저항";
            case "diamond" -> "다이아: 힘"; case "netherite" -> "네더라이트: 재생"; default -> k;
        };
    }

    // ---------- 우클릭 처리 ----------
    static InteractionResult onUse(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        BlockPos pos = hit.getBlockPos();
        ItemStack held = player.getItemInHand(hand);

        if (level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
            removeQueue.add(new Rm(dimKey(level), pos.immutable()));
            say(sp, "엔더 상자는 이 서버에서 사용할 수 없습니다.", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        if (held.is(Items.ENDER_CHEST)) { held.shrink(1); return InteractionResult.FAIL; }
        if (held.is(Items.TNT) && sp.getItemBySlot(EquipmentSlot.HEAD).is(Items.CREEPER_HEAD)) {
            BlockPlaceContext tc = new BlockPlaceContext(player, hand, held, hit);
            if (tc.canPlace() && level instanceof ServerLevel tl) {
                BlockPos tp = tc.getClickedPos();
                if (!sp.getAbilities().instabuild) held.shrink(1);
                Vec3 c = new Vec3(tp.getX() + 0.5, tp.getY() + 0.5, tp.getZ() + 0.5);
                cmd(tl, "particle minecraft:explosion_emitter " + c.x + " " + c.y + " " + c.z + " 0 0 0 0 1 force");
                cmd(tl, "playsound minecraft:entity.generic.explode block @a " + c.x + " " + c.y + " " + c.z + " 4 1");
                for (LivingEntity le : tl.getEntitiesOfClass(LivingEntity.class, new AABB(c.x - 5, c.y - 5, c.z - 5, c.x + 5, c.y + 5, c.z + 5))) {
                    double d = le.position().add(0, le.getBbHeight() / 2.0, 0).distanceTo(c);
                    if (d > 5.0) continue;
                    double dmg = (le == sp ? 10.0 : 5.0) * (1.0 - d / 5.0);        // 거리에 따라 감소
                    if (dmg < 0.5) continue;
                    cmd(tl, "damage " + le.getUUID() + " " + dmg + " minecraft:player_explosion by " + sp.getName().getString());
                }
                return InteractionResult.FAIL;
            }
        }
        // 1) 신호기 우클릭: 바닐라 GUI(효과 선택)를 막고 광물 투입/상태 표시
        if (level.getBlockState(pos).is(Blocks.BEACON) && !player.isShiftKeyDown()) {
            useBeacon(sp, level, pos, held);
            return InteractionResult.SUCCESS;
        }
        // 2) 신호기 설치: 3번 제한
        if (held.is(Items.BEACON)) {
            BlockPlaceContext ctx = new BlockPlaceContext(player, hand, held, hit);
            if (!ctx.canPlace()) return InteractionResult.PASS;
            String id = sp.getUUID().toString();
            if (countOwned(id) >= MAX_BEACONS) {
                held.shrink(1);
                say(sp, "신호기는 한 사람당 " + MAX_BEACONS + "번만 설치할 수 있습니다. 신호기가 사라졌습니다.", ChatFormatting.RED);
                return InteractionResult.FAIL;
            }
            Pending p = new Pending();
            p.dim = dimKey(level); p.pos = ctx.getClickedPos().immutable();
            p.uuid = sp.getUUID(); p.name = sp.getName().getString();
            pending.add(p);
        }
        return InteractionResult.PASS;
    }

    static void useBeacon(ServerPlayer sp, Level level, BlockPos pos, ItemStack held) {
        Rec r = find(dimKey(level), pos);
        if (r == null) { say(sp, "이 신호기는 등록되지 않았습니다. (효과 없음)", ChatFormatting.GRAY); return; }
        if (!r.owner.equals(sp.getUUID().toString())) {
            say(sp, "이 신호기는 " + r.ownerName + " 님의 것입니다.", ChatFormatting.GRAY);
            return;
        }
        String key = blockKey(held);
        int lvl = 3;                                           // 광물 블록 = 3단계
        if (key == null) { key = ingotKey(held); lvl = 1; }    // 광물(주괴/에메랄드/다이아) = 1단계
        if (key != null) {
            if (key.equals(r.effect) && r.level == lvl) { say(sp, "이미 적용 중인 버프입니다: " + ingotLabel(key) + " (" + lvl + "단계)", ChatFormatting.GRAY); return; }
            if (!sp.getAbilities().instabuild) held.shrink(1);
            r.effect = key; r.level = lvl;
            save();
            say(sp, "신호기 버프 설정: " + ingotLabel(key) + " (" + lvl + "단계)", ChatFormatting.GREEN);
            return;
        }
        say(sp, r.effect == null ? "신호기 버프: 없음 (광물=1단계, 광물 블록=3단계를 우클릭으로 넣으세요)"
                                 : "신호기 버프: " + ingotLabel(r.effect) + " (" + r.level + "단계)", ChatFormatting.AQUA);
    }

    // 주인이 아니면 신호기를 부술 수 없음
    static boolean beforeBreak(Level level, Player player, BlockPos pos, net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.block.entity.BlockEntity be) {
        if (level.isClientSide() || !state.is(Blocks.BEACON)) return true;
        Rec r = find(dimKey(level), pos);
        if (r == null || r.owner.equals(player.getUUID().toString())) return true;
        if (player instanceof ServerPlayer sp) say(sp, "다른 사람의 신호기는 부술 수 없습니다.", ChatFormatting.RED);
        return false;
    }

    // ---------- 틱 처리 ----------
    static void onTick(MinecraftServer s) {
        // 설치 확인 (설치 직후 1~3틱 안에 블록이 생겼는지 확인)
        Iterator<Pending> it = pending.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            ServerLevel lvl = levelOf(s, p.dim);
            boolean done = false;
            if (lvl != null && lvl.getBlockState(p.pos).is(Blocks.BEACON) && find(p.dim, p.pos) == null) {
                Rec r = new Rec();
                r.dim = p.dim; r.x = p.pos.getX(); r.y = p.pos.getY(); r.z = p.pos.getZ();
                r.owner = p.uuid.toString(); r.ownerName = p.name;
                data.beacons.add(r);
                int n = 0;
                for (Rec q : data.beacons) if (q.owner.equals(p.uuid.toString())) n++;
                save();
                ServerPlayer pl = s.getPlayerList().getPlayer(p.uuid);
                if (pl != null) say(pl, "신호기 설치 (" + n + "/" + MAX_BEACONS + ")", ChatFormatting.YELLOW);
                done = true;
            }
            if (done || ++p.age > 3) it.remove();
        }
        // 철퇴: 공격 후 낙하거리 복원
        if (!macePending.isEmpty()) {
            for (Map.Entry<UUID, Double> e : new ArrayList<>(macePending.entrySet())) {
                ServerPlayer mp = s.getPlayerList().getPlayer(e.getKey());
                if (mp != null) mp.fallDistance = (float) (double) e.getValue();
            }
            macePending.clear();
        }
        // 신호기 바닐라 활성화(빛줄기/효과) 차단: 피라미드 레벨을 매 틱 0으로
        for (Rec r : data.beacons) {
            ServerLevel bl = levelOf(s, r.dim);
            if (bl != null && bl.hasChunkAt(new BlockPos(r.x, r.y, r.z)))
                cmd(bl, "data modify block " + r.x + " " + r.y + " " + r.z + " Levels set value 0");
        }
        if (!shieldPending.isEmpty()) {   // 도끼 방패 무력화 시간: 바닐라가 건 쿨다운을 도끼 등급별 시간으로 교체
            for (Map.Entry<UUID, Integer> e : new ArrayList<>(shieldPending.entrySet())) {
                ServerPlayer tp = s.getPlayerList().getPlayer(e.getKey());
                if (tp != null) for (ItemStack st : List.of(tp.getOffhandItem(), tp.getMainHandItem()))
                    if (st.is(Items.SHIELD) && (tp.getCooldowns().isOnCooldown(st) || !tp.isUsingItem())) {
                        tp.getCooldowns().addCooldown(st, e.getValue());
                        tp.stopUsingItem();
                    }
            }
            shieldPending.clear();
        }
        for (Iterator<Rm> ri = removeQueue.iterator(); ri.hasNext(); ) {   // 엔더 상자 제거
            Rm rm = ri.next();
            ServerLevel rl = levelOf(s, rm.dim());
            if (rl == null || !rl.hasChunkAt(rm.pos())) continue;
            if (rl.getBlockState(rm.pos()).is(Blocks.ENDER_CHEST))
                cmd(rl, "setblock " + rm.pos().getX() + " " + rm.pos().getY() + " " + rm.pos().getZ() + " air");
            ri.remove();
        }
        safe(() -> tickDragon(s), "dragon");
        safe(() -> tickHooks(s), "hooks");
        List<Entity> dq = new ArrayList<>(discardQueue);
        discardQueue.clear();
        for (Entity en : dq) if (!en.isRemoved()) en.discard();
        List<Orb> oq = new ArrayList<>(orbQueue);
        orbQueue.clear();
        for (Orb o : oq) cmd(o.level(), "summon minecraft:experience_orb " + o.x() + " " + o.y() + " " + o.z() + " {Value:" + o.value() + "s}");
        if (handledBottles.size() > 2000) handledBottles.clear();
        for (Entity de : new ArrayList<>(dragonQueue)) {               // 엔더 드래곤 체력 1000
            if (de instanceof LivingEntity le) {
                var at = le.getAttribute(Attributes.MAX_HEALTH);
                if (at != null) at.setBaseValue(1000.0);
                le.setHealth(1000.0F);
            }
        }
        dragonQueue.clear();
        for (Iterator<ArrowTrack> ai = arrowTracks.iterator(); ai.hasNext(); ) {   // 스켈레톤 머리 화살: 100칸 넘으면 사라짐
            ArrowTrack at2 = ai.next();
            Entity ar = at2.arrow();
            if (ar.isRemoved()) { ai.remove(); continue; }
            double dx = ar.getX() - at2.x(), dy = ar.getY() - at2.y(), dz = ar.getZ() - at2.z();
            if (dx * dx + dy * dy + dz * dz > 10000.0) { ar.discard(); ai.remove(); }
        }
        for (Iterator<Delayed> di = delayedOrbs.iterator(); di.hasNext(); ) {      // 드래곤 재처치 경험치
            Delayed d = di.next();
            if (s.getTickCount() >= d.due()) {
                cmd(d.level(), "summon minecraft:experience_orb " + d.x() + " " + d.y() + " " + d.z() + " {Value:" + d.value() + "s}");
                di.remove();
            }
        }
        if (s.getTickCount() % 5 == 0) { safe(() -> tickCombat(s), "combat"); safe(() -> tickTotems(s), "totems"); }
        if (s.getTickCount() % 20 == 0) safe(() -> tickWolves(s), "wolves");
        if (s.getTickCount() % 10 == 0) safe(() -> tickGhasts(s), "ghasts");
        if (s.getTickCount() % 2 == 0) {
            safe(() -> tickHeads(s), "heads");
            safe(() -> tickSmelt(s), "smelt");
            safe(() -> tickAnvil(s), "anvil");
            safe(() -> tickZ(s), "zhead");
            for (ServerPlayer cp : s.getPlayerList().getPlayers()) {
                try { for (var slot : cp.containerMenu.slots) clampMace(slot.getItem()); }
                catch (Exception ex) { if (!patchWarned) { patchWarned = true; System.err.println("[wildrules] mace clamp failed: " + ex); } }
            }
        }
        if (s.getTickCount() % 10 != 0) return;
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            String nm = p.getName().getString();
            if (score(s, p, "wr_banclear") > 0) {
                data.bans.remove(p.getUUID().toString()); banNotified.remove(p.getUUID()); save();
                cmd(s.overworld(), "scoreboard players set " + nm + " wr_banclear 0");
            }
            if (score(s, p, "wr_banreq") > 0) {
                data.bans.put(p.getUUID().toString(), System.currentTimeMillis() + 48L * 3600L * 1000L); save();
                cmd(s.overworld(), "scoreboard players set " + nm + " wr_banreq 0");
            }
            long left = banLeft(p.getUUID());
            if (left > 0 && banNotified.add(p.getUUID()))
                say(p, "[패널티] 보상 악용으로 PvP 금지 중입니다. 남은 시간: " + fmt(left) + " (현실 시간 기준, /trigger pvpban 으로 확인)", ChatFormatting.RED);
            if (left <= 0) banNotified.remove(p.getUUID());
            if (score(s, p, "pvpban") > 0) {
                say(p, left > 0 ? "[패널티] PvP 금지 남은 시간: " + fmt(left) : "[패널티] PvP 금지 상태가 아닙니다.", ChatFormatting.YELLOW);
                cmd(s.overworld(), "scoreboard players set " + nm + " pvpban 0");
            }
            patchInventory(s, p);
        }

        for (Rec r : new ArrayList<>(data.beacons)) {
            ServerLevel lvl = levelOf(s, r.dim);
            if (lvl == null) continue;
            BlockPos bp = new BlockPos(r.x, r.y, r.z);
            if (!lvl.hasChunkAt(bp)) continue;
            if (!lvl.getBlockState(bp).is(Blocks.BEACON)) {   // 부서졌으면 기록 삭제 (설치 횟수는 유지)
                data.beacons.remove(r);
                flagged.remove(r.dim + r.x + "," + r.y + "," + r.z);
                save();
                continue;
            }
            ServerPlayer owner = s.getPlayerList().getPlayer(UUID.fromString(r.owner));
            String key = r.dim + r.x + "," + r.y + "," + r.z;
            if (owner == null) { flagged.remove(key); continue; }

            // (1) 주인 버프: 주인이 근처에 있으면 유지. 전투(PvP) 모드 중에는 받지 못함
            if (r.effect != null && !inCombat(owner.getUUID()) && dimKey(owner.level()).equals(r.dim)
                    && owner.distanceToSqr(r.x + 0.5, r.y + 0.5, r.z + 0.5) <= BUFF_RANGE * BUFF_RANGE) {
                Holder<MobEffect> eff = effectOf(r.effect);
                if (eff != null) owner.addEffect(new MobEffectInstance(eff, 60, Math.max(0, r.level - 1), true, false, true));
            }

            // (2) 신호기 범위(100칸) 안의 주인 외 플레이어: 발광 + 주인에게 알림 (연속킬 5 이상은 빨간 글씨)
            Set<UUID> prev = flagged.getOrDefault(key, new HashSet<>());
            Set<UUID> cur = new HashSet<>();
            for (ServerPlayer t : s.getPlayerList().getPlayers()) {
                if (t.getUUID().equals(owner.getUUID()) || t.isSpectator() || t.getItemBySlot(EquipmentSlot.HEAD).is(Items.ZOMBIE_HEAD)) continue;
                if (!dimKey(t.level()).equals(r.dim)) continue;
                if (t.distanceToSqr(r.x + 0.5, r.y + 0.5, r.z + 0.5) > DETECT_RANGE * DETECT_RANGE) continue;
                cur.add(t.getUUID());
                t.addEffect(new MobEffectInstance(MobEffects.GLOWING, 40, 0, true, false, false));
                if (!prev.contains(t.getUUID())) {
                    boolean big = streak(s, t) >= 5;
                    say(owner, t.getName().getString() + " 님이 신호기 범위(" + (int) DETECT_RANGE + "칸)에 들어왔습니다." + (big ? " (연속킬 5 이상!)" : ""),
                        big ? ChatFormatting.RED : ChatFormatting.YELLOW);
                }
            }
            flagged.put(key, cur);
        }
    }

    // ---------- PvP 보호 (무적/패널티): 플레이어가 준 피해만 완전 차단, 넉백도 없음 ----------
    static int score(MinecraftServer s, ServerPlayer p, String objective) {
        Scoreboard sb = s.getScoreboard();
        Objective obj = sb.getObjective(objective);
        if (obj == null) return 0;
        ReadOnlyScoreInfo info = sb.getPlayerScoreInfo(p, obj);
        return info == null ? 0 : info.value();
    }
    static void notice(ServerPlayer p, String text, ChatFormatting c) {
        long now = System.currentTimeMillis();
        Long l = lastNotice.get(p.getUUID());
        if (l != null && now - l < 1000) return;
        lastNotice.put(p.getUUID(), now);
        p.sendSystemMessage(Component.literal(text).withStyle(c), true);
    }
    static long banLeft(UUID id) {
        Long e = data.bans.get(id.toString());
        if (e == null) return 0;
        long left = e - System.currentTimeMillis();
        if (left <= 0) { data.bans.remove(id.toString()); save(); return 0; }
        return left;
    }
    static String fmt(long ms) { long m = ms / 60000L; return (m / 60) + "시간 " + (m % 60) + "분"; }
    static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
        if (server != null && victim instanceof ServerPlayer bp && bp.isBlocking()) fixStack(server, bp, bp.getUseItem());   // 막기 직전에 방패 규칙 보장
        if (server != null && victim instanceof ServerPlayer vp && vp.isBlocking()
                && source.getDirectEntity() == source.getEntity() && source.getEntity() instanceof LivingEntity axeman) {
            Integer sec = AXE_SECONDS.get(itemId(axeman.getMainHandItem()));
            if (sec != null) shieldPending.put(vp.getUUID(), sec * 20);          // 도끼 등급별 방패 무력화 시간
        }
        if (server != null && victim.isBlocking() && source.getEntity() instanceof LivingEntity spearer
                && itemId(spearer.getMainHandItem()).endsWith("_spear")) victim.stopUsingItem();   // 창: 방패 무시
        if (server == null || !(victim instanceof ServerPlayer v)) {
            if (server != null) dragonHit(source.getEntity(), victim);
            return true;
        }
        Entity a = source.getEntity();                 // 투사체면 쏜 사람
        if (!(a instanceof ServerPlayer att) || att == v) return true;   // 몹/환경 피해는 그대로
        if (score(server, v, "wr_prot") > 0) { notice(att, "상대는 무적 시간입니다", ChatFormatting.AQUA); return false; }
        if (score(server, att, "wr_prot") > 0) { notice(att, "무적 시간 중에는 다른 플레이어를 공격할 수 없습니다", ChatFormatting.AQUA); return false; }
        long left = banLeft(att.getUUID());
        if (left > 0) { notice(att, "PvP 금지 중 - 남은 시간 " + fmt(left), ChatFormatting.RED); return false; }
        combatHit(att, v);
        dragonHit(att, v);
        return true;
    }

    // ---------- 명령 실행 도우미 ----------
    static void cmd(ServerLevel l, String c) {
        try {
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withLevel(l).withSuppressedOutput(), c);
        } catch (Exception ignored) { }
    }

    // ---------- 철퇴: 공격 쿨타임이 다 차지 않으면 낙하 추가 피해/효과 없음 ----------
    static InteractionResult onAttack(Player player, Level level, InteractionHand hand, Entity target, EntityHitResult hit) {
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        if (sp.getMainHandItem().is(Items.MACE) && sp.getAttackStrengthScale(0.5F) < 0.9F && sp.fallDistance > 1.5) {
            macePending.put(sp.getUUID(), (double) sp.fallDistance);
            sp.fallDistance = 0;
        }
        if (target instanceof ServerPlayer tp && tp.isBlocking()) {
            String hid = itemId(sp.getMainHandItem());
            if (AXE_SECONDS.containsKey(hid)) shieldPending.put(tp.getUUID(), AXE_SECONDS.get(hid) * 20);
        }
        return InteractionResult.PASS;
    }
    static String itemId(ItemStack st) {
        var k = BuiltInRegistries.ITEM.getKey(st.getItem());
        return k == null ? "" : k.toString();
    }

    // ---------- 머리 착용: 해당 몹은 플레이어를 쫓지 않음 ----------
    static String typeId(Entity e) {
        var k = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return k == null ? "" : k.toString();
    }
    static final Set<String> ZOMBIES = Set.of("minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager");
    static final Set<String> SKELETONS = Set.of("minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:parched");
    static final Set<String> PIGLIN_IGNORE = Set.of("minecraft:zombie", "minecraft:husk", "minecraft:stray", "minecraft:skeleton", "minecraft:parched",
        "minecraft:creeper", "minecraft:spider", "minecraft:piglin", "minecraft:piglin_brute", "minecraft:hoglin", "minecraft:wither_skeleton",
        "minecraft:magma_cube", "minecraft:blaze", "minecraft:slime");
    static final Set<String> UNDEAD = Set.of("minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombie_villager",
        "minecraft:zombified_piglin", "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:wither_skeleton",
        "minecraft:wither", "minecraft:zoglin", "minecraft:skeleton_horse", "minecraft:zombie_horse", "minecraft:phantom");
    static boolean ignores(ItemStack head, Mob m) {
        String id = typeId(m);
        if (head.is(Items.ZOMBIE_HEAD)) return ZOMBIES.contains(id);
        if (head.is(Items.SKELETON_SKULL)) return SKELETONS.contains(id);
        if (head.is(Items.CREEPER_HEAD)) return id.equals("minecraft:creeper");
        if (head.is(Items.WITHER_SKELETON_SKULL)) return id.equals("minecraft:wither_skeleton");
        if (head.is(Items.PIGLIN_HEAD)) return PIGLIN_IGNORE.contains(id);
        return false;
    }
    /** 머리를 쓴 플레이어를 해당 몹이 목표로 삼지 못하게 하는 판정 (MobTargetMixin 이 사용) */
    public static boolean ignoresTarget(Mob m, LivingEntity target) {
        if (!(target instanceof ServerPlayer p)) return false;
        ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
        return !head.isEmpty() && ignores(head, m);
    }
    static net.minecraft.world.item.Item smelted(ItemStack st) {
        if (st.is(Items.RAW_IRON) || st.is(Items.IRON_ORE) || st.is(Items.DEEPSLATE_IRON_ORE)) return Items.IRON_INGOT;
        if (st.is(Items.RAW_GOLD) || st.is(Items.GOLD_ORE) || st.is(Items.DEEPSLATE_GOLD_ORE) || st.is(Items.NETHER_GOLD_ORE)) return Items.GOLD_INGOT;
        if (st.is(Items.RAW_COPPER) || st.is(Items.COPPER_ORE) || st.is(Items.DEEPSLATE_COPPER_ORE)) return Items.COPPER_INGOT;
        if (st.is(Items.ANCIENT_DEBRIS)) return Items.NETHERITE_SCRAP;
        return null;
    }
    static void tickSmelt(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (!p.getItemBySlot(EquipmentSlot.HEAD).is(Items.PIGLIN_HEAD)) continue;
            Inventory inv = p.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty()) continue;
                var out = smelted(st);
                if (out != null) inv.setItem(i, new ItemStack(out, st.getCount()));
            }
        }
    }
    static void tickHeads(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            ItemStack head = p.getItemBySlot(EquipmentSlot.HEAD);
            if (head.isEmpty()) continue;
            for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(32.0))) {
                if (m.getTarget() == p && ignores(head, m)) m.setTarget(null);
            }
        }
    }

    // ---------- 머리 드랍 확률 (플레이어가 처치했을 때) ----------
    static void afterDeath(LivingEntity e, DamageSource src) {
        if (server != null && typeId(e).equals("minecraft:ender_dragon") && e.level() instanceof ServerLevel dl) {
            if (data.dragonKilled) delayedOrbs.add(new Delayed(dl, e.getX(), e.getY() + 1.0, e.getZ(), 11500, server.getTickCount() + 210));   // 재처치도 12000 (바닐라 500 + 11500)
            else { data.dragonKilled = true; save(); }
        }
        if (server != null && e instanceof ServerPlayer dp) {
            String n = dp.getName().getString(), dm = dimKey(dp.level());
            int di = dm.contains("the_nether") ? 1 : dm.contains("the_end") ? 2 : 0;
            ServerLevel o = server.overworld();
            cmd(o, "scoreboard players set " + n + " wr_dx " + (int) Math.floor(dp.getX()));
            cmd(o, "scoreboard players set " + n + " wr_dy " + (int) Math.floor(dp.getY()));
            cmd(o, "scoreboard players set " + n + " wr_dz " + (int) Math.floor(dp.getZ()));
            cmd(o, "scoreboard players set " + n + " wr_ddim " + di);
            cmd(o, "scoreboard players set " + n + " wr_dvalid 1");
        }
        if (e instanceof ServerPlayer && src.getEntity() instanceof ServerPlayer k && k != e) {
            dragonReady.remove(k.getUUID());   // 플레이어를 죽이면 스킬 재사용 가능 + 쿨타임 표시 해제
            try { k.getCooldowns().addCooldown(new ItemStack(Items.DRAGON_HEAD), 0); } catch (Exception ignored) { }
        }
        if (e instanceof ServerPlayer dp) combats.removeIf(c -> c.a.equals(dp.getUUID()) || c.b.equals(dp.getUUID()));
        if (server == null || !(src.getEntity() instanceof ServerPlayer)) return;
        if (!(e.level() instanceof ServerLevel lvl)) return;
        String id = typeId(e);
        String item = null; double chance = 0;
        if (ZOMBIES.contains(id)) { item = "zombie_head"; chance = 0.005; }
        else if (SKELETONS.contains(id)) { item = "skeleton_skull"; chance = 0.005; }
        else if (id.equals("minecraft:creeper")) { item = "creeper_head"; chance = 0.005; }
        else if (id.equals("minecraft:piglin") || id.equals("minecraft:piglin_brute")) { item = "piglin_head"; chance = 0.001; }
        else if (id.equals("minecraft:wither_skeleton")) { item = "wither_skeleton_skull"; chance = 0.075; }   // 바닐라 확률과 합쳐 약 10%
        if (item == null || lvl.getRandom().nextDouble() >= chance) return;
        cmd(lvl, "summon minecraft:item " + e.getX() + " " + (e.getY() + 0.3) + " " + e.getZ()
                + " {Item:{id:\"minecraft:" + item + "\",count:1}}");
    }

    // ---------- 엔드 크리스탈: 3번 맞아야 부서지고, 맞을 때마다 주변 엔티티를 밀쳐냄 ----------
    /** @return true 면 바닐라대로 부서짐, false 면 이번 타격은 무효 */
    public static boolean crystalHit(ServerLevel level, EndCrystal crystal, DamageSource source) {
        // 플레이어(또는 플레이어가 쏜 투사체/설치한 TNT)가 때린 경우만 센다. 불/드래곤/기타는 바닐라 처리.
        if (!(source.getEntity() instanceof ServerPlayer)) return true;
        UUID id = crystal.getUUID();
        long gt = level.getGameTime();
        Long last = crystalLast.get(id);
        if (last != null && gt - last < 10) return false;      // 같은 타격의 중복 호출은 무시(파티클/밀치기 1번만)
        crystalLast.put(id, gt);
        int hits = crystalHits.merge(id, 1, Integer::sum);
        if (hits >= 3) { crystalHits.remove(id); crystalLast.remove(id); return true; }
        Vec3 c = crystal.position();
        cmd(level, "particle minecraft:poof " + c.x + " " + (c.y + 0.5) + " " + c.z + " 0.4 0.4 0.4 0.02 8 force");
        for (Entity en : level.getEntitiesOfClass(Entity.class, crystal.getBoundingBox().inflate(10.0))) {
            if (en == crystal || en instanceof EndCrystal || en instanceof EnderDragon || en.getClass().getSimpleName().contains("EnderDragon")) continue;
            Vec3 d = en.position().subtract(c);
            double h = Math.sqrt(d.x * d.x + d.z * d.z);
            double nx = h < 0.01 ? 1.0 : d.x / h, nz = h < 0.01 ? 0.0 : d.z / h;
            en.push(nx * 2.5, 0.9, nz * 2.5);
            if (en instanceof ServerPlayer sp) sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(sp));
        }
        return false;
    }

    // ---------- 방패 피해 감소 80% / 철퇴 날카로움 최대 5 ----------
    static <T> boolean patch(MinecraftServer s, ItemStack st, DataComponentType<T> type, java.util.function.Predicate<JsonElement> edit) {
        T cur = st.get(type);
        if (cur == null) return false;
        var ops = RegistryOps.create(JsonOps.INSTANCE, s.registryAccess());
        var codec = type.codecOrThrow();
        JsonElement j = codec.encodeStart(ops, cur).getOrThrow();
        if (!edit.test(j)) return false;
        T neu = codec.parse(ops, j).getOrThrow();
        st.set(type, neu);
        return true;
    }
    static void fixStack(MinecraftServer s, ServerPlayer p, ItemStack st) {
        if (st.isEmpty()) return;
        try {
            boolean ch = st.is(Items.SHIELD) && patch(s, st, DataComponents.BLOCKS_ATTACKS, WildRulesMod::shieldEdit);
            clampMace(st);
            if (ch && shieldNotified.add(p.getUUID())) say(p, "[wildrules] 방패 효과 적용됨 (공격 80% 경감, 활/투사체 100% 차단)", ChatFormatting.GRAY);
        } catch (Exception ex) {
            if (!patchWarned) { patchWarned = true; System.err.println("[wildrules] item patch failed: " + ex); say(p, "[wildrules] 아이템 패치 실패: " + ex, ChatFormatting.RED); }
        }
    }
    static void patchInventory(MinecraftServer s, ServerPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) fixStack(s, p, inv.getItem(i));
        for (EquipmentSlot es : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
            fixStack(s, p, p.getItemBySlot(es));
        try { for (var slot : p.containerMenu.slots) fixStack(s, p, slot.getItem()); } catch (Exception ignored) { }
    }
    static final Set<UUID> shieldNotified = new HashSet<>();
    static final Set<String> warnedTasks = new HashSet<>();
    static void safe(Runnable r, String name) {
        try { r.run(); }
        catch (Throwable ex) { if (warnedTasks.add(name)) { System.err.println("[wildrules] " + name + " failed: " + ex); ex.printStackTrace(); } }
    }
    static boolean patchWarned = false;
    static void clampMace(ItemStack st) {
        if (st.isEmpty() || !st.is(Items.MACE)) return;
        ItemEnchantments en = st.get(DataComponents.ENCHANTMENTS);
        if (en == null) return;
        for (Holder<Enchantment> h : en.keySet()) {
            if (h.is(Enchantments.SHARPNESS) && en.getLevel(h) > 5) {
                EnchantmentHelper.updateEnchantments(st, m -> m.set(h, 5));
                return;
            }
        }
    }
    static boolean shieldEdit(JsonElement j) {
        if (!j.isJsonObject()) return false;
        JsonObject root = j.getAsJsonObject();
        JsonElement arr = root.get("damage_reductions");
        if (arr != null && arr.isJsonArray() && arr.getAsJsonArray().size() == 2) {      // 이미 적용됨?
            boolean has80 = false, hasProj = false;
            for (JsonElement e : arr.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                if (o.has("type")) hasProj = true;
                else if (o.has("factor") && Math.abs(o.get("factor").getAsDouble() - 0.8) < 0.001) has80 = true;
            }
            if (has80 && hasProj) return false;
        }
        com.google.gson.JsonArray na = new com.google.gson.JsonArray();
        JsonObject all = new JsonObject();                    // 모든 피해: 80% 감소
        all.addProperty("horizontal_blocking_angle", 90.0);
        all.addProperty("base", 0.0);
        all.addProperty("factor", 0.8);
        na.add(all);
        JsonObject proj = new JsonObject();                   // 투사체(활 등): 합산되어 100% 막힘
        proj.addProperty("horizontal_blocking_angle", 90.0);
        proj.addProperty("type", "#minecraft:is_projectile");
        proj.addProperty("base", 0.0);
        proj.addProperty("factor", 1.0);
        na.add(proj);
        root.add("damage_reductions", na);
        return true;
    }
    static boolean maceEdit(JsonElement j) {
        if (!j.isJsonObject()) return false;
        JsonObject o = j.getAsJsonObject();
        if (o.has("levels") && o.get("levels").isJsonObject()) o = o.getAsJsonObject("levels");
        JsonElement sh = o.get("minecraft:sharpness");
        if (sh != null && sh.getAsInt() > 5) { o.addProperty("minecraft:sharpness", 5); return true; }
        return false;
    }

    // ---------- 전투(PvP) 모드 ----------
    static void pvpBar(ServerPlayer p, long secs) {
        p.sendSystemMessage(Component.literal("PVP 활성화 끝나기 전까지 " + secs + "초").withStyle(ChatFormatting.RED), true);
    }
    static void setCenter(Combat c, ServerPlayer v) { c.dim = dimKey(v.level()); c.x = v.getX(); c.y = v.getY(); c.z = v.getZ(); }

    static void combatHit(ServerPlayer att, ServerPlayer v) {
        long now = System.currentTimeMillis();
        Combat c = null;
        for (Combat q : combats)
            if ((q.a.equals(att.getUUID()) && q.b.equals(v.getUUID())) || (q.a.equals(v.getUUID()) && q.b.equals(att.getUUID()))) { c = q; break; }
        if (c == null) { c = new Combat(); c.a = att.getUUID(); c.b = v.getUUID(); combats.add(c); }
        if (!c.active && now - c.lastMs > HIT_WINDOW_MS) { c.hitsA = 0; c.hitsB = 0; }
        c.lastMs = now;
        if (c.active) { setCenter(c, v); c.endMs = now + COMBAT_MS; return; }   // 서로 때릴 때마다 구역 이동 + 10분 리셋
        boolean fromA = c.a.equals(att.getUUID());
        int n = fromA ? ++c.hitsA : ++c.hitsB;
        if (n >= HITS_TO_START) {
            c.active = true; c.outA = -1; c.outB = -1;
            setCenter(c, v); c.endMs = now + COMBAT_MS;
            pvpBar(att, COMBAT_MS / 1000); pvpBar(v, COMBAT_MS / 1000);
        }
    }

    static void tickCombat(MinecraftServer s) {
        long now = System.currentTimeMillis();
        int tick = s.getTickCount();
        for (Combat c : new ArrayList<>(combats)) {
            if (!c.active) { if (now - c.lastMs > HIT_WINDOW_MS) combats.remove(c); continue; }
            ServerPlayer pa = s.getPlayerList().getPlayer(c.a), pb = s.getPlayerList().getPlayer(c.b);
            if (pa == null || pb == null || !pa.isAlive() || !pb.isAlive()) { combats.remove(c); continue; }
            if (now > c.endMs) {
                combats.remove(c);
                pa.sendSystemMessage(Component.literal("PVP 활성화가 종료되었습니다").withStyle(ChatFormatting.GRAY), true);
                pb.sendSystemMessage(Component.literal("PVP 활성화가 종료되었습니다").withStyle(ChatFormatting.GRAY), true);
                continue;
            }
            checkDim(pa);
            checkDim(pb);
            lastPos.put(pa.getUUID(), new LastPos(dimKey(pa.level()), pa.getX(), pa.getY(), pa.getZ()));
            lastPos.put(pb.getUUID(), new LastPos(dimKey(pb.level()), pb.getX(), pb.getY(), pb.getZ()));
            if (zoneCheck(s, c, pa, pb, true, tick)) { combats.remove(c); continue; }
            if (zoneCheck(s, c, pb, pa, false, tick)) { combats.remove(c); continue; }
            long secs = Math.max(0, (c.endMs - now + 999) / 1000);
            if (c.outA < 0) pvpBar(pa, secs);      // 구역 안: PVP 남은 시간 표시 (구역 밖이면 이탈 경고가 우선)
            if (c.outB < 0) pvpBar(pb, secs);
        }
    }

    static boolean zoneCheck(MinecraftServer s, Combat c, ServerPlayer p, ServerPlayer other, boolean isA, int tick) {
        boolean inside = dimKey(p.level()).equals(c.dim)
                && Math.hypot(p.getX() - c.x, p.getZ() - c.z) <= ZONE_R && Math.abs(p.getY() - c.y) <= ZONE_H;
        long out = isA ? c.outA : c.outB;
        if (inside) { if (isA) c.outA = -1; else c.outB = -1; return false; }
        if (out < 0) { out = tick; if (isA) c.outA = out; else c.outB = out; }
        long elapsed = tick - out;
        if (elapsed >= OUT_TICKS) { killByZone(s, p, other); return true; }
        int left = (int) Math.ceil((OUT_TICKS - elapsed) / 20.0);
        p.sendSystemMessage(Component.literal("전투 구역을 벗어났습니다! " + left + "초 후 사망").withStyle(ChatFormatting.RED), true);
        return false;
    }

    // 구역 이탈 사망: 머리 없이 아이템 드랍(데이터팩 wr_nohead), 상대에게 킬 +1
    static void killByZone(MinecraftServer s, ServerPlayer dead, ServerPlayer killer) {
        String dn = dead.getName().getString(), kn = killer.getName().getString();
        cmd(s.overworld(), "scoreboard players set " + dn + " wr_nohead 1");
        cmd(s.overworld(), "scoreboard players add " + kn + " wr_kills 1");
        say(killer, "상대가 전투 구역을 벗어나 사망했습니다. 전투 모드가 종료됩니다.", ChatFormatting.GREEN);
        cmd(s.overworld(), "kill " + dn);
    }

    // 전투 모드 중 접속 종료: 상대는 킬 +1 / 나간 사람은 머리 없이 아이템 드랍 후 사망
    static void onDisconnect(ServerPlayer p) {
        List<Combat> mine = new ArrayList<>();
        for (Combat c : combats) if (c.active && (c.a.equals(p.getUUID()) || c.b.equals(p.getUUID()))) mine.add(c);
        if (mine.isEmpty()) return;
        for (Combat c : mine) {
            UUID ou = c.a.equals(p.getUUID()) ? c.b : c.a;
            ServerPlayer o = server.getPlayerList().getPlayer(ou);
            if (o != null) {
                say(o, "상대가 전투 중 접속을 종료했습니다. 연속킬이 올라가고 전투 모드가 종료됩니다.", ChatFormatting.GREEN);
                cmd(server.overworld(), "execute as " + o.getName().getString() + " at @s run function wr:kill/streak_up");
            }
            combats.remove(c);
        }
        String nm = p.getName().getString();
        if (p.level() instanceof ServerLevel lvl) {
            int xp = p.totalExperience;
            p.getInventory().dropAll();
            while (xp > 0) {
                int v = Math.min(xp, 30000);
                cmd(lvl, "summon minecraft:experience_orb " + p.getX() + " " + (p.getY() + 0.3) + " " + p.getZ() + " {Value:" + v + "s}");
                xp -= v;
            }
        }
        cmd(server.overworld(), "experience set " + nm + " 0 levels");
        cmd(server.overworld(), "experience set " + nm + " 0 points");
        cmd(server.overworld(), "scoreboard players set " + nm + " wr_logout 1");   // 재접속 시 데이터팩이 머리 없이 사망 처리
    }

    // ---------- 경험치 병: 병 하나 = 던진 사람의 다음 1레벨 분량 ----------
    static void onEntityLoad(Entity entity, ServerLevel world) {
        String id = typeId(entity);
        if ((id.equals("minecraft:arrow") || id.equals("minecraft:spectral_arrow")) && entity.tickCount == 0
                && entity instanceof Projectile ap && ap.getOwner() instanceof ServerPlayer shooter
                && shooter.getItemBySlot(EquipmentSlot.HEAD).is(Items.SKELETON_SKULL)
                && (shooter.getMainHandItem().is(Items.BOW) || shooter.getMainHandItem().is(Items.CROSSBOW))) {
            Vec3 v = entity.getDeltaMovement();
            double sp = v.length();
            if (sp > 0.1) entity.setDeltaMovement(v.scale(4.5 / sp));      // 일반 화살(3.0)보다 빠르게
            entity.setNoGravity(true);                                      // 일자로 날아감
            arrowTracks.add(new ArrowTrack(entity, entity.getX(), entity.getY(), entity.getZ()));
            return;
        }
        if (id.equals("minecraft:ender_dragon") && entity.addTag("wr_dragon_mod")) { dragonQueue.add(entity); return; }
        if (id.equals("minecraft:experience_orb")) {
            for (Entity b : world.getEntitiesOfClass(Entity.class, entity.getBoundingBox().inflate(2.5))) {
                if (!typeId(b).equals("minecraft:experience_bottle")) continue;
                discardQueue.add(entity);
                if (handledBottles.add(b.getUUID())) {
                    int v = 7;
                    if (b instanceof Projectile pr && pr.getOwner() instanceof ServerPlayer op)
                        v = Math.max(1, Math.min(32000, op.getXpNeededForNextLevel()));
                    orbQueue.add(new Orb(world, b.getX(), b.getY(), b.getZ(), v));
                }
                return;
            }
            return;
        }
        // 신호기 100칸 안에서는 몬스터 생성 금지
        if (data.beacons.isEmpty()) return;
        if (entity.getType().getCategory() != MobCategory.MONSTER) return;
        if (id.equals("minecraft:wither") || id.equals("minecraft:ender_dragon")) return;
        String dim = dimKey(world);
        for (Rec r : data.beacons) {
            if (r.dim.equals(dim) && Math.hypot(entity.getX() - (r.x + 0.5), entity.getZ() - (r.z + 0.5)) <= SPAWN_BLOCK_R
                    && Math.abs(entity.getY() - (r.y + 0.5)) <= SPAWN_BLOCK_H) {
                discardQueue.add(entity);
                return;
            }
        }
    }

    // ---------- 드래곤 머리 ----------
    static void tickDragon(MinecraftServer s) {
        long now = System.currentTimeMillis();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (!p.isAlive() || !p.getItemBySlot(EquipmentSlot.HEAD).is(Items.DRAGON_HEAD)) continue;
            if (p.getHealth() > 6.0F) continue;                         // 체력 3칸(하트 3개) 이하일 때만
            Long ready = dragonReady.get(p.getUUID());
            if (ready != null && now < ready) continue;                  // 30분에 한 번 (킬 시 초기화)
            activateDragon(p, now);
        }
    }
    static void activateDragon(ServerPlayer p, long now) {
        long until = now + 30L * 60L * 1000L;
        dragonReady.put(p.getUUID(), until);
        dragonActive.put(p.getUUID(), until);
        try { p.getCooldowns().addCooldown(new ItemStack(Items.DRAGON_HEAD), 36000); } catch (Exception ignored) { }   // 30분: 아이템에 쿨타임 표시
        if (!(p.level() instanceof ServerLevel lvl)) return;
        cmd(lvl, "playsound minecraft:entity.ender_dragon.growl master @a " + p.getX() + " " + p.getY() + " " + p.getZ() + " 4 1");
        p.setHealth(p.getMaxHealth());
        p.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 36000, 4, false, true, true));   // 하트 한 줄(20) 추가
        Vec3 c = p.position();
        for (LivingEntity e : lvl.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(10.0))) {
            if (e == p) continue;
            Vec3 d = e.position().subtract(c);
            double h = Math.sqrt(d.x * d.x + d.z * d.z);
            double nx = h < 0.01 ? 1.0 : d.x / h, nz = h < 0.01 ? 0.0 : d.z / h;
            e.push(nx * 2.5, 0.9, nz * 2.5);
            if (e instanceof ServerPlayer sp) sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(sp));
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 12000, 0, false, true, true));  // 나약함 I, 10분
        }
        say(p, "드래곤의 포효! 30분 동안 공격할 때마다 상대에게 위더 V가 3초 걸립니다.", ChatFormatting.DARK_PURPLE);
    }
    static void dragonHit(Entity a, LivingEntity victim) {
        if (!(a instanceof ServerPlayer ap) || victim == ap) return;
        Long until = dragonActive.get(ap.getUUID());
        if (until == null || System.currentTimeMillis() > until) return;
        victim.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 4, false, true, true));     // 위더 V, 3초
    }

    // ---------- 낚싯대: 끌어당기는 힘 강화 (엔티티에 걸려 있을 때 감을 때만) ----------
    static void tickHooks(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            var hook = p.fishing;
            Entity he = hook == null ? null : hook.getHookedIn();
            if (he != null) { hookedMap.put(p.getUUID(), he); continue; }
            Entity prev = hookedMap.remove(p.getUUID());
            if (prev != null && prev.isAlive() && !prev.isRemoved()) pullBoost(p, prev);
        }
    }
    static void pullBoost(ServerPlayer p, Entity e) {
        Vec3 d = p.position().subtract(e.position());
        double dist = d.length();
        if (dist < 0.5) return;
        double sp = Math.min(1.6, 0.45 + dist * 0.18);
        Vec3 n = d.scale(1.0 / dist);
        e.push(n.x * sp, Math.max(0.35, n.y * sp + 0.3), n.z * sp);
        if (e instanceof ServerPlayer tp) tp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(tp));
    }

    // ---------- 좀비 머리 착용 상태를 데이터팩(발광 면제)에 전달 ----------
    static void tickZ(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            boolean w = p.getItemBySlot(EquipmentSlot.HEAD).is(Items.ZOMBIE_HEAD);
            Boolean prev = zState.put(p.getUUID(), w);
            if (prev == null || prev != w) cmd(s.overworld(), "scoreboard players set " + p.getName().getString() + " wr_zhead " + (w ? 1 : 0));
        }
    }

    // ---------- 불사의 토템: 인벤토리(왼손 포함) 최대 3개, 초과분은 바닥에 (셜커 상자 안은 제외) ----------
    static void tickTotems(MinecraftServer s) {
        long now = System.currentTimeMillis();
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            Inventory inv = p.getInventory();
            ItemStack off = p.getOffhandItem();
            int count = off.is(Items.TOTEM_OF_UNDYING) ? off.getCount() : 0;
            boolean dropped = false;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack st = inv.getItem(i);
                if (st.isEmpty() || st == off || !st.is(Items.TOTEM_OF_UNDYING)) continue;
                if (count + st.getCount() > 3) {
                    ItemStack copy = st.copy();
                    inv.setItem(i, ItemStack.EMPTY);
                    if (p.level() instanceof ServerLevel pl) {
                        net.minecraft.world.entity.item.ItemEntity ie = new net.minecraft.world.entity.item.ItemEntity(pl, p.getX(), p.getY() + 0.5, p.getZ(), copy);
                        ie.setPickUpDelay(60);
                        pl.addFreshEntity(ie);
                    }
                    dropped = true;
                } else count += st.getCount();
            }
            if (dropped) {
                Long l = totemMsg.get(p.getUUID());
                if (l == null || now - l > 2000) {
                    totemMsg.put(p.getUUID(), now);
                    say(p, "불사의 토템은 인벤토리(왼손 포함)에 최대 3개까지만 가질 수 있어 초과분을 바닥에 떨어뜨렸습니다. (셜커 상자 안은 제한 없음)", ChatFormatting.RED);
                }
            }
        }
    }

    // ---------- 길들인 늑대 + 늑대 갑옷: 저항 I, 힘 II ----------
    static void tickWolves(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            for (Mob m : p.level().getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(48.0))) {
                if (!typeId(m).equals("minecraft:wolf") || !(m instanceof TamableAnimal ta) || !ta.isTame()) continue;
                if (!m.getItemBySlot(EquipmentSlot.BODY).is(Items.WOLF_ARMOR)) continue;
                m.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 100, 0, true, false, false));
                m.addEffect(new MobEffectInstance(MobEffects.STRENGTH, 100, 1, true, false, false));
            }
        }
    }

    // ---------- 전투(PVP) 중 차원 이동 금지: 이동 직후 원래 위치로 되돌림 ----------
    static void checkDim(ServerPlayer p) {
        LastPos lp = lastPos.get(p.getUUID());
        if (lp == null || lp.dim().equals(dimKey(p.level()))) return;
        String d = lp.dim();
        String id = d.substring(d.lastIndexOf("/ ") + 2, d.length() - 1);
        cmd(server.overworld(), "execute in " + id + " run tp " + p.getName().getString() + " " + lp.x() + " " + lp.y() + " " + lp.z());
        say(p, "PVP 중에는 다른 차원으로 이동할 수 없습니다.", ChatFormatting.RED);
    }

    // ---------- 해피 가스트: 탑승 중 속도 1.5배 ----------
    static void restoreBoost(Boost b) {
        if (b.e instanceof LivingEntity le) {
            var fa = le.getAttribute(Attributes.FLYING_SPEED);
            var ma = le.getAttribute(Attributes.MOVEMENT_SPEED);
            if (fa != null && b.fly >= 0) fa.setBaseValue(b.fly);
            if (ma != null && b.mov >= 0) ma.setBaseValue(b.mov);
        }
    }
    static void restoreGhasts() {
        for (Boost b : ghastBoost.values()) restoreBoost(b);
        ghastBoost.clear();
    }
    static void tickGhasts(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            Entity v = p.getVehicle();
            if (v instanceof LivingEntity le && typeId(v).equals("minecraft:happy_ghast") && !ghastBoost.containsKey(v.getUUID())) {
                var fa = le.getAttribute(Attributes.FLYING_SPEED);
                var ma = le.getAttribute(Attributes.MOVEMENT_SPEED);
                Boost b = new Boost();
                b.e = v;
                if (fa != null) { b.fly = fa.getBaseValue(); fa.setBaseValue(b.fly * 1.5); }
                if (ma != null) { b.mov = ma.getBaseValue(); ma.setBaseValue(b.mov * 1.5); }
                ghastBoost.put(v.getUUID(), b);
            }
        }
        for (Iterator<Map.Entry<UUID, Boost>> it = ghastBoost.entrySet().iterator(); it.hasNext(); ) {
            Boost b = it.next().getValue();
            boolean ridden = false;
            if (!b.e.isRemoved()) for (Entity pa : b.e.getPassengers()) if (pa instanceof ServerPlayer) ridden = true;
            if (!ridden) { restoreBoost(b); it.remove(); }
        }
    }

    // ---------- 모루: "너무 비쌉니다"(40레벨) 해제 + 비용 20, 25, 30 ... ----------
    // 방법: 결과가 비어 있고 비용이 40 이상이면, 아주 짧은 순간만 '제한 없는 재료' 상태로 결과를 다시 계산시킨다.
    static java.lang.reflect.Field anvilCostField;
    static final Map<UUID, Integer> anvilFixed = new HashMap<>();
    static int repairPenalty(ItemStack st) {
        Integer rc = st.get(DataComponents.REPAIR_COST);
        return rc == null ? 0 : rc;
    }
    static void tickAnvil(MinecraftServer s) {
        for (ServerPlayer p : s.getPlayerList().getPlayers()) {
            if (!(p.containerMenu instanceof AnvilMenu am)) { anvilFixed.remove(p.getUUID()); continue; }
            ItemStack left = am.getSlot(0).getItem();
            if (left.isEmpty()) continue;
            boolean capped = !am.getSlot(2).hasItem() && am.getCost() >= 40 && !p.getAbilities().instabuild;
            if (capped) {
                boolean old = p.getAbilities().instabuild;
                p.getAbilities().instabuild = true;
                try { am.slotsChanged(am.getSlot(0).container); }
                finally { p.getAbilities().instabuild = old; }
                anvilFixed.remove(p.getUUID());
            }
            if (!am.getSlot(2).hasItem()) continue;
            int vanilla = am.getCost();
            Integer last = anvilFixed.get(p.getUUID());
            if (last != null && last == vanilla) continue;                      // 이미 우리가 정한 비용
            ItemStack right = am.getSlot(1).getItem();
            int uses = repairPenalty(left) <= 0 ? 0 : 31 - Integer.numberOfLeadingZeros(repairPenalty(left) + 1);
            int base = Math.max(1, vanilla - repairPenalty(left) - repairPenalty(right));
            int fixed = 20 + 5 * uses;                                            // 인챈트 종류와 상관없이 사용 횟수만으로 20, 25, 30...
            if (!am.getSlot(1).hasItem()) fixed = 1;                              // 이름만 바꿀 때(재료 없음)는 1레벨
            try {
                if (anvilCostField == null) { anvilCostField = AnvilMenu.class.getDeclaredField("cost"); anvilCostField.setAccessible(true); }
                ((net.minecraft.world.inventory.DataSlot) anvilCostField.get(am)).set(fixed);
                anvilFixed.put(p.getUUID(), fixed);
            } catch (Exception ex) {
                if (!warnedTasks.contains("anvilcost")) { warnedTasks.add("anvilcost"); System.err.println("[wildrules] anvil cost field: " + ex); }
            }
        }
    }
}
