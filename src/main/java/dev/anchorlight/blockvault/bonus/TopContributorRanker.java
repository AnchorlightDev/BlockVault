package dev.anchorlight.blockvault.bonus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The single rule for "who is the top contributor of a block": most given, and
 * on a tie whoever reached that amount first (earliest timestamp). This mirrors
 * the SQL {@code TOP_ORDER} (`quantity DESC, last_at ASC, uuid ASC`) that the database
 * uses on the live path; keeping it here as a plain {@link Comparator} lets the
 * tie-break be unit-tested without a database.
 */
public final class TopContributorRanker {

    private TopContributorRanker() {}

    /**
     * @param reachedAtMillis when the player reached {@link #quantity} - the time
     *                        of the donation that brought them to their current
     *                        total (monotonic, so it is when they reached it)
     */
    public record Standing(UUID uuid, long quantity, long reachedAtMillis) {}

    /**
     * Most given first; ties to the earliest to reach that amount; then uuid, for
     * stability. The uuid step compares as unsigned bytes, matching MySQL's
     * ordering of the BINARY(16) column in {@code TOP_ORDER} ({@code d.uuid ASC}),
     * which {@link UUID#compareTo} (signed) would not.
     */
    public static final Comparator<Standing> ORDER =
            Comparator.comparingLong(Standing::quantity).reversed()
                    .thenComparingLong(Standing::reachedAtMillis)
                    .thenComparing(Standing::uuid, Comparator.nullsLast(TopContributorRanker::compareBytes));

    /** Unsigned big-endian comparison - the order of the uuid's 16 stored bytes. */
    static int compareBytes(UUID a, UUID b) {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    /** The top contributor among {@code standings}, or null if empty. */
    public static Standing top(Collection<Standing> standings) {
        return standings.stream().min(ORDER).orElse(null);
    }

    /** {@code standings} sorted from top contributor downward. */
    public static List<Standing> ranked(Collection<Standing> standings) {
        List<Standing> list = new ArrayList<>(standings);
        list.sort(ORDER);
        return list;
    }
}
