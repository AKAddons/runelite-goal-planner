package com.goalplanner.util;

import com.goalplanner.util.AccountBindingGate.Decision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision logic behind "may these goals be tracked against this account?".
 *
 * <p>This is the half that can fail silently: a gate that answers TRACK when it
 * should PAUSE re-enables the bug it was written to prevent, and one that
 * announces on every tick spams the chat box. Neither is visible from the store
 * tests, which only cover persistence.
 */
class AccountBindingGateTest
{
	private static final long MAIN = 111_111L;
	private static final long ALT = 222_222L;
	private static final long UNBOUND = 0L;

	@Nested
	@DisplayName("decisions")
	class Decisions
	{
		@Test
		@DisplayName("an unbound goal set adopts whoever is logged in")
		void unboundAdopts()
		{
			assertEquals(Decision.ADOPT, new AccountBindingGate().evaluate(MAIN, UNBOUND));
		}

		@Test
		@DisplayName("a matching account tracks")
		void matchTracks()
		{
			assertEquals(Decision.TRACK, new AccountBindingGate().evaluate(MAIN, MAIN));
		}

		@Test
		@DisplayName("a different account pauses - the whole point of the gate")
		void mismatchPauses()
		{
			assertEquals(Decision.PAUSE, new AccountBindingGate().evaluate(ALT, MAIN));
		}

		@Test
		@DisplayName("any non-positive live hash is 'no account', whatever sentinel the client uses")
		void nonPositiveIsNoAccount()
		{
			AccountBindingGate gate = new AccountBindingGate();
			for (long sentinel : new long[]{-1L, 0L, Long.MIN_VALUE})
			{
				assertEquals(Decision.NO_ACCOUNT, gate.evaluate(sentinel, MAIN),
					"sentinel " + sentinel + " must not fall through");
				assertEquals(Decision.NO_ACCOUNT, gate.evaluate(sentinel, UNBOUND),
					"and must never trigger adoption, which would unbind the goal set");
			}
		}

		@Test
		@DisplayName("only TRACK and ADOPT permit tracking")
		void allowsTracking()
		{
			assertTrue(AccountBindingGate.allowsTracking(Decision.TRACK));
			assertTrue(AccountBindingGate.allowsTracking(Decision.ADOPT));
			assertFalse(AccountBindingGate.allowsTracking(Decision.PAUSE));
			assertFalse(AccountBindingGate.allowsTracking(Decision.NO_ACCOUNT));
		}
	}

	@Nested
	@DisplayName("announcing a mismatch")
	class Announcing
	{
		@Test
		@DisplayName("announces once, then stays quiet across repeated drains")
		void announcesOnce()
		{
			AccountBindingGate gate = new AccountBindingGate();
			gate.evaluate(ALT, MAIN);

			assertTrue(gate.shouldAnnounceMismatch(), "the user must be told once");
			// A drain runs every tick; without the latch this is chat spam.
			for (int tick = 0; tick < 100; tick++)
			{
				gate.evaluate(ALT, MAIN);
				assertFalse(gate.shouldAnnounceMismatch(),
					"repeated at tick " + tick + " - the warning must not repeat per tick");
			}
		}

		@Test
		@DisplayName("a later mismatch announces again after the account comes back into line")
		void reAnnouncesAfterRecovery()
		{
			AccountBindingGate gate = new AccountBindingGate();
			gate.evaluate(ALT, MAIN);
			assertTrue(gate.shouldAnnounceMismatch());

			// User switches to the right profile.
			assertEquals(Decision.TRACK, gate.evaluate(MAIN, MAIN));

			// ...and later gets it wrong again. Silence here would hide a real problem.
			assertEquals(Decision.PAUSE, gate.evaluate(ALT, MAIN));
			assertTrue(gate.shouldAnnounceMismatch(), "a fresh mismatch is fresh news");
		}

		@Test
		@DisplayName("logging out does not clear the latch, so a relog on the wrong account stays quiet")
		void logoutDoesNotResetLatch()
		{
			AccountBindingGate gate = new AccountBindingGate();
			gate.evaluate(ALT, MAIN);
			assertTrue(gate.shouldAnnounceMismatch());

			// Log out, log back in on the same wrong account.
			assertEquals(Decision.NO_ACCOUNT, gate.evaluate(-1L, MAIN));
			assertEquals(Decision.PAUSE, gate.evaluate(ALT, MAIN));

			assertFalse(gate.shouldAnnounceMismatch(),
				"relogging on the same wrong account is the same episode, not a new one");
		}

		@Test
		@DisplayName("adoption clears the latch, so a rebound set can warn again later")
		void adoptionResetsLatch()
		{
			AccountBindingGate gate = new AccountBindingGate();
			gate.evaluate(ALT, MAIN);
			assertTrue(gate.shouldAnnounceMismatch());

			assertEquals(Decision.ADOPT, gate.evaluate(ALT, UNBOUND));
			assertEquals(Decision.PAUSE, gate.evaluate(MAIN, ALT));
			assertTrue(gate.shouldAnnounceMismatch());
		}
	}
}
