package com.goalplanner.ui.nest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NestIndentAssigner — outline-tree nesting from dependencies")
class NestIndentAssignerTest
{
	private static NestIndentAssigner.Node node(String id, String... prereqs)
	{
		return new NestIndentAssigner.Node(id, new ArrayList<>(Arrays.asList(prereqs)));
	}

	@Test
	@DisplayName("resolveVisiblePrereqs follows a chain through a hidden (completed) goal")
	void resolveVisiblePrereqsBridgesHiddenNode()
	{
		// a requires b, b requires c; b is hidden (completed) → a's visible prereq is c.
		java.util.Map<String, List<String>> edges = new java.util.HashMap<>();
		edges.put("a", Arrays.asList("b"));
		edges.put("b", Arrays.asList("c"));
		edges.put("c", new ArrayList<>());
		java.util.Set<String> hidden = new java.util.HashSet<>(Arrays.asList("b"));

		List<String> visible = NestIndentAssigner.resolveVisiblePrereqs(
			edges.get("a"), edges::get, hidden::contains);
		assertEquals(Arrays.asList("c"), visible);
	}

	@Test
	@DisplayName("resolveVisiblePrereqs keeps visible prereqs, drops fully-hidden chains, survives cycles")
	void resolveVisiblePrereqsMixedAndCycleSafe()
	{
		// a requires [b(hidden), d]; b requires c(hidden); c requires b (cycle) → just d.
		java.util.Map<String, List<String>> edges = new java.util.HashMap<>();
		edges.put("a", Arrays.asList("b", "d"));
		edges.put("b", Arrays.asList("c"));
		edges.put("c", Arrays.asList("b"));
		edges.put("d", new ArrayList<>());
		java.util.Set<String> hidden = new java.util.HashSet<>(Arrays.asList("b", "c"));

		List<String> visible = NestIndentAssigner.resolveVisiblePrereqs(
			edges.get("a"), edges::get, hidden::contains);
		assertEquals(Arrays.asList("d"), visible);
	}

	@Test
	@DisplayName("a chain through a completed goal still nests the dependent under its grandparent")
	void chainThroughCompletedGoalKeepsNesting()
	{
		// Section: c (incomplete), b (completed, requires c), a (requires b).
		// The completed b is excluded from the outline; expanding a's prereqs
		// through b must yield c, so a nests under c instead of flattening.
		java.util.Map<String, List<String>> edges = new java.util.HashMap<>();
		edges.put("c", new ArrayList<>());
		edges.put("b", Arrays.asList("c"));
		edges.put("a", Arrays.asList("b"));
		java.util.Set<String> completed = new java.util.HashSet<>(Arrays.asList("b"));

		List<NestIndentAssigner.Node> nodes = new ArrayList<>();
		for (String id : Arrays.asList("c", "a")) // topo order, completed excluded
		{
			nodes.add(new NestIndentAssigner.Node(id, NestIndentAssigner.resolveVisiblePrereqs(
				edges.get(id), edges::get, completed::contains)));
		}
		NestIndentAssigner.Result r = NestIndentAssigner.assign(nodes);
		assertEquals(Arrays.asList("c", "a"), r.ordered);
		assertEquals(0, r.level.get("c"));
		assertEquals(1, r.level.get("a"));
		assertEquals("c", r.primaryParent.get("a"));
	}

	@Test
	@DisplayName("a goal with no in-section prerequisites is a root at level 0")
	void rootsAreLevelZero()
	{
		NestIndentAssigner.Result r = NestIndentAssigner.assign(Arrays.asList(
			node("a"), node("b")));
		assertEquals(0, r.level.get("a"));
		assertEquals(0, r.level.get("b"));
		assertNull(r.primaryParent.get("a"));
	}

	@Test
	@DisplayName("a straight chain nests each goal under the one it needs")
	void chainNestsUnderParent()
	{
		NestIndentAssigner.Result r = NestIndentAssigner.assign(Arrays.asList(
			node("a"), node("b", "a"), node("c", "b")));
		assertEquals(Arrays.asList("a", "b", "c"), r.ordered);
		assertEquals(0, r.level.get("a"));
		assertEquals(1, r.level.get("b"));
		assertEquals(2, r.level.get("c"));
		assertEquals("a", r.primaryParent.get("b"));
		assertEquals("b", r.primaryParent.get("c"));
	}

	@Test
	@DisplayName("a child is emitted immediately after its parent (pre-order)")
	void preOrderPlacesChildUnderParent()
	{
		// two independent roots, each with a child — children follow their own parent
		NestIndentAssigner.Result r = NestIndentAssigner.assign(Arrays.asList(
			node("root1"), node("root2"), node("child1", "root1"), node("child2", "root2")));
		assertEquals(Arrays.asList("root1", "child1", "root2", "child2"), r.ordered);
	}

	@Test
	@DisplayName("a diamond goal nests under its lowest-placed prerequisite; the rest are 'extra'")
	void diamondNestsUnderPrimaryAndCountsExtras()
	{
		NestIndentAssigner.Result r = NestIndentAssigner.assign(Arrays.asList(
			node("agility"), node("combat"), node("ranged"),
			node("mm2", "agility", "combat"),
			node("dt2", "combat", "ranged"),
			node("sote", "agility", "ranged"),
			node("qpc", "mm2", "dt2", "sote"),
			node("fire"), node("inferno"),
			node("gm", "qpc", "fire", "inferno")));

		// Outline: agility, combat, mm2, ranged, dt2, sote, qpc, fire, inferno, gm.
		// Quest Cape's lowest prereq is sote → it nests there; mm2 + dt2 are extras.
		assertEquals("sote", r.primaryParent.get("qpc"));
		assertEquals(2, r.level.get("qpc"));
		assertEquals(2, r.extraPrereqs.get("qpc"));

		// Grandmaster's lowest prereq is inferno, appended after Quest Cape's subtree.
		assertEquals("inferno", r.primaryParent.get("gm"));
		assertEquals(1, r.level.get("gm"));
		assertEquals(2, r.extraPrereqs.get("gm")); // qpc + fire

		assertPrereqsAbove(r, Arrays.asList(
			node("mm2", "agility", "combat"), node("dt2", "combat", "ranged"),
			node("sote", "agility", "ranged"), node("qpc", "mm2", "dt2", "sote"),
			node("gm", "qpc", "fire", "inferno")));
	}

	@Test
	@DisplayName("a diary renders below ALL of its requirements, not just the first (issue #5)")
	void diaryRendersBelowEveryRequirement()
	{
		NestIndentAssigner.Node diary = node("ardyElite", "fletching", "fishing", "agility",
			"cooking", "smithing", "magic", "hauntedMine");
		NestIndentAssigner.Result r = NestIndentAssigner.assign(Arrays.asList(
			node("fletching"), node("fishing"), node("agility"), node("cooking"),
			node("smithing"), node("magic"), node("hauntedMine"), diary));

		assertEquals(Arrays.asList("fletching", "fishing", "agility", "cooking",
			"smithing", "magic", "hauntedMine", "ardyElite"), r.ordered);
		assertEquals("hauntedMine", r.primaryParent.get("ardyElite"));
		assertEquals(1, r.level.get("ardyElite"));
		assertEquals(6, r.extraPrereqs.get("ardyElite"));
	}

	@Test
	@DisplayName("on any DAG, every prerequisite renders above the goal that needs it")
	void prereqsAlwaysAboveOnRandomDags()
	{
		java.util.Random rnd = new java.util.Random(5);
		for (int trial = 0; trial < 500; trial++)
		{
			int n = 2 + rnd.nextInt(14);
			List<NestIndentAssigner.Node> nodes = new ArrayList<>();
			for (int i = 0; i < n; i++)
			{
				List<String> prereqs = new ArrayList<>();
				for (int j = 0; j < i; j++)
				{
					if (rnd.nextInt(3) == 0) prereqs.add("g" + j);
				}
				java.util.Collections.shuffle(prereqs, rnd);
				nodes.add(new NestIndentAssigner.Node("g" + i, prereqs));
			}
			NestIndentAssigner.Result r = NestIndentAssigner.assign(nodes);
			assertEquals(n, r.ordered.size(), "trial " + trial + " dropped or duplicated a goal");
			assertPrereqsAbove(r, nodes);
			// Pre-order shape: a goal's parent is the nearest shallower row above it.
			for (int i = 0; i < r.ordered.size(); i++)
			{
				String id = r.ordered.get(i);
				String parent = r.primaryParent.get(id);
				if (parent == null) continue;
				int k = i - 1;
				while (r.level.get(r.ordered.get(k)) >= r.level.get(id)) k--;
				assertEquals(parent, r.ordered.get(k), "trial " + trial + ": " + id + " is not under its parent");
			}
		}
	}

	private static void assertPrereqsAbove(NestIndentAssigner.Result r, List<NestIndentAssigner.Node> nodes)
	{
		for (NestIndentAssigner.Node n : nodes)
		{
			for (String p : n.prereqs)
			{
				assertTrue(r.ordered.indexOf(p) < r.ordered.indexOf(n.id),
					p + " must render above " + n.id + " but order was " + r.ordered);
			}
		}
	}

	@Test
	@DisplayName("OR prerequisites nest just like AND prerequisites")
	void orPrereqsNestToo()
	{
		NestIndentAssigner.Result r = NestIndentAssigner.assign(Arrays.asList(
			node("a"), node("b", "a"), node("gm", "b")));
		assertEquals(2, r.level.get("gm"));
		assertEquals("b", r.primaryParent.get("gm"));
	}

	@Test
	@DisplayName("prerequisites not present in the section are ignored")
	void crossSectionPrereqsIgnored()
	{
		NestIndentAssigner.Result r = NestIndentAssigner.assign(Arrays.asList(
			node("b", "external")));
		assertEquals(0, r.level.get("b"));
		assertNull(r.primaryParent.get("b"));
		assertEquals(0, r.extraPrereqs.get("b"));
	}

	@Test
	@DisplayName("every input goal appears exactly once in the order")
	void everyGoalEmittedOnce()
	{
		NestIndentAssigner.Result r = NestIndentAssigner.assign(Arrays.asList(
			node("a"), node("b", "a"), node("c", "a", "b")));
		assertEquals(3, r.ordered.size());
		assertEquals(3, r.level.size());
	}
}
