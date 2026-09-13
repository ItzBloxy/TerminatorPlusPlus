package net.nuggetmc.tplus.agent.legacy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotRegistry;
import net.nuggetmc.tplus.bot.EnemyTarget;
import net.nuggetmc.tplus.event.TerminatorLocateTargetEvent;
import net.nuggetmc.tplus.util.PlayerUtils;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks what a bot should chase. Ported from {@code LegacyAgent.locateTarget},
 * {@code validateCloserEntity} and {@code getWeightedRegionDist}, plus the region accessors.
 */
public final class Targeting {

    private static final Pattern NAME_PATTERN = Pattern.compile("[^A-Za-z]+");

    /**
     * Mob types the {@code CUSTOM_LIST} goal and the custom branches of the hostile, raider and
     * mob goals consider.
     *
     * <p>Always empty in v1: its only writer is {@code BotEnvironmentCommand}, deferred by spec
     * §4.4. An unconfigured list finds nothing, which is exactly what upstream did.
     */
    public static final Set<EntityType<?>> CUSTOM_MOB_LIST = new HashSet<>();

    public static CustomListMode customListMode = CustomListMode.CUSTOM;

    private final BotRegistry registry;

    private TargetGoal goal = TargetGoal.NEAREST_VULNERABLE_PLAYER;
    private @Nullable AABB region;
    private double regionWeightX;
    private double regionWeightY;
    private double regionWeightZ;

    /** Bots that are in the real PlayerList, recomputed once per tick. */
    private List<Bot> botsInPlayerList = List.of();

    public Targeting(BotRegistry registry) {
        this.registry = registry;
    }

    /**
     * Per-tick setup, from upstream's {@code tick()}.
     *
     * <p>Bots that joined the PlayerList look like online players to every player-scanning
     * goal, so they are collected once and excluded. Bots outside the list are invisible to
     * {@code getPlayers()} anyway and never needed excluding.
     */
    public void beginTick() {
        botsInPlayerList = registry.botsView().stream().filter(Bot::isInPlayerList).toList();
    }

    public TargetGoal getTargetType() {
        return goal;
    }

    public void setTargetType(TargetGoal goal) {
        this.goal = goal;
    }

    public void setRegion(@Nullable AABB region, double weightX, double weightY, double weightZ) {
        this.region = region;
        this.regionWeightX = weightX;
        this.regionWeightY = weightY;
        this.regionWeightZ = weightZ;
    }

    public @Nullable AABB getRegion() {
        return region;
    }

    public double getRegionWeightX() {
        return regionWeightX;
    }

    public double getRegionWeightY() {
        return regionWeightY;
    }

    public double getRegionWeightZ() {
        return regionWeightZ;
    }

    public @Nullable LivingEntity locateTarget(Bot bot, Vec3 pos) {
        return locateTarget(bot, pos, goal);
    }

    /**
     * Finds {@code bot}'s target for this tick, or null.
     *
     * <p>Verbatim from upstream's switch, including the shape: every branch scans a candidate
     * set and keeps whichever passes {@link #validateCloserEntity}, and the event is posted once
     * at the end even when nothing was found.
     */
    public @Nullable LivingEntity locateTarget(Bot bot, Vec3 pos, TargetGoal g) {
        ServerLevel level = (ServerLevel) bot.level();
        MinecraftServer server = level.getServer();
        LivingEntity result = null;

        switch (g) {
            case NONE:
                return null;

            case NEAREST_PLAYER: {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!botsInPlayerList.contains(player) && validateCloserEntity(bot, player, pos, result)) {
                        result = player;
                    }
                }
                break;
            }

            case NEAREST_VULNERABLE_PLAYER: {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (!botsInPlayerList.contains(player)
                            && !PlayerUtils.isInvincible(player.gameMode())
                            && validateCloserEntity(bot, player, pos, result)) {
                        result = player;
                    }
                }
                break;
            }

            case NEAREST_HOSTILE: {
                for (LivingEntity entity : livingEntities(level)) {
                    if ((entity instanceof Monster
                            || (customListMode == CustomListMode.HOSTILE
                                    && CUSTOM_MOB_LIST.contains(entity.getType())))
                            && validateCloserEntity(bot, entity, pos, result)) {
                        result = entity;
                    }
                }
                break;
            }

            case NEAREST_RAIDER: {
                for (LivingEntity entity : livingEntities(level)) {
                    boolean raider = entity instanceof Raider
                            || (entity instanceof Vex vex && vex.getOwner() instanceof Raider);

                    if ((raider || (customListMode == CustomListMode.RAIDER
                                    && CUSTOM_MOB_LIST.contains(entity.getType())))
                            && validateCloserEntity(bot, entity, pos, result)) {
                        result = entity;
                    }
                }
                break;
            }

            case NEAREST_MOB: {
                for (LivingEntity entity : livingEntities(level)) {
                    if ((entity instanceof Mob
                            || (customListMode == CustomListMode.MOB
                                    && CUSTOM_MOB_LIST.contains(entity.getType())))
                            && validateCloserEntity(bot, entity, pos, result)) {
                        result = entity;
                    }
                }
                break;
            }

            case NEAREST_BOT: {
                for (Bot other : registry.botsView()) {
                    if (bot != other && validateCloserEntity(bot, other, pos, result)) {
                        result = other;
                    }
                }
                break;
            }

            case NEAREST_BOT_DIFFER: {
                String name = bot.getBotName();

                for (Bot other : registry.botsView()) {
                    if (bot != other && !name.equals(other.getBotName())
                            && validateCloserEntity(bot, other, pos, result)) {
                        result = other;
                    }
                }
                break;
            }

            case NEAREST_BOT_DIFFER_ALPHA: {
                String name = NAME_PATTERN.matcher(bot.getBotName()).replaceAll("");

                for (Bot other : registry.botsView()) {
                    if (bot != other
                            && !name.equals(NAME_PATTERN.matcher(other.getBotName()).replaceAll(""))
                            && validateCloserEntity(bot, other, pos, result)) {
                        result = other;
                    }
                }
                break;
            }

            case CUSTOM_LIST: {
                for (LivingEntity entity : livingEntities(level)) {
                    if (customListMode == CustomListMode.CUSTOM
                            && CUSTOM_MOB_LIST.contains(entity.getType())
                            && validateCloserEntity(bot, entity, pos, result)) {
                        result = entity;
                    }
                }
                break;
            }

            case PLAYER: {
                if (bot.getTargetPlayer() != null) {
                    ServerPlayer player = server.getPlayerList().getPlayer(bot.getTargetPlayer());

                    // Note the `null` fourth argument, not `result`: upstream passed null here
                    // where every other branch passes the incumbent. With one candidate the
                    // distance comparison is skipped entirely, so a PLAYER-goal bot chases its
                    // named player regardless of range. Kept.
                    if (player != null && !botsInPlayerList.contains(player)
                            && validateCloserEntity(bot, player, pos, null)) {
                        result = player;
                    }
                }
                break;
            }

            case ENTITY: {
                EnemyTarget enemy = bot.getEnemyTarget();

                if (!enemy.isEmpty()) {
                    for (LivingEntity entity : livingEntities(level)) {
                        // `bot != entity` or `generic player` makes every bot target itself and
                        // stand still. Other bots are deliberately not excluded: bots are
                        // ServerPlayers, and naming one with `specific` is the point.
                        //
                        // The fourth argument is `result`, not `null` as the PLAYER branch
                        // passes: there can be several candidates here, so the incumbent has to
                        // be compared against. With a set of one the first candidate meets a
                        // null incumbent anyway and range is skipped, so a single specific
                        // target behaves exactly like PLAYER.
                        if (bot != entity
                                && enemy.matches(entity.getType(), entity.getUUID())
                                && validateCloserEntity(bot, entity, pos, result)) {
                            result = entity;
                        }
                    }
                }

                break;
            }
        }

        TerminatorLocateTargetEvent event = new TerminatorLocateTargetEvent(bot, result);
        NeoForge.EVENT_BUS.post(event);

        return event.isCanceled() ? null : event.getTarget();
    }

    /**
     * Every living entity in the level.
     *
     * <p>Upstream called {@code world.getLivingEntities()}. Vanilla has no level-wide list, so
     * this is the type-test form. It is the same O(all entities) scan upstream did, once per bot
     * per tick for the mob goals — upstream's own header comment on that file reads "Yes, this
     * code is very unoptimized, I know."
     */
    private static List<? extends LivingEntity> livingEntities(ServerLevel level) {
        return level.getEntities(EntityTypeTest.forClass(LivingEntity.class), e -> true);
    }

    /**
     * Whether {@code entity} is a better target than {@code incumbent}.
     *
     * <p>Verbatim, including the parenthesisation of the distance comparison — upstream wrote
     * {@code (a + regionA) < (b) + regionB}, which is the same arithmetic and is left as-is so
     * a diff against {@code master} stays clean.
     */
    private boolean validateCloserEntity(Bot bot, LivingEntity entity, Vec3 pos,
                                         @Nullable LivingEntity incumbent) {
        double regionDistEntity = weightedRegionDist(region, entity.position(),
                regionWeightX, regionWeightY, regionWeightZ);

        if (regionDistEntity == Double.MAX_VALUE) {
            return false;
        }

        double regionDistResult = incumbent == null ? 0 : weightedRegionDist(region,
                incumbent.position(), regionWeightX, regionWeightY, regionWeightZ);

        return bot.level() == entity.level() && entity.isAlive()
                && (incumbent == null
                        || (pos.distanceToSqr(entity.position()) + regionDistEntity)
                                < pos.distanceToSqr(incumbent.position()) + regionDistResult);
    }

    /**
     * How far outside {@code region} {@code pos} is, weighted per axis and squared.
     *
     * <p>Ported from {@code getWeightedRegionDist}. Two rules, both upstream's: inside the
     * region the penalty is zero, and if <b>all three</b> weights are zero the region becomes a
     * hard boundary — anything outside returns {@code Double.MAX_VALUE}, which
     * {@link #validateCloserEntity} reads as "not a candidate".
     *
     * <p>Static and parameterised so it can be unit tested without a level.
     */
    public static double weightedRegionDist(@Nullable AABB region, Vec3 pos,
                                            double weightX, double weightY, double weightZ) {
        if (region == null) {
            return 0;
        }

        Vec3 centre = region.getCenter();

        double diffX = Math.max(0, Math.abs(centre.x - pos.x) - region.getXsize() * 0.5);
        double diffY = Math.max(0, Math.abs(centre.y - pos.y) - region.getYsize() * 0.5);
        double diffZ = Math.max(0, Math.abs(centre.z - pos.z) - region.getZsize() * 0.5);

        if (weightX == 0 && weightY == 0 && weightZ == 0) {
            if (diffX > 0 || diffY > 0 || diffZ > 0) {
                return Double.MAX_VALUE;
            }
        }

        return diffX * diffX * weightX + diffY * diffY * weightY + diffZ * diffZ * weightZ;
    }
}
