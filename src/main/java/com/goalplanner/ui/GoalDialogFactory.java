package com.goalplanner.ui;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.api.GoalPlannerInternalApi;
import com.goalplanner.api.SectionView;
import com.goalplanner.model.Goal;
import com.goalplanner.model.GoalType;
import com.goalplanner.util.FormatUtil;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.game.SpriteManager;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JRadioButton;

/**
 * Factory that builds and shows every goal/section dialog the panel needs.
 * Extracted from GoalPanel to keep dialog construction out of the panel class.
 */
@Slf4j
class GoalDialogFactory
{
	private final GoalPlannerApiImpl api;
	private final com.goalplanner.persistence.GoalStore goalStore;
	private final SkillIconManager skillIconManager;
	private final ItemManager itemManager;
	private final SpriteManager spriteManager;
	private final GoalPanel.ItemSearchRequest itemSearchCallback;
	private final Component parentComponent;

	/** In-section position to place the next goal created via
	 *  showAddGoalDialog. -1 = default (bottom). Cleared after each create. */
	int pendingAddPositionInSection = -1;

	private Client client;

	GoalDialogFactory(GoalPlannerApiImpl api,
					  com.goalplanner.persistence.GoalStore goalStore,
					  SkillIconManager skillIconManager,
					  ItemManager itemManager,
					  SpriteManager spriteManager,
					  GoalPanel.ItemSearchRequest itemSearchCallback,
					  Component parentComponent)
	{
		this.api = api;
		this.goalStore = goalStore;
		this.skillIconManager = skillIconManager;
		this.itemManager = itemManager;
		this.spriteManager = spriteManager;
		this.itemSearchCallback = itemSearchCallback;
		this.parentComponent = parentComponent;
	}

	void setClient(Client client)
	{
		this.client = client;
	}

	/** Runs a task on the client thread; defaults to synchronous until the
	 *  plugin wires {@code clientThread::invokeLater}. The Max button reads the
	 *  live account-metric ceiling (quest DB table / collection-log varp),
	 *  which asserts the client thread. */
	private Consumer<Runnable> clientThreadExec = Runnable::run;

	void setClientThreadExecutor(Consumer<Runnable> exec)
	{
		this.clientThreadExec = exec != null ? exec : Runnable::run;
	}

	/**
	 * Re-target dialog - opens a modal with a
	 * SkillTargetForm with synced Level/XP fields plus a Mode toggle so the
	 * user can target an absolute level/XP OR a delta gain.
	 */
	void showChangeSkillTargetDialog(Goal goal)
	{
		net.runelite.api.Skill skill;
		try
		{
			skill = net.runelite.api.Skill.valueOf(goal.getSkillName());
		}
		catch (Exception ex) { return; }

		int currentXp = client != null ? client.getSkillExperience(skill) : 0;
		int currentTargetLevel = goal.getTargetValue() > 0
			? net.runelite.api.Experience.getLevelForXp(goal.getTargetValue()) : 1;

		SkillTargetForm form = new SkillTargetForm(currentTargetLevel);

		JRadioButton modeAbsolute = new JRadioButton("Reach X", true);
		JRadioButton modeRelative = new JRadioButton("Gain X more");
		modeAbsolute.setOpaque(false);
		modeRelative.setOpaque(false);
		ButtonGroup grp = new ButtonGroup();
		grp.add(modeAbsolute); grp.add(modeRelative);
		JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		modeRow.setOpaque(false);
		modeRow.add(modeAbsolute);
		modeRow.add(modeRelative);
		modeAbsolute.addActionListener(ev -> form.setRelativeBaseline(-1));
		modeRelative.addActionListener(ev -> form.setRelativeBaseline(currentXp));

		JPanel panel = new JPanel(new GridBagLayout());
		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(4, 4, 4, 4);
		gbc.anchor = GridBagConstraints.WEST;
		gbc.gridx = 0; gbc.gridy = 0;
		panel.add(new JLabel("Mode:"), gbc);
		gbc.gridx = 1;
		panel.add(modeRow, gbc);
		gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
		panel.add(form, gbc);

		int result = JOptionPane.showConfirmDialog(parentComponent, panel,
			"Change " + skill.getName() + " Target",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (result != JOptionPane.OK_OPTION) return;

		int formValue = form.getTargetXp();
		if (formValue < 0) return;
		int newXp = modeRelative.isSelected()
			? RelativeTargetResolver.resolveSkillXp(currentXp, formValue)
			: formValue;
		if (newXp < 0) return;
		api.changeTarget(goal.getId(), newXp);
	}








	void showRenameSectionDialog(SectionView section)
	{
		String input = (String) JOptionPane.showInputDialog(parentComponent, "New name:", "Rename Section",
			JOptionPane.PLAIN_MESSAGE, null, null, section.name);
		if (input == null) return;
		boolean ok = api.renameSection(section.id, input);
		if (!ok)
		{
			JOptionPane.showMessageDialog(parentComponent,
				"Could not rename section. Name may be invalid, duplicate, or unchanged.",
				"Rename failed", JOptionPane.WARNING_MESSAGE);
		}
	}


	private void addSkillGoal(JComboBox<Skill> skillCombo, SkillTargetForm form, String preferredSectionId, boolean relative)
	{
		Skill skill = (Skill) skillCombo.getSelectedItem();
		int formValue = form.getTargetXp();
		if (formValue < 0)
		{
			JOptionPane.showMessageDialog(parentComponent,
				relative ? "Enter a valid XP delta (1-200,000,000)."
					: "Enter a valid target level (1-99) or XP (0-200,000,000).",
				"Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		// In relative mode, the form returns a delta. Resolve to
		// absolute by adding the player's current XP for the chosen skill.
		int targetXp;
		if (relative)
		{
			int currentXp = client != null ? client.getSkillExperience(skill) : 0;
			targetXp = RelativeTargetResolver.resolveSkillXp(currentXp, formValue);
			if (targetXp < 0)
			{
				JOptionPane.showMessageDialog(parentComponent, "XP delta must be greater than 0.",
					"Error", JOptionPane.ERROR_MESSAGE);
				return;
			}
		}
		else
		{
			targetXp = formValue;
		}

		String conflict = checkSkillConflict(skill, targetXp);
		if (conflict != null)
		{
			JOptionPane.showMessageDialog(parentComponent, conflict, "Conflict", JOptionPane.WARNING_MESSAGE);
			return;
		}

		// Wrap create + position in a single compound undo entry
		// so one undo fully reverses the operation.
		api.beginCompound("Add goal: " + skill.getName());
		try
		{
			String createdId = api.addSkillGoal(skill, targetXp);
			moveToPreferredSection(createdId, preferredSectionId);
		}
		finally
		{
			api.endCompound();
		}
	}

	private void addCustomGoal(JTextField nameField, JTextField descField, String preferredSectionId)
	{
		String name = nameField.getText().trim();
		if (name.isEmpty())
		{
			JOptionPane.showMessageDialog(parentComponent, "Goal name is required.", "Error", JOptionPane.ERROR_MESSAGE);
			return;
		}

		api.beginCompound("Add goal: " + name);
		try
		{
			String createdId = api.addCustomGoal(name, descField.getText().trim());
			moveToPreferredSection(createdId, preferredSectionId);
		}
		finally
		{
			api.endCompound();
		}
	}

	/**
	 * Move a freshly-created goal to a section other than the default Incomplete.
	 * Used by the section header "Add Goal" entry to drop new goals directly
	 * into the section the user right-clicked. No-op when preferredSectionId is
	 * null (the toolbar + button) or the goal didn't actually get created.
	 *
	 * <p>Also honors {@link #pendingAddPositionInSection} so the
	 * goal lands at the exact slot the user picked from the context menu
	 * (Top, Bottom, Above, Below). Field is cleared after use.
	 */
	private void moveToPreferredSection(String goalId, String preferredSectionId)
	{
		if (goalId == null) return;
		try
		{
			if (preferredSectionId != null && pendingAddPositionInSection >= 0)
			{
				api.positionGoalInSection(goalId, preferredSectionId, pendingAddPositionInSection);
			}
			else if (preferredSectionId != null)
			{
				api.moveGoalToSection(goalId, preferredSectionId);
			}
		}
		finally
		{
			pendingAddPositionInSection = -1;
		}
	}

	/**
	 * Check if a new skill goal conflicts with existing goals.
	 * Blocks exact duplicates only. Multiple levels for the same skill are fine.
	 * Returns an error message if conflicting, null if OK.
	 */
	String checkSkillConflict(Skill skill, int target)
	{
		for (Goal existing : goalStore.getGoals())
		{
			if (existing.getType() != GoalType.SKILL || existing.getSkillName() == null)
			{
				continue;
			}
			if (!existing.getSkillName().equals(skill.name()))
			{
				continue;
			}
			if (existing.isComplete())
			{
				continue;
			}

			if (existing.getTargetValue() == target)
			{
				return String.format("You already have a %s goal for %s.",
					skill.getName(), target > 99 ? FormatUtil.formatNumber(target) + " XP" : "Level " + target);
			}
		}
		return null;
	}
}
