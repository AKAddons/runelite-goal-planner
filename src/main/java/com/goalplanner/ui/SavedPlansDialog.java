package com.goalplanner.ui;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.persistence.SavedPlan;
import com.goalplanner.persistence.SavedPlanStore;
import com.goalplanner.share.SavedPlanSections;
import com.goalplanner.share.ShareBundle;
import com.goalplanner.share.ShareCodec;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * The "Saved Plans" library: a list of bookmarked share codes that can be
 * imported later. Each row shows the save name, a decoded preview, and per-row
 * Import / Copy / Edit / Delete actions. Edit lets the user rename the save and
 * change how each section will be named when imported.
 *
 * <p>Interactive and EDT-only - no unit tests; the store and the section-name
 * override logic it drives are covered in the persistence/share packages.
 */
public final class SavedPlansDialog extends JDialog
{
	private final GoalPlannerApiImpl api;
	private final ShareCodec codec;
	private final SavedPlanStore store;
	private final Runnable onImported;
	private final JPanel listPanel;

	private SavedPlansDialog(Frame owner, GoalPlannerApiImpl api, ShareCodec codec,
		SavedPlanStore store, Runnable onImported)
	{
		super(owner, "Saved Plans", true);
		this.api = api;
		this.codec = codec;
		this.store = store;
		this.onImported = onImported;

		setLayout(new BorderLayout());
		setSize(440, 540);
		setLocationRelativeTo(owner);

		JLabel header = new JLabel("Saved Plans", SwingConstants.LEFT);
		header.setFont(PanelFonts.derive(Font.BOLD, 14f));
		header.setBorder(new EmptyBorder(10, 12, 4, 12));
		add(header, BorderLayout.NORTH);

		listPanel = new JPanel();
		listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
		listPanel.setBorder(new EmptyBorder(4, 8, 8, 8));
		JScrollPane scroll = new JScrollPane(listPanel);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.setBorder(null);
		add(scroll, BorderLayout.CENTER);

		rebuild();
	}

	/** Open the library dialog, modal over the panel. */
	public static void open(Component parent, GoalPlannerApiImpl api, ShareCodec codec,
		SavedPlanStore store, Runnable onImported)
	{
		Window w = SwingUtilities.getWindowAncestor(parent);
		Frame owner = (w instanceof Frame) ? (Frame) w : null;
		new SavedPlansDialog(owner, api, codec, store, onImported).setVisible(true);
	}

	private void rebuild()
	{
		listPanel.removeAll();
		List<SavedPlan> plans = store.getPlans();
		if (plans.isEmpty())
		{
			JLabel empty = new JLabel("<html><div style='width:340px'>No saved plans yet.<br><br>"
				+ "Save a code from a section's <b>Share &gt; Save share code...</b> menu, or choose "
				+ "<b>Save for later</b> when importing a code.</div></html>");
			empty.setBorder(new EmptyBorder(16, 8, 8, 8));
			listPanel.add(empty);
		}
		else
		{
			for (SavedPlan plan : plans)
			{
				listPanel.add(row(plan));
			}
		}
		listPanel.revalidate();
		listPanel.repaint();
	}

	private JPanel row(SavedPlan plan)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, new java.awt.Color(0, 0, 0, 40)),
			new EmptyBorder(6, 4, 6, 4)));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		JLabel name = new JLabel(plan.getName() == null ? "(unnamed)" : plan.getName());
		name.setFont(PanelFonts.derive(Font.BOLD, 12f));
		JLabel sub = new JLabel(preview(plan));
		sub.setFont(PanelFonts.derive(Font.PLAIN, 11f));
		sub.setForeground(new java.awt.Color(150, 150, 150));
		text.add(name);
		text.add(sub);
		row.add(text, BorderLayout.CENTER);

		JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
		actions.add(smallButton("Import", e -> importPlan(plan)));
		actions.add(smallButton("Copy", e -> copyPlan(plan)));
		actions.add(smallButton("Edit", e -> editPlan(plan)));
		actions.add(smallButton("Delete", e -> deletePlan(plan)));
		row.add(actions, BorderLayout.EAST);
		return row;
	}

	private static JButton smallButton(String label, java.awt.event.ActionListener onClick)
	{
		JButton b = new JButton(label);
		b.setMargin(new java.awt.Insets(1, 6, 1, 6));
		b.setFont(PanelFonts.derive(Font.PLAIN, 11f));
		b.addActionListener(onClick);
		return b;
	}

	/** "3 goals - 2 sections", or "unreadable code" if the saved code won't decode. */
	private String preview(SavedPlan plan)
	{
		try
		{
			ShareBundle b = codec.decode(plan.getCode());
			int n = b.totalGoalCount();
			int secs = b.effectiveSections().size();
			String s = n + " goal" + (n == 1 ? "" : "s");
			return secs > 1 ? s + " - " + secs + " sections" : s;
		}
		catch (RuntimeException e)
		{
			return "unreadable code";
		}
	}

	private void importPlan(SavedPlan plan)
	{
		ShareBundle bundle;
		try
		{
			bundle = codec.decode(plan.getCode());
		}
		catch (RuntimeException e)
		{
			JOptionPane.showMessageDialog(this, "This saved code could not be read - it may be from an "
				+ "incompatible plugin version.", "Saved plans", JOptionPane.WARNING_MESSAGE);
			return;
		}
		SavedPlanSections.applySectionNames(bundle, plan.getSectionNames());
		ShareDialogs.doImport(this, api, bundle, codec.encode(bundle), onImported);
		dispose();
	}

	private void copyPlan(SavedPlan plan)
	{
		java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
			.setContents(new StringSelection(plan.getCode()), null);
		JOptionPane.showMessageDialog(this, "Copied the share code to your clipboard.",
			"Saved plans", JOptionPane.INFORMATION_MESSAGE);
	}

	private void editPlan(SavedPlan plan)
	{
		JPanel form = new JPanel();
		form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

		JTextField nameField = new JTextField(plan.getName() == null ? "" : plan.getName(), 22);
		form.add(new JLabel("Save name:"));
		form.add(nameField);

		List<String> current = sectionNamesForEdit(plan);
		List<JTextField> sectionFields = new ArrayList<>();
		if (!current.isEmpty())
		{
			form.add(javax.swing.Box.createVerticalStrut(8));
			form.add(new JLabel("Section names on import:"));
			for (int i = 0; i < current.size(); i++)
			{
				String val = current.get(i) == null ? "" : current.get(i);
				JTextField f = new JTextField(val, 22);
				sectionFields.add(f);
				form.add(f);
			}
		}

		int res = JOptionPane.showConfirmDialog(this, form, "Edit saved plan",
			JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (res != JOptionPane.OK_OPTION)
		{
			return;
		}
		String newName = nameField.getText().trim();
		store.rename(plan.getId(), newName.isEmpty() ? plan.getName() : newName);
		if (!sectionFields.isEmpty())
		{
			List<String> names = new ArrayList<>();
			for (JTextField f : sectionFields)
			{
				names.add(f.getText());
			}
			store.setSectionNames(plan.getId(), names);
		}
		rebuild();
	}

	/** The section names to pre-fill the edit form: saved overrides if present,
	 *  else the code's own section names (decoded). Empty if the code won't read. */
	private List<String> sectionNamesForEdit(SavedPlan plan)
	{
		if (plan.getSectionNames() != null && !plan.getSectionNames().isEmpty())
		{
			return plan.getSectionNames();
		}
		try
		{
			return SavedPlanSections.sectionNamesOf(codec.decode(plan.getCode()));
		}
		catch (RuntimeException e)
		{
			return new ArrayList<>();
		}
	}

	private void deletePlan(SavedPlan plan)
	{
		int res = JOptionPane.showConfirmDialog(this,
			"Delete saved plan \"" + plan.getName() + "\"?", "Delete saved plan",
			JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (res == JOptionPane.YES_OPTION)
		{
			store.remove(plan.getId());
			rebuild();
		}
	}
}
