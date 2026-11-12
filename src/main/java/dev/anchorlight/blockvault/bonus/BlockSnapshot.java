package dev.anchorlight.blockvault.bonus;

import java.util.UUID;

/**
 * One block's standing at the moment a weekly payout is taken: its current top
 * contributor, how much they have given, and how much second place has given.
 * {@code featured} marks the week's featured block(s). Pure data - no Bukkit or
 * SQL - so the payout logic in {@link WeeklyBonus} can be unit-tested directly.
 *
 * @param material   the block's registry id
 * @param top        the current top contributor, or null if nobody has given it
 * @param topQty     how much the top contributor has given
 * @param secondQty  how much second place has given (0 if there is no second)
 * @param featured   true if this block is featured this week
 */
public record BlockSnapshot(String material, UUID top, long topQty, long secondQty, boolean featured) {

    /** The top contributor's lead over second place - how the paid 10 are chosen. */
    public long margin() {
        return topQty - secondQty;
    }
}
