package com.goalplanner.api;

import com.goalplanner.data.WikiCaRepository;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalStatus;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.ItemTag;
import com.goalplanner.model.Section;
import com.goalplanner.model.TagCategory;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.testsupport.InMemoryConfigManager;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GoalPlannerApiImpl}, the canonical mutation surface for
 * the plugin. Every other layer (panel, trackers, right-click menus) goes
 * through this class. Coverage focuses on:
 *
 * <ul>
 *   <li>The public API: addX, removeGoal, changeTarget, markGoal pair, setX</li>
 *   <li>The internal API: section CRUD, color overrides, selection,
 *       recordGoalProgress, moveGoalToSection</li>
 *   <li>onGoalsChanged callback firing semantics (only on actual change)</li>
 *   <li>Validation rules (built-ins immutable, no-op detection, type gates)</li>
 * </ul>
 *
 * <p>Uses a real GoalStore against an InMemoryConfigManager. ItemManager and
 * WikiCaRepository are mocked because the test class does not exercise item
 * lookups (those have their own coverage).
 */
class GoalPlannerApiImplTest
{
	private GoalStore store;
	private GoalPlannerApiImpl api;
	private AtomicInteger callbackFireCount;

	@BeforeEach
	void setUp()
	{
		ConfigManager configManager = InMemoryConfigManager.create();
		store = new GoalStore(configManager, new com.google.gson.Gson());
		store.load();

		ItemManager itemManager = mock(ItemManager.class);
		WikiCaRepository wikiCaRepository = mock(WikiCaRepository.class);
		GoalReorderingService reorderingService = new GoalReorderingService(store);
		api = new GoalPlannerApiImpl(store, reorderingService, itemManager, wikiCaRepository);

		callbackFireCount = new AtomicInteger(0);
		api.setOnGoalsChanged(callbackFireCount::incrementAndGet);
		api.setOnSelectionChanged(callbackFireCount::incrementAndGet);
	}

	@Test
	@DisplayName("removeEmptyUserSections deletes only goal-less sections, undoably")
	void removeEmptyUserSectionsDeletesOnlyEmpties()
	{
		String full = api.createSection("Full");
		String empty1 = api.createSection("Empty1");
		String empty2 = api.createSection("Empty2");

		// One goal in "Full" so it is NOT empty.
		store.addGoal(Goal.builder().type(GoalType.CUSTOM).name("X").sectionId(full).build());

		int deleted = api.removeEmptyUserSections();
		assertEquals(2, deleted);

		Set<String> ids = new java.util.HashSet<>();
		for (SectionView s : api.queryAllSections()) ids.add(s.id);
		assertTrue(ids.contains(full), "non-empty section is kept");
		assertFalse(ids.contains(empty1), "empty section removed");
		assertFalse(ids.contains(empty2), "empty section removed");

		// One undo restores both empty sections (the sweep is a single compound).
		api.undo();
		Set<String> afterUndo = new java.util.HashSet<>();
		for (SectionView s : api.queryAllSections()) afterUndo.add(s.id);
		assertTrue(afterUndo.contains(empty1));
		assertTrue(afterUndo.contains(empty2));
		assertTrue(afterUndo.contains(full));

		// Re-running after undo finds the two restored empties again (Full stays).
		assertEquals(2, api.removeEmptyUserSections());
	}

	@Test
	@DisplayName("removeAllGoalsAndSections wipes every goal (incl. completed) + user sections, undoably")
	void removeAllGoalsAndSectionsWipesEverything()
	{
		String sec = api.createSection("S");
		store.addGoal(Goal.builder().type(GoalType.CUSTOM).name("Incomplete").sectionId(sec).build());
		store.addGoal(Goal.builder().type(GoalType.CUSTOM).name("Done")
			.status(GoalStatus.COMPLETE).sectionId(sec).build());

		int goalsBefore = store.getGoals().size();
		assertTrue(goalsBefore >= 2);

		api.removeAllGoalsAndSections();

		assertEquals(0, store.getGoals().size(), "every goal removed, completed included");
		for (SectionView s : api.queryAllSections()) assertTrue(s.builtIn, "only built-in sections remain");

		// One undo restores all goals and the user section.
		api.undo();
		assertEquals(goalsBefore, store.getGoals().size());
		boolean secBack = false;
		for (SectionView s : api.queryAllSections()) if (s.id.equals(sec)) secBack = true;
		assertTrue(secBack, "section restored by one undo");
	}

	// ====================================================================
	// Public API: addAccountGoal target clamping
	// ====================================================================

	@Nested
	@DisplayName("addAccountGoal")
	class AddAccountGoalTests
	{
		@Test
		@DisplayName("allows a target above the metric max — ceilings grow with game updates")
		void allowsTargetAboveMax()
		{
			String id = api.addAccountGoal(
				com.goalplanner.model.AccountMetric.DIARY_TIERS_COMPLETED.name(), 100);

			Goal goal = api.findGoal(id);
			assertNotNull(goal);
			assertEquals(100, goal.getTargetValue());
			assertEquals("100 Diary Tiers", goal.getName());
		}

		@Test
		@DisplayName("allows an over-max collection log target without a client")
		void allowsOverMaxCollectionLogTarget()
		{
			String id = api.addAccountGoal(
				com.goalplanner.model.AccountMetric.COLLECTION_LOG_SLOTS.name(), 5000);

			Goal goal = api.findGoal(id);
			assertNotNull(goal);
			assertEquals(5000, goal.getTargetValue());
		}

		@Test
		@DisplayName("clamps a below-minimum target up to the metric's floor")
		void clampsBelowMinTargetToFloor()
		{
			// COMBAT_LEVEL's minimum is 3 — a target of 1 is not a real state.
			String id = api.addAccountGoal(
				com.goalplanner.model.AccountMetric.COMBAT_LEVEL.name(), 1);

			Goal goal = api.findGoal(id);
			assertNotNull(goal);
			assertEquals(3, goal.getTargetValue());
		}

		@Test
		@DisplayName("keeps an in-range collection log target unchanged")
		void keepsInRangeTarget()
		{
			String id = api.addAccountGoal(
				com.goalplanner.model.AccountMetric.COLLECTION_LOG_SLOTS.name(), 600);

			Goal goal = api.findGoal(id);
			assertNotNull(goal);
			assertEquals(600, goal.getTargetValue());
			assertEquals("600 Collection Log Slots", goal.getName());
		}
	}

	// ====================================================================
	// Public API: addSkillGoal / addCustomGoal
	// ====================================================================

	@Nested
	@DisplayName("addSkillGoal")
	class AddSkillGoalTests
	{
		@Test
		@DisplayName("creates a new SKILL goal and fires the callbacks")
		void createsNewSkillGoal()
		{
			String id = api.addSkillGoal(Skill.ATTACK, 13_034_431);
			assertNotNull(id);
			assertEquals(1, store.getGoals().size());
			// Two fires: onGoalsChanged for the new goal, plus
			// onSelectionChanged from the post-creation selectAfterCreate
			// rule that promotes the new goal to the active selection.
			assertEquals(2, callbackFireCount.get());

			Goal g = store.getGoals().get(0);
			assertEquals(GoalType.SKILL, g.getType());
			assertEquals("ATTACK", g.getSkillName());
			assertEquals(13_034_431, g.getTargetValue());
		}

		@Test
		@DisplayName("returns existing id on duplicate (skill + target match)")
		void duplicateReturnsExistingId()
		{
			String first = api.addSkillGoal(Skill.ATTACK, 13_034_431);
			String second = api.addSkillGoal(Skill.ATTACK, 13_034_431);
			assertEquals(first, second);
			assertEquals(1, store.getGoals().size());
		}

		@Test
		@DisplayName("auto-links lower targets as prerequisites of higher targets")
		void autoLinksSameSkillChain()
		{
			// The old implicit chain sort has been replaced with explicit
			// auto-link on add. Adding 99 Prayer then 96 Prayer
			// should create an edge "99 requires 96" (96 is a prerequisite)
			// so that queryGoalsTopologicallySorted puts 96 visually above 99.
			// The flat priority order in store.getGoals() stays at insertion
			// order (99 first, 96 second); it's the topo projection that
			// handles visual ordering.
			String id99 = api.addSkillGoal(Skill.PRAYER, 13_034_431); // L99
			String id96 = api.addSkillGoal(Skill.PRAYER, 9_684_577);  // L96

			// The 99 goal should now list 96 as a requirement.
			List<String> reqs = api.getRequirements(id99);
			assertTrue(reqs.contains(id96),
				"L99 should require L96 via auto-link");
			// And 96 should list 99 as a dependent.
			List<String> deps = api.getDependents(id96);
			assertTrue(deps.contains(id99),
				"L96 should be required-by L99");

			// Topo-sorted view of the section: 96 before 99.
			String sectionId = store.getGoals().stream()
				.filter(x -> id99.equals(x.getId())).findFirst().orElseThrow()
				.getSectionId();
			List<com.goalplanner.api.GoalView> topo =
				api.queryGoalsTopologicallySorted(sectionId);
			int topoIdx96 = -1, topoIdx99 = -1;
			for (int i = 0; i < topo.size(); i++)
			{
				if (id96.equals(topo.get(i).id)) topoIdx96 = i;
				if (id99.equals(topo.get(i).id)) topoIdx99 = i;
			}
			assertTrue(topoIdx96 < topoIdx99, "L96 should render above L99 in topo order");
		}
	}

	@Nested
	@DisplayName("addCustomGoal")
	class AddCustomGoalTests
	{
		@Test
		@DisplayName("creates a new CUSTOM goal")
		void createsNewCustomGoal()
		{
			String id = api.addCustomGoal("My Goal", "A description");
			assertNotNull(id);
			Goal g = store.getGoals().get(0);
			assertEquals(GoalType.CUSTOM, g.getType());
			assertEquals("My Goal", g.getName());
			assertEquals("A description", g.getDescription());
		}

		@Test
		@DisplayName("rejects null/empty name")
		void rejectsBlankName()
		{
			assertNull(api.addCustomGoal(null, "desc"));
			assertNull(api.addCustomGoal("", "desc"));
			assertNull(api.addCustomGoal("   ", "desc"));
			assertEquals(0, store.getGoals().size());
		}
	}

	// ====================================================================
	// Public API: removeGoal
	// ====================================================================

	@Test
	@DisplayName("removeGoal deletes the goal and fires the callback")
	void removeGoalSuccess()
	{
		String id = api.addCustomGoal("To Delete", "");
		callbackFireCount.set(0);

		assertTrue(api.removeGoal(id));
		assertEquals(0, store.getGoals().size());
		assertEquals(1, callbackFireCount.get());
	}

	@Test
	@DisplayName("removeGoal cleans up the selection set")
	void removeGoalCleansSelection()
	{
		String id = api.addCustomGoal("To Delete", "");
		api.addToGoalSelection(id);
		assertTrue(api.getSelectedGoalIds().contains(id));

		api.removeGoal(id);
		assertFalse(api.getSelectedGoalIds().contains(id));
	}

	@Test
	@DisplayName("undoing a compound add prunes the now-deleted goals from the selection")
	void undoPrunesStaleSelection()
	{
		// Mirrors "Add requirements" then undo: a compound creates goals and
		// the gesture selects them directly; undo removes the goals but the
		// selection must not keep their dead ids (the stale "N selected" bug).
		api.beginCompound("seed-like gesture");
		String a = api.addCustomGoal("Prereq A", "");
		String b = api.addCustomGoal("Prereq B", "");
		api.endCompound();
		api.addToGoalSelection(a);
		api.addToGoalSelection(b);
		assertEquals(2, api.getSelectedGoalIds().size());

		api.undo();

		assertTrue(api.getSelectedGoalIds().isEmpty(),
			"selection should drop ids whose goals were removed by undo");
		assertNull(api.findGoal(a));
		assertNull(api.findGoal(b));
	}

	@Nested
	@DisplayName("seedBossRequirementsForGoal (boss Add requirements)")
	class SeedBossRequirementsTests
	{
		private String bareBossGoal(String boss)
		{
			// A boss goal WITHOUT its prereqs seeded (e.g. an imported one).
			Goal g = Goal.builder().type(GoalType.BOSS).name(boss).bossName(boss)
				.description("1 kills").targetValue(1).build();
			store.addGoal(g);
			return g.getId();
		}

		@Test
		@DisplayName("\"All\" seeds the boss's skill + quest requirements")
		void seedsAllRequirements()
		{
			// Abyssal Sire: 85 Slayer + Enter the Abyss.
			String id = bareBossGoal("Abyssal Sire");
			int added = api.seedBossRequirementsForGoal(id, true);
			assertTrue(added >= 2, "expected at least the Slayer + quest prereqs");
			assertTrue(store.getGoals().stream().anyMatch(g ->
					g.getType() == GoalType.SKILL && "SLAYER".equals(g.getSkillName())),
				"Slayer requirement should be seeded");
			assertTrue(store.getGoals().stream().anyMatch(g ->
					g.getType() == GoalType.QUEST && "ENTER_THE_ABYSS".equals(g.getQuestName())),
				"Enter the Abyss requirement should be seeded");
		}

		@Test
		@DisplayName("\"Incomplete only\" skips a skill the player already has")
		void incompleteOnlySkipsMetSkill()
		{
			net.runelite.api.Client client = mock(net.runelite.api.Client.class);
			when(client.getRealSkillLevel(Skill.SLAYER)).thenReturn(99); // already past 85
			GoalPlannerApiImpl liveApi = new GoalPlannerApiImpl(
				store, new GoalReorderingService(store), mock(ItemManager.class),
				mock(WikiCaRepository.class), client);

			Goal g = Goal.builder().type(GoalType.BOSS).name("Abyssal Sire").bossName("Abyssal Sire")
				.description("1 kills").targetValue(1).build();
			store.addGoal(g);

			liveApi.seedBossRequirementsForGoal(g.getId(), false);
			assertTrue(store.getGoals().stream().noneMatch(x ->
					x.getType() == GoalType.SKILL && "SLAYER".equals(x.getSkillName())),
				"already-met Slayer level should be skipped");
		}

		@Test
		@DisplayName("returns 0 for a non-boss goal or a boss with no prereqs")
		void noOpCases()
		{
			String custom = api.addCustomGoal("not a boss", "");
			assertEquals(0, api.seedBossRequirementsForGoal(custom, true));
			// A boss with no defined prereqs.
			String none = bareBossGoal("Giant Mole");
			assertEquals(0, api.seedBossRequirementsForGoal(none, true));
		}
	}

	@Nested
	@DisplayName("blocked-by-prereqs detection (recomputeBlockedRequirements)")
	class BlockedPrereqsTests
	{
		private GoalPlannerApiImpl liveApi(net.runelite.api.Client client)
		{
			return new GoalPlannerApiImpl(store, new GoalReorderingService(store),
				mock(ItemManager.class), mock(WikiCaRepository.class), client);
		}

		@Test
		@DisplayName("flags an unmet, not-in-plan boss prereq (Abyssal Sire → 85 Slayer)")
		void flagsUnmetMissingPrereq()
		{
			net.runelite.api.Client client = mock(net.runelite.api.Client.class);
			when(client.getRealSkillLevel(Skill.SLAYER)).thenReturn(50); // below 85
			GoalPlannerApiImpl live = liveApi(client);

			Goal boss = Goal.builder().type(GoalType.BOSS).name("Abyssal Sire").bossName("Abyssal Sire")
				.description("1 kills").targetValue(1).build();
			store.addGoal(boss);

			assertTrue(live.recomputeBlockedRequirements());
			assertTrue(live.blockedRequirements(boss.getId()).stream()
					.anyMatch(s -> s.contains("Slayer")),
				"unmet 85 Slayer should be flagged as blocking");
		}

		@Test
		@DisplayName("a prereq already covered by a plan goal is NOT flagged")
		void coveredPrereqNotFlagged()
		{
			net.runelite.api.Client client = mock(net.runelite.api.Client.class);
			when(client.getRealSkillLevel(Skill.SLAYER)).thenReturn(50);
			GoalPlannerApiImpl live = liveApi(client);

			Goal boss = Goal.builder().type(GoalType.BOSS).name("Abyssal Sire").bossName("Abyssal Sire")
				.description("1 kills").targetValue(1).build();
			store.addGoal(boss);
			// A 99 Slayer goal in the plan covers the 85 requirement.
			Goal slayer = Goal.builder().type(GoalType.SKILL).name("Slayer - Level 99")
				.skillName("SLAYER").targetValue(net.runelite.api.Experience.getXpForLevel(99)).build();
			store.addGoal(slayer);

			live.recomputeBlockedRequirements();
			assertTrue(live.blockedRequirements(boss.getId()).stream()
					.noneMatch(s -> s.contains("Slayer")),
				"a 99 Slayer goal in the plan covers the 85 requirement");
		}

		@Test
		@DisplayName("no client → no badges (empty map)")
		void noClientNoBadges()
		{
			Goal boss = Goal.builder().type(GoalType.BOSS).name("Abyssal Sire").bossName("Abyssal Sire")
				.description("1 kills").targetValue(1).build();
			store.addGoal(boss);
			// `api` (the default field) has no client.
			assertFalse(api.recomputeBlockedRequirements());
			assertTrue(api.blockedRequirements(boss.getId()).isEmpty());
		}
	}

	@Test
	@DisplayName("removeGoal returns false for unknown id")
	void removeGoalUnknownId()
	{
		assertFalse(api.removeGoal("nonexistent"));
		assertFalse(api.removeGoal(null));
	}

	// ====================================================================
	// Public API: markGoalComplete / markGoalIncomplete
	// ====================================================================

	@Test
	@DisplayName("markGoalComplete works on CUSTOM goals")
	void markCustomGoalComplete()
	{
		String id = api.addCustomGoal("Custom", "");
		assertTrue(api.markGoalComplete(id));

		Goal g = store.getGoals().get(0);
		assertTrue(g.isComplete());
		assertEquals(GoalStatus.COMPLETE, g.getStatus());
		assertTrue(g.getCompletedAt() > 0);
	}

	@Test
	@DisplayName("markGoalComplete works on ITEM_GRIND goals")
	void markItemGoalComplete()
	{
		Goal item = Goal.builder().type(GoalType.ITEM_GRIND).name("Cannonballs")
			.itemId(2).targetValue(200).currentValue(0).build();
		store.addGoal(item);

		assertTrue(api.markGoalComplete(item.getId()));
		assertTrue(item.isComplete());
	}

	@Test
	@DisplayName("markGoalComplete rejects non-CUSTOM/non-ITEM_GRIND goals")
	void markGoalCompleteRejectsTrackedTypes()
	{
		String skillId = api.addSkillGoal(Skill.ATTACK, 13_034_431);
		assertFalse(api.markGoalComplete(skillId));
	}

	@Test
	@DisplayName("markGoalIncomplete reverts a complete goal")
	void markGoalIncomplete()
	{
		String id = api.addCustomGoal("Custom", "");
		api.markGoalComplete(id);
		assertTrue(store.getGoals().get(0).isComplete());

		assertTrue(api.markGoalIncomplete(id));
		assertFalse(store.getGoals().get(0).isComplete());
		assertEquals(GoalStatus.ACTIVE, store.getGoals().get(0).getStatus());
	}

	// ====================================================================
	// User sections keep their completed goals inline (each section its own bucket)
	// ====================================================================

	@Test
	@DisplayName("a keep-inline section keeps its completed goal in place, not relocated to Completed")
	void keepInlineSectionKeepsCompletedGoal()
	{
		String sectionId = api.createSection("Inferno Prep");
		store.setSectionAutoArchiveOverride(sectionId, false); // keep inline
		Goal g = Goal.builder().type(GoalType.SKILL).name("Ranged").skillName("RANGED")
			.targetValue(100).currentValue(0).sectionId(sectionId).build();
		store.addGoal(g);

		// The goal tracks + completes normally.
		assertTrue(api.recordGoalProgress(g.getId(), 100));
		assertTrue(g.isComplete());

		// A keep-inline section holds its completed goal as a ticked-off item.
		store.reconcileDerivedSections();
		assertEquals(sectionId, g.getSectionId());
	}

	@Test
	@DisplayName("user section orders completed goals last (checklist view)")
	void userSectionSortsCompletedLast()
	{
		String sectionId = api.createSection("Inferno Prep");
		// Added done-first to prove the sort sinks it, not just insertion order.
		Goal done = Goal.builder().type(GoalType.CUSTOM).name("done")
			.completedAt(123L).status(GoalStatus.COMPLETE).sectionId(sectionId).build();
		Goal todo = Goal.builder().type(GoalType.CUSTOM).name("todo").sectionId(sectionId).build();
		store.addGoal(done);
		store.addGoal(todo);

		java.util.List<com.goalplanner.api.GoalView> views = api.queryGoalsTopologicallySorted(sectionId);
		assertEquals(2, views.size());
		assertEquals("todo", views.get(0).name);   // incomplete requirement first
		assertEquals("done", views.get(1).name);   // ticked-off sinks to the bottom
	}

	@Test
	@DisplayName("duplicateGoalsToSection copies into target (originals kept), remaps in-set edges, dedups per-section, undoable")
	void duplicateGoalsToSection()
	{
		String target = api.createSection("Target");
		String aId = api.addCustomGoal("A", "");
		String bId = api.addCustomGoal("B", "");
		store.addRequirement(aId, bId); // A requires B

		java.util.List<String> created = api.duplicateGoalsToSection(java.util.Arrays.asList(aId, bId), target);
		assertEquals(2, created.size());
		// Originals untouched.
		assertNotNull(api.findGoal(aId));
		assertNotNull(api.findGoal(bId));

		Goal copyA = null, copyB = null;
		for (String id : created)
		{
			Goal g = api.findGoal(id);
			assertEquals(target, g.getSectionId());
			if ("A".equals(g.getName())) copyA = g;
			else if ("B".equals(g.getName())) copyB = g;
		}
		assertNotNull(copyA);
		assertNotNull(copyB);
		// Edge remapped to the copies, not the originals.
		assertTrue(copyA.getRequiredGoalIds().contains(copyB.getId()));
		assertFalse(copyA.getRequiredGoalIds().contains(bId));

		// Per-section no-duplicates: duplicating again into the same target is a no-op.
		assertTrue(api.duplicateGoalsToSection(java.util.Arrays.asList(aId, bId), target).isEmpty());

		// One undo reverses the whole gesture.
		api.undo();
		assertNull(api.findGoal(copyA.getId()));
		assertNull(api.findGoal(copyB.getId()));
	}

	@Test
	@DisplayName("per-section identity: adding a goal that exists only in a user section creates a NEW default instance")
	void addCreatesNewInstanceWhenOnlyInUserSection()
	{
		String sectionId = api.createSection("Guide");
		Goal inSection = Goal.builder().type(GoalType.CUSTOM).name("Beat Inferno")
			.sectionId(sectionId).build();
		store.addGoal(inSection);

		// The normal add path dedups only within the DEFAULT namespace, so the
		// user-section copy does not block it — a fresh default instance is made.
		String newId = api.addCustomGoal("Beat Inferno", "");
		assertNotNull(newId);
		assertNotEquals(inSection.getId(), newId);
		assertEquals(store.getIncompleteSection().getId(), api.findGoal(newId).getSectionId());
		long count = store.getGoals().stream()
			.filter(g -> "Beat Inferno".equalsIgnoreCase(g.getName())).count();
		assertEquals(2, count); // one per namespace

		// Adding again now dedups against the default copy (idempotent within default).
		assertEquals(newId, api.addCustomGoal("Beat Inferno", ""));
	}

	@Test
	@DisplayName("moveGoalsToDefault routes each goal to its completion-matching built-in, undoable")
	void moveGoalsToDefault()
	{
		String section = api.createSection("Stuff");
		Goal todo = Goal.builder().type(GoalType.CUSTOM).name("todo").sectionId(section).build();
		Goal done = Goal.builder().type(GoalType.CUSTOM).name("done")
			.completedAt(123L).status(GoalStatus.COMPLETE).sectionId(section).build();
		store.addGoal(todo);
		store.addGoal(done);

		assertEquals(2, api.moveGoalsToDefault(java.util.Arrays.asList(todo.getId(), done.getId())));
		assertEquals(store.getIncompleteSection().getId(), todo.getSectionId()); // incomplete → Incomplete
		assertEquals(store.getCompletedSection().getId(), done.getSectionId());  // complete → Completed

		api.undo(); // one gesture restores both to the user section
		assertEquals(section, todo.getSectionId());
		assertEquals(section, done.getSectionId());
	}

	@Test
	@DisplayName("deleteSection deletes the section's goals; one undo restores goals, edges, and cross-section dependents")
	void deleteSectionDeletesGoalsAndUndoRestoresThem()
	{
		String section = api.createSection("Doomed");
		Goal x = Goal.builder().type(GoalType.CUSTOM).name("x").sectionId(section).build();
		Goal y = Goal.builder().type(GoalType.CUSTOM).name("y").sectionId(section).build();
		Goal outside = Goal.builder().type(GoalType.CUSTOM).name("outside").build();
		store.addGoal(x);
		store.addGoal(y);
		store.addGoal(outside);
		store.addRequirement(y.getId(), x.getId());       // internal: y requires x
		store.addRequirement(outside.getId(), y.getId()); // survivor: outside requires y

		assertTrue(api.deleteSection(section));
		assertNull(store.findSection(section));
		assertNull(api.findGoal(x.getId()));
		assertNull(api.findGoal(y.getId()));
		// The survivor's edge into the deleted section is scrubbed, not dangling.
		assertNotNull(api.findGoal(outside.getId()));
		assertFalse(outside.getRequiredGoalIds().contains(y.getId()));

		api.undo();
		assertNotNull(store.findSection(section));
		Goal rx = api.findGoal(x.getId());
		Goal ry = api.findGoal(y.getId());
		assertNotNull(rx);
		assertNotNull(ry);
		assertEquals(section, rx.getSectionId());
		assertEquals(section, ry.getSectionId());
		// Both edge classes come back: internal and survivor→doomed.
		assertTrue(ry.getRequiredGoalIds().contains(x.getId()));
		assertTrue(api.findGoal(outside.getId()).getRequiredGoalIds().contains(y.getId()));
	}

	@Test
	@DisplayName("deleteSection with moveGoalsToDefault spares the goals, routing them to the default buckets; undoable")
	void deleteSectionMoveGoalsToDefault()
	{
		String section = api.createSection("Spared");
		store.setSectionAutoArchiveOverride(section, false); // keep completed inline
		Goal todo = Goal.builder().type(GoalType.CUSTOM).name("todo").sectionId(section).build();
		Goal done = Goal.builder().type(GoalType.CUSTOM).name("done")
			.completedAt(123L).status(GoalStatus.COMPLETE).sectionId(section).build();
		store.addGoal(todo);
		store.addGoal(done);

		assertTrue(api.deleteSection(section, true));
		assertNull(store.findSection(section));
		// Spared: incomplete → Incomplete, complete → Completed.
		assertNotNull(api.findGoal(todo.getId()));
		assertNotNull(api.findGoal(done.getId()));
		assertEquals(store.getIncompleteSection().getId(), todo.getSectionId());
		assertEquals(store.getCompletedSection().getId(), done.getSectionId());

		api.undo();
		assertNotNull(store.findSection(section));
		assertEquals(section, todo.getSectionId());
		assertEquals(section, done.getSectionId());
	}

	@Test
	@DisplayName("undoing 'create section' relocates its goals to the default bucket instead of deleting them")
	void undoCreateSectionEvacuatesGoals()
	{
		String section = api.createSection("Fresh");
		Goal kept = Goal.builder().type(GoalType.CUSTOM).name("kept").sectionId(section).build();
		store.addGoal(kept);
		assertEquals(section, kept.getSectionId());

		api.undo(); // undo the section creation
		assertNull(store.findSection(section));
		// The goal survives, evacuated to the default Incomplete bucket.
		assertNotNull(api.findGoal(kept.getId()));
		assertEquals(store.getIncompleteSection().getId(), kept.getSectionId());
	}

	@Test
	@DisplayName("setSectionAutoArchiveOverride to archive sweeps existing completed goals out; undoable")
	void setSectionAutoArchiveOverrideArchives()
	{
		String section = api.createSection("Boss Tasks");
		store.setSectionAutoArchiveOverride(section, false); // keep inline first
		Goal done = Goal.builder().type(GoalType.CUSTOM).name("done")
			.completedAt(123L).status(GoalStatus.COMPLETE).sectionId(section).build();
		store.addGoal(done);
		assertEquals(section, done.getSectionId());

		// Switching this section to archive graduates the completed goal out now.
		assertTrue(api.setSectionAutoArchiveOverride(section, Boolean.TRUE));
		assertEquals(Boolean.TRUE, store.findSection(section).getAutoArchiveOverride());
		assertEquals(store.getCompletedSection().getId(), done.getSectionId());

		// Undo restores the override (relocation follows reconcile semantics).
		api.undo();
		assertEquals(Boolean.FALSE, store.findSection(section).getAutoArchiveOverride());
	}

	@Test
	@DisplayName("removeDuplicateGoals collapses same-identity dups within a namespace (keeps complete), spares cross-section copies")
	void removeDuplicateGoals()
	{
		String secId = api.createSection("S");
		// Two "Quest X" in the default bucket (simulating archived dups); one complete.
		Goal d1 = Goal.builder().type(GoalType.QUEST).questName("X").build();
		Goal d2 = Goal.builder().type(GoalType.QUEST).questName("X")
			.completedAt(1L).status(GoalStatus.COMPLETE).build();
		// Same quest in a user section — a different namespace, must be spared.
		Goal s1 = Goal.builder().type(GoalType.QUEST).questName("X").sectionId(secId).build();
		store.addGoal(d1);
		store.addGoal(d2);
		store.addGoal(s1);
		assertEquals(3, store.getGoals().size());

		assertEquals(1, api.removeDuplicateGoals());
		assertEquals(2, store.getGoals().size());
		assertNotNull(api.findGoal(d2.getId())); // complete one kept
		assertNull(api.findGoal(d1.getId()));    // incomplete dup removed
		assertNotNull(api.findGoal(s1.getId()));  // cross-section copy spared

		api.undo();
		assertEquals(3, store.getGoals().size()); // undo restores the removed dup
	}

	// ====================================================================
	// Internal API: section CRUD
	// ====================================================================

	@Nested
	@DisplayName("section CRUD")
	class SectionCrudTests
	{
		@Test
		@DisplayName("createSection returns the new section's id")
		void createSectionReturnsId()
		{
			String id = api.createSection("Boss Tasks");
			assertNotNull(id);
			assertNotNull(store.findSection(id));
			assertEquals("Boss Tasks", store.findSection(id).getName());
		}

		@Test
		@DisplayName("createSection is idempotent on case-insensitive name")
		void createSectionIdempotent()
		{
			String first = api.createSection("Boss Tasks");
			String second = api.createSection("boss tasks");
			assertEquals(first, second);
		}

		@Test
		@DisplayName("createSection throws on invalid name")
		void createSectionRejectsInvalid()
		{
			assertThrows(IllegalArgumentException.class, () -> api.createSection(""));
			assertThrows(IllegalArgumentException.class, () -> api.createSection("Incomplete"));
		}

		@Test
		@DisplayName("renameSection succeeds for user sections")
		void renameSectionUser()
		{
			String id = api.createSection("Alpha");
			callbackFireCount.set(0);
			assertTrue(api.renameSection(id, "Apex"));
			assertEquals("Apex", store.findSection(id).getName());
			assertEquals(1, callbackFireCount.get());
		}

		@Test
		@DisplayName("renameSection rejects built-in sections")
		void renameSectionBuiltIn()
		{
			String incompleteId = store.getIncompleteSection().getId();
			assertFalse(api.renameSection(incompleteId, "Whatever"));
		}

		@Test
		@DisplayName("deleteSection deletes the section's goals with it")
		void deleteSectionDeletesGoals()
		{
			String sectionId = api.createSection("Boss Tasks");
			String goalId = api.addCustomGoal("Test", "");
			api.moveGoalToSection(goalId, sectionId);

			assertTrue(api.deleteSection(sectionId));
			assertNull(store.findSection(sectionId));
			// A section owns its goals — deleting it deletes them too.
			assertNull(api.findGoal(goalId));
		}

		@Test
		@DisplayName("moveGoalToSection enforces skill ordering on destination")
		void moveGoalToSectionEnforcesSkillOrdering()
		{
			String sectionId = api.createSection("Skill Goals");
			String prayer99 = api.addSkillGoal(Skill.PRAYER, 13_034_431);
			String prayer96 = api.addSkillGoal(Skill.PRAYER, 9_684_577);

			// Both currently in Incomplete. Move 99 to Skill Goals first.
			api.moveGoalToSection(prayer99, sectionId);
			// Then move 96 to Skill Goals — 96 should bubble above 99
			api.moveGoalToSection(prayer96, sectionId);

			// Find both in the goals list
			Goal g96 = store.getGoals().stream()
				.filter(g -> g.getId().equals(prayer96)).findFirst().orElseThrow();
			Goal g99 = store.getGoals().stream()
				.filter(g -> g.getId().equals(prayer99)).findFirst().orElseThrow();
			assertEquals(sectionId, g96.getSectionId());
			assertEquals(sectionId, g99.getSectionId());
			assertTrue(g96.getPriority() < g99.getPriority(),
				"L96 should be above L99 within the destination section");
		}
	}

	// ====================================================================
	// Internal API: color overrides
	// ====================================================================

	@Nested
	@DisplayName("color overrides")
	class ColorOverrideTests
	{
		@Test
		@DisplayName("setSectionColor stores override and fires callback")
		void setSectionColor()
		{
			String id = api.createSection("Custom");
			callbackFireCount.set(0);
			assertTrue(api.setSectionColor(id, 0xE74C3C));
			assertEquals(0xE74C3C, store.findSection(id).getColorRgb());
			assertEquals(1, callbackFireCount.get());
		}

		@Test
		@DisplayName("setSectionColor with -1 clears the override")
		void clearSectionColor()
		{
			String id = api.createSection("Custom");
			api.setSectionColor(id, 0xE74C3C);
			assertTrue(api.setSectionColor(id, -1));
			assertEquals(-1, store.findSection(id).getColorRgb());
		}

		@Test
		@DisplayName("setSectionColor no-op returns false (no callback)")
		void setSectionColorNoop()
		{
			String id = api.createSection("Custom");
			api.setSectionColor(id, 0xE74C3C);
			callbackFireCount.set(0);
			assertFalse(api.setSectionColor(id, 0xE74C3C));
			assertEquals(0, callbackFireCount.get());
		}

		@Test
		@DisplayName("setSectionColor works on built-in sections")
		void setSectionColorBuiltIn()
		{
			String id = store.getIncompleteSection().getId();
			assertTrue(api.setSectionColor(id, 0x3498DB));
			assertEquals(0x3498DB, store.findSection(id).getColorRgb());
		}

		@Test
		@DisplayName("setGoalColor works on all goal types")
		void setGoalColorAllTypes()
		{
			String customId = api.addCustomGoal("Custom", "");
			String skillId = api.addSkillGoal(Skill.ATTACK, 13_034_431);
			assertTrue(api.setGoalColor(customId, 0xE74C3C));
			assertTrue(api.setGoalColor(skillId, 0x2ECC71));
		}

		@Test
		@DisplayName("setTagColor recolors a non-skill tag entity")
		void setTagColor()
		{
			// setTagColor on a non-OTHER tag delegates to setCategoryColor.
			// The BOSS tag's color now reflects the BOSS category color.
			String goalId = api.addCustomGoal("Custom", "");
			com.goalplanner.model.Tag bossTag = store.findOrCreateSystemTag("Zulrah",
				com.goalplanner.model.TagCategory.BOSS);
			Goal g = store.getGoals().get(0);
			g.setTagIds(new java.util.ArrayList<>(List.of(bossTag.getId())));
			g.setDefaultTagIds(new java.util.ArrayList<>(List.of(bossTag.getId())));

			assertTrue(api.setTagColor(goalId, "Zulrah", 0xF1C40F));
			// Check the category override, not the per-tag field (BOSS has no per-tag colors)
			assertEquals(0xF1C40F, store.getCategoryColor(com.goalplanner.model.TagCategory.BOSS));
		}

		@Test
		@DisplayName("setTagColor on an OTHER tag uses per-tag override")
		void setTagColorOnOtherTag()
		{
			String goalId = api.addCustomGoal("Custom", "");
			com.goalplanner.model.Tag pet = store.findOrCreateSystemTag("Pet",
				com.goalplanner.model.TagCategory.OTHER);
			Goal g = store.getGoals().get(0);
			g.setTagIds(new java.util.ArrayList<>(List.of(pet.getId())));

			assertTrue(api.setTagColor(goalId, "Pet", 0xFF69B4));
			// Per-tag color stored on the Tag entity (OTHER is the only category that does this)
			assertEquals(0xFF69B4, store.findTag(pet.getId()).getColorRgb());
		}

		@Test
		@DisplayName("setTagColor on a SKILLING tag delegates to the SKILLING category color")
		void setTagColorOnSkillingDelegatesToCategory()
		{
			String goalId = api.addCustomGoal("Custom", "");
			com.goalplanner.model.Tag slayerTag = store.findOrCreateSystemTag("Slayer",
				com.goalplanner.model.TagCategory.SKILLING);
			Goal g = store.getGoals().get(0);
			g.setTagIds(new java.util.ArrayList<>(List.of(slayerTag.getId())));

			assertTrue(api.setTagColor(goalId, "Slayer", 0xF1C40F));
			assertEquals(0xF1C40F, store.getCategoryColor(com.goalplanner.model.TagCategory.SKILLING));
		}
	}

	// ====================================================================
	// Internal API: selection
	// ====================================================================

	@Nested
	@DisplayName("selection")
	class SelectionTests
	{
		@Test
		@DisplayName("addToGoalSelection adds an id and fires callback")
		void addToGoalSelection()
		{
			String id = api.addCustomGoal("Test", "");
			callbackFireCount.set(0);

			assertTrue(api.addToGoalSelection(id));
			assertTrue(api.getSelectedGoalIds().contains(id));
			assertEquals(1, callbackFireCount.get());
		}

		@Test
		@DisplayName("addToGoalSelection no-op for already-selected")
		void addToGoalSelectionNoop()
		{
			String id = api.addCustomGoal("Test", "");
			api.addToGoalSelection(id);
			callbackFireCount.set(0);

			assertFalse(api.addToGoalSelection(id));
			assertEquals(0, callbackFireCount.get());
		}

		@Test
		@DisplayName("removeFromGoalSelection removes an id and fires callback")
		void removeFromGoalSelection()
		{
			String id = api.addCustomGoal("Test", "");
			api.addToGoalSelection(id);
			callbackFireCount.set(0);

			assertTrue(api.removeFromGoalSelection(id));
			assertFalse(api.getSelectedGoalIds().contains(id));
			assertEquals(1, callbackFireCount.get());
		}

		@Test
		@DisplayName("replaceGoalSelection swaps the entire selection")
		void replaceGoalSelection()
		{
			String a = api.addCustomGoal("A", "");
			String b = api.addCustomGoal("B", "");
			String c = api.addCustomGoal("C", "");

			api.addToGoalSelection(a);
			api.addToGoalSelection(b);
			assertTrue(api.replaceGoalSelection(Set.of(c)));

			assertEquals(Set.of(c), api.getSelectedGoalIds());
		}

		@Test
		@DisplayName("clearGoalSelection wipes everything and fires once")
		void clearGoalSelection()
		{
			String a = api.addCustomGoal("A", "");
			String b = api.addCustomGoal("B", "");
			api.addToGoalSelection(a);
			api.addToGoalSelection(b);
			callbackFireCount.set(0);

			assertTrue(api.clearGoalSelection());
			assertTrue(api.getSelectedGoalIds().isEmpty());
			assertEquals(1, callbackFireCount.get());
		}

		@Test
		@DisplayName("clearGoalSelection on empty set is a no-op")
		void clearGoalSelectionNoop()
		{
			assertFalse(api.clearGoalSelection());
		}

		@Test
		@DisplayName("selectAllInSection adds all goals in the section")
		void selectAllInSection()
		{
			String sectionId = api.createSection("Custom");
			String a = api.addCustomGoal("A", "");
			String b = api.addCustomGoal("B", "");
			api.moveGoalToSection(a, sectionId);
			api.moveGoalToSection(b, sectionId);

			int added = api.selectAllInSection(sectionId);
			assertEquals(2, added);
			assertEquals(Set.of(a, b), api.getSelectedGoalIds());
		}

		@Test
		@DisplayName("deselectAllInSection removes all section goals from selection")
		void deselectAllInSection()
		{
			String sectionId = api.createSection("Custom");
			String a = api.addCustomGoal("A", "");
			String b = api.addCustomGoal("B", "");
			api.moveGoalToSection(a, sectionId);
			api.moveGoalToSection(b, sectionId);
			api.selectAllInSection(sectionId);

			int removed = api.deselectAllInSection(sectionId);
			assertEquals(2, removed);
			assertTrue(api.getSelectedGoalIds().isEmpty());
		}
	}

	// ====================================================================
	// Internal API: recordGoalProgress
	// ====================================================================

	@Nested
	@DisplayName("recordGoalProgress")
	class RecordGoalProgressTests
	{
		@Test
		@DisplayName("updates currentValue and returns true on change")
		void updatesCurrentValue()
		{
			String id = api.addSkillGoal(Skill.ATTACK, 13_034_431);
			assertTrue(api.recordGoalProgress(id, 5_000_000));
			assertEquals(5_000_000, store.getGoals().get(0).getCurrentValue());
		}

		@Test
		@DisplayName("no-op when newValue equals currentValue")
		void noopOnSameValue()
		{
			String id = api.addSkillGoal(Skill.ATTACK, 13_034_431);
			api.recordGoalProgress(id, 5_000_000);
			assertFalse(api.recordGoalProgress(id, 5_000_000));
		}

		@Test
		@DisplayName("flips to COMPLETE when target reached")
		void flipsToComplete()
		{
			String id = api.addSkillGoal(Skill.ATTACK, 13_034_431);
			api.recordGoalProgress(id, 13_034_431);
			Goal g = store.getGoals().get(0);
			assertTrue(g.isComplete());
			assertTrue(g.getCompletedAt() > 0);
		}

		@Test
		@DisplayName("symmetric un-complete when value drops below target")
		void unCompletesOnDrop()
		{
			Goal g = Goal.builder().type(GoalType.ITEM_GRIND).name("Coal")
				.itemId(1).targetValue(100).currentValue(0).build();
			store.addGoal(g);

			api.recordGoalProgress(g.getId(), 100);
			assertTrue(g.isComplete());

			api.recordGoalProgress(g.getId(), 50);
			assertFalse(g.isComplete());
			assertEquals(0, g.getCompletedAt());
			assertEquals(GoalStatus.ACTIVE, g.getStatus());
		}

		@Test
		@DisplayName("does NOT save or fire onGoalsChanged (silent mutation)")
		void silentMutation()
		{
			String id = api.addSkillGoal(Skill.ATTACK, 13_034_431);
			callbackFireCount.set(0);
			api.recordGoalProgress(id, 5_000_000);
			assertEquals(0, callbackFireCount.get(),
				"recordGoalProgress must not fire onGoalsChanged — trackers batch via the GameTick handler");
		}
	}

	// ====================================================================
	// changeTarget regenerates display strings
	// ====================================================================

	@Nested
	@DisplayName("changeTarget display-string regeneration")
	class ChangeTargetDisplayStringTests
	{
		@Test
		@DisplayName("regenerates SKILL goal name from new XP target")
		void regeneratesSkillName()
		{
			String id = api.addSkillGoal(Skill.PRAYER, 13_034_431);
			assertEquals("Prayer - Level 99", store.getGoals().get(0).getName());

			api.changeTarget(id, 9_684_577); // L96
			assertEquals("Prayer - Level 96", store.getGoals().get(0).getName());
		}

		@Test
		@DisplayName("regenerates ITEM_GRIND description from new quantity")
		void regeneratesItemDescription()
		{
			Goal g = Goal.builder().type(GoalType.ITEM_GRIND).name("Cannonballs")
				.itemId(2).targetValue(100).description("100 total").build();
			store.addGoal(g);

			api.changeTarget(g.getId(), 5000);
			assertEquals("5K total", g.getDescription());
		}

		@Test
		@DisplayName("rejects target change for non-SKILL/non-ITEM_GRIND types")
		void rejectsNonEditableTypes()
		{
			Goal quest = Goal.builder().type(GoalType.QUEST).name("Quest")
				.questName("DRAGON_SLAYER_II").targetValue(1).build();
			store.addGoal(quest);
			assertFalse(api.changeTarget(quest.getId(), 2));
		}
	}

	// ====================================================================
	// changeTarget completion re-evaluation (completed goals are retargetable)
	// ====================================================================

	@Nested
	@DisplayName("changeTarget completion re-evaluation")
	class ChangeTargetCompletionTests
	{
		private Goal completedItemGoal(int current, int target)
		{
			Goal g = Goal.builder().type(GoalType.ITEM_GRIND).name("Cannonballs")
				.itemId(2).currentValue(current).targetValue(target)
				.description(target + " total").build();
			g.setStatus(GoalStatus.COMPLETE);
			g.setCompletedAt(123_456_789L);
			store.addGoal(g);
			store.reconcileDerivedSections(); // archive into Completed like the live flow
			return g;
		}

		@Test
		@DisplayName("raising the target past the recorded progress reopens a completed goal")
		void raisingTargetReopens()
		{
			Goal g = completedItemGoal(100, 100);
			assertEquals(store.getCompletedSection().getId(), g.getSectionId());

			assertTrue(api.changeTarget(g.getId(), 200));
			assertEquals(GoalStatus.ACTIVE, g.getStatus());
			assertEquals(0, g.getCompletedAt());
			assertEquals(store.getIncompleteSection().getId(), g.getSectionId(),
				"reopened goal leaves the Completed section");
		}

		@Test
		@DisplayName("lowering the target to the recorded progress completes an active goal")
		void loweringTargetCompletes()
		{
			Goal g = Goal.builder().type(GoalType.ITEM_GRIND).name("Cannonballs")
				.itemId(2).currentValue(80).targetValue(100)
				.description("100 total").build();
			store.addGoal(g);

			assertTrue(api.changeTarget(g.getId(), 50));
			assertEquals(GoalStatus.COMPLETE, g.getStatus());
			assertTrue(g.getCompletedAt() > 0);
			assertEquals(store.getCompletedSection().getId(), g.getSectionId(),
				"newly-met goal archives into Completed");
		}

		@Test
		@DisplayName("undo restores the prior completion state and section")
		void undoRestoresCompletion()
		{
			Goal g = completedItemGoal(100, 100);
			api.changeTarget(g.getId(), 200);
			assertEquals(GoalStatus.ACTIVE, g.getStatus());

			api.undo();
			assertEquals(GoalStatus.COMPLETE, g.getStatus());
			assertEquals(123_456_789L, g.getCompletedAt(), "original completion timestamp survives");
			assertEquals(100, g.getTargetValue());
			assertEquals(store.getCompletedSection().getId(), g.getSectionId());
		}

		@Test
		@DisplayName("re-typing the same target is a no-op and keeps a manual completion")
		void sameTargetIsNoOp()
		{
			// Manually-completed goal whose recorded progress is below target —
			// re-evaluating it would reopen it, so a same-value change must not.
			Goal g = completedItemGoal(0, 1);
			assertFalse(api.changeTarget(g.getId(), 1));
			assertEquals(GoalStatus.COMPLETE, g.getStatus());
		}

		@Test
		@DisplayName("an ACCOUNT goal without a persisted sprite renders the metric's sprite")
		void importedAccountGoalGetsMetricSprite()
		{
			// Built like a share import: accountMetric set, spriteId left 0.
			Goal g = Goal.builder().type(GoalType.ACCOUNT).name("Quest Points")
				.accountMetric("QUEST_POINTS").targetValue(335).build();
			store.addGoal(g);
			assertEquals(com.goalplanner.model.AccountMetric.QUEST_POINTS.getSpriteId(),
				api.queryGoalView(g.getId()).spriteId,
				"icon derives from the metric at query time, not the creation path");
		}

		@Test
		@DisplayName("a persisted sprite on an ACCOUNT goal is never overridden")
		void persistedSpriteWins()
		{
			Goal g = Goal.builder().type(GoalType.ACCOUNT).name("QP")
				.accountMetric("QUEST_POINTS").targetValue(335).spriteId(1234).build();
			store.addGoal(g);
			assertEquals(1234, api.queryGoalView(g.getId()).spriteId);
		}

		@Test
		@DisplayName("reopens a completed SKILL goal when the XP target is raised")
		void skillGoalReopens()
		{
			String id = api.addSkillGoal(Skill.PRAYER, 1000);
			api.recordGoalProgress(id, 1000); // tracker-style completion
			Goal g = api.findGoal(id);
			assertEquals(GoalStatus.COMPLETE, g.getStatus());

			assertTrue(api.changeTarget(id, 50_000));
			assertEquals(GoalStatus.ACTIVE, g.getStatus());
			assertEquals("Prayer - Level " + net.runelite.api.Experience.getLevelForXp(50_000),
				g.getName(), "name regenerates alongside the re-evaluation");
		}
	}

	// ====================================================================
	// addTagWithCategory (legacy panel direct-mutation removal)
	// ====================================================================

	@Nested
	@DisplayName("addTagWithCategory")
	class AddTagWithCategoryTests
	{
		@Test
		@DisplayName("adds a tag with the requested category and fires callback")
		void addsTagWithCategory()
		{
			String id = api.addCustomGoal("Custom", "");
			callbackFireCount.set(0);

			assertTrue(api.addTagWithCategory(id, "Zulrah", "BOSS"));

			Goal g = store.getGoals().get(0);
			assertEquals(1, g.getTagIds().size());
			com.goalplanner.model.Tag tag = store.findTag(g.getTagIds().get(0));
			assertNotNull(tag);
			assertEquals("Zulrah", tag.getLabel());
			assertEquals(TagCategory.BOSS, tag.getCategory());
			assertEquals(1, callbackFireCount.get());
		}

		@Test
		@DisplayName("rejects unknown category names")
		void rejectsUnknownCategory()
		{
			String id = api.addCustomGoal("Custom", "");
			assertFalse(api.addTagWithCategory(id, "Test", "NONEXISTENT_CATEGORY"));
		}

		@Test
		@DisplayName("rejects null/blank label")
		void rejectsBlankLabel()
		{
			String id = api.addCustomGoal("Custom", "");
			assertFalse(api.addTagWithCategory(id, null, "BOSS"));
			assertFalse(api.addTagWithCategory(id, "", "BOSS"));
			assertFalse(api.addTagWithCategory(id, "   ", "BOSS"));
		}

		@Test
		@DisplayName("rejects unknown goal id")
		void rejectsUnknownGoalId()
		{
			assertFalse(api.addTagWithCategory("nonexistent", "Test", "BOSS"));
		}

		@Test
		@DisplayName("trims whitespace from the label")
		void trimsWhitespace()
		{
			String id = api.addCustomGoal("Custom", "");
			api.addTagWithCategory(id, "  Vorkath  ", "BOSS");
			Goal g = store.getGoals().get(0);
			com.goalplanner.model.Tag tag = store.findTag(g.getTagIds().get(0));
			assertEquals("Vorkath", tag.getLabel());
		}
	}

	// ====================================================================
	// Tag entity CRUD
	// ====================================================================

	@Nested
	@DisplayName("tag entity CRUD")
	class TagEntityTests
	{
		@Test
		@DisplayName("createUserTag returns the new tag id")
		void createUserTag()
		{
			String id = api.createUserTag("Pets", "OTHER");
			assertNotNull(id);
			assertEquals("Pets", store.findTag(id).getLabel());
			assertFalse(store.findTag(id).isSystem());
		}

		@Test
		@DisplayName("createUserTag is idempotent on case-insensitive (label, category)")
		void createUserTagIdempotent()
		{
			String first = api.createUserTag("Pets", "OTHER");
			String second = api.createUserTag("pets", "OTHER");
			assertEquals(first, second);
		}

		@Test
		@DisplayName("renameTag updates the label and propagates via the entity")
		void renameTag()
		{
			String id = api.createUserTag("Pets", "OTHER");
			assertTrue(api.renameTag(id, "All Pets"));
			assertEquals("All Pets", store.findTag(id).getLabel());
		}

		@Test
		@DisplayName("renameTag rejects system tags")
		void renameTagRejectsSystem()
		{
			com.goalplanner.model.Tag system = store.findOrCreateSystemTag("Slayer",
				com.goalplanner.model.TagCategory.SKILLING);
			assertFalse(api.renameTag(system.getId(), "Slayer Task"));
		}

		@Test
		@DisplayName("recolorTag on a BOSS tag delegates to the BOSS category color")
		void recolorNonSkillSystemTag()
		{
			com.goalplanner.model.Tag bossTag = store.findOrCreateSystemTag("Zulrah",
				com.goalplanner.model.TagCategory.BOSS);
			assertTrue(api.recolorTag(bossTag.getId(), 0xE74C3C));
			// Check the category color (per-tag colorRgb is meaningless for BOSS)
			assertEquals(0xE74C3C, store.getCategoryColor(com.goalplanner.model.TagCategory.BOSS));
		}

		@Test
		@DisplayName("recolorTag on a SKILLING tag delegates to the SKILLING category color")
		void recolorSkillingTagDelegatesToCategory()
		{
			com.goalplanner.model.Tag slayer = store.findOrCreateSystemTag("Slayer",
				com.goalplanner.model.TagCategory.SKILLING);
			assertTrue(api.recolorTag(slayer.getId(), 0xE74C3C));
			assertEquals(0xE74C3C, store.getCategoryColor(com.goalplanner.model.TagCategory.SKILLING));
		}

		@Test
		@DisplayName("recolorTag on an OTHER tag uses per-tag override")
		void recolorOtherTagPerInstance()
		{
			com.goalplanner.model.Tag pet = store.findOrCreateSystemTag("Pet",
				com.goalplanner.model.TagCategory.OTHER);
			com.goalplanner.model.Tag other = store.createUserTag("Misc",
				com.goalplanner.model.TagCategory.OTHER);

			assertTrue(api.recolorTag(pet.getId(), 0xFF69B4));
			// Pet has the override; Misc still uses the OTHER default (no category color for OTHER)
			assertEquals(0xFF69B4, store.findTag(pet.getId()).getColorRgb());
			assertEquals(-1, store.findTag(other.getId()).getColorRgb());
		}

		@Test
		@DisplayName("setCategoryColor accepts BOSS and SKILLING, rejects OTHER")
		void setCategoryColorScope()
		{
			assertTrue(api.setCategoryColor("BOSS", 0xE74C3C));
			// SKILLING accepts the override — system skill tags ignore it (icons)
			// but user-created SKILLING tags fall through to colored pills.
			assertTrue(api.setCategoryColor("SKILLING", 0xE74C3C));
			// OTHER uses per-tag colors, not a category-wide color.
			assertFalse(api.setCategoryColor("OTHER", 0xE74C3C));
		}

		@Test
		@DisplayName("Boss goal added AFTER setting BOSS category color picks up the custom color")
		void newBossGoalInheritsCategoryColor() throws Exception
		{
			// 1. User changes the BOSS category color to a custom red
			final int customRed = 0xE74C3C;
			assertTrue(api.setCategoryColor("BOSS", customRed));

			// 2. Stub the wiki repo so addCombatAchievementGoal succeeds. Use
			//    a real CaInfo struct because the impl reads multiple fields.
			com.goalplanner.data.WikiCaRepository wikiMock = api.wikiCaRepository;
			com.goalplanner.data.WikiCaRepository.CaInfo info =
				new com.goalplanner.data.WikiCaRepository.CaInfo();
			info.id = 42;
			info.name = "Defeat Vorkath";
			info.task = "Defeat Vorkath without taking damage";
			info.tier = "Hard";
			info.monster = "Vorkath";
			info.type = "Mechanical";
			when(wikiMock.getById(42)).thenReturn(info);

			// 3. Add the CA goal — addCombatAchievementGoal will call
			//    findOrCreateSystemTag("Vorkath", BOSS) which creates a fresh
			//    system tag in the BOSS category.
			String goalId = api.addCombatAchievementGoal(42);
			assertNotNull(goalId);

			// 4. Query the goal back via the public read API and assert that
			//    the BOSS tag's TagView reflects the custom red. This proves
			//    that newly-created system tags pick up the category color
			//    override at render time (not at creation time).
			com.goalplanner.api.GoalView view = api.queryAllGoals().stream()
				.filter(g -> g.id.equals(goalId)).findFirst().orElseThrow();
			com.goalplanner.api.TagView vorkathTag = null;
			for (com.goalplanner.api.TagView t : view.defaultTags)
			{
				if ("Vorkath".equals(t.label))
				{
					vorkathTag = t;
					break;
				}
			}
			assertNotNull(vorkathTag, "BOSS tag should appear in the goal's default tags");
			assertEquals("BOSS", vorkathTag.category);
			assertEquals(customRed, vorkathTag.colorRgb);
			assertTrue(vorkathTag.colorOverridden);
		}

		@Test
		@DisplayName("Existing boss tag updates color when BOSS category color changes again")
		void existingBossTagUpdatesOnSecondRecolor() throws Exception
		{
			// 1. Set BOSS to red and create a CA goal so a Vorkath system tag exists
			final int red = 0xE74C3C;
			final int blue = 0x3498DB;
			assertTrue(api.setCategoryColor("BOSS", red));

			com.goalplanner.data.WikiCaRepository wikiMock = api.wikiCaRepository;
			com.goalplanner.data.WikiCaRepository.CaInfo info =
				new com.goalplanner.data.WikiCaRepository.CaInfo();
			info.id = 42;
			info.name = "Defeat Vorkath";
			info.task = "Defeat Vorkath without taking damage";
			info.tier = "Hard";
			info.monster = "Vorkath";
			info.type = "Mechanical";
			when(wikiMock.getById(42)).thenReturn(info);

			String goalId = api.addCombatAchievementGoal(42);
			assertNotNull(goalId);

			// 2. Confirm the tag is red
			com.goalplanner.api.TagView tag = findVorkathTag(goalId);
			assertEquals(red, tag.colorRgb);

			// 3. Change the BOSS category color to blue (the existing goal stays put)
			assertTrue(api.setCategoryColor("BOSS", blue));

			// 4. Re-query the same goal — the tag should now reflect blue.
			//    This proves toTagView reads the category color at render time
			//    rather than caching it on the tag at creation.
			com.goalplanner.api.TagView reread = findVorkathTag(goalId);
			assertEquals(blue, reread.colorRgb);
			assertTrue(reread.colorOverridden);

			// 5. Reset the override and confirm the tag now uses the BOSS default
			assertTrue(api.resetCategoryColor("BOSS"));
			com.goalplanner.api.TagView afterReset = findVorkathTag(goalId);
			assertFalse(afterReset.colorOverridden);
			assertEquals(afterReset.defaultColorRgb, afterReset.colorRgb);
		}

		private com.goalplanner.api.TagView findVorkathTag(String goalId)
		{
			com.goalplanner.api.GoalView view = api.queryAllGoals().stream()
				.filter(g -> g.id.equals(goalId)).findFirst().orElseThrow();
			for (com.goalplanner.api.TagView t : view.defaultTags)
			{
				if ("Vorkath".equals(t.label)) return t;
			}
			throw new AssertionError("Vorkath tag not found on goal");
		}

		// =====================================================================
		// Icon tags
		// =====================================================================

		@Test
		@DisplayName("setTagIcon stores the iconKey on the tag entity")
		void setTagIconHappyPath()
		{
			com.goalplanner.model.Tag tag = store.findOrCreateSystemTag("Slayer",
				com.goalplanner.model.TagCategory.SKILLING);
			assertTrue(api.setTagIcon(tag.getId(), "SLAYER"));
			assertEquals("SLAYER", store.findTag(tag.getId()).getIconKey());
		}

		@Test
		@DisplayName("clearTagIcon nulls out the iconKey")
		void clearTagIconResets()
		{
			com.goalplanner.model.Tag tag = store.findOrCreateSystemTag("Slayer",
				com.goalplanner.model.TagCategory.SKILLING);
			api.setTagIcon(tag.getId(), "SLAYER");
			assertTrue(api.clearTagIcon(tag.getId()));
			assertNull(store.findTag(tag.getId()).getIconKey());
		}

		@Test
		@DisplayName("setTagIcon no-op returns false when value is unchanged")
		void setTagIconNoop()
		{
			com.goalplanner.model.Tag tag = store.findOrCreateSystemTag("Slayer",
				com.goalplanner.model.TagCategory.SKILLING);
			api.setTagIcon(tag.getId(), "SLAYER");
			assertFalse(api.setTagIcon(tag.getId(), "SLAYER"));
		}

		@Test
		@DisplayName("setTagIcon trims whitespace and treats blank as clear")
		void setTagIconBlankIsClear()
		{
			com.goalplanner.model.Tag tag = store.findOrCreateSystemTag("Slayer",
				com.goalplanner.model.TagCategory.SKILLING);
			api.setTagIcon(tag.getId(), "SLAYER");
			assertTrue(api.setTagIcon(tag.getId(), "   "));
			assertNull(store.findTag(tag.getId()).getIconKey());
		}

		@Test
		@DisplayName("TagView.iconKey populated from the entity at render time")
		void tagViewCarriesIconKey()
		{
			String goalId = api.addCustomGoal("Test", "");
			com.goalplanner.model.Tag tag = store.findOrCreateSystemTag("Vorkath",
				com.goalplanner.model.TagCategory.BOSS);
			api.setTagIcon(tag.getId(), "VORKATH");
			Goal g = store.getGoals().get(0);
			g.setTagIds(new java.util.ArrayList<>(java.util.List.of(tag.getId())));
			g.setDefaultTagIds(new java.util.ArrayList<>(java.util.List.of(tag.getId())));

			com.goalplanner.api.GoalView view = api.queryAllGoals().stream()
				.filter(gv -> gv.id.equals(goalId)).findFirst().orElseThrow();
			com.goalplanner.api.TagView tv = view.defaultTags.get(0);
			assertEquals("VORKATH", tv.iconKey);
		}

		@Test
		@DisplayName("deleteTag removes the entity and cascades to goal references")
		void deleteTagCascades()
		{
			String tagId = api.createUserTag("ToDelete", "OTHER");
			String goalId = api.addCustomGoal("Custom", "");
			api.addTagWithCategory(goalId, "ToDelete", "OTHER");
			Goal g = store.getGoals().get(0);
			assertEquals(1, g.getTagIds().size());

			assertTrue(api.deleteTag(tagId));
			assertNull(store.findTag(tagId));
			assertEquals(0, g.getTagIds().size());
		}

		@Test
		@DisplayName("deleteTag rejects system tags")
		void deleteTagRejectsSystem()
		{
			com.goalplanner.model.Tag system = store.findOrCreateSystemTag("Slayer",
				com.goalplanner.model.TagCategory.SKILLING);
			assertFalse(api.deleteTag(system.getId()));
			assertNotNull(store.findTag(system.getId()));
		}

		@Test
		@DisplayName("queryAllTags returns every tag in the store")
		void queryAllTags()
		{
			api.createUserTag("Tag1", "OTHER");
			api.createUserTag("Tag2", "BOSS");
			store.findOrCreateSystemTag("Slayer", com.goalplanner.model.TagCategory.SKILLING);
			assertEquals(3, api.queryAllTags().size());
		}
	}

	@Nested
	@DisplayName("Bulk multi-selection actions")
	class BulkActions
	{
		private Goal addCustomWithDefaults(String name, String... defaultTagIds)
		{
			String id = api.addCustomGoal(name, "");
			Goal g = store.getGoals().stream().filter(x -> id.equals(x.getId())).findFirst().orElseThrow();
			java.util.List<String> defaults = new java.util.ArrayList<>(java.util.Arrays.asList(defaultTagIds));
			g.setDefaultTagIds(defaults);
			g.setTagIds(new java.util.ArrayList<>(defaults));
			return g;
		}

		@Test
		@DisplayName("isGoalOverridden true when color is set")
		void overriddenByColor()
		{
			Goal g = addCustomWithDefaults("Foo");
			assertFalse(api.isGoalOverridden(g.getId()));
			api.setGoalColor(g.getId(), 0x00FF00);
			assertTrue(api.isGoalOverridden(g.getId()));
		}

		@Test
		@DisplayName("isGoalOverridden true when tags drift from defaults")
		void overriddenByTagDrift()
		{
			com.goalplanner.model.Tag defaultTag = store.findOrCreateSystemTag(
				"Slayer", com.goalplanner.model.TagCategory.SKILLING);
			Goal g = addCustomWithDefaults("Foo", defaultTag.getId());
			assertFalse(api.isGoalOverridden(g.getId()));
			com.goalplanner.model.Tag extra = store.createUserTag(
				"Extra", com.goalplanner.model.TagCategory.OTHER);
			g.getTagIds().add(extra.getId());
			assertTrue(api.isGoalOverridden(g.getId()));
		}

		@Test
		@DisplayName("bulkRestoreDefaults resets eligible goals and skips clean ones")
		void bulkRestoreHappyPath()
		{
			com.goalplanner.model.Tag t1 = store.findOrCreateSystemTag(
				"Slayer", com.goalplanner.model.TagCategory.SKILLING);
			Goal a = addCustomWithDefaults("A", t1.getId());
			Goal b = addCustomWithDefaults("B", t1.getId());
			Goal c = addCustomWithDefaults("C", t1.getId()); // clean

			com.goalplanner.model.Tag extra = store.createUserTag(
				"Extra", com.goalplanner.model.TagCategory.OTHER);
			a.getTagIds().add(extra.getId());
			api.setGoalColor(b.getId(), 0xFF0000);

			int changed = api.bulkRestoreDefaults(new java.util.HashSet<>(
				java.util.Arrays.asList(a.getId(), b.getId(), c.getId())));
			assertEquals(2, changed);
			assertEquals(java.util.List.of(t1.getId()), a.getTagIds());
			assertEquals(-1, b.getCustomColorRgb());
			assertFalse(api.isGoalOverridden(c.getId()));
		}

		@Test
		@DisplayName("bulkRestoreDefaults returns 0 on empty selection")
		void bulkRestoreEmpty()
		{
			assertEquals(0, api.bulkRestoreDefaults(java.util.Collections.emptySet()));
			assertEquals(0, api.bulkRestoreDefaults(null));
		}

		@Test
		@DisplayName("bulkRemoveTagFromGoals removes from every goal that has it removably")
		void bulkRemoveTagHappyPath()
		{
			com.goalplanner.model.Tag def = store.findOrCreateSystemTag(
				"Slayer", com.goalplanner.model.TagCategory.SKILLING);
			com.goalplanner.model.Tag user = store.createUserTag(
				"Hot", com.goalplanner.model.TagCategory.OTHER);

			Goal a = addCustomWithDefaults("A", def.getId());
			a.getTagIds().add(user.getId());
			Goal b = addCustomWithDefaults("B", def.getId());
			b.getTagIds().add(user.getId());
			Goal c = addCustomWithDefaults("C", def.getId()); // doesn't have it

			int removed = api.bulkRemoveTagFromGoals(
				new java.util.HashSet<>(java.util.Arrays.asList(a.getId(), b.getId(), c.getId())),
				user.getId());
			assertEquals(2, removed);
			assertFalse(a.getTagIds().contains(user.getId()));
			assertFalse(b.getTagIds().contains(user.getId()));
		}

		@Test
		@DisplayName("bulkRemoveTagFromGoals skips default tags on non-CUSTOM goals")
		void bulkRemoveTagSkipsDefaultsOnNonCustom()
		{
			// Use a SKILL goal so default tags are non-removable
			com.goalplanner.model.Tag def = store.findOrCreateSystemTag(
				"Slayer", com.goalplanner.model.TagCategory.SKILLING);
			String skillGoalId = api.addSkillGoal(net.runelite.api.Skill.ATTACK, 99);
			Goal sg = store.getGoals().stream().filter(x -> skillGoalId.equals(x.getId()))
				.findFirst().orElseThrow();
			sg.setDefaultTagIds(new java.util.ArrayList<>(java.util.List.of(def.getId())));
			sg.setTagIds(new java.util.ArrayList<>(java.util.List.of(def.getId())));

			int removed = api.bulkRemoveTagFromGoals(
				java.util.Set.of(skillGoalId), def.getId());
			assertEquals(0, removed);
			assertTrue(sg.getTagIds().contains(def.getId()));
		}

		@Test
		@DisplayName("getRemovableTagsForSelection counts and sorts correctly")
		void removableTagsCountsAndSort()
		{
			com.goalplanner.model.Tag t1 = store.createUserTag("Alpha", com.goalplanner.model.TagCategory.OTHER);
			com.goalplanner.model.Tag t2 = store.createUserTag("Bravo", com.goalplanner.model.TagCategory.OTHER);
			com.goalplanner.model.Tag t3 = store.createUserTag("Charlie", com.goalplanner.model.TagCategory.OTHER);

			Goal a = addCustomWithDefaults("A");
			a.getTagIds().add(t1.getId());
			a.getTagIds().add(t2.getId());
			Goal b = addCustomWithDefaults("B");
			b.getTagIds().add(t1.getId());
			b.getTagIds().add(t3.getId());
			Goal c = addCustomWithDefaults("C");
			c.getTagIds().add(t1.getId());

			java.util.List<GoalPlannerInternalApi.TagRemovalOption> opts =
				api.getRemovableTagsForSelection(new java.util.HashSet<>(
					java.util.Arrays.asList(a.getId(), b.getId(), c.getId())));
			assertEquals(3, opts.size());
			assertEquals("Alpha", opts.get(0).label); // count 3
			assertEquals(3, opts.get(0).count);
			// Bravo and Charlie both at count 1, sorted alphabetically
			assertEquals("Bravo", opts.get(1).label);
			assertEquals("Charlie", opts.get(2).label);
		}

		@Test
		@DisplayName("getRemovableTagsForSelection empty selection returns empty list")
		void removableTagsEmpty()
		{
			assertTrue(api.getRemovableTagsForSelection(java.util.Collections.emptySet()).isEmpty());
			assertTrue(api.getRemovableTagsForSelection(null).isEmpty());
		}
	}

	@Nested
	@DisplayName("positionGoalInSection")
	class PositionGoalInSection
	{
		@Test
		@DisplayName("places a goal at the requested in-section index")
		void placesAtIndex()
		{
			String secId = api.createSection("Bossing");
			String a = api.addCustomGoal("A", "");
			String b = api.addCustomGoal("B", "");
			String c = api.addCustomGoal("C", "");
			api.moveGoalToSection(a, secId);
			api.moveGoalToSection(b, secId);
			api.moveGoalToSection(c, secId);

			// Move B to position 0 within Bossing
			assertTrue(api.positionGoalInSection(b, secId, 0));

			java.util.List<String> ids = store.getGoals().stream()
				.filter(g -> secId.equals(g.getSectionId()))
				.map(Goal::getId).collect(java.util.stream.Collectors.toList());
			assertEquals(b, ids.get(0));
		}

		@Test
		@DisplayName("clamps out-of-range positions to the section bounds")
		void clampsOutOfRange()
		{
			String secId = api.createSection("S");
			String a = api.addCustomGoal("A", "");
			String b = api.addCustomGoal("B", "");
			api.moveGoalToSection(a, secId);
			api.moveGoalToSection(b, secId);

			// Position 99 should clamp to last slot — A moves to bottom
			api.positionGoalInSection(a, secId, 99);
			java.util.List<String> ids = store.getGoals().stream()
				.filter(g -> secId.equals(g.getSectionId()))
				.map(Goal::getId).collect(java.util.stream.Collectors.toList());
			assertEquals(a, ids.get(ids.size() - 1));
		}

		@Test
		@DisplayName("moves goal to a different section AND positions it")
		void crossSectionMove()
		{
			String src = api.createSection("Source");
			String dst = api.createSection("Dest");
			String a = api.addCustomGoal("A", "");
			String x = api.addCustomGoal("X", "");
			String y = api.addCustomGoal("Y", "");
			api.moveGoalToSection(a, src);
			api.moveGoalToSection(x, dst);
			api.moveGoalToSection(y, dst);

			// Move A to position 1 in Dest (between X and Y)
			assertTrue(api.positionGoalInSection(a, dst, 1));

			java.util.List<String> ids = store.getGoals().stream()
				.filter(g -> dst.equals(g.getSectionId()))
				.map(Goal::getId).collect(java.util.stream.Collectors.toList());
			assertEquals(java.util.List.of(x, a, y), ids);
		}

		@Test
		@DisplayName("returns false when goal id is missing")
		void rejectsMissingGoal()
		{
			String secId = api.createSection("S");
			assertFalse(api.positionGoalInSection("nope", secId, 0));
		}

		@Test
		@DisplayName("moveGoalToSection rejects no-op when goal is already in target section")
		void moveGoalToSameSectionIsNoop()
		{
			String secId = api.createSection("Same");
			String goalId = api.addCustomGoal("G", "");
			api.moveGoalToSection(goalId, secId);
			// Second call to the same section should be a no-op
			assertFalse(api.moveGoalToSection(goalId, secId));
		}

		@Test
		@DisplayName("moveGoalToSection rejects no-op when goal is already in Incomplete (default)")
		void moveGoalToCurrentBuiltInIsNoop()
		{
			String goalId = api.addCustomGoal("G", "");
			Goal g = store.getGoals().stream().filter(x -> goalId.equals(x.getId()))
				.findFirst().orElseThrow();
			String currentSection = g.getSectionId();
			assertNotNull(currentSection);
			assertFalse(api.moveGoalToSection(goalId, currentSection));
		}
	}

	@Nested
	@DisplayName("Undo / Redo")
	class UndoRedo
	{
		@Test
		@DisplayName("addCustomGoal undo removes the goal, redo restores it")
		void addCustomGoalRoundTrip()
		{
			String id = api.addCustomGoal("Foo", "");
			assertEquals(1, store.getGoals().size());
			assertTrue(api.canUndo());

			api.undo();
			assertTrue(store.getGoals().isEmpty());
			assertTrue(api.canRedo());

			api.redo();
			assertEquals(1, store.getGoals().size());
			assertEquals(id, store.getGoals().get(0).getId()); // SAME id
		}

		@Test
		@DisplayName("removeGoal undo restores the goal with the same id")
		void removeGoalRoundTrip()
		{
			String id = api.addCustomGoal("Bar", "");
			api.removeGoal(id);
			assertTrue(store.getGoals().isEmpty());

			api.undo(); // undo removeGoal
			assertEquals(1, store.getGoals().size());
			assertEquals(id, store.getGoals().get(0).getId());
		}

		@Test
		@DisplayName("markGoalComplete undo flips status back to ACTIVE")
		void markCompleteRoundTrip()
		{
			String id = api.addCustomGoal("X", "");
			api.markGoalComplete(id);
			Goal g = store.getGoals().stream().filter(x -> id.equals(x.getId()))
				.findFirst().orElseThrow();
			assertTrue(g.isComplete());

			api.undo(); // undo markComplete
			assertFalse(g.isComplete());
			assertEquals(0L, g.getCompletedAt());
		}

		@Test
		@DisplayName("setGoalColor undo restores the previous color")
		void setGoalColorRoundTrip()
		{
			String id = api.addCustomGoal("X", "");
			api.setGoalColor(id, 0xFF0000);
			api.setGoalColor(id, 0x00FF00);
			Goal g = store.getGoals().stream().filter(x -> id.equals(x.getId()))
				.findFirst().orElseThrow();
			assertEquals(0x00FF00, g.getCustomColorRgb());

			api.undo(); // back to red
			assertEquals(0xFF0000, g.getCustomColorRgb());
			api.undo(); // back to no override
			assertEquals(-1, g.getCustomColorRgb());
		}

		@Test
		@DisplayName("changeTarget undo restores target + auto-derived display strings")
		void changeTargetRoundTrip()
		{
			String id = api.addSkillGoal(net.runelite.api.Skill.ATTACK, 1000);
			Goal g = store.getGoals().stream().filter(x -> id.equals(x.getId()))
				.findFirst().orElseThrow();
			String origName = g.getName();
			int origTarget = g.getTargetValue();

			api.changeTarget(id, 50000);
			assertEquals(50000, g.getTargetValue());
			assertNotEquals(origName, g.getName()); // name was regenerated

			api.undo();
			assertEquals(origTarget, g.getTargetValue());
			assertEquals(origName, g.getName());
		}

		@Test
		@DisplayName("a new action after undo clears the redo stack")
		void newActionClearsRedo()
		{
			api.addCustomGoal("A", "");
			api.addCustomGoal("B", "");
			api.undo(); // remove B
			assertTrue(api.canRedo());
			api.addCustomGoal("C", ""); // forks history
			assertFalse(api.canRedo());
		}

		@Test
		@DisplayName("addTag undo removes the tag from the goal")
		void addTagRoundTrip()
		{
			String id = api.addCustomGoal("X", "");
			api.addTag(id, "spicy");
			Goal g = store.getGoals().stream().filter(x -> id.equals(x.getId()))
				.findFirst().orElseThrow();
			assertEquals(1, g.getTagIds().size());

			api.undo();
			assertTrue(g.getTagIds().isEmpty());

			api.redo();
			assertEquals(1, g.getTagIds().size());
		}

		@Test
		@DisplayName("removeTag undo restores the tag at its original index")
		void removeTagRoundTrip()
		{
			String id = api.addCustomGoal("X", "");
			api.addTag(id, "alpha");
			api.addTag(id, "bravo");
			api.addTag(id, "charlie");
			Goal g = store.getGoals().stream().filter(x -> id.equals(x.getId()))
				.findFirst().orElseThrow();

			api.removeTag(id, "bravo");
			assertEquals(2, g.getTagIds().size());

			api.undo(); // restore bravo
			assertEquals(3, g.getTagIds().size());
			// bravo should be back at index 1
			com.goalplanner.model.Tag t1 = store.findTag(g.getTagIds().get(1));
			assertEquals("bravo", t1.getLabel());
		}

		@Test
		@DisplayName("bulkRestoreDefaults undo restores prior tags + colors for every changed goal")
		void bulkRestoreDefaultsRoundTrip()
		{
			com.goalplanner.model.Tag def = store.findOrCreateSystemTag(
				"Slayer", com.goalplanner.model.TagCategory.SKILLING);
			String aId = api.addCustomGoal("A", "");
			Goal a = store.getGoals().stream().filter(x -> aId.equals(x.getId()))
				.findFirst().orElseThrow();
			a.setDefaultTagIds(new java.util.ArrayList<>(java.util.List.of(def.getId())));
			a.setTagIds(new java.util.ArrayList<>(java.util.List.of(def.getId())));
			api.setGoalColor(aId, 0xFF0000);
			com.goalplanner.model.Tag extra = store.createUserTag("Extra",
				com.goalplanner.model.TagCategory.OTHER);
			a.getTagIds().add(extra.getId());

			int changed = api.bulkRestoreDefaults(java.util.Set.of(aId));
			assertEquals(1, changed);
			assertEquals(-1, a.getCustomColorRgb());
			assertEquals(1, a.getTagIds().size());

			// Undo (this is the bulkRestoreDefaults entry; setGoalColor was a
			// separate command pushed earlier)
			api.undo();
			assertEquals(0xFF0000, a.getCustomColorRgb());
			assertEquals(2, a.getTagIds().size());
		}

		@Test
		@DisplayName("bulkRemoveTagFromGoals undo restores tag at original index on every goal")
		void bulkRemoveTagRoundTrip()
		{
			com.goalplanner.model.Tag tag = store.createUserTag("Hot",
				com.goalplanner.model.TagCategory.OTHER);
			String aId = api.addCustomGoal("A", "");
			String bId = api.addCustomGoal("B", "");
			Goal a = store.getGoals().stream().filter(x -> aId.equals(x.getId()))
				.findFirst().orElseThrow();
			Goal b = store.getGoals().stream().filter(x -> bId.equals(x.getId()))
				.findFirst().orElseThrow();
			a.getTagIds().add(tag.getId());
			b.getTagIds().add(tag.getId());

			int removed = api.bulkRemoveTagFromGoals(java.util.Set.of(aId, bId), tag.getId());
			assertEquals(2, removed);
			assertFalse(a.getTagIds().contains(tag.getId()));
			assertFalse(b.getTagIds().contains(tag.getId()));

			api.undo();
			assertTrue(a.getTagIds().contains(tag.getId()));
			assertTrue(b.getTagIds().contains(tag.getId()));
		}

		@Test
		@DisplayName("restoreDefaultTags undo restores the previous tag set")
		void restoreDefaultTagsRoundTrip()
		{
			com.goalplanner.model.Tag def = store.findOrCreateSystemTag(
				"D", com.goalplanner.model.TagCategory.OTHER);
			String id = api.addCustomGoal("X", "");
			Goal g = store.getGoals().stream().filter(x -> id.equals(x.getId()))
				.findFirst().orElseThrow();
			g.setDefaultTagIds(new java.util.ArrayList<>(java.util.List.of(def.getId())));
			g.setTagIds(new java.util.ArrayList<>(java.util.List.of(def.getId())));
			com.goalplanner.model.Tag extra = store.createUserTag("E",
				com.goalplanner.model.TagCategory.OTHER);
			g.getTagIds().add(extra.getId());

			api.restoreDefaultTags(id);
			assertEquals(1, g.getTagIds().size());

			api.undo();
			assertEquals(2, g.getTagIds().size());
		}

		@Test
		@DisplayName("addSkillGoal undo removes the goal, redo restores with same id")
		void addSkillGoalRoundTrip()
		{
			String id = api.addSkillGoal(net.runelite.api.Skill.ATTACK, 1000);
			assertEquals(1, store.getGoals().size());
			api.undo();
			assertTrue(store.getGoals().isEmpty());
			api.redo();
			assertEquals(1, store.getGoals().size());
			assertEquals(id, store.getGoals().get(0).getId());
		}

		@Test
		@DisplayName("createSection undo deletes the section")
		void createSectionRoundTrip()
		{
			String secId = api.createSection("Bossing");
			assertNotNull(store.findSection(secId));
			api.undo();
			assertNull(store.findSection(secId));
			api.redo();
			assertNotNull(store.findSection(secId));
		}

		@Test
		@DisplayName("deleteSection undo restores the section AND its deleted goals")
		void deleteSectionRoundTrip()
		{
			String secId = api.createSection("Temp");
			String goalId = api.addCustomGoal("In temp", "");
			api.moveGoalToSection(goalId, secId);
			assertEquals(secId, store.getGoals().stream()
				.filter(g -> goalId.equals(g.getId())).findFirst().orElseThrow().getSectionId());

			api.deleteSection(secId);
			assertNull(store.findSection(secId));
			// The section's goal is deleted with it.
			assertNull(api.findGoal(goalId));

			api.undo();
			assertNotNull(store.findSection(secId));
			assertEquals(secId, store.getGoals().stream()
				.filter(g -> goalId.equals(g.getId())).findFirst().orElseThrow().getSectionId());
		}

		@Test
		@DisplayName("renameSection undo restores the previous name")
		void renameSectionRoundTrip()
		{
			String secId = api.createSection("Old");
			api.renameSection(secId, "New");
			assertEquals("New", store.findSection(secId).getName());
			api.undo();
			assertEquals("Old", store.findSection(secId).getName());
		}

		@Test
		@DisplayName("renameTag undo restores the previous label")
		void renameTagRoundTrip()
		{
			String tagId = api.createUserTag("Original", "OTHER");
			api.renameTag(tagId, "Renamed");
			assertEquals("Renamed", store.findTag(tagId).getLabel());
			api.undo();
			assertEquals("Original", store.findTag(tagId).getLabel());
		}

		@Test
		@DisplayName("deleteTag undo restores tag + every goal that referenced it")
		void deleteTagRoundTrip()
		{
			String tagId = api.createUserTag("Hot", "OTHER");
			String goalId = api.addCustomGoal("X", "");
			Goal g = store.getGoals().stream().filter(x -> goalId.equals(x.getId()))
				.findFirst().orElseThrow();
			g.getTagIds().add(tagId);

			api.deleteTag(tagId);
			assertNull(store.findTag(tagId));
			assertFalse(g.getTagIds().contains(tagId));

			api.undo();
			assertNotNull(store.findTag(tagId));
			assertTrue(g.getTagIds().contains(tagId));
		}

		@Test
		@DisplayName("setTagIcon undo restores the previous iconKey")
		void setTagIconRoundTrip()
		{
			String tagId = api.createUserTag("X", "OTHER");
			api.setTagIcon(tagId, "FIRE");
			assertEquals("FIRE", store.findTag(tagId).getIconKey());
			api.undo();
			assertNull(store.findTag(tagId).getIconKey());
		}

		@Test
		@DisplayName("removeAllGoals undo restores every goal")
		void removeAllGoalsRoundTrip()
		{
			api.addCustomGoal("A", "");
			api.addCustomGoal("B", "");
			api.addCustomGoal("C", "");
			api.removeAllGoals();
			assertTrue(store.getGoals().isEmpty());
			api.undo();
			assertEquals(3, store.getGoals().size());
		}

		@Test
		@DisplayName("compound: bulk move-to-section undo restores every goal's section in one step")
		void compoundBulkMoveRoundTrip()
		{
			String secId = api.createSection("Dest");
			String aId = api.addCustomGoal("A", "");
			String bId = api.addCustomGoal("B", "");
			Goal a = store.getGoals().stream().filter(x -> aId.equals(x.getId()))
				.findFirst().orElseThrow();
			Goal b = store.getGoals().stream().filter(x -> bId.equals(x.getId()))
				.findFirst().orElseThrow();
			String origA = a.getSectionId();
			String origB = b.getSectionId();

			api.beginCompound("Move 2 goals to Dest");
			api.moveGoalToSection(aId, secId);
			api.moveGoalToSection(bId, secId);
			api.endCompound();
			assertEquals(secId, a.getSectionId());
			assertEquals(secId, b.getSectionId());

			api.undo(); // single undo should reverse BOTH
			assertEquals(origA, a.getSectionId());
			assertEquals(origB, b.getSectionId());
		}

		@Test
		@DisplayName("bulkRemoveGoals: undo restores exact original order (even after redo→undo)")
		void bulkRemoveGoalsPreservesOrder()
		{
			String aId = api.addCustomGoal("A", "");
			String bId = api.addCustomGoal("B", "");
			String cId = api.addCustomGoal("C", "");
			String dId = api.addCustomGoal("D", "");
			// Sanity
			java.util.List<String> before = store.getGoals().stream()
				.map(Goal::getId).collect(java.util.stream.Collectors.toList());
			assertEquals(java.util.List.of(aId, bId, cId, dId), before);

			// Bulk-remove B and C
			api.bulkRemoveGoals(new java.util.LinkedHashSet<>(java.util.List.of(bId, cId)));
			java.util.List<String> afterRemove = store.getGoals().stream()
				.map(Goal::getId).collect(java.util.stream.Collectors.toList());
			assertEquals(java.util.List.of(aId, dId), afterRemove);

			// Undo — order should be EXACTLY [A, B, C, D] again
			api.undo();
			java.util.List<String> afterUndo = store.getGoals().stream()
				.map(Goal::getId).collect(java.util.stream.Collectors.toList());
			assertEquals(java.util.List.of(aId, bId, cId, dId), afterUndo);

			// Redo → undo round-trip: still [A, B, C, D]
			api.redo();
			api.undo();
			java.util.List<String> afterRedoUndo = store.getGoals().stream()
				.map(Goal::getId).collect(java.util.stream.Collectors.toList());
			assertEquals(java.util.List.of(aId, bId, cId, dId), afterRedoUndo);
		}

		@Test
		@DisplayName("moveGoal single-step: undo restores exact original position")
		void moveGoalSingleStepRoundTrip()
		{
			String aId = api.addCustomGoal("A", "");
			String bId = api.addCustomGoal("B", "");
			String cId = api.addCustomGoal("C", "");
			String dId = api.addCustomGoal("D", "");

			// Move B from index 1 to index 2 (single step)
			api.moveGoal(bId, 2);
			assertEquals(java.util.List.of(aId, cId, bId, dId),
				store.getGoals().stream().map(Goal::getId)
					.collect(java.util.stream.Collectors.toList()));

			api.undo();
			assertEquals(java.util.List.of(aId, bId, cId, dId),
				store.getGoals().stream().map(Goal::getId)
					.collect(java.util.stream.Collectors.toList()));
		}

		@Test
		@DisplayName("moveGoal multi-step: undo+redo round-trip preserves order")
		void moveGoalMultiStepRoundTrip()
		{
			String aId = api.addCustomGoal("A", "");
			String bId = api.addCustomGoal("B", "");
			String cId = api.addCustomGoal("C", "");
			String dId = api.addCustomGoal("D", "");

			// Move A from index 0 to index 3 (Move to Bottom)
			api.moveGoal(aId, 3);
			assertEquals(java.util.List.of(bId, cId, dId, aId),
				store.getGoals().stream().map(Goal::getId)
					.collect(java.util.stream.Collectors.toList()));

			api.undo();
			assertEquals(java.util.List.of(aId, bId, cId, dId),
				store.getGoals().stream().map(Goal::getId)
					.collect(java.util.stream.Collectors.toList()));

			api.redo();
			api.undo();
			assertEquals(java.util.List.of(aId, bId, cId, dId),
				store.getGoals().stream().map(Goal::getId)
					.collect(java.util.stream.Collectors.toList()));
		}

		@Test
		@DisplayName("bulkMoveGoalsToSection: undo restores exact original sections + order")
		void bulkMoveGoalsPreservesOrder()
		{
			String secId = api.createSection("Dest");
			String aId = api.addCustomGoal("A", "");
			String bId = api.addCustomGoal("B", "");
			String cId = api.addCustomGoal("C", "");
			// Original: all in Incomplete
			String origSection = store.getGoals().stream()
				.filter(x -> aId.equals(x.getId())).findFirst().orElseThrow().getSectionId();

			api.bulkMoveGoalsToSection(
				new java.util.LinkedHashSet<>(java.util.List.of(aId, bId, cId)), secId);
			for (String id : java.util.List.of(aId, bId, cId))
			{
				assertEquals(secId, store.getGoals().stream()
					.filter(g -> id.equals(g.getId())).findFirst().orElseThrow().getSectionId());
			}

			api.undo();
			for (String id : java.util.List.of(aId, bId, cId))
			{
				assertEquals(origSection, store.getGoals().stream()
					.filter(g -> id.equals(g.getId())).findFirst().orElseThrow().getSectionId());
			}

			// Redo → undo round-trip: still all in orig section
			api.redo();
			api.undo();
			for (String id : java.util.List.of(aId, bId, cId))
			{
				assertEquals(origSection, store.getGoals().stream()
					.filter(g -> id.equals(g.getId())).findFirst().orElseThrow().getSectionId());
			}
		}

		@Test
		@DisplayName("compound: create-and-position is one undo entry")
		void compoundAddAndPositionIsOneEntry()
		{
			String secId = api.createSection("Bossing");

			api.beginCompound("Add goal: Vorkath");
			String goalId = api.addCustomGoal("Vorkath KC", "");
			api.moveGoalToSection(goalId, secId);
			api.endCompound();

			Goal g = store.getGoals().stream().filter(x -> goalId.equals(x.getId()))
				.findFirst().orElseThrow();
			assertEquals(secId, g.getSectionId());

			// Single undo removes the goal entirely (and reverts the position)
			api.undo();
			assertTrue(store.getGoals().stream().noneMatch(x -> goalId.equals(x.getId())));
		}
	}

	@Nested
	@DisplayName("searchGoals")
	class SearchGoals
	{
		private String tagGoal(String name, String description, String tagLabel,
			com.goalplanner.model.TagCategory cat)
		{
			String id = api.addCustomGoal(name, description);
			com.goalplanner.model.Tag tag = store.findOrCreateSystemTag(tagLabel, cat);
			Goal g = store.getGoals().stream().filter(x -> id.equals(x.getId())).findFirst().orElseThrow();
			g.setTagIds(new java.util.ArrayList<>(java.util.List.of(tag.getId())));
			g.setDefaultTagIds(new java.util.ArrayList<>(java.util.List.of(tag.getId())));
			return id;
		}

		@Test
		@DisplayName("empty or null query returns every goal")
		void emptyQueryReturnsAll()
		{
			api.addCustomGoal("Goal A", "");
			api.addCustomGoal("Goal B", "");
			assertEquals(2, api.searchGoals(null).size());
			assertEquals(2, api.searchGoals("").size());
			assertEquals(2, api.searchGoals("   ").size());
		}

		@Test
		@DisplayName("matches goal name with case-insensitive partial substring")
		void matchesName()
		{
			api.addCustomGoal("Kill Vorkath 100 times", "");
			api.addCustomGoal("Train Slayer", "");
			assertEquals(1, api.searchGoals("vork").size());
			assertEquals(1, api.searchGoals("VORK").size());
		}

		@Test
		@DisplayName("matches goal description")
		void matchesDescription()
		{
			String id = api.addCustomGoal("Foo", "Get the dragon pet from Zulrah");
			java.util.List<GoalView> hits = api.searchGoals("zulrah");
			assertEquals(1, hits.size());
			assertEquals(id, hits.get(0).id);
		}

		@Test
		@DisplayName("matches tag label")
		void matchesTagLabel()
		{
			tagGoal("Some quest", "", "Vorkath", com.goalplanner.model.TagCategory.BOSS);
			api.addCustomGoal("Other goal", "");
			assertEquals(1, api.searchGoals("vorkath").size());
		}

		@Test
		@DisplayName("matches tag category display name (e.g. 'skill' hits SKILLING tags)")
		void matchesTagCategory()
		{
			tagGoal("Train Attack", "", "Attack", com.goalplanner.model.TagCategory.SKILLING);
			api.addCustomGoal("Unrelated", "");
			java.util.List<GoalView> hits = api.searchGoals("skill");
			assertEquals(1, hits.size());
			assertEquals("Train Attack", hits.get(0).name);
		}

		@Test
		@DisplayName("matches GoalType display name")
		void matchesGoalType()
		{
			api.addSkillGoal(net.runelite.api.Skill.ATTACK, 99);
			api.addCustomGoal("Other", "");
			java.util.List<GoalView> hits = api.searchGoals("skill");
			// Both the SKILL goal type AND any SKILLING-tagged goal would hit;
			// here only the SKILL goal exists, plus the auto-tag may apply.
			assertTrue(hits.stream().anyMatch(g -> "SKILL".equals(g.type)));
		}

		@Test
		@DisplayName("matches section title")
		void matchesSectionTitle()
		{
			String secId = api.createSection("Boss Grinds");
			String id = api.addCustomGoal("Foo", "");
			api.moveGoalToSection(id, secId);
			java.util.List<GoalView> hits = api.searchGoals("boss grind");
			assertEquals(1, hits.size());
			assertEquals(id, hits.get(0).id);
		}

		@Test
		@DisplayName("addTagWithCategory attaches an existing SKILLING tag to a CA goal")
		void addExistingSkillingTagToCaGoal()
		{
			// Seed Slayer like the plugin does at startup
			com.goalplanner.model.Tag slayer = store.findOrCreateSystemTag(
				"Slayer", com.goalplanner.model.TagCategory.SKILLING);
			// Create a CA-style goal (use custom for simplicity)
			String goalId = api.addCustomGoal("Master tier task", "");

			assertTrue(api.addTagWithCategory(goalId, "Slayer", "SKILLING"));
			Goal g = store.getGoals().stream().filter(x -> goalId.equals(x.getId()))
				.findFirst().orElseThrow();
			assertTrue(g.getTagIds().contains(slayer.getId()));
		}

		@Test
		@DisplayName("addTagWithCategory rejects creating a NEW SKILLING tag")
		void addTagWithCategoryRejectsNewSkillingTag()
		{
			String goalId = api.addCustomGoal("Foo", "");
			assertFalse(api.addTagWithCategory(goalId, "MadeUpSkill", "SKILLING"));
			// And no tag was created
			assertFalse(api.queryAllTags().stream()
				.anyMatch(t -> "MadeUpSkill".equals(t.label)));
		}

		@Test
		@DisplayName("returns empty list when nothing matches")
		void noMatchReturnsEmpty()
		{
			api.addCustomGoal("Alpha", "");
			api.addCustomGoal("Bravo", "");
			assertTrue(api.searchGoals("zzzzzz").isEmpty());
		}
	}

	// ====================================================================
	// queryGoalsTopologicallySorted
	// ====================================================================

	@Nested
	@DisplayName("queryGoalsTopologicallySorted")
	class TopoSortTest
	{
		/** Create a custom goal in the given section and return its id. */
		private String customIn(String name, String sectionId)
		{
			String id = api.addCustomGoal(name, "");
			Goal g = store.getGoals().stream()
				.filter(x -> id.equals(x.getId())).findFirst().orElseThrow();
			g.setSectionId(sectionId);
			return id;
		}

		@Test
		@DisplayName("empty section returns empty list")
		void emptySection()
		{
			String section = store.getIncompleteSection().getId();
			assertTrue(api.queryGoalsTopologicallySorted(section).isEmpty());
		}

		@Test
		@DisplayName("single isolated goal returns just itself")
		void singleGoal()
		{
			String section = store.getIncompleteSection().getId();
			String a = customIn("A", section);
			List<com.goalplanner.api.GoalView> result = api.queryGoalsTopologicallySorted(section);
			assertEquals(1, result.size());
			assertEquals(a, result.get(0).id);
		}

		@Test
		@DisplayName("linear chain: leaves come first, dependents last")
		void linearChain()
		{
			String section = store.getIncompleteSection().getId();
			String a = customIn("A", section);
			String b = customIn("B", section);
			String c = customIn("C", section);
			// C depends on B depends on A. Expected output: A, B, C.
			assertTrue(api.addRequirement(b, a));
			assertTrue(api.addRequirement(c, b));
			List<com.goalplanner.api.GoalView> result = api.queryGoalsTopologicallySorted(section);
			assertEquals(3, result.size());
			assertEquals(a, result.get(0).id);
			assertEquals(b, result.get(1).id);
			assertEquals(c, result.get(2).id);
		}

		@Test
		@DisplayName("diamond DAG produces valid topological order (local-repair semantics)")
		void diamond()
		{
			String section = store.getIncompleteSection().getId();
			String top = customIn("top", section);
			String left = customIn("left", section);
			String right = customIn("right", section);
			String bottom = customIn("bottom", section);
			// top requires left + right; both require bottom.
			// Local-repair semantics: start from priority order
			// [top, left, right, bottom] and move violators the minimum
			// amount needed. The resulting order is a valid topological
			// sort, but the exact permutation isn't strictly tier-grouped —
			// we just verify the DAG invariant holds.
			assertTrue(api.addRequirement(top, left));
			assertTrue(api.addRequirement(top, right));
			assertTrue(api.addRequirement(left, bottom));
			assertTrue(api.addRequirement(right, bottom));
			List<com.goalplanner.api.GoalView> result = api.queryGoalsTopologicallySorted(section);
			assertEquals(4, result.size());
			// Invariant check: bottom must come before left and right;
			// left and right must come before top.
			int posTop = indexOf(result, top);
			int posLeft = indexOf(result, left);
			int posRight = indexOf(result, right);
			int posBottom = indexOf(result, bottom);
			assertTrue(posBottom < posLeft, "bottom must come before left");
			assertTrue(posBottom < posRight, "bottom must come before right");
			assertTrue(posLeft < posTop, "left must come before top");
			assertTrue(posRight < posTop, "right must come before top");
		}

		@Test
		@DisplayName("disconnected subgraphs stay in priority order when no violations")
		void disconnected()
		{
			String section = store.getIncompleteSection().getId();
			String a1 = customIn("A1", section);
			String a2 = customIn("A2", section);
			String b1 = customIn("B1", section);
			String b2 = customIn("B2", section);
			// Two independent chains: A2 requires A1, B2 requires B1.
			// Initial priority order: [A1, A2, B1, B2] — both requirements
			// are already satisfied (A1 before A2, B1 before B2). Local
			// repair is a no-op: the output preserves priority order exactly.
			assertTrue(api.addRequirement(a2, a1));
			assertTrue(api.addRequirement(b2, b1));
			List<com.goalplanner.api.GoalView> result = api.queryGoalsTopologicallySorted(section);
			assertEquals(4, result.size());
			assertEquals(a1, result.get(0).id);
			assertEquals(a2, result.get(1).id);
			assertEquals(b1, result.get(2).id);
			assertEquals(b2, result.get(3).id);
		}

		@Test
		@DisplayName("already-valid order is not disturbed when a new edge is added")
		void noopOnAlreadyValid()
		{
			String section = store.getIncompleteSection().getId();
			String a = customIn("A", section);
			String b = customIn("B", section);
			// A is above B in priority order. Add edge B requires A — the
			// order is already satisfied, so the result should be [A, B].
			assertTrue(api.addRequirement(b, a));
			List<com.goalplanner.api.GoalView> result = api.queryGoalsTopologicallySorted(section);
			assertEquals(2, result.size());
			assertEquals(a, result.get(0).id);
			assertEquals(b, result.get(1).id);
		}

		@Test
		@DisplayName("violator moves to just after its prerequisite, not to the end")
		void localRepairMinimalMove()
		{
			String section = store.getIncompleteSection().getId();
			// Create many goals so the "move to the bottom" failure mode
			// would be obvious if present.
			String claw = customIn("Hydra claw", section);
			String leather = customIn("Hydra leather", section);
			String other1 = customIn("Other 1", section);
			String other2 = customIn("Other 2", section);
			String other3 = customIn("Other 3", section);
			String other4 = customIn("Other 4", section);
			// claw is above leather in priority order. Add edge: claw
			// requires leather. Expected: claw moves to just after leather,
			// the Other goals stay put.
			assertTrue(api.addRequirement(claw, leather));
			List<com.goalplanner.api.GoalView> result = api.queryGoalsTopologicallySorted(section);
			assertEquals(6, result.size());
			int posClaw = indexOf(result, claw);
			int posLeather = indexOf(result, leather);
			// claw just below leather
			assertEquals(posLeather + 1, posClaw,
				"claw should sit immediately below leather after repair");
			// other goals haven't been disturbed — they appear in the same
			// order (Other 1, 2, 3, 4) as they were added.
			assertTrue(indexOf(result, other1) < indexOf(result, other2));
			assertTrue(indexOf(result, other2) < indexOf(result, other3));
			assertTrue(indexOf(result, other3) < indexOf(result, other4));
		}

		private int indexOf(List<com.goalplanner.api.GoalView> list, String id)
		{
			for (int i = 0; i < list.size(); i++)
			{
				if (id.equals(list.get(i).id)) return i;
			}
			return -1;
		}

		@Test
		@DisplayName("cross-section edges don't affect in-section sort order")
		void crossSectionEdgesIgnored()
		{
			Section other = store.createUserSection("Other");
			String thisSection = store.getIncompleteSection().getId();
			String a = customIn("A", thisSection);
			String b = customIn("B", thisSection);
			String external = customIn("External", other.getId());
			// A requires External (cross-section). B has no deps. In the
			// THIS section view, A and B should both be in tier 0 because
			// A's only requirement is out-of-section. Priority order within
			// tier 0 = insertion order: A before B.
			assertTrue(api.addRequirement(a, external));
			List<com.goalplanner.api.GoalView> result = api.queryGoalsTopologicallySorted(thisSection);
			assertEquals(2, result.size());
			assertEquals(a, result.get(0).id);
			assertEquals(b, result.get(1).id);
		}
	}

	@org.junit.jupiter.api.Nested
	@DisplayName("recovery: reopening goals completed against the wrong account")
	class ReopenRecovery
	{
		private Goal completed(GoalType type, String name)
		{
			Goal g = Goal.builder().type(type).name(name)
				.targetValue(100).currentValue(100)
				.status(GoalStatus.COMPLETE).completedAt(1_000L).build();
			store.addGoal(g);
			return g;
		}

		@Test
		@DisplayName("an auto-tracked goal can now be reopened, not just CUSTOM and ITEM_GRIND")
		void autoTrackedTypesReopen()
		{
			for (GoalType type : new GoalType[]{
				GoalType.SKILL, GoalType.BOSS, GoalType.QUEST,
				GoalType.DIARY, GoalType.COMBAT_ACHIEVEMENT, GoalType.ACCOUNT})
			{
				Goal g = completed(type, "Wrongly complete " + type);
				assertTrue(api.markGoalIncomplete(g.getId()),
					type + " must be reopenable - it is the only recovery from a bad completion");
				assertFalse(g.isComplete());
			}
		}

		@Test
		@DisplayName("a reopened but still-maxed goal re-completes on the next unchanged tracker read")
		void reopenedMaxedGoalSelfHeals()
		{
			// The reported bug: reopen a 99/maxed goal, its value never changes,
			// so the tracker reads the SAME value - the no-op guard must not
			// swallow it, or the goal stays incomplete forever.
			Goal g = completed(GoalType.SKILL, "Maxed skill");
			assertTrue(api.markGoalIncomplete(g.getId()));
			assertFalse(g.isComplete(), "reopened");

			// Tracker drain reports the unchanged, still-at-target value.
			api.recordGoalProgress(g.getId(), 100);

			assertTrue(g.isComplete(),
				"a genuinely-met goal must re-complete even when the value did not change");
		}

		@Test
		@DisplayName("a reopened goal that no longer meets target stays open on an unchanged read")
		void reopenedUnmetGoalStaysOpen()
		{
			// Wrong-account recovery: the goal was falsely completed and the real
			// value is below target. An unchanged read must NOT re-complete it.
			Goal g = Goal.builder().type(GoalType.SKILL).name("Not really done")
				.targetValue(100).currentValue(40)
				.status(GoalStatus.COMPLETE).completedAt(1_000L).build();
			store.addGoal(g);
			assertTrue(api.markGoalIncomplete(g.getId()));

			api.recordGoalProgress(g.getId(), 40);

			assertFalse(g.isComplete(), "40 < 100 target - recovery must hold it open");
		}

		@Test
		@DisplayName("reopening an already-open goal is a no-op")
		void openGoalIsNoOp()
		{
			Goal g = Goal.builder().type(GoalType.SKILL).name("Open").targetValue(100).build();
			store.addGoal(g);
			assertFalse(api.markGoalIncomplete(g.getId()));
		}

		@Test
		@DisplayName("bulk reset reopens every completed goal in the selection and counts them")
		void bulkReopens()
		{
			Goal a = completed(GoalType.SKILL, "A");
			Goal b = completed(GoalType.BOSS, "B");
			Goal open = Goal.builder().type(GoalType.SKILL).name("C").targetValue(100).build();
			store.addGoal(open);

			int reopened = api.bulkMarkIncomplete(
				new java.util.LinkedHashSet<>(java.util.List.of(a.getId(), b.getId(), open.getId())));

			assertEquals(2, reopened, "only the completed ones count");
			assertFalse(a.isComplete());
			assertFalse(b.isComplete());
		}

		@Test
		@DisplayName("bulk reset is one undo step, so a mistaken wipe is recoverable")
		void bulkIsSingleUndo()
		{
			Goal a = completed(GoalType.SKILL, "A");
			Goal b = completed(GoalType.BOSS, "B");

			api.bulkMarkIncomplete(new java.util.LinkedHashSet<>(
				java.util.List.of(a.getId(), b.getId())));
			assertFalse(a.isComplete());

			api.undo();

			assertTrue(a.isComplete(), "one undo must restore the whole reset");
			assertTrue(b.isComplete());
		}

		@Test
		@DisplayName("empty and null selections do nothing")
		void bulkEmpty()
		{
			assertEquals(0, api.bulkMarkIncomplete(null));
			assertEquals(0, api.bulkMarkIncomplete(new java.util.LinkedHashSet<>()));
		}
	}

	@org.junit.jupiter.api.Nested
	@DisplayName("OR-unlock cascade is order-independent")
	class OrCascadeOrder
	{
		@Test
		@DisplayName("the parent completes even when the AND-prereq finishes last")
		void andPrereqLastStillCascades()
		{
			// Warriors-style: unlock requires quest AND (99 Attack OR 99 Strength).
			Goal quest = Goal.builder().type(GoalType.QUEST).name("Bone Voyage")
				.questName("BONE_VOYAGE").targetValue(1).build();
			Goal att = Goal.builder().type(GoalType.SKILL).name("99 Attack")
				.skillName("ATTACK").targetValue(13_034_431).build();
			Goal unlock = Goal.builder().type(GoalType.CUSTOM).name("Unlock").build();
			store.addGoal(quest); store.addGoal(att); store.addGoal(unlock);
			api.addRequirement(unlock.getId(), quest.getId());
			api.addOrRequirement(unlock.getId(), att.getId());

			// Tracker order in the real drain: skills BEFORE quests.
			api.recordGoalProgress(att.getId(), 13_034_431);
			assertFalse(unlock.isComplete(), "AND-prereq still open - must not complete yet");
			api.recordGoalProgress(quest.getId(), 1);

			assertTrue(unlock.isComplete(),
				"the AND-prereq finishing last must still trigger the OR-unlock cascade");
		}

		@Test
		@DisplayName("removeRequirement refuses an OR-only edge, which getDependents still lists")
		void removeRequirementRefusesOrOnlyEdge()
		{
			// A boss whose skill prereq is an ALTERNATIVE (OR) unlock, the shape the
			// boss/quest seeders produce. The dock's relations list renders this edge
			// from the SKILL side ("Required by: Boss"), because the reverse index
			// carries OR edges too - but no API can drop it, so the row must render
			// WITHOUT an X (see GoalPanel.isAndRequirementOf).
			Goal boss = Goal.builder().type(GoalType.BOSS).name("Boss")
				.bossName("Zulrah").targetValue(1).build();
			Goal skill = Goal.builder().type(GoalType.SKILL).name("Skill")
				.skillName("SLAYER").targetValue(1000).build();
			store.addGoal(boss); store.addGoal(skill);
			assertTrue(api.addOrRequirement(boss.getId(), skill.getId()));

			assertTrue(api.getDependents(skill.getId()).contains(boss.getId()),
				"the reverse index lists OR-edge dependents, so the row is rendered");
			assertFalse(api.getRequirements(boss.getId()).contains(skill.getId()),
				"an OR edge is not an AND requirement");

			assertFalse(api.removeRequirement(boss.getId(), skill.getId()),
				"removeRequirement only drops AND edges - the X would be a silent no-op");
			assertTrue(api.getDependents(skill.getId()).contains(boss.getId()),
				"the OR edge survives the refused removal");
		}

		@Test
		@DisplayName("removeRequirement drops an AND edge, and one undo restores it")
		void removeRequirementDropsAndEdge()
		{
			Goal parent = Goal.builder().type(GoalType.CUSTOM).name("Parent").build();
			Goal prereq = Goal.builder().type(GoalType.CUSTOM).name("Prereq").build();
			store.addGoal(parent); store.addGoal(prereq);
			assertTrue(api.addRequirement(parent.getId(), prereq.getId()));

			assertTrue(api.removeRequirement(parent.getId(), prereq.getId()));
			assertFalse(api.getDependents(prereq.getId()).contains(parent.getId()));

			api.undo();
			assertTrue(api.getRequirements(parent.getId()).contains(prereq.getId()),
				"one undo restores the removed AND edge");
		}

		@Test
		@DisplayName("a plain AND-only parent never auto-completes from its prereqs")
		void andOnlyParentUntouched()
		{
			Goal quest = Goal.builder().type(GoalType.QUEST).name("Q")
				.questName("BONE_VOYAGE").targetValue(1).build();
			Goal parent = Goal.builder().type(GoalType.CUSTOM).name("Manual step").build();
			store.addGoal(quest); store.addGoal(parent);
			api.addRequirement(parent.getId(), quest.getId());

			api.recordGoalProgress(quest.getId(), 1);

			assertFalse(parent.isComplete(),
				"completing prerequisites must never auto-complete a non-OR goal");
		}
	}
}
