package com.goalplanner.api;

import com.goalplanner.data.WikiCaRepository;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalStatus;
import com.goalplanner.model.GoalType;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.testsupport.InMemoryConfigManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Repro harness for "Move to Section → Move to New Section…" doing nothing.
 * Mirrors the exact UI flow: api.createSection(name) then
 * api.moveGoalToSection(goalId, newId), across the scenarios a user testing
 * the new delete-section paradigm would hit.
 */
class MoveToNewSectionReproTest
{
	private GoalStore store;
	private GoalPlannerApiImpl api;

	@BeforeEach
	void setUp()
	{
		ConfigManager configManager = InMemoryConfigManager.create();
		store = new GoalStore(configManager, new com.google.gson.Gson());
		store.load();
		GoalReorderingService reordering = new GoalReorderingService(store);
		api = new GoalPlannerApiImpl(store, reordering,
			mock(ItemManager.class), mock(WikiCaRepository.class));
		api.setOnGoalsChanged(() -> {});
	}

	@Test
	@DisplayName("plain: incomplete default goal → new section")
	void plainMove()
	{
		String goalId = api.addCustomGoal("Test", "");
		String newId = api.createSection("Fresh");
		assertNotNull(newId, "createSection returned null");
		assertTrue(api.moveGoalToSection(goalId, newId), "move returned false");
		assertEquals(newId, api.findGoal(goalId).getSectionId());
	}

	@Test
	@DisplayName("completed goal → new section")
	void completedMove()
	{
		Goal done = Goal.builder().type(GoalType.CUSTOM).name("done")
			.completedAt(123L).status(GoalStatus.COMPLETE)
			.sectionId(null).build();
		store.addGoal(done);
		store.reconcileDerivedSections();
		String newId = api.createSection("Fresh");
		assertNotNull(newId);
		assertTrue(api.moveGoalToSection(done.getId(), newId), "completed move returned false");
		assertEquals(newId, done.getSectionId());
	}

	@Test
	@DisplayName("after deleting a section, move to a new section with the SAME name")
	void moveAfterDeleteReusingName()
	{
		String goalId = api.addCustomGoal("Survivor", "");
		String doomed = api.createSection("PvM");
		assertTrue(api.deleteSection(doomed));
		String newId = api.createSection("PvM");
		assertNotNull(newId, "createSection(\"PvM\") after delete returned null");
		assertTrue(api.moveGoalToSection(goalId, newId), "move returned false");
		assertEquals(newId, api.findGoal(goalId).getSectionId());
	}

	@Test
	@DisplayName("after delete-section UNDO, move another goal to a new section")
	void moveAfterDeleteUndo()
	{
		String inDoomed = api.addCustomGoal("In doomed", "");
		String doomed = api.createSection("Doomed");
		api.moveGoalToSection(inDoomed, doomed);
		assertTrue(api.deleteSection(doomed));
		api.undo();

		String goalId = api.addCustomGoal("Mover", "");
		String newId = api.createSection("Fresh");
		assertNotNull(newId);
		assertTrue(api.moveGoalToSection(goalId, newId), "move after undo returned false");
		assertEquals(newId, api.findGoal(goalId).getSectionId());
	}
}
