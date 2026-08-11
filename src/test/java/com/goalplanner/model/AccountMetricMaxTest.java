package com.goalplanner.model;

import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AccountMetric.effectiveMaxTarget — the live collection-log slot ceiling,
 * and the static fallback for every other metric (quest points / ToG PB
 * intentionally stay static and wiki-authoritative).
 */
class AccountMetricMaxTest
{
	@Test
	@DisplayName("collection log slots prefers the live in-game slot total when synced")
	void collectionLogPrefersLiveSlotTotal()
	{
		Client client = mock(Client.class);
		when(client.getVarpValue(eq(VarPlayerID.COLLECTION_COUNT_MAX))).thenReturn(1750);
		assertEquals(1750, AccountMetric.COLLECTION_LOG_SLOTS.effectiveMaxTarget(client));
	}

	@Test
	@DisplayName("collection log slots falls back to the static when the live read is 0")
	void collectionLogFallsBackWhenUnsynced()
	{
		Client client = mock(Client.class);
		when(client.getVarpValue(anyInt())).thenReturn(0);
		assertEquals(AccountMetric.COLLECTION_LOG_SLOTS.getMaxTarget(),
			AccountMetric.COLLECTION_LOG_SLOTS.effectiveMaxTarget(client));
	}

	@Test
	@DisplayName("quest points / ToG PB use the static ceiling, never a live sum")
	void questPointsStayStatic()
	{
		// Even with a fully-populated client, QP/ToG must return the static
		// (the live DB-table sum over-counted; wiki static is authoritative).
		Client client = mock(Client.class);
		when(client.getVarpValue(anyInt())).thenReturn(99999);
		assertEquals(341, AccountMetric.QUEST_POINTS.effectiveMaxTarget(client));
		assertEquals(341, AccountMetric.TOG_MAX_TEARS.effectiveMaxTarget(client));
		assertEquals(AccountMetric.QUEST_POINTS.getMaxTarget(),
			AccountMetric.QUEST_POINTS.effectiveMaxTarget(client));
	}

	@Test
	@DisplayName("null client returns the static max for every metric")
	void nullClientUsesStatics()
	{
		for (AccountMetric m : AccountMetric.values())
		{
			assertEquals(m.getMaxTarget(), m.effectiveMaxTarget(null));
		}
	}

	@Test
	@DisplayName("quest point and ToG PB statics are equal (cape == full tears window)")
	void staticsAgree()
	{
		assertEquals(AccountMetric.QUEST_POINTS.getMaxTarget(),
			AccountMetric.TOG_MAX_TEARS.getMaxTarget());
	}

	@Test
	@DisplayName("an off-thread collection-log read (AssertionError) degrades to the static")
	void clientThreadAssertDegradesToStatic()
	{
		// The live varp read off the client thread raises AssertionError under
		// -ea — effectiveMaxTarget must swallow it and return the static, not
		// let it escape into the calling UI action (the Max-button bug).
		Client throwing = mock(Client.class);
		when(throwing.getVarpValue(anyInt()))
			.thenThrow(new AssertionError("must be called on client thread"));
		assertEquals(AccountMetric.COLLECTION_LOG_SLOTS.getMaxTarget(),
			AccountMetric.COLLECTION_LOG_SLOTS.effectiveMaxTarget(throwing));
	}
}
