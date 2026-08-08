package com.goalplanner.api;

import com.goalplanner.data.WikiCaRepository;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.Section;
import com.goalplanner.persistence.GoalStore;
import com.goalplanner.service.GoalReorderingService;
import com.goalplanner.share.GoalShareDto;
import com.goalplanner.share.SectionShareDto;
import com.goalplanner.share.ShareBundle;
import com.goalplanner.testsupport.InMemoryConfigManager;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Schema-v2 import behaviour: multiple sections from one bundle, and
 * default-target sections that REUSE existing equivalents instead of
 * duplicating. Real {@link GoalStore} per the stateful-store testing rule.
 */
class ShareImportV2Test
{
	private GoalStore store;
	private GoalPlannerApiImpl api;

	@BeforeEach
	void setUp()
	{
		ConfigManager configManager = InMemoryConfigManager.create();
		store = new GoalStore(configManager, new Gson());
		store.load();

		ItemManager itemManager = mock(ItemManager.class);
		WikiCaRepository wikiCaRepository = mock(WikiCaRepository.class);
		GoalReorderingService reorderingService = new GoalReorderingService(store);
		api = new GoalPlannerApiImpl(store, reorderingService, itemManager, wikiCaRepository);
		api.setOnGoalsChanged(() -> {});
	}

	private static GoalShareDto skillDto(int ref, String name, String skill, int xp)
	{
		GoalShareDto dto = new GoalShareDto();
		dto.setRef(ref);
		dto.setType("SKILL");
		dto.setName(name);
		dto.setSkillName(skill);
		dto.setTargetValue(xp);
		return dto;
	}

	private static GoalShareDto bossDto(int ref, String bossName, int kc, Integer requiresRef)
	{
		GoalShareDto dto = new GoalShareDto();
		dto.setRef(ref);
		dto.setType("BOSS");
		dto.setName(bossName);
		dto.setBossName(bossName);
		dto.setTargetValue(kc);
		if (requiresRef != null)
		{
			dto.setRequires(Collections.singletonList(requiresRef));
		}
		return dto;
	}

	private static SectionShareDto section(String name, boolean targetDefault, GoalShareDto... goals)
	{
		SectionShareDto s = new SectionShareDto();
		s.setName(name);
		s.setTargetDefault(targetDefault);
		s.setGoals(Arrays.asList(goals));
		return s;
	}

	private static ShareBundle v2Bundle(String sharedBy, SectionShareDto... sections)
	{
		ShareBundle bundle = new ShareBundle();
		bundle.setSharedBy(sharedBy);
		bundle.setSections(Arrays.asList(sections));
		return bundle;
	}

	private List<Goal> goalsInSection(String sectionId)
	{
		return store.getGoals().stream()
			.filter(g -> sectionId.equals(g.getSectionId()))
			.collect(Collectors.toList());
	}

	private List<Section> userSections()
	{
		return store.getSections().stream()
			.filter(s -> !s.isBuiltIn())
			.collect(Collectors.toList());
	}

	@Test
	@DisplayName("v2 item goal keeps its system BOSS source tag, visible on the card")
	void v2ImportAppliesItemSourceTag()
	{
		// Mirror an MCP source-tagged item goal: ITEM_GRIND + a system BOSS tag,
		// in a multi-section (v2) bundle - the combination the in-app tests missed.
		com.goalplanner.share.TagShareDto cerb = new com.goalplanner.share.TagShareDto();
		cerb.setLabel("Cerberus");
		cerb.setCategory("BOSS");
		cerb.setColorRgb(-1);
		cerb.setSystem(true);

		GoalShareDto peg = new GoalShareDto();
		peg.setRef(0);
		peg.setType("ITEM_GRIND");
		peg.setName("Pegasian crystal");
		peg.setItemId(13227);
		peg.setTargetValue(1);
		peg.setTags(Collections.singletonList(cerb));

		String sectionId = api.importShareBundle(v2Bundle("Tag Test", section("Cerberus", false, peg)));
		assertNotNull(sectionId);

		Goal g = goalsInSection(sectionId).stream()
			.filter(x -> "Pegasian crystal".equals(x.getName())).findFirst().orElseThrow();

		// Model: tag find-or-created as a system BOSS tag and attached.
		assertEquals(1, g.getTagIds().size(), "item goal should carry its source tag");
		com.goalplanner.model.Tag tag = store.findTag(g.getTagIds().get(0));
		assertNotNull(tag);
		assertEquals("Cerberus", tag.getLabel());
		assertEquals(com.goalplanner.model.TagCategory.BOSS, tag.getCategory());

		// Render path: the tag surfaces in the card's render lists (customTags,
		// since import sets tagIds but not defaultTagIds).
		GoalView gv = api.queryGoalView(g.getId());
		assertNotNull(gv);
		boolean shown =
			(gv.defaultTags != null && gv.defaultTags.stream().anyMatch(t -> "Cerberus".equals(t.label)))
			|| (gv.customTags != null && gv.customTags.stream().anyMatch(t -> "Cerberus".equals(t.label)));
		assertTrue(shown, "Cerberus source tag should appear on the imported card");
	}

	@Test
	@DisplayName("imported QUEST and BOSS goals get enriched icon fields the wire omits")
	void importEnrichesQuestAndBossIcons()
	{
		GoalShareDto quest = new GoalShareDto();
		quest.setRef(0);
		quest.setType("QUEST");
		quest.setName("Dragon Slayer II");
		quest.setQuestName("Dragon Slayer II");

		ShareBundle bundle = v2Bundle("Andrew",
			section("Quests", false, quest),
			section("Bossing", false, bossDto(0, "Zulrah", 100, null)));

		String firstSection = api.importShareBundle(bundle);
		assertNotNull(firstSection);

		Goal q = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.QUEST).findFirst().orElseThrow();
		assertEquals(GoalPlannerApiImpl.QUEST_SPRITE_ID, q.getSpriteId(),
			"a QUEST goal with no wire sprite should get the quest journal sprite");

		Goal boss = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.BOSS).findFirst().orElseThrow();
		assertEquals(com.goalplanner.data.BossKillData.getPetItemId("Zulrah"), boss.getItemId(),
			"a BOSS goal with no wire item should get its pet item id for the card icon");
		assertTrue(boss.getItemId() > 0, "Zulrah should resolve to a real pet item id");
	}

	@Test
	@DisplayName("import carries a section's nested-view preference")
	void importCarriesNestedOverride()
	{
		SectionShareDto sec = section("Tree", false, skillDto(0, "Attack 90", "ATTACK", 5_346_332));
		sec.setNestedOverride(Boolean.TRUE);
		String landed = api.importShareBundle(v2Bundle("Andrew", sec));

		assertNotNull(landed);
		Section created = userSections().get(0);
		assertEquals(Boolean.TRUE, created.getNestedOverride());
	}

	@Test
	@DisplayName("a multi-section bundle imports each section as its own user section")
	void multiSectionImportsEachSection()
	{
		ShareBundle bundle = v2Bundle("Andrew",
			section("Slayer", false, skillDto(0, "Slayer 93", "SLAYER", 7_195_629)),
			section("Raids", false,
				skillDto(0, "Ranged 90", "RANGED", 5_346_332),
				bossDto(1, "TzKal-Zuk", 1, 0)));

		String landed = api.importShareBundle(bundle);

		assertNotNull(landed);
		List<Section> created = userSections();
		assertEquals(2, created.size());
		assertEquals("Slayer", created.get(0).getName());
		assertEquals("Raids", created.get(1).getName());
		assertEquals(1, goalsInSection(created.get(0).getId()).size());

		List<Goal> raids = goalsInSection(created.get(1).getId());
		assertEquals(2, raids.size());
		Goal zuk = raids.stream().filter(g -> g.getType() == GoalType.BOSS).findFirst().orElseThrow();
		Goal ranged = raids.stream().filter(g -> g.getType() == GoalType.SKILL).findFirst().orElseThrow();
		assertEquals(Collections.singletonList(ranged.getId()), zuk.getRequiredGoalIds());
	}

	@Test
	@DisplayName("relation refs are scoped per section — same ref index in two sections never cross-wires")
	void refsAreSectionScoped()
	{
		ShareBundle bundle = v2Bundle(null,
			section("A", false, skillDto(0, "Attack 99", "ATTACK", 13_034_431)),
			section("B", false, bossDto(0, "Zulrah", 100, null)));

		api.importShareBundle(bundle);

		for (Goal g : store.getGoals())
		{
			if (g.getType() == GoalType.BOSS)
			{
				assertTrue(g.getRequiredGoalIds().isEmpty(),
					"ref 0 in section B must not link to ref 0 of section A");
			}
		}
	}

	@Test
	@DisplayName("a default-target section lands goals in the Incomplete built-in, not a new section")
	void defaultTargetLandsInDefaultPlan()
	{
		ShareBundle bundle = v2Bundle("Andrew",
			section(null, true, skillDto(0, "Mining 99", "MINING", 13_034_431)));

		String landed = api.importShareBundle(bundle);

		assertEquals(store.getIncompleteSection().getId(), landed);
		assertTrue(userSections().isEmpty(), "no user section should be created");
		assertEquals(1, goalsInSection(store.getIncompleteSection().getId()).size());
	}

	@Test
	@DisplayName("default-target import reuses an existing equivalent goal instead of duplicating")
	void defaultTargetReusesEquivalent()
	{
		// Recipient already tracks Mining 99 in their default plan.
		Goal existing = Goal.builder()
			.type(GoalType.SKILL).name("Mining 99").skillName("MINING")
			.targetValue(13_034_431).currentValue(0)
			.build();
		store.addGoal(existing);
		int before = store.getGoals().size();

		ShareBundle bundle = v2Bundle("Andrew",
			section(null, true,
				skillDto(0, "Mining 99", "MINING", 13_034_431),
				bossDto(1, "Zulrah", 50, 0)));

		api.importShareBundle(bundle);

		// Only the boss goal is new; the skill goal was reused.
		assertEquals(before + 1, store.getGoals().size());
		Goal zulrah = store.getGoals().stream()
			.filter(g -> g.getType() == GoalType.BOSS).findFirst().orElseThrow();
		assertEquals(Collections.singletonList(existing.getId()), zulrah.getRequiredGoalIds(),
			"the imported relation must attach to the REUSED goal");
	}

	@Test
	@DisplayName("re-importing the same default-target bundle adds nothing new")
	void defaultTargetReimportIsIdempotent()
	{
		ShareBundle bundle = v2Bundle(null,
			section(null, true, skillDto(0, "Mining 99", "MINING", 13_034_431)));

		api.importShareBundle(bundle);
		int after = store.getGoals().size();
		api.importShareBundle(bundle);

		assertEquals(after, store.getGoals().size());
	}

	@Test
	@DisplayName("one undo reverses the whole multi-section import, leaving pre-existing goals intact")
	void undoReversesEverything()
	{
		Goal existing = Goal.builder()
			.type(GoalType.SKILL).name("Mining 99").skillName("MINING")
			.targetValue(13_034_431).currentValue(0)
			.build();
		store.addGoal(existing);
		int goalsBefore = store.getGoals().size();
		int sectionsBefore = store.getSections().size();

		ShareBundle bundle = v2Bundle("Andrew",
			section("Slayer", false, skillDto(0, "Slayer 93", "SLAYER", 7_195_629)),
			section(null, true,
				skillDto(0, "Mining 99", "MINING", 13_034_431),
				bossDto(1, "Zulrah", 50, 0)));
		api.importShareBundle(bundle);
		assertTrue(api.undo());

		assertEquals(goalsBefore, store.getGoals().size());
		assertEquals(sectionsBefore, store.getSections().size());
		assertTrue(existing.getRequiredGoalIds().isEmpty(),
			"relation hung on the reused goal must be removed by undo");
		assertNotNull(store.findGoalById(existing.getId()), "pre-existing goal must survive undo");
	}

	@Test
	@DisplayName("a legacy v1 bundle still imports exactly as before")
	void legacyV1BundleStillImports()
	{
		ShareBundle bundle = new ShareBundle();
		bundle.setKind(ShareBundle.Kind.SECTION);
		bundle.setSectionName("Inferno Prep");
		bundle.setSharedBy("Andrew");
		bundle.setGoals(Arrays.asList(
			skillDto(0, "Ranged 90", "RANGED", 5_346_332),
			bossDto(1, "TzKal-Zuk", 1, 0)));

		String landed = api.importShareBundle(bundle);

		assertNotNull(landed);
		assertEquals(1, userSections().size());
		assertEquals("Inferno Prep", userSections().get(0).getName());
		assertEquals(2, goalsInSection(landed).size());
	}

	@org.junit.jupiter.api.Test
	@org.junit.jupiter.api.DisplayName("an imported section keeps its completed goals inline - it is a checklist")
	void importedSectionKeepsCompletedInline()
	{
		com.goalplanner.share.ShareBundle bundle = new com.goalplanner.share.ShareBundle();
		com.goalplanner.share.SectionShareDto sec = new com.goalplanner.share.SectionShareDto();
		sec.setName("Imported");
		com.goalplanner.share.GoalShareDto dto = new com.goalplanner.share.GoalShareDto();
		dto.setRef(0);
		dto.setType("QUEST");
		dto.setName("Bone Voyage");
		dto.setQuestName("BONE_VOYAGE");
		dto.setTargetValue(1);
		sec.setGoals(java.util.List.of(dto));
		bundle.setSections(java.util.List.of(sec));
		bundle.setV(com.goalplanner.share.ShareBundle.SCHEMA_VERSION);

		api.importShareBundle(bundle);
		com.goalplanner.model.Goal imported = store.getGoals().stream()
			.filter(g -> "Bone Voyage".equals(g.getName())).findFirst().orElseThrow();

		// The recipient has the quest done: first tracker read completes it.
		api.recordGoalProgress(imported.getId(), 1);
		store.reconcileDerivedSections();

		// DELIBERATE (ShareImportService sets autoArchiveOverride=false on the
		// new section): the recipient sees the shared set as a checklist, with
		// what they have already done ticked in place - even when the global
		// auto-archive default is on. First surprised a user during 0.5.0
		// stress testing; pinned here so the next surprise reads this instead.
		org.junit.jupiter.api.Assertions.assertNotEquals(
			store.getCompletedSection().getId(), imported.getSectionId(),
			"imported sections keep completed goals inline by design");
		org.junit.jupiter.api.Assertions.assertTrue(imported.isComplete());
	}
}
