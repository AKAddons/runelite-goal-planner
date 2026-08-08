package com.goalplanner.share;

import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.model.Tag;
import com.goalplanner.model.TagCategory;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Export-side mapping: real Goal/Tag objects → ShareBundle. Verifies field
 * copy, ref indexing, tag resolution, relation remap, the recipient-state
 * strip, and a round-trip back through {@link ShareCodec}.
 */
public class ShareMapperTest
{
	private final Map<String, Tag> tags = new HashMap<>();
	private final Function<String, Tag> lookup = tags::get;

	private Tag infernoTag()
	{
		Tag t = Tag.builder()
			.id("t1").label("Inferno").category(TagCategory.OTHER)
			.colorRgb(0xAA3322).system(false).build();
		tags.put("t1", t);
		return t;
	}

	@Test
	public void mapsDefinitionFieldsAndAssignsRefIndices()
	{
		infernoTag();
		Goal ranged = Goal.builder()
			.id("a").type(GoalType.SKILL).name("Ranged - Level 75")
			.skillName("RANGED").targetValue(1_210_421)
			.tagIds(Collections.singletonList("t1"))
			.currentValue(500_000)           // recipient state — must not travel
			.build();

		ShareBundle bundle = ShareMapper.toBundle(
			ShareBundle.Kind.SECTION, "Inferno Prep", 0x112233,
			Collections.singletonList(ranged), lookup, "Andrew");

		assertEquals(ShareBundle.Kind.SECTION, bundle.getKind());
		assertEquals("Inferno Prep", bundle.getSectionName());
		assertEquals("Andrew", bundle.getSharedBy());
		assertEquals(1, bundle.getGoals().size());

		GoalShareDto dto = bundle.getGoals().get(0);
		assertEquals(0, dto.getRef());
		assertEquals("SKILL", dto.getType());
		assertEquals("Ranged - Level 75", dto.getName());
		assertEquals("RANGED", dto.getSkillName());
		assertEquals(1_210_421, dto.getTargetValue());
		assertEquals(1, dto.getTags().size());
		assertEquals("Inferno", dto.getTags().get(0).getLabel());
		assertEquals("OTHER", dto.getTags().get(0).getCategory());
	}

	@Test
	public void resolvesTagsAndDropsDanglingTagRefs()
	{
		infernoTag();   // only "t1" resolves; "ghost" does not
		Goal g = Goal.builder()
			.id("a").type(GoalType.BOSS).name("TzKal-Zuk").bossName("TzKal-Zuk")
			.targetValue(1).tagIds(Arrays.asList("t1", "ghost"))
			.build();

		ShareBundle bundle = ShareMapper.toBundle(
			ShareBundle.Kind.GOALS, null, -1,
			Collections.singletonList(g), lookup, "Andrew");

		GoalShareDto dto = bundle.getGoals().get(0);
		assertEquals("only the resolvable tag survives", 1, dto.getTags().size());
		assertEquals("Inferno", dto.getTags().get(0).getLabel());
	}

	@Test
	public void remapsInternalRelationsAndDropsExternalEdges()
	{
		Goal ranged = Goal.builder().id("a").type(GoalType.SKILL).name("Ranged").build();
		Goal zuk = Goal.builder()
			.id("b").type(GoalType.BOSS).name("TzKal-Zuk")
			// requires the in-bundle Ranged goal AND an out-of-bundle goal.
			.requiredGoalIds(Arrays.asList("a", "not-in-bundle"))
			.build();

		ShareBundle bundle = ShareMapper.toBundle(
			ShareBundle.Kind.SECTION, "Inferno Prep", -1,
			Arrays.asList(ranged, zuk), lookup, "Andrew");

		GoalShareDto zukDto = bundle.getGoals().get(1);
		assertEquals("external edge dropped, internal kept", 1, zukDto.getRequires().size());
		assertEquals("points at Ranged's ref index (0)", Integer.valueOf(0), zukDto.getRequires().get(0));
	}

	@Test
	public void recipientStateDoesNotAffectTheBundle()
	{
		// Same definition, different recipient-specific state → identical DTO.
		Goal trained = Goal.builder()
			.id("x1").type(GoalType.SKILL).name("Ranged").skillName("RANGED")
			.targetValue(1_210_421).currentValue(1_210_421).completedAt(123_456L)
			.build();
		Goal fresh = Goal.builder()
			.id("x2").type(GoalType.SKILL).name("Ranged").skillName("RANGED")
			.targetValue(1_210_421).currentValue(0).completedAt(0)
			.build();

		GoalShareDto a = ShareMapper.toBundle(ShareBundle.Kind.GOALS, null, -1,
			Collections.singletonList(trained), lookup, "A").getGoals().get(0);
		GoalShareDto b = ShareMapper.toBundle(ShareBundle.Kind.GOALS, null, -1,
			Collections.singletonList(fresh), lookup, "A").getGoals().get(0);
		assertEquals(a, b);
	}

	@Test
	public void roundTripsThroughTheCodec()
	{
		infernoTag();
		Goal ranged = Goal.builder().id("a").type(GoalType.SKILL).name("Ranged")
			.skillName("RANGED").targetValue(1_210_421)
			.tagIds(Collections.singletonList("t1")).build();
		Goal zuk = Goal.builder().id("b").type(GoalType.BOSS).name("TzKal-Zuk")
			.bossName("TzKal-Zuk").targetValue(1)
			.requiredGoalIds(Collections.singletonList("a")).build();

		ShareBundle bundle = ShareMapper.toBundle(
			ShareBundle.Kind.SECTION, "Inferno Prep", 0x112233,
			Arrays.asList(ranged, zuk), lookup, "Andrew");

		ShareCodec codec = new ShareCodec(new Gson());
		assertEquals(bundle, codec.decode(codec.encode(bundle)));
	}

	@Test
	public void toleratesNullAndEmptyInput()
	{
		ShareBundle bundle = ShareMapper.toBundle(
			ShareBundle.Kind.GOALS, null, -1, null, lookup, null);
		assertTrue(bundle.getGoals().isEmpty());
		assertNull(bundle.getSectionName());
	}

	// ---- repeat fields on the wire ----

	/** A repeatable goal must carry its period and chunk, or a shared daily
	 *  routine silently imports as a set of one-shots. */
	@Test
	public void repeatFieldsExported()
	{
		Goal g = Goal.builder()
			.type(GoalType.SKILL).name("Prayer +10K XP").skillName("PRAYER")
			.targetValue(9_810_000)
			.repeatEvery(com.goalplanner.model.RepeatPeriod.WEEKLY)
			.repeatChunk(10_000)
			.build();

		GoalShareDto dto = ShareMapper.toBundle(ShareBundle.Kind.GOALS, null, -1,
			Collections.singletonList(g), id -> null, "tester").getGoals().get(0);

		assertEquals("WEEKLY", dto.getRepeatEvery());
		assertEquals(10_000, dto.getRepeatChunk());
	}

	@Test
	public void plainGoalExportsNone()
	{
		Goal g = Goal.builder()
			.type(GoalType.SKILL).name("Prayer - Level 99").skillName("PRAYER")
			.targetValue(13_034_431).build();

		GoalShareDto dto = ShareMapper.toBundle(ShareBundle.Kind.GOALS, null, -1,
			Collections.singletonList(g), id -> null, "tester").getGoals().get(0);

		assertEquals("NONE", dto.getRepeatEvery());
		assertEquals(0, dto.getRepeatChunk());
	}

	/** Gson leaves repeatEvery null on goals persisted before the feature; the
	 *  null-guarding getter must keep that off the wire. */
	@Test
	public void legacyGoalExportsNone()
	{
		Goal legacy = Goal.builder().type(GoalType.CUSTOM).name("Old").build();
		legacy.setRepeatEvery(null);

		GoalShareDto dto = ShareMapper.toBundle(ShareBundle.Kind.GOALS, null, -1,
			Collections.singletonList(legacy), id -> null, "tester").getGoals().get(0);

		assertEquals("NONE", dto.getRepeatEvery());
	}
}
