package com.goalplanner.integration;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.data.WikiCaRepository;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.testsupport.InMemoryConfigManager;
import com.goalplanner.testsupport.MockClientFactory;
import com.goalplanner.testsupport.MockGameState;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@code addBossGoal}'s prereq-seeding behavior.
 *
 * <p>Validates that when a boss has a quest prereq, the quest's OWN
 * prereqs are transitively seeded — same chain-through behavior as
 * {@code addQuestGoalWithPrereqs} and {@code addDiaryGoalWithPrereqs}.
 * Without this chain, the card list would display the boss's quest as a
 * goal but none of the deeper skill/quest requirements underneath it.
 */
class BossGoalPrereqSeedingTest
{
	private GoalStore store;
	private GoalPlannerApiImpl api;

	@BeforeEach
	void setUp()
	{
		ConfigManager configManager = InMemoryConfigManager.create();
		store = new GoalStore(configManager, new com.google.gson.Gson());
		store.load();

		MockGameState freshState = new MockGameState();
		Client client = MockClientFactory.createClient(freshState);

		ItemManager itemManager = mock(ItemManager.class);
		WikiCaRepository wikiCaRepository = mock(WikiCaRepository.class);
		GoalReorderingService reorderingService = new GoalReorderingService(store);
		api = new GoalPlannerApiImpl(store, reorderingService, itemManager,
			wikiCaRepository, client);
	}

	private Set<String> questNames()
	{
		return store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.QUEST)
			.map(Goal::getQuestName)
			.collect(Collectors.toSet());
	}

	private List<Goal> skillGoals()
	{
		return store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.SKILL)
			.collect(Collectors.toList());
	}

	@Test
	@DisplayName("Amoxliatl seeds Heart of Darkness AND its transitive prereqs")
	void amoxliatlChainsThroughHeartOfDarkness()
	{
		String bossId = api.addBossGoal("Amoxliatl", 50);
		assertNotNull(bossId, "boss goal should be created");

		Set<String> quests = questNames();
		assertTrue(quests.contains(Quest.THE_HEART_OF_DARKNESS.name()),
			"direct quest prereq should be seeded: " + quests);
		// HoD's own quest prereq per QuestRequirements.java:1466
		assertTrue(quests.contains(Quest.TWILIGHTS_PROMISE.name()),
			"transitive quest prereq (HoD → Twilight's Promise) should be seeded: " + quests);

		// HoD's skill prereqs per QuestRequirements.java:1460-1467
		// (Mining 55, Thieving 48, Slayer 48, Agility 46)
		List<Goal> skills = skillGoals();
		assertFalse(skills.isEmpty(),
			"HoD's own skill prereqs should be seeded transitively");
		Set<String> skillNames = skills.stream()
			.map(Goal::getSkillName).collect(Collectors.toSet());
		assertTrue(skillNames.contains("MINING"), "Mining 55 from HoD");
		assertTrue(skillNames.contains("THIEVING"), "Thieving 48 from HoD");
		assertTrue(skillNames.contains("SLAYER"), "Slayer 48 from HoD");
		assertTrue(skillNames.contains("AGILITY"), "Agility 46 from HoD");
	}

	@Test
	@DisplayName("Vorkath seeds Dragon Slayer II AND chain of DS2 prereq quests")
	void vorkathChainsThroughDragonSlayerII()
	{
		String bossId = api.addBossGoal("Vorkath", 100);
		assertNotNull(bossId);

		Set<String> quests = questNames();
		assertTrue(quests.contains(Quest.DRAGON_SLAYER_II.name()),
			"direct DS2 quest prereq: " + quests);
		// DS2's own prereq chain should be seeded; Bone Voyage is a
		// well-known DS2 prereq per QuestRequirements.java:409.
		assertTrue(quests.contains(Quest.BONE_VOYAGE.name()),
			"transitive DS2 → Bone Voyage prereq: " + quests);
	}

	@Test
	@DisplayName("Zalcano seeds SotE chain plus 70/70/70 skill reqs")
	void zalcanoChainsQuestsAndSkills()
	{
		String bossId = api.addBossGoal("Zalcano", 50);
		assertNotNull(bossId);

		Set<String> quests = questNames();
		assertTrue(quests.contains(Quest.SONG_OF_THE_ELVES.name()),
			"direct SotE prereq: " + quests);
		// SotE has many quest prereqs; assert the chain extends beyond
		// just the direct quest.
		assertTrue(quests.size() >= 2,
			"SotE's prereq chain should add quests beyond SotE itself: " + quests);

		// Zalcano's direct skill prereqs (70 Mining, 70 Smithing, 70 Runecraft)
		Set<String> skillNames = skillGoals().stream()
			.map(Goal::getSkillName).collect(Collectors.toSet());
		assertTrue(skillNames.contains("MINING"));
		assertTrue(skillNames.contains("SMITHING"));
		assertTrue(skillNames.contains("RUNECRAFT"));
	}

	@Test
	@DisplayName("The Gauntlet seeds SotE chain (deepest elf questline)")
	void gauntletChainsThroughSotE()
	{
		String bossId = api.addBossGoal("The Gauntlet", 100);
		assertNotNull(bossId);

		Set<String> quests = questNames();
		assertTrue(quests.contains(Quest.SONG_OF_THE_ELVES.name()),
			"direct SotE prereq: " + quests);
		// SotE requires the full elf chain. Sanity-check a few deep
		// prereqs are seeded (e.g. Regicide, Waterfall Quest).
		assertTrue(quests.contains(Quest.REGICIDE.name()),
			"deep prereq Regicide should be seeded: " + quests);
	}

	@Test
	@DisplayName("Corrupted Gauntlet seeds SotE chain AND base Gauntlet kill")
	void corruptedGauntletRequiresStandardKill()
	{
		String bossId = api.addBossGoal("Corrupted Gauntlet", 100);
		assertNotNull(bossId);

		// Should have seeded the standard Gauntlet boss as a prereq.
		boolean hasGauntletPrereq = store.getGoals().stream()
			.anyMatch(g -> g.getType() == com.goalplanner.model.GoalType.BOSS
				&& "The Gauntlet".equals(g.getBossName()));
		assertTrue(hasGauntletPrereq,
			"Corrupted Gauntlet should seed 'The Gauntlet' as a boss prereq");

		// And SotE itself is in the quest chain.
		assertTrue(questNames().contains(Quest.SONG_OF_THE_ELVES.name()));
	}

	@Test
	@DisplayName("Demonic Brutus requires Brutus kill AND Defender of Varrock")
	void demonicBrutusChain()
	{
		String bossId = api.addBossGoal("Demonic Brutus", 50);
		assertNotNull(bossId);

		Set<String> quests = questNames();
		assertTrue(quests.contains(Quest.DEFENDER_OF_VARROCK.name()),
			"Defender of Varrock should be seeded: " + quests);

		boolean hasBrutusPrereq = store.getGoals().stream()
			.anyMatch(g -> g.getType() == com.goalplanner.model.GoalType.BOSS
				&& "Brutus".equals(g.getBossName()));
		assertTrue(hasBrutusPrereq,
			"Demonic Brutus should seed 'Brutus' as a boss prereq");
	}

	@Test
	@DisplayName("addBossGoalNoPrereqs creates just the boss goal, no prereq tree")
	void bareAddSeedsNoPrereqs()
	{
		// Amoxliatl normally chains Heart of Darkness + its transitive skills/quests
		// (see amoxliatlChainsThroughHeartOfDarkness). The bare add (Task 4's
		// unchecked "Add prerequisites") must create ONLY the boss goal.
		String bossId = api.addBossGoalNoPrereqs("Amoxliatl", 50);
		assertNotNull(bossId, "bare boss goal should be created");

		List<Goal> bossGoals = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.BOSS)
			.collect(Collectors.toList());
		assertEquals(1, bossGoals.size(),
			"bare add should create exactly one BOSS goal, no boss prereqs: "
				+ bossGoals.stream().map(Goal::getBossName).collect(Collectors.toList()));
		assertEquals("Amoxliatl", bossGoals.get(0).getBossName());

		assertTrue(questNames().isEmpty(),
			"bare add must not seed quest prereqs: " + questNames());
		assertTrue(skillGoals().isEmpty(),
			"bare add must not seed skill prereqs");
	}
}
