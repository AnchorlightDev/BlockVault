package dev.anchorlight.blockvault.bonus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TieBreakTest {

    private static final UUID EARLY = new UUID(0, 1);
    private static final UUID LATE = new UUID(0, 2);
    private static final UUID BIG = new UUID(0, 3);

    @Test
    void equalAmountGoesToWhoReachedItFirst() {
        var early = new TopContributorRanker.Standing(EARLY, 64, 1_000L);
        var late = new TopContributorRanker.Standing(LATE, 64, 2_000L);
        assertEquals(EARLY, TopContributorRanker.top(List.of(late, early)).uuid(),
                "same quantity -> earliest timestamp wins");
    }

    @Test
    void moreGivenBeatsAnEarlierTimestamp() {
        var early = new TopContributorRanker.Standing(EARLY, 64, 1_000L);
        var big = new TopContributorRanker.Standing(BIG, 100, 9_000L);
        assertEquals(BIG, TopContributorRanker.top(List.of(early, big)).uuid(),
                "quantity dominates; the tie-break only applies when quantities are equal");
    }

    @Test
    void rankedOrdersByQuantityThenTime() {
        var a = new TopContributorRanker.Standing(BIG, 100, 5_000L);
        var b = new TopContributorRanker.Standing(EARLY, 64, 1_000L);
        var c = new TopContributorRanker.Standing(LATE, 64, 2_000L);
        var ranked = TopContributorRanker.ranked(List.of(c, a, b));
        assertEquals(List.of(BIG, EARLY, LATE), ranked.stream().map(TopContributorRanker.Standing::uuid).toList());
    }

    @Test
    void exactTieFallsBackToUuidByteOrderLikeMysql() {
        // MySQL orders BINARY(16) as unsigned bytes: 0x00.. sorts before 0xFF..
        // (UUID.compareTo, which is signed, would put the 0xFF.. one first).
        java.util.UUID low = new java.util.UUID(1L, 0L);
        java.util.UUID high = new java.util.UUID(-1L, 0L);
        var a = new TopContributorRanker.Standing(high, 64, 1_000L);
        var b = new TopContributorRanker.Standing(low, 64, 1_000L);
        assertEquals(low, TopContributorRanker.top(List.of(a, b)).uuid());
    }
}
