package com.goalplanner.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-function tests for the relative goal target math. Cover the
 * resolution from "gain X" → absolute target without touching Swing
 * or the RuneLite client.
 */
class RelativeTargetResolverTest
{
	@Nested
	@DisplayName("resolveSkillXp")
	class SkillXp
	{
		@Test
		@DisplayName("adds delta to current XP")
		void simpleAdd()
		{
			assertEquals(150_000, RelativeTargetResolver.resolveSkillXp(50_000, 100_000));
		}

		@Test
		@DisplayName("clamps at 200M")
		void clampsAtCap()
		{
			assertEquals(200_000_000,
				RelativeTargetResolver.resolveSkillXp(199_000_000, 5_000_000));
		}

		@Test
		@DisplayName("zero current XP is fine")
		void zeroCurrent()
		{
			assertEquals(1000, RelativeTargetResolver.resolveSkillXp(0, 1000));
		}

		@Test
		@DisplayName("zero delta returns -1 (invalid)")
		void zeroDeltaInvalid()
		{
			assertEquals(-1, RelativeTargetResolver.resolveSkillXp(50_000, 0));
		}

		@Test
		@DisplayName("negative delta returns -1 (invalid)")
		void negativeDeltaInvalid()
		{
			assertEquals(-1, RelativeTargetResolver.resolveSkillXp(50_000, -1));
		}

		@Test
		@DisplayName("at max XP, any positive delta still resolves to max")
		void atMaxXp()
		{
			assertEquals(200_000_000,
				RelativeTargetResolver.resolveSkillXp(200_000_000, 100));
		}
	}

	@Nested
	@DisplayName("resolveKillCount")
	class KillCount
	{
		@Test
		@DisplayName("adds delta to current kill count")
		void simpleAdd()
		{
			assertEquals(1947, RelativeTargetResolver.resolveKillCount(1847, 100));
		}

		@Test
		@DisplayName("unknown current count (0) falls back to the raw delta")
		void zeroCurrent()
		{
			assertEquals(50, RelativeTargetResolver.resolveKillCount(0, 50));
		}

		@Test
		@DisplayName("negative current read is floored to 0")
		void negativeCurrentFloored()
		{
			assertEquals(50, RelativeTargetResolver.resolveKillCount(-1, 50));
		}

		@Test
		@DisplayName("zero delta returns -1 (invalid)")
		void zeroDeltaInvalid()
		{
			assertEquals(-1, RelativeTargetResolver.resolveKillCount(100, 0));
		}

		@Test
		@DisplayName("negative delta returns -1 (invalid)")
		void negativeDeltaInvalid()
		{
			assertEquals(-1, RelativeTargetResolver.resolveKillCount(100, -5));
		}
	}

}
