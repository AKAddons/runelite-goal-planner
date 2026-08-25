package com.goalplanner.data;

import com.goalplanner.model.TagCategory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Known tag labels per category for the Add Tag dialog.
 * Sourced from the OSRS Collection Log.
 */
public final class TagOptions
{
	private TagOptions() {}

	private static final Map<TagCategory, String[]> OPTIONS = new EnumMap<>(TagCategory.class);

	static
	{
		OPTIONS.put(TagCategory.BOSS, new String[]{
			"Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor",
			"Barrows Chests", "Brutus", "Bryophyta", "Callisto and Artio",
			"Cerberus", "Chaos Elemental", "Chaos Fanatic", "Commander Zilyana",
			"Corporeal Beast", "Crazy archaeologist", "Dagannoth Kings",
			"Deranged Archaeologist", "Doom of Mokhaiotl", "Duke Sucellus",
			"Fortis Colosseum", "General Graardor", "Giant Mole",
			"Grotesque Guardians", "Hespori", "K'ril Tsutsaroth",
			"Kalphite Queen", "King Black Dragon", "Kraken", "Kree'arra",
			"Moons of Peril", "Nex", "Obor", "Phantom Muspah", "Royal Titans",
			"Sarachnis", "Scorpia", "Scurrius", "Shellbane Gryphon", "Skotizo",
			"The Fight Caves", "The Gauntlet", "The Hueycoatl",
			"The Inferno", "The Leviathan", "The Nightmare", "The Whisperer",
			"Thermonuclear smoke devil", "Vardorvis", "Venenatis and Spindel",
			"Vet'ion and Calvar'ion", "Vorkath", "Yama",
			"Zalcano", "Zulrah"
		});

		OPTIONS.put(TagCategory.RAID, new String[]{
			"Chambers of Xeric", "Theatre of Blood", "Tombs of Amascut"
		});

		OPTIONS.put(TagCategory.CLUE, new String[]{
			"Beginner Treasure Trails", "Easy Treasure Trails",
			"Medium Treasure Trails", "Hard Treasure Trails",
			"Elite Treasure Trails", "Master Treasure Trails"
		});

		OPTIONS.put(TagCategory.MINIGAME, new String[]{
			"Barbarian Assault", "Barracuda Trials", "Brimhaven Agility Arena",
			"Castle Wars", "Fishing Trawler", "Giants' Foundry",
			"Gnome Restaurant", "Guardians of the Rift", "Hallowed Sepulchre",
			"Last Man Standing", "Magic Training Arena", "Mahogany Homes",
			"Mastering Mixology", "Pest Control", "Rogues' Den",
			"Shades of Mort'ton", "Soul Wars", "Temple Trekking",
			"Tempoross", "Tithe Farm", "Trouble Brewing", "Vale Totems",
			"Volcanic Mine", "Wintertodt"
		});

		OPTIONS.put(TagCategory.SKILLING, new String[]{
			"Attack", "Strength", "Defence", "Ranged", "Prayer", "Magic",
			"Runecraft", "Hitpoints", "Crafting", "Mining", "Smithing",
			"Fishing", "Cooking", "Firemaking", "Woodcutting", "Agility",
			"Herblore", "Thieving", "Fletching", "Slayer", "Farming",
			"Construction", "Hunter", "Sailing"
		});
		// SPECIAL is dev-only, not in OPTIONS
		OPTIONS.put(TagCategory.OTHER, new String[]{
			"Quest Reward", "Achievement Diary", "Misc"
		});
	}

	/**
	 * Get known labels for a category. Empty array for freeform categories.
	 */
	public static String[] getOptions(TagCategory category)
	{
		return OPTIONS.getOrDefault(category, new String[]{});
	}

}
