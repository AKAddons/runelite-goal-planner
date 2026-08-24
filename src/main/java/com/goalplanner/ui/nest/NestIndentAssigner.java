package com.goalplanner.ui.nest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Arranges one section's goals into a subtly-nested <em>outline tree</em> so that
 * indentation encodes dependency: each goal is placed directly beneath the goal
 * it depends on, indented one level deeper, and the section is emitted in
 * pre-order (a parent immediately followed by its children).
 *
 * <p>Goals form a DAG, not a tree - a "diamond" goal can depend on several others
 * (Quest Cape needs three quests; Grandmaster CA needs Quest Cape AND a cape).
 * Indentation can only nest a goal under <em>one</em> parent, so each goal is
 * placed under its <b>primary parent</b>: the in-section prerequisite with the
 * greatest dependency depth (ties broken by input order), so the longest chain
 * forms the spine. Any remaining prerequisites are reported via
 * {@link Result#extraPrereqs} (count) and {@link Result#primaryParent} so the UI
 * can hint at them (e.g. a tooltip) without drawing connector lines.
 *
 * <p>Both AND ({@code requires}) and OR ({@code orRequires}) prerequisites count.
 * Prerequisites not present in the same section are ignored. Input MUST be in
 * topological order (prereqs before dependents).
 */
public final class NestIndentAssigner
{
	private NestIndentAssigner() {}

	/** One goal: its id and its in-section prerequisites (AND + OR combined), in display order. */
	public static final class Node
	{
		public final String id;
		public final List<String> prereqs;

		public Node(String id, List<String> prereqs)
		{
			this.id = id;
			this.prereqs = prereqs != null ? prereqs : new ArrayList<>();
		}
	}

	/** The computed outline: render order, indent level per goal, and multi-parent info. */
	public static final class Result
	{
		/** Goal ids in pre-order (parent immediately before its children). */
		public final List<String> ordered;
		/** id → indent level (0 = root / no in-section prereqs). */
		public final Map<String, Integer> level;
		/** id → primary-parent id (the prereq it's nested under), or {@code null} for roots. */
		public final Map<String, String> primaryParent;
		/** id → number of in-section prerequisites beyond the primary parent (0 if ≤1). */
		public final Map<String, Integer> extraPrereqs;

		Result(List<String> ordered, Map<String, Integer> level,
			   Map<String, String> primaryParent, Map<String, Integer> extraPrereqs)
		{
			this.ordered = ordered;
			this.level = level;
			this.primaryParent = primaryParent;
			this.extraPrereqs = extraPrereqs;
		}
	}

	/**
	 * Resolve a goal's visible prerequisites by following edges THROUGH hidden
	 * nodes: a hidden prerequisite (e.g. a completed goal the caller filters
	 * out of the outline) is replaced by its own visible prerequisites,
	 * transitively - so A → B(hidden) → C still nests A under C instead of
	 * flattening A to a root. Cycle-safe via the visited set; result order
	 * follows edge order (depth-first through hidden nodes).
	 *
	 * @param direct    the goal's direct prerequisite ids (already scoped, e.g.
	 *                  in-section only)
	 * @param prereqsOf direct prerequisite ids for any id (same scoping)
	 * @param hidden    true for ids excluded from the outline (pass-through)
	 */
	public static List<String> resolveVisiblePrereqs(
		List<String> direct,
		Function<String, List<String>> prereqsOf,
		Predicate<String> hidden)
	{
		List<String> out = new ArrayList<>();
		expandThroughHidden(direct, prereqsOf, hidden, new HashSet<>(), out);
		return out;
	}

	private static void expandThroughHidden(
		List<String> edges,
		Function<String, List<String>> prereqsOf,
		Predicate<String> hidden,
		Set<String> visited,
		List<String> out)
	{
		if (edges == null) return;
		for (String id : edges)
		{
			if (!visited.add(id)) continue;
			if (hidden.test(id))
			{
				expandThroughHidden(prereqsOf.apply(id), prereqsOf, hidden, visited, out);
			}
			else
			{
				out.add(id);
			}
		}
	}

	/**
	 * @param nodes goals in topological order (prereqs before dependents)
	 * @return the outline tree (order, levels, primary parents, extra-prereq counts)
	 */
	public static Result assign(List<Node> nodes)
	{
		Set<String> present = new HashSet<>();
		for (Node n : nodes) present.add(n.id);

		// 1) dependency depth (max over prereqs + 1) - used to choose the primary parent.
		Map<String, Integer> depth = new HashMap<>();
		for (Node n : nodes)
		{
			int d = 0;
			for (String p : n.prereqs)
			{
				if (!present.contains(p)) continue;
				Integer pd = depth.get(p);
				if (pd != null) d = Math.max(d, pd + 1);
			}
			depth.put(n.id, d);
		}

		// 2) primary parent = deepest in-section prereq (ties → first in input order);
		//    build child lists (preserving input order) and the root list.
		Map<String, String> primaryParent = new HashMap<>();
		Map<String, Integer> extraPrereqs = new HashMap<>();
		Map<String, List<String>> children = new HashMap<>();
		for (Node n : nodes) children.put(n.id, new ArrayList<>());
		List<String> roots = new ArrayList<>();

		for (Node n : nodes)
		{
			String best = null;
			int bestDepth = -1;
			int inSection = 0;
			for (String p : n.prereqs)
			{
				if (!present.contains(p)) continue;
				inSection++;
				int pd = depth.getOrDefault(p, 0);
				if (pd > bestDepth)
				{
					bestDepth = pd;
					best = p;
				}
			}
			primaryParent.put(n.id, best);
			extraPrereqs.put(n.id, Math.max(0, inSection - 1));
			if (best == null) roots.add(n.id);
			else children.get(best).add(n.id);
		}

		// 3) pre-order walk: each parent immediately followed by its children.
		List<String> ordered = new ArrayList<>(nodes.size());
		Map<String, Integer> level = new HashMap<>();
		for (String root : roots) walk(root, 0, children, ordered, level);

		// Safety net: if the input wasn't strictly topo-ordered and something
		// wasn't reached, append it at level 0 so nothing is silently dropped.
		for (Node n : nodes)
		{
			if (!level.containsKey(n.id))
			{
				ordered.add(n.id);
				level.put(n.id, 0);
			}
		}

		return new Result(ordered, level, primaryParent, extraPrereqs);
	}

	private static void walk(String id, int lvl, Map<String, List<String>> children,
							 List<String> ordered, Map<String, Integer> level)
	{
		if (level.containsKey(id)) return; // defensive against accidental re-entry
		ordered.add(id);
		level.put(id, lvl);
		for (String c : children.getOrDefault(id, new ArrayList<>()))
		{
			walk(c, lvl + 1, children, ordered, level);
		}
	}
}
