package com.goalplanner;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;

public class GoalPlannerPluginTest
{
	/**
	 * Companion hub plugins the dev client will also load IF their jar is on
	 * the classpath (drop it into dev-plugins/ - see build.gradle). Lets the
	 * cross-plugin link-ins (PluginMessage) be exercised end-to-end locally,
	 * e.g. against a patched Quest Helper before its upstream PR ships.
	 */
	private static final String[] COMPANION_PLUGINS = {
		"com.questhelper.QuestHelperPlugin",
		"inventorysetups.InventorySetupsPlugin",
		"com.loadoutlab.LoadoutLabPlugin",
	};

	public static void main(String[] args) throws Exception
	{
		List<Class<? extends Plugin>> plugins = new ArrayList<>();
		plugins.add(GoalPlannerPlugin.class);
		for (String className : COMPANION_PLUGINS)
		{
			try
			{
				@SuppressWarnings("unchecked")
				Class<? extends Plugin> plugin = (Class<? extends Plugin>) Class.forName(className);
				plugins.add(plugin);
				System.out.println("dev client: loading companion plugin " + className);
			}
			catch (ClassNotFoundException e)
			{
				// jar not in dev-plugins/ - run without it
			}
		}
		@SuppressWarnings("unchecked")
		Class<? extends Plugin>[] arr = plugins.toArray(new Class[0]);
		ExternalPluginManager.loadBuiltin(arr);
		RuneLite.main(args);
	}
}
