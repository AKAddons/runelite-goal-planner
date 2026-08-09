package com.goalplanner.ui;

import net.runelite.api.Experience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The unified edit form (ADR-0008) seeds an existing skill goal's target into
 * the shared {@link SkillTargetForm} via {@link SkillTargetForm#setTargetXp}.
 * These cover that the seed round-trips through {@link SkillTargetForm#getTargetXp}
 * and keeps the Level/XP rows in sync.
 */
class SkillTargetFormTest
{
	@Test
	@DisplayName("setTargetXp round-trips through getTargetXp for a level-boundary XP")
	void seedLevelBoundary()
	{
		SkillTargetForm form = new SkillTargetForm(99);
		int xp = Experience.getXpForLevel(70);
		form.setTargetXp(xp);
		assertEquals(xp, form.getTargetXp());
	}

	@Test
	@DisplayName("setTargetXp round-trips a raw (non-level-boundary) XP target")
	void seedRawXp()
	{
		SkillTargetForm form = new SkillTargetForm(99);
		form.setTargetXp(1_234_567);
		assertEquals(1_234_567, form.getTargetXp());
	}

	@Test
	@DisplayName("setTargetXp accepts the max XP target")
	void seedMaxXp()
	{
		SkillTargetForm form = new SkillTargetForm(99);
		form.setTargetXp(200_000_000);
		assertEquals(200_000_000, form.getTargetXp());
	}
}
