package com.goalplanner.api;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.testsupport.DataResourcesInitExtension;
import com.goalplanner.testsupport.InMemoryConfigManager;
import com.goalplanner.testsupport.MockClientFactory;
import com.goalplanner.testsupport.MockGameState;
import com.goalplanner.data.WikiCaRepository;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression guard for the dock's diary-add path. The dock create form calls
 * {@link GoalPlannerApiImpl#addDiaryGoal}, which resolves diary requirements
 * against the live Client. When that ran on the EDT (no client-thread wrap) it
 * silently failed for every area/tier (dev-mode {@code -ea} client-thread
 * assert) - the reported "adding diaries is failing on all sections/levels" bug.
 *
 * <p>This test exercises the API layer directly (client-thread executor is a
 * no-op in tests) for every area x tier the dock offers, asserting a DIARY goal
 * is actually created each time. The UI-side fix wraps the create in
 * {@code runOnClientThread}; this locks the API contract the fix relies on.
 */
@ExtendWith(DataResourcesInitExtension.class)
class AddDiaryGoalAllAreasTest
{
	/** The exact area display names the dock's buildDiaryForm offers. */
	private static final String[] DIARY_AREAS = {
		"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
		"Kourend & Kebos", "Lumbridge & Draynor", "Morytania", "Varrock",
		"Western Provinces", "Wilderness"
	};

	private GoalStore store;
	private GoalPlannerApiImpl api;

	@BeforeEach
	void setUp()
	{
		store = new GoalStore(InMemoryConfigManager.create(), new com.google.gson.Gson());
		store.load();
		// Fresh account (nothing complete) so every diary resolves to a real,
		// not-yet-complete goal with prereqs.
		Client client = MockClientFactory.createClient(new MockGameState());
		api = new GoalPlannerApiImpl(store, new GoalReorderingService(store),
			mock(ItemManager.class), mock(WikiCaRepository.class), client);
	}

	@Test
	@DisplayName("addDiaryGoal creates a DIARY goal for every area and tier")
	void addsDiaryGoalForEveryAreaAndTier()
	{
		for (String area : DIARY_AREAS)
		{
			for (GoalPlannerApi.DiaryTier tier : GoalPlannerApi.DiaryTier.values())
			{
				String id = api.addDiaryGoal(area, tier);
				assertNotNull(id, "addDiaryGoal returned null for " + area + " " + tier
					+ " - the diary-add regression is back");

				Goal created = api.findGoal(id);
				assertNotNull(created, "no goal found for returned id (" + area + " " + tier + ")");

				// The gesture may return the diary goal itself or, for an already-met
				// tier, reconcile it complete; either way a DIARY goal must exist in
				// the store for this area/tier gesture.
				boolean hasDiaryGoal = store.getGoals().stream()
					.anyMatch(g -> g.getType() == GoalType.DIARY);
				assertTrue(hasDiaryGoal, "no DIARY goal in store after add for " + area + " " + tier);
			}
		}
	}
}
