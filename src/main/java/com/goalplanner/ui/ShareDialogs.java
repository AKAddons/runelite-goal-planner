package com.goalplanner.ui;

import com.goalplanner.api.GoalPlannerApiImpl;
import com.goalplanner.share.ShareBundle;
import com.goalplanner.share.ShareCodec;
import com.goalplanner.share.ShareFormatException;
import com.goalplanner.share.ShareText;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Swing dialogs for sharing/importing goals (export to clipboard, paste-import,
 * copy-code flows). EDT-only and interactive, so there are no unit tests - these
 * are verified in-client. The non-interactive engine they drive (export, codec,
 * import) is covered by tests in the api/share packages.
 */
public final class ShareDialogs
{
	private ShareDialogs()
	{
	}

	/** Paste a share code → import now, or (when the library is wired) save it to
	 *  the Saved Plans library for later. {@code onDone} (e.g. a panel rebuild)
	 *  runs on a successful import. */
	public static void promptImport(Component parent, GoalPlannerApiImpl api, ShareCodec codec,
		com.goalplanner.persistence.SavedPlanStore savedPlanStore, Runnable onDone)
	{
		// Multi-line text area (not a single-line input): long codes wrap when
		// pasted from chat, and a single-line field would drop everything after
		// the first newline. The decoder skips embedded whitespace, so a wrapped
		// paste still decodes.
		JTextArea codeArea = new JTextArea(4, 28);
		codeArea.setLineWrap(true);
		codeArea.setWrapStyleWord(false);
		JScrollPane codeScroll = new JScrollPane(codeArea);
		int ok = JOptionPane.showConfirmDialog(parent,
			new Object[]{"Paste a Goal Planner share code:", codeScroll},
			"Import shared goals", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (ok != JOptionPane.OK_OPTION)
		{
			return;
		}
		String text = codeArea.getText();
		if (text == null || text.trim().isEmpty())
		{
			return;
		}
		ShareBundle bundle;
		try
		{
			bundle = codec.decode(text);
		}
		catch (ShareFormatException e)
		{
			JOptionPane.showMessageDialog(parent,
				"That doesn't look like a valid Goal Planner share code.",
				"Import failed", JOptionPane.WARNING_MESSAGE);
			return;
		}
		// Offer import-now vs bank-for-later when the Saved Plans library is wired.
		if (savedPlanStore != null)
		{
			String[] options = {"Import now", "Save for later", "Cancel"};
			int choice = JOptionPane.showOptionDialog(parent,
				"Import these goals now, or save the code to your library to import later?",
				"Import shared goals", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
				null, options, options[0]);
			if (choice == 2 || choice == JOptionPane.CLOSED_OPTION)
			{
				return;
			}
			if (choice == 1)
			{
				promptSavePlan(parent, savedPlanStore, bundle, codec.encode(bundle));
				return;
			}
		}
		doImport(parent, api, bundle, codec.encode(bundle), onDone);
	}

	/** Import an already-decoded bundle, with the standard "imported N goal(s)"
	 *  confirmation. Shared by the import dialog and the Saved Plans library. */
	static void doImport(Component parent, GoalPlannerApiImpl api, ShareBundle bundle,
		String canonicalCode, Runnable onDone)
	{
		// Re-import protection: pasting the same code twice silently duplicated
		// every goal outside the default plan (named-section imports have no
		// dedup). The history is per character, so a code you gave YOUR alt is
		// still fresh for the alt. Confirming still allows the duplicate -
		// sometimes two copies is what you want.
		if (canonicalCode != null && api.wasCodeImported(canonicalCode))
		{
			int again = JOptionPane.showConfirmDialog(parent,
				"You've imported this code before - goals outside your Default plan "
					+ "will be duplicated.\nImport again anyway?",
				"Already imported", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (again != JOptionPane.YES_OPTION)
			{
				return;
			}
		}
		String sectionId = api.importShareBundle(bundle);
		if (sectionId == null)
		{
			JOptionPane.showMessageDialog(parent, "Nothing to import.", "Import",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		if (canonicalCode != null)
		{
			api.rememberImportedCode(canonicalCode);
		}
		if (onDone != null)
		{
			onDone.run();
		}
		int n = 0;
		int sections = 0;
		for (com.goalplanner.share.SectionShareDto sec : bundle.effectiveSections())
		{
			if (sec.getGoals() != null && !sec.getGoals().isEmpty())
			{
				sections++;
				n += sec.getGoals().size();
			}
		}
		String where = sections > 1 ? " across " + sections + " sections" : "";
		JOptionPane.showMessageDialog(parent, "Imported " + n + " goal(s)" + where + ".", "Import",
			JOptionPane.INFORMATION_MESSAGE);
	}

	/** Prompt for a name and bank {@code code} into the Saved Plans library,
	 *  remembering the bundle's section display names (editable later). */
	static void promptSavePlan(Component parent, com.goalplanner.persistence.SavedPlanStore store,
		ShareBundle bundle, String code)
	{
		String defaultName = defaultPlanName(bundle);
		String name = JOptionPane.showInputDialog(parent, "Name this saved plan:", defaultName);
		if (name == null)
		{
			return;
		}
		name = name.trim();
		if (name.isEmpty())
		{
			name = defaultName;
		}
		store.add(name, code, com.goalplanner.share.SavedPlanSections.sectionNamesOf(bundle));
		JOptionPane.showMessageDialog(parent,
			"Saved \"" + name + "\" to your plans.\nImport it later from the panel menu (Saved plans).",
			"Saved plans", JOptionPane.INFORMATION_MESSAGE);
	}

	private static String defaultPlanName(ShareBundle bundle)
	{
		for (com.goalplanner.share.SectionShareDto s : bundle.effectiveSections())
		{
			if (s.getName() != null && !s.getName().trim().isEmpty())
			{
				return s.getName().trim();
			}
		}
		return "Shared plan";
	}

	/** Bookmark a section's code into the Saved Plans library. */
	public static void savePlanForSection(Component parent, GoalPlannerApiImpl api, ShareCodec codec,
		Supplier<String> playerName, com.goalplanner.persistence.SavedPlanStore store, String sectionId)
	{
		ShareBundle bundle = api.exportSectionBundle(sectionId, safeName(playerName));
		if (bundle == null || bundle.getGoals().isEmpty())
		{
			JOptionPane.showMessageDialog(parent, "That section has no goals to save.",
				"Saved plans", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		promptSavePlan(parent, store, bundle, codec.encode(bundle));
	}

	/** Bookmark a goal selection's code into the Saved Plans library. */
	public static void savePlanForGoals(Component parent, GoalPlannerApiImpl api, ShareCodec codec,
		Supplier<String> playerName, com.goalplanner.persistence.SavedPlanStore store, List<String> goalIds)
	{
		ShareBundle bundle = api.exportGoalsBundle(goalIds, safeName(playerName));
		if (bundle == null || bundle.totalGoalCount() == 0)
		{
			JOptionPane.showMessageDialog(parent, "Nothing to save.", "Saved plans",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		promptSavePlan(parent, store, bundle, codec.encode(bundle));
	}

	/** Bookmark an all-sections code into the Saved Plans library. */
	public static void savePlanForAllSections(Component parent, GoalPlannerApiImpl api, ShareCodec codec,
		Supplier<String> playerName, com.goalplanner.persistence.SavedPlanStore store)
	{
		ShareBundle bundle = api.exportAllSectionsBundle(safeName(playerName));
		if (bundle == null)
		{
			JOptionPane.showMessageDialog(parent, "No user sections with goals to save.",
				"Saved plans", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		promptSavePlan(parent, store, bundle, codec.encode(bundle));
	}

	/** Copy a paste-ready share line carrying EVERY user section (one v2 code). */
	public static void copyAllSections(Component parent, GoalPlannerApiImpl api,
		ShareCodec codec, Supplier<String> playerName)
	{
		ShareBundle bundle = api.exportAllSectionsBundle(safeName(playerName));
		if (bundle == null)
		{
			JOptionPane.showMessageDialog(parent, "No user sections with goals to share.",
				"Share", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		String line = ShareText.invite(bundle, codec.encode(bundle));
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(line), null);
		int sections = bundle.effectiveSections().size();
		JOptionPane.showMessageDialog(parent,
			"Copied a share code for " + sections + " section" + (sections == 1 ? "" : "s")
				+ " to your clipboard.\n"
				+ "Paste it in Discord or chat - anyone with the plugin can import it.\n"
				+ "(Multi-section codes need a recent plugin version to import.)",
			"Share", JOptionPane.INFORMATION_MESSAGE);
	}

	/** Copy a paste-ready share line for a section to the clipboard. */
	public static void copySection(Component parent, GoalPlannerApiImpl api,
		ShareCodec codec, Supplier<String> playerName, String sectionId)
	{
		ShareBundle bundle = api.exportSectionBundle(sectionId, safeName(playerName));
		if (bundle == null || bundle.getGoals().isEmpty())
		{
			JOptionPane.showMessageDialog(parent, "That section has no goals to share.",
				"Share", JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		String line = ShareText.invite(bundle, codec.encode(bundle));
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(line), null);
		JOptionPane.showMessageDialog(parent,
			"Copied a share code for \"" + bundle.getSectionName() + "\" to your clipboard.\n"
				+ "Paste it in Discord or chat - anyone with the plugin can import it.",
			"Share", JOptionPane.INFORMATION_MESSAGE);
	}

	/** Copy a paste-ready share line for the given goals to the clipboard. */
	public static void copyGoals(Component parent, GoalPlannerApiImpl api, ShareCodec codec,
		Supplier<String> playerName, List<String> goalIds)
	{
		ShareBundle bundle = api.exportGoalsBundle(goalIds, safeName(playerName));
		if (bundle == null || bundle.totalGoalCount() == 0)
		{
			JOptionPane.showMessageDialog(parent, "Nothing to share.", "Share",
				JOptionPane.INFORMATION_MESSAGE);
			return;
		}
		String line = ShareText.invite(bundle, codec.encode(bundle));
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(line), null);
		int n = bundle.totalGoalCount();
		// A selection spanning sections exports per-section (v2 wire).
		String multiNote = bundle.needsV2()
			? "\n(The selection spans sections, which are preserved - importing"
				+ " needs a recent plugin version.)"
			: "";
		JOptionPane.showMessageDialog(parent,
			"Copied a share code for " + n + " goal" + (n == 1 ? "" : "s") + " to your clipboard.\n"
				+ "Paste it in Discord or chat - anyone with the plugin can import it."
				+ multiNote,
			"Share", JOptionPane.INFORMATION_MESSAGE);
	}

	private static String safeName(Supplier<String> playerName)
	{
		try
		{
			String n = playerName != null ? playerName.get() : null;
			return n != null && !n.isEmpty() ? n : "Someone";
		}
		catch (RuntimeException e)
		{
			return "Someone";
		}
	}
}
