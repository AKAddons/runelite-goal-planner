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









}
