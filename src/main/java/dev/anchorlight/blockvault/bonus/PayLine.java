package dev.anchorlight.blockvault.bonus;

import java.util.UUID;

/**
 * One paid line of a weekly payout: a player earned {@code points} for being the
 * top contributor on {@code material} this week. {@code featured} marks the
 * featured-block bonus; {@code margin} is their lead over second place, carried
 * through for the audit log.
 */
public record PayLine(UUID uuid, String material, int points, boolean featured, long margin) {}
