package com.goalplanner.tracker;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Tracks combat achievement completion via bit-packed CA_TASK_COMPLETED
 * varplayers. OSRS stores CA completion across 21 varplayers (32 bits
 * each = 672 task slots).
 */
@Singleton
public class CombatAchievementTracker extends AbstractTracker
{
	private static final int[] CA_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0,
		VarPlayerID.CA_TASK_COMPLETED_1,
		VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3,
		VarPlayerID.CA_TASK_COMPLETED_4,
		VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6,
		VarPlayerID.CA_TASK_COMPLETED_7,
		VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9,
		VarPlayerID.CA_TASK_COMPLETED_10,
		VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12,
		VarPlayerID.CA_TASK_COMPLETED_13,
		VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15,
		VarPlayerID.CA_TASK_COMPLETED_16,
		VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18,
		VarPlayerID.CA_TASK_COMPLETED_19,
		VarPlayerID.CA_TASK_COMPLETED_20,
	};

	/** Highest task id a varplayer covers (671). */
	public static final int MAX_TASK_ID = CA_VARPS.length * 32 - 1;

	@Inject
	public CombatAchievementTracker(Client client, GoalPlannerApiImpl api)
	{
		super(client, api);
	}

	@Override
	protected GoalType targetType()
	{
		return GoalType.COMBAT_ACHIEVEMENT;
	}

	@Override
	protected boolean shouldTrack(Goal goal)
	{
		int taskId = goal.getCaTaskId();
		return taskId >= 0 && taskId <= MAX_TASK_ID;
	}

	@Override
	protected int readCurrentValue(Goal goal)
	{
		int taskId = goal.getCaTaskId();
		int varpId = CA_VARPS[taskId / 32];
		int bitIndex = taskId % 32;
		int varpValue = client.getVarpValue(varpId);
		boolean done = ((varpValue >> bitIndex) & 1) == 1;
		return done ? 1 : 0;
	}
}
