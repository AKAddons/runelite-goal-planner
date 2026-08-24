package com.goalplanner.api;

import com.goalplanner.model.Goal;

import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Encapsulates relation (requirement/dependency) methods extracted from {@link GoalPlannerApiImpl}.
 * Package-private - only {@link GoalPlannerApiImpl} instantiates and delegates to this.
 */
@Slf4j
class RelationService
{
	private final GoalPlannerApiImpl api;

	RelationService(GoalPlannerApiImpl api)
	{
		this.api = api;
	}

	boolean addRequirement(String fromGoalId, String toGoalId)
	{
		log.debug("API.internal addRequirement(from={}, to={})", fromGoalId, toGoalId);
		if (fromGoalId == null || toGoalId == null) return false;
		if (fromGoalId.equals(toGoalId)) return false;
		Goal from = api.findGoal(fromGoalId);
		Goal to = api.findGoal(toGoalId);
		if (from == null || to == null) return false;
		// Idempotent precheck so we don't push a no-op command on the stack.
		if (from.getRequiredGoalIds() != null && from.getRequiredGoalIds().contains(toGoalId))
		{
			return false;
		}
		if (api.goalStore.wouldCreateCycle(fromGoalId, toGoalId)) return false;

		final String fromName = from.getName();
		final String toName = to.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				return api.goalStore.addRequirement(fromGoalId, toGoalId);
			}
			@Override public boolean revert()
			{
				return api.goalStore.removeRequirement(fromGoalId, toGoalId);
			}
			@Override public String getDescription()
			{
				return "Link: " + fromName + " requires " + toName;
			}
		});
	}

	boolean removeRequirement(String fromGoalId, String toGoalId)
	{
		log.debug("API.internal removeRequirement(from={}, to={})", fromGoalId, toGoalId);
		if (fromGoalId == null || toGoalId == null) return false;
		Goal from = api.findGoal(fromGoalId);
		Goal to = api.findGoal(toGoalId);
		if (from == null || to == null) return false;
		// Idempotent precheck.
		if (from.getRequiredGoalIds() == null
			|| !from.getRequiredGoalIds().contains(toGoalId))
		{
			return false;
		}

		final String fromName = from.getName();
		final String toName = to.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				return api.goalStore.removeRequirement(fromGoalId, toGoalId);
			}
			@Override public boolean revert()
			{
				return api.goalStore.addRequirement(fromGoalId, toGoalId);
			}
			@Override public String getDescription()
			{
				return "Unlink: " + fromName + " requires " + toName;
			}
		});
	}

	boolean addOrRequirement(String fromGoalId, String toGoalId)
	{
		log.debug("API.internal addOrRequirement(from={}, to={})", fromGoalId, toGoalId);
		if (fromGoalId == null || toGoalId == null) return false;
		if (fromGoalId.equals(toGoalId)) return false;
		Goal from = api.findGoal(fromGoalId);
		Goal to = api.findGoal(toGoalId);
		if (from == null || to == null) return false;
		if (from.getOrRequiredGoalIds() != null && from.getOrRequiredGoalIds().contains(toGoalId))
		{
			return false;
		}
		if (api.goalStore.wouldCreateCycle(fromGoalId, toGoalId)) return false;

		final String fromName = from.getName();
		final String toName = to.getName();
		return api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				return api.goalStore.addOrRequirement(fromGoalId, toGoalId);
			}
			@Override public boolean revert()
			{
				return api.goalStore.removeOrRequirement(fromGoalId, toGoalId);
			}
			@Override public String getDescription()
			{
				return "Link (OR): " + fromName + " or-requires " + toName;
			}
		});
	}

	List<String> getRequirements(String goalId)
	{
		Goal g = api.findGoal(goalId);
		if (g == null || g.getRequiredGoalIds() == null) return new ArrayList<>();
		return new ArrayList<>(g.getRequiredGoalIds());
	}

	List<String> getDependents(String goalId)
	{
		return api.goalStore.getDependents(goalId);
	}

	GoalPlannerInternalApi.FindOrCreateResult findOrCreateRequirement(Goal template, String preferredSectionId)
	{
		log.debug("API.internal findOrCreateRequirement(type={})",
			template == null ? null : template.getType());
		if (template == null || template.getType() == null) return null;

		// Structural match first - reuse an existing goal if one satisfies.
		Goal existing = api.goalStore.findMatchingGoal(template);
		if (existing != null)
		{
			return new GoalPlannerInternalApi.FindOrCreateResult(existing.getId(), false);
		}

		// No match - create a seed goal from the template, marked autoSeeded.
		Goal seed = Goal.builder()
			.type(template.getType())
			.name(template.getName())
			.description(template.getDescription())
			.targetValue(template.getTargetValue())
			.currentValue(template.getCurrentValue())
			.skillName(template.getSkillName())
			.questName(template.getQuestName())
			.varbitId(template.getVarbitId())
			.itemId(template.getItemId())
			.spriteId(template.getSpriteId())
			.caTaskId(template.getCaTaskId())
			.sectionId(preferredSectionId)
			.autoSeeded(true)
			.build();

		final String seedId = seed.getId();
		final Goal capturedSeed = seed;
		final String displayName = seed.getName() != null ? seed.getName() : "goal";
		boolean ok = api.executeCommand(new com.goalplanner.command.Command()
		{
			@Override public boolean apply()
			{
				if (api.findGoal(seedId) != null) return false;
				api.goalStore.addGoal(capturedSeed);
				return true;
			}
			@Override public boolean revert()
			{
				api.goalStore.removeGoal(seedId);
				return true;
			}
			@Override public String getDescription()
			{
				return "Seed requirement: " + displayName;
			}
		});
		return ok ? new GoalPlannerInternalApi.FindOrCreateResult(seedId, true) : null;
	}

	com.goalplanner.data.QuestRequirementResolver.Resolved resolveQuestRequirements(
		net.runelite.api.Quest quest)
	{
		log.debug("API.internal resolveQuestRequirements(quest={})", quest);
		if (quest == null || api.client == null)
		{
			return new com.goalplanner.data.QuestRequirementResolver.Resolved(
				List.of(), 0, 0, 0, 0);
		}
		return com.goalplanner.data.QuestRequirementResolver.resolve(quest, api.client);
	}
}
