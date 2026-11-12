package dev.anchorlight.blockvault.bonus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The weekly top-contributor payout, as a pure function of the week's
 * {@link BlockSnapshot}s. No Bukkit, no SQL - so the cap, the biggest-lead
 * selection and the featured-block rules can be unit-tested directly.
 *
 * <p>Rules (see the spec):
 * <ul>
 *   <li>The top contributor on each unlocked block earns {@code pointsPerBlock}.</li>
 *   <li>A player is paid for at most {@code capBlocks} non-featured blocks per
 *       payout. If they lead more, the paid ones are those where their lead over
 *       second place is biggest; the rest pay nothing.</li>
 *   <li>Featured blocks pay {@code featuredPoints} and do <em>not</em> count
 *       toward the cap.</li>
 *   <li>The cap applies to everyone, including the overall leader - it falls out
 *       of counting blocks per player, with no special case.</li>
 * </ul>
 */
public final class WeeklyBonus {

    private WeeklyBonus() {}

    /**
     * @param lines         every paid line (points &gt; 0), featured first then by
     *                      descending lead
     * @param cappedPlayers players who led more than {@code capBlocks} non-featured
     *                      blocks and so left some unpaid
     */
    public record Result(List<PayLine> lines, Set<UUID> cappedPlayers) {}

    public static Result award(List<BlockSnapshot> snapshots, int pointsPerBlock,
                               int featuredPoints, int capBlocks) {
        Map<UUID, List<BlockSnapshot>> byPlayer = new LinkedHashMap<>();
        for (BlockSnapshot s : snapshots) {
            if (s.top() == null) continue; // nobody has given this block yet
            byPlayer.computeIfAbsent(s.top(), k -> new ArrayList<>()).add(s);
        }

        List<PayLine> lines = new ArrayList<>();
        Set<UUID> capped = new LinkedHashSet<>();

        for (var entry : byPlayer.entrySet()) {
            UUID uuid = entry.getKey();
            List<BlockSnapshot> featured = new ArrayList<>();
            List<BlockSnapshot> normal = new ArrayList<>();
            for (BlockSnapshot s : entry.getValue()) {
                (s.featured() ? featured : normal).add(s);
            }

            // Featured blocks always pay and never count toward the cap.
            for (BlockSnapshot s : featured) {
                lines.add(new PayLine(uuid, s.material(), featuredPoints, true, s.margin()));
            }

            // Biggest lead first; material name breaks ties so the choice is stable.
            normal.sort(Comparator.comparingLong(BlockSnapshot::margin).reversed()
                    .thenComparing(BlockSnapshot::material));
            if (normal.size() > capBlocks) capped.add(uuid);
            for (int i = 0; i < normal.size() && i < capBlocks; i++) {
                BlockSnapshot s = normal.get(i);
                lines.add(new PayLine(uuid, s.material(), pointsPerBlock, false, s.margin()));
            }
        }
        return new Result(lines, capped);
    }
}
