package com.goalplanner.tracker;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.model.AccountMetric;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarbitID;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.function.LongSupplier;

/**
 * Tracks account-wide goals: quest points, combat level, total level,
 * CA points, slayer points, museum kudos.
 */
@Slf4j
@Singleton
public class AccountTracker extends AbstractTracker
{
	/**
	 * How long after a login a below-target read is distrusted for a decaying
	 * metric. The varp/varbit block lands a moment AFTER the client reports
	 * LOGGED_IN - MISC_APPROVAL demonstrably reads 0 first and then jumps to
	 * its real value (the same sync jump {@link MiscellaniaAutoGoal} has to
	 * ignore). Without this window, every login would briefly re-open a
	 * completed favour goal and then re-complete it with a fresh date.
	 *
	 * <p>Only re-opening waits; completions are unaffected, and the window is
	 * over long before a player could change a tracked value.
	 */
	static final long LOGIN_SETTLE_MS = 5_000;

	private final LongSupplier clock;

	/** Wall-clock time from which a below-target read may re-open a completed
	 *  decaying goal. 0 = no login seen yet (tests, or the plugin starting up
	 *  while already logged in), which is treated as settled. */
	private long reopenTrustedFrom = 0;

	@Inject
	public AccountTracker(Client client, GoalPlannerApiImpl api)
	{
		this(client, api, System::currentTimeMillis);
	}

	/** Test seam: inject the clock backing the post-login settle window. */
	AccountTracker(Client client, GoalPlannerApiImpl api,
		LongSupplier clock)
	{
		super(client, api);
		this.clock = clock;
	}

	/**
	 * Arm the post-login settle window. Called on GameState LOGGED_IN, the same
	 * hook that re-arms the Miscellania favour-gain detection.
	 */
	public void onLogin()
	{
		reopenTrustedFrom = clock.getAsLong() + LOGIN_SETTLE_MS;
	}

	@Override
	protected GoalType targetType()
	{
		return GoalType.ACCOUNT;
	}

	@Override
	protected boolean shouldTrack(Goal goal)
	{
		return goal.getAccountMetric() != null;
	}

	/**
	 * Keep reading a COMPLETE goal when its metric can lose value, so
	 * completion follows the live varbit in both directions - the user's rule
	 * for Miscellania favour: "it should be based absolutely on the varbit".
	 *
	 * <p>This also settles the manual-completion question for account goals:
	 * the live value always wins. A user cannot mark an ACCOUNT goal complete
	 * by hand at all ({@code markGoalComplete} accepts CUSTOM/ITEM_GRIND only),
	 * and marking one incomplete is the documented wrong-account recovery path,
	 * where re-deriving from the client is exactly the intent. There is no
	 * flip-flop: once re-opened the goal sits at its live value, and
	 * {@code recordGoalProgress} no-ops while neither the value nor the
	 * completion state disagrees with the target.
	 */
	@Override
	protected boolean tracksAfterCompletion(Goal goal)
	{
		AccountMetric metric = AccountMetric.parse(goal.getAccountMetric());
		return metric != null && metric.decays()
			&& clock.getAsLong() >= reopenTrustedFrom;
	}

	@Override
	protected int readCurrentValue(Goal goal)
	{
		AccountMetric metric;
		try
		{
			metric = AccountMetric.valueOf(goal.getAccountMetric());
		}
		catch (IllegalArgumentException e)
		{
			log.warn("Unknown account metric: {}", goal.getAccountMetric());
			return -1;
		}
		return readMetric(metric);
	}

	private int readMetric(AccountMetric metric)
	{
		// Leagues-specific metrics only have meaningful values on leagues accounts.
		// On main worlds the varbits return 0, which would otherwise overwrite
		// prior leagues progress. Return -1 (skip) so the tracker leaves the
		// stored value alone. The profile-scoped store also keeps these goals
		// out of the main profile, so this is a belt-and-suspenders check.
		if (metric.isLeagues())
		{
			int leagueAccount = client.getVarbitValue(VarbitID.LEAGUE_ACCOUNT);
			boolean seasonal = client.getWorldType() != null
				&& client.getWorldType().contains(net.runelite.api.WorldType.SEASONAL);
			if (leagueAccount == 0 && !seasonal) return -1;
		}

		// The per-metric live reads live on AccountMetric (shared with the
		// requirement resolvers); the leagues guard above is tracker policy.
		return metric.currentValue(client);
	}
}
