package dev.anchorlight.blockvault.bonus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Picks the week's featured block(s) from the unlocked floors, favouring blocks
 * with the fewest contributions so more of the vault gets filled in. Pure - the
 * {@link Random} is injected so the choice is deterministic in tests.
 */
public final class FeaturedPicker {

    private FeaturedPicker() {}

    /**
     * A block that could be featured and its contribution weight (e.g. number of
     * donors, or total items given). Lower weight = picked sooner.
     */
    public record Candidate(String material, long weight) {}

    /**
     * Choose up to {@code count} blocks, preferring the lowest weight, excluding
     * anything in {@code exclude} (e.g. last week's pick). Among blocks of equal
     * lowest weight the pick is random (seeded by {@code random}).
     */
    public static List<String> pick(List<Candidate> candidates, Set<String> exclude,
                                    int count, Random random) {
        List<Candidate> pool = new ArrayList<>();
        for (Candidate c : candidates) {
            if (exclude == null || !exclude.contains(c.material())) pool.add(c);
        }
        // Shuffle first, then a STABLE sort by weight: random order survives within
        // each weight tier, so ties among the least-contributed blocks are random.
        Collections.shuffle(pool, random);
        pool.sort(Comparator.comparingLong(Candidate::weight));

        List<String> out = new ArrayList<>();
        for (int i = 0; i < pool.size() && i < count; i++) {
            out.add(pool.get(i).material());
        }
        return out;
    }
}
