package dev.anchorlight.blockvault.bonus;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyBonusTest {

    private static final UUID P = new UUID(0, 1);
    private static final UUID Q = new UUID(0, 2);

    private static BlockSnapshot block(String name, UUID top, long margin, boolean featured) {
        // topQty = margin, secondQty = 0 -> margin() == margin
        return new BlockSnapshot(name, top, margin, 0, featured);
    }

    @Test
    void capsAtTenNonFeaturedBlocks() {
        List<BlockSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 12; i++) snaps.add(block("m" + i, P, i, false));

        WeeklyBonus.Result r = WeeklyBonus.award(snaps, 2, 4, 10);

        List<PayLine> mine = linesFor(r, P);
        assertEquals(10, mine.size(), "only 10 of 12 blocks are paid");
        assertEquals(20, totalPoints(r, P), "10 blocks x 2 points = 20 (the weekly max)");
        assertTrue(r.cappedPlayers().contains(P), "player led more than the cap");
    }

    @Test
    void paysTheTenBiggestLeads() {
        List<BlockSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 12; i++) snaps.add(block("m" + i, P, i, false)); // margins 1..12

        WeeklyBonus.Result r = WeeklyBonus.award(snaps, 2, 4, 10);

        Set<String> paid = linesFor(r, P).stream().map(PayLine::material).collect(Collectors.toSet());
        assertFalse(paid.contains("m1"), "smallest lead is dropped");
        assertFalse(paid.contains("m2"), "second smallest lead is dropped");
        assertTrue(paid.contains("m3") && paid.contains("m12"), "the 10 biggest leads are paid");
    }

    @Test
    void featuredBlockPaysFourAndIsOutsideTheCap() {
        List<BlockSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 10; i++) snaps.add(block("n" + i, P, 5, false)); // exactly the cap
        snaps.add(block("featured", P, 2, true));                             // plus a featured block

        WeeklyBonus.Result r = WeeklyBonus.award(snaps, 2, 4, 10);

        assertEquals(11, linesFor(r, P).size(), "all 10 normal + the featured block are paid");
        assertEquals(20 + 4, totalPoints(r, P), "10x2 plus featured 4; featured does not displace a normal block");
        assertFalse(r.cappedPlayers().contains(P), "featured blocks do not count toward the cap");
        PayLine feat = linesFor(r, P).stream().filter(PayLine::featured).findFirst().orElseThrow();
        assertEquals(4, feat.points());
    }

    @Test
    void capAppliesToEveryPlayerIndependently() {
        List<BlockSnapshot> snaps = new ArrayList<>();
        for (int i = 1; i <= 11; i++) snaps.add(block("p" + i, P, i, false));
        for (int i = 1; i <= 3; i++) snaps.add(block("q" + i, Q, i, false));

        WeeklyBonus.Result r = WeeklyBonus.award(snaps, 2, 4, 10);

        assertEquals(20, totalPoints(r, P), "overall leader is capped like everyone");
        assertTrue(r.cappedPlayers().contains(P));
        assertEquals(6, totalPoints(r, Q), "a player under the cap is paid for all their blocks");
        assertFalse(r.cappedPlayers().contains(Q));
    }

    @Test
    void marginTieIsBrokenDeterministicallyByMaterial() {
        // Two blocks, same lead, cap of 1 -> the lower material name wins, every run.
        List<BlockSnapshot> snaps = List.of(block("banana", P, 5, false), block("apple", P, 5, false));

        WeeklyBonus.Result r1 = WeeklyBonus.award(snaps, 2, 4, 1);
        WeeklyBonus.Result r2 = WeeklyBonus.award(snaps, 2, 4, 1);

        assertEquals("apple", linesFor(r1, P).get(0).material());
        assertEquals(linesFor(r1, P).get(0).material(), linesFor(r2, P).get(0).material());
    }

    private static List<PayLine> linesFor(WeeklyBonus.Result r, UUID who) {
        return r.lines().stream().filter(l -> l.uuid().equals(who)).collect(Collectors.toList());
    }

    private static int totalPoints(WeeklyBonus.Result r, UUID who) {
        return linesFor(r, who).stream().mapToInt(PayLine::points).sum();
    }
}
