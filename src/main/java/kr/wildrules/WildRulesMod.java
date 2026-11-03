package kr.wildrules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
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
    static final double DETECT_RANGE = 150.0;     // 감지 범위(칸)
    static final int MIN_STREAK = 2;              // 이 연속킬 이상이면 감지 대상
    static final double BUFF_RANGE = 50.0;        // 주인 버프 범위(칸)
    static final long BUFF_MILLIS = 10L * 60L * 1000L; // 광물 1개당 버프 지속(현실 시간 10분)
    static final String STREAK_OBJECTIVE = "wr_streak"; // 데이터팩의 연속킬 점수판

    // ---- 저장 데이터 ----
    static class Rec {
        String dim; int x, y, z; String owner; String ownerName;
        Map<String, Long> until = new HashMap<>();
    }
    static class Data {
        Map<String, Integer> placed = new HashMap<>();
        List<Rec> beacons = new ArrayList<>();
    }
    static class Pending { String dim; BlockPos pos; UUID uuid; String name; int age; }

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    static Data data = new Data();
    static Path file;
    static MinecraftServer server;
    static final List<Pending> pending = new ArrayList<>();
    static final Map<String, Set<UUID>> flagged = new HashMap<>();

    @Override
    public void onInitialize() {
        file = FabricLoader.getInstance().getConfigDir().resolve("wildrules-beacons.json");
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { server = s; load(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> save());
        UseBlockCallback.EVENT.register(WildRulesMod::onUse);
        ServerTickEvents.END_SERVER_TICK.register(WildRulesMod::onTick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(WildRulesMod::allowDamage);
    }

    // ---------- 저장/불러오기 ----------
    static void load() {
        try {
            if (Files.exists(file)) {
                Data d = GSON.fromJson(Files.readString(file), Data.class);
                if (d != null) data = d;
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
            if (data.placed.getOrDefault(id, 0) >= MAX_BEACONS) {
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
        String key = ingotKey(held);
        long now = System.currentTimeMillis();
        if (key != null) {
            if (!sp.getAbilities().instabuild) held.shrink(1);
            long base = Math.max(now, r.until.getOrDefault(key, 0L));
            r.until.put(key, base + BUFF_MILLIS);
            save();
            say(sp, "신호기 버프 충전: " + ingotLabel(key) + " (+10분)", ChatFormatting.GREEN);
            return;
        }
        StringBuilder sb = new StringBuilder("신호기 버프 남은 시간: ");
        boolean any = false;
        for (String k : List.of("iron", "gold", "emerald", "diamond", "netherite")) {
            long left = r.until.getOrDefault(k, 0L) - now;
            if (left > 0) { sb.append(ingotLabel(k)).append(" ").append(left / 60000L).append("분  "); any = true; }
        }
        if (!any) sb.append("없음 (철/금/에메랄드/다이아/네더라이트를 우클릭으로 넣으세요)");
        say(sp, sb.toString(), ChatFormatting.AQUA);
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
                int n = data.placed.merge(p.uuid.toString(), 1, Integer::sum);
                save();
                ServerPlayer pl = s.getPlayerList().getPlayer(p.uuid);
                if (pl != null) say(pl, "신호기 설치 (" + n + "/" + MAX_BEACONS + ")", ChatFormatting.YELLOW);
                done = true;
            }
            if (done || ++p.age > 3) it.remove();
        }
        if (s.getTickCount() % 10 != 0) return;
        long now = System.currentTimeMillis();

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

            // (1) 주인 버프: 주인이 근처에 있을 때만
            if (dimKey(owner.level()).equals(r.dim) && owner.distanceToSqr(r.x + 0.5, r.y + 0.5, r.z + 0.5) <= BUFF_RANGE * BUFF_RANGE) {
                for (Map.Entry<String, Long> e : r.until.entrySet()) {
                    if (e.getValue() > now) {
                        Holder<MobEffect> eff = effectOf(e.getKey());
                        if (eff != null) owner.addEffect(new MobEffectInstance(eff, 60, 0, true, false, true));
                    }
                }
            }

            // (2) 연속킬 MIN_STREAK 이상 플레이어 감지 (주인에게만 알림/표시)
            Set<UUID> prev = flagged.getOrDefault(key, new HashSet<>());
            Set<UUID> cur = new HashSet<>();
            for (ServerPlayer t : s.getPlayerList().getPlayers()) {
                if (t.getUUID().equals(owner.getUUID()) || t.isSpectator()) continue;
                if (!dimKey(t.level()).equals(r.dim)) continue;
                if (t.distanceToSqr(r.x + 0.5, r.y + 0.5, r.z + 0.5) > DETECT_RANGE * DETECT_RANGE) continue;
                if (streak(s, t) < MIN_STREAK) continue;
                cur.add(t.getUUID());
                if (!prev.contains(t.getUUID())) {
                    say(owner, t.getName().getString() + " 님이 신호기 범위(" + (int) DETECT_RANGE + "칸)에 들어왔습니다.", ChatFormatting.RED);
                }
                if (dimKey(owner.level()).equals(r.dim) && owner.level() instanceof ServerLevel ol) {
                    ol.sendParticles(owner, ParticleTypes.ANGRY_VILLAGER, true, true,
                            t.getX(), t.getY() + 2.3, t.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
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
    static boolean allowDamage(LivingEntity victim, DamageSource source, float amount) {
        if (server == null || !(victim instanceof ServerPlayer v)) return true;
        Entity a = source.getEntity();                 // 투사체면 쏜 사람
        if (!(a instanceof ServerPlayer att) || att == v) return true;   // 몹/환경 피해는 그대로
        if (score(server, v, "wr_prot") > 0) return false;    // 맞는 사람이 무적
        if (score(server, att, "wr_prot") > 0) return false;  // 때리는 사람이 무적
        int banEnd = score(server, att, "wr_banend");          // 패널티 PvP 금지
        if (banEnd > 0 && banEnd > server.overworld().getGameTime()) return false;
        return true;
    }
}
