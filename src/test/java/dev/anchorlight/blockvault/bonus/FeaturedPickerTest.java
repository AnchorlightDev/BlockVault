package dev.anchorlight.blockvault.bonus;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FeaturedPickerTest {

    private static final List<FeaturedPicker.Candidate> POOL = List.of(
            new FeaturedPicker.Candidate("apple", 0),
            new FeaturedPicker.Candidate("banana", 5),
            new FeaturedPicker.Candidate("cherry", 3));

    @Test
    void favoursTheLeastContributedBlock() {
        List<String> pick = FeaturedPicker.pick(POOL, Set.of(), 1, new Random(1));
        assertEquals(List.of("apple"), pick, "weight 0 is picked before weight 3 or 5");
    }

    @Test
    void excludesLastWeeksPick() {
        List<String> pick = FeaturedPicker.pick(POOL, Set.of("apple"), 1, new Random(1));
        assertEquals(List.of("cherry"), pick, "next lowest weight after the excluded block");
        assertFalse(pick.contains("apple"));
    }

    @Test
    void picksSeveralWhenCountIsAboveOne() {
        List<String> pick = FeaturedPicker.pick(POOL, Set.of(), 2, new Random(1));
        assertEquals(List.of("apple", "cherry"), pick, "two lowest weights, in order");
    }

    @Test
    void isDeterministicForAGivenSeedWhenWeightsTie() {
        List<FeaturedPicker.Candidate> flat = List.of(
                new FeaturedPicker.Candidate("a", 0),
                new FeaturedPicker.Candidate("b", 0),
                new FeaturedPicker.Candidate("c", 0));
        List<String> first = FeaturedPicker.pick(flat, Set.of(), 1, new Random(42));
        List<String> again = FeaturedPicker.pick(flat, Set.of(), 1, new Random(42));
        assertEquals(first, again, "same seed -> same pick among equal-weight blocks");
    }

    @Test
    void returnsAllWhenFewerCandidatesThanCount() {
        List<String> pick = FeaturedPicker.pick(POOL, Set.of("banana", "cherry"), 5, new Random(1));
        assertEquals(List.of("apple"), pick);
    }
}
