package com.goalplanner.util;

/**
 * Decides whether the loaded goal set may be tracked against the logged-in
 * account.
 *
 * <p><b>The problem.</b> Goals are stored per config profile, but a profile is
 * only a file - nothing guarantees it matches whoever is at the keyboard.
 * Trackers score whatever goals are in memory against whatever account is live,
 * and completion is terminal for auto-tracked types, so one drain with the
 * wrong pairing permanently completes every goal the other account satisfies.
 * Config sync then carries that to the user's other machines.
 *
 * <p><b>The gate.</b> A goal set is bound to an account hash on first tracked
 * login. Tracking proceeds only while the live hash matches. An unbound set
 * adopts the current account, so existing users migrate with no action.
 *
 * <p><b>Pure by design.</b> No RuneLite dependencies - the caller passes the
 * live and bound hashes in. This mirrors {@link SkillSyncGate} and exists for
 * the same reason: the decision logic is the part that can silently disable
 * itself or spam the chat box, so it has to be testable without a client.
 */
public final class AccountBindingGate
{
	/** What the caller should do with the current (live, bound) pairing. */
	public enum Decision
	{
		/** Hashes match - track normally. */
		TRACK,
		/** No binding yet - claim this account and track. */
		ADOPT,
		/** Bound to a different account - do not track. */
		PAUSE,
		/** Logged out or no readable account - nothing to track against. */
		NO_ACCOUNT
	}

	private boolean mismatchAnnounced = false;

	/**
	 * Classify a pairing. Called on every tracker drain, so it must be cheap and
	 * side-effect-free beyond the announce latch.
	 *
	 * @param liveAccountHash  the logged-in account, or any non-positive value
	 *                         when logged out. Deliberately not just {@code -1}:
	 *                         the guard must not depend on which sentinel the
	 *                         client uses, because falling through would unbind
	 *                         the goal set and re-enable the very bug this
	 *                         prevents.
	 * @param boundAccountHash the account this goal set belongs to, or 0 if unbound
	 */
	public Decision evaluate(long liveAccountHash, long boundAccountHash)
	{
		if (liveAccountHash <= 0L)
		{
			// Logged out is not a mismatch - leave the latch alone so logging
			// out and back in on the wrong account does not re-announce.
			return Decision.NO_ACCOUNT;
		}
		if (boundAccountHash == 0L)
		{
			mismatchAnnounced = false;
			return Decision.ADOPT;
		}
		if (boundAccountHash == liveAccountHash)
		{
			mismatchAnnounced = false;
			return Decision.TRACK;
		}
		return Decision.PAUSE;
	}

	/**
	 * True exactly once per mismatch episode, so the warning does not repeat on
	 * every game tick. Resets once a matching account is seen, so a later
	 * mismatch is announced again rather than silently swallowed.
	 */
	public boolean shouldAnnounceMismatch()
	{
		if (mismatchAnnounced)
		{
			return false;
		}
		mismatchAnnounced = true;
		return true;
	}

	/** Whether tracking may proceed for this decision. */
	public static boolean allowsTracking(Decision decision)
	{
		return decision == Decision.TRACK || decision == Decision.ADOPT;
	}
}
