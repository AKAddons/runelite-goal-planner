package com.goalplanner.data;

import com.goalplanner.data.BossKillData.AccountReq;
import com.goalplanner.data.BossKillData.Alternative;
import com.goalplanner.data.BossKillData.BossPrereqs;
import com.goalplanner.data.BossKillData.BossReq;
import com.goalplanner.data.BossKillData.ItemReq;
import com.goalplanner.data.BossKillData.SkillReq;
import com.goalplanner.data.BossKillData.UnlockRef;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.Quest;
import net.runelite.api.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BossKillData}'s {@link BossPrereqs} shape and lookup.
 *
 * <p>Covers the widened schema (items, account metrics, boss-kill
 * prereqs, OR-alternatives) and preserves coverage for the original
 * skills/unlocks/quests fields so existing bosses (Zulrah, Thermy,
 * GWD trio) remain valid.
 */
class BossKillDataTest
{
	@Nested
	@DisplayName("BossPrereqs constructors")
	class ConstructorTests
	{
		@Test
		@DisplayName("skills-only constructor leaves other fields empty")
		void skillsOnly()
		{
			BossPrereqs p = new BossPrereqs(
				List.of(new SkillReq(Skill.SLAYER, 93)));
			assertEquals(1, p.skills.size());
			assertTrue(p.unlocks.isEmpty());
			assertTrue(p.quests.isEmpty());
			assertTrue(p.itemReqs.isEmpty());
			assertTrue(p.accountReqs.isEmpty());
			assertTrue(p.bossKills.isEmpty());
			assertTrue(p.alternatives.isEmpty());
		}

		@Test
		@DisplayName("skills+unlocks constructor preserves quests empty")
		void skillsAndUnlocks()
		{
			BossPrereqs p = new BossPrereqs(
				List.of(new SkillReq(Skill.RANGED, 70)),
				List.of(new UnlockRef("Mith Grapple", 0,
					List.of(new SkillReq(Skill.FLETCHING, 59)))));
			assertEquals(1, p.skills.size());
			assertEquals(1, p.unlocks.size());
			assertEquals("Mith Grapple", p.unlocks.get(0).name);
			assertTrue(p.quests.isEmpty());
		}

		@Test
		@DisplayName("quests-inclusive constructor preserves legacy 3-arg signature")
		void legacyThreeArg()
		{
			BossPrereqs p = new BossPrereqs(
				List.of(), List.of(), List.of(Quest.REGICIDE));
			assertEquals(1, p.quests.size());
			assertEquals(Quest.REGICIDE, p.quests.get(0));
		}

		@Test
		@DisplayName("full constructor populates every new field")
		void fullConstructor()
		{
			BossPrereqs p = new BossPrereqs(
				List.of(new SkillReq(Skill.ATTACK, 80)),
				List.of(new UnlockRef("Unlock A", 1, List.of())),
				List.of(Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE),
				List.of(new BossReq("Vardorvis", 1)),
				List.of(new ItemReq(42, "Awakener's orb", 1)),
				List.of(new AccountReq("COMBAT_LEVEL", 126)),
				List.of(new Alternative("Solo",
					List.of(new SkillReq(Skill.HITPOINTS, 99)),
					List.of())));
			assertEquals(1, p.skills.size());
			assertEquals(1, p.unlocks.size());
			assertEquals(1, p.quests.size());
			assertEquals(1, p.bossKills.size());
			assertEquals("Vardorvis", p.bossKills.get(0).bossName);
			assertEquals(1, p.itemReqs.size());
			assertEquals(42, p.itemReqs.get(0).itemId);
			assertEquals(1, p.accountReqs.size());
			assertEquals("COMBAT_LEVEL", p.accountReqs.get(0).metricName);
			assertEquals(1, p.alternatives.size());
			assertEquals("Solo", p.alternatives.get(0).label);
		}

		@Test
		@DisplayName("all list fields are unmodifiable")
		void listsUnmodifiable()
		{
			BossPrereqs p = new BossPrereqs(
				List.of(new SkillReq(Skill.ATTACK, 80)),
				List.of(new UnlockRef("U", 1, List.of())),
				List.of(Quest.COOKS_ASSISTANT),
				List.of(new BossReq("Zulrah", 1)),
				List.of(new ItemReq(1, "Item", 1)),
				List.of(new AccountReq("M", 1)),
				List.of(new Alternative("A", List.of(), List.of())));
			assertThrows(UnsupportedOperationException.class,
				() -> p.skills.add(new SkillReq(Skill.STRENGTH, 1)));
			assertThrows(UnsupportedOperationException.class,
				() -> p.unlocks.add(new UnlockRef("X", 0, List.of())));
			assertThrows(UnsupportedOperationException.class,
				() -> p.quests.add(Quest.COOKS_ASSISTANT));
			assertThrows(UnsupportedOperationException.class,
				() -> p.bossKills.add(new BossReq("X", 1)));
			assertThrows(UnsupportedOperationException.class,
				() -> p.itemReqs.add(new ItemReq(1, "X", 1)));
			assertThrows(UnsupportedOperationException.class,
				() -> p.accountReqs.add(new AccountReq("X", 1)));
			assertThrows(UnsupportedOperationException.class,
				() -> p.alternatives.add(new Alternative("X", List.of(), List.of())));
		}
	}

	@Nested
	@DisplayName("Alternative overloads")
	class AlternativeTests
	{
		@Test
		@DisplayName("two-arg constructor leaves bosses and quests empty")
		void twoArg()
		{
			Alternative a = new Alternative("99 Attack",
				List.of(new SkillReq(Skill.ATTACK, 99)),
				List.of());
			assertEquals("99 Attack", a.label);
			assertEquals(1, a.skills.size());
			assertTrue(a.accounts.isEmpty());
			assertTrue(a.bosses.isEmpty());
			assertTrue(a.quests.isEmpty());
		}

		@Test
		@DisplayName("four-arg constructor carries quests field")
		void fourArg()
		{
			Alternative a = new Alternative("Via quest",
				List.of(), List.of(),
				List.of(new BossReq("Zulrah", 1)),
				List.of(Quest.REGICIDE));
			assertEquals(1, a.bosses.size());
			assertEquals(1, a.quests.size());
			assertEquals(Quest.REGICIDE, a.quests.get(0));
		}
	}

	@Nested
	@DisplayName("getPrereqs lookup")
	class LookupTests
	{
		@Test
		@DisplayName("Zulrah has Regicide quest prereq")
		void zulrahRegicide()
		{
			BossPrereqs p = BossKillData.getPrereqs("Zulrah");
			assertNotNull(p);
			assertTrue(p.quests.contains(Quest.REGICIDE));
			assertTrue(p.skills.isEmpty());
		}

		@Test
		@DisplayName("Thermy requires 93 Slayer")
		void thermySlayer()
		{
			BossPrereqs p = BossKillData.getPrereqs("Thermy");
			assertNotNull(p);
			assertEquals(1, p.skills.size());
			assertEquals(Skill.SLAYER, p.skills.get(0).skill);
			assertEquals(93, p.skills.get(0).level);
		}

		@Test
		@DisplayName("Kree'arra has skill and unlock prereqs")
		void kreearraSkillAndUnlock()
		{
			BossPrereqs p = BossKillData.getPrereqs("Kree'arra");
			assertNotNull(p);
			assertEquals(1, p.skills.size());
			assertEquals(Skill.RANGED, p.skills.get(0).skill);
			assertEquals(1, p.unlocks.size());
			assertEquals("Mith Grapple", p.unlocks.get(0).name);
			assertEquals(2, p.unlocks.get(0).optionalSkills.size());
		}

		@Test
		@DisplayName("unknown boss returns null")
		void unknownNull()
		{
			assertNull(BossKillData.getPrereqs("Definitely Not A Boss"));
		}

		@Test
		@DisplayName("bosses with no encodable prereqs return null from getPrereqs")
		void noHardPrereqsReturnsNull()
		{
			// Wilderness bosses, low-barrier bosses, and newest Varlamore
			// content have no encodable hard prereqs and should remain
			// null rather than empty.
			assertNull(BossKillData.getPrereqs("Callisto"));
			assertNull(BossKillData.getPrereqs("Vet'ion"));
			assertNull(BossKillData.getPrereqs("King Black Dragon"));
			assertNull(BossKillData.getPrereqs("Giant Mole"));
			assertNull(BossKillData.getPrereqs("Scurrius"));
			assertNull(BossKillData.getPrereqs("TzTok-Jad"));
			assertNull(BossKillData.getPrereqs("CoX"));
		}
	}

	@Nested
	@DisplayName("Populated prereqs (smoke tests for S4 data)")
	class PopulatedPrereqsTests
	{
		@Test
		@DisplayName("Slayer bosses carry their slayer level")
		void slayerBosses()
		{
			assertEquals(85, BossKillData.getPrereqs("Abyssal Sire").skills.get(0).level);
			assertEquals(91, BossKillData.getPrereqs("Cerberus").skills.get(0).level);
			assertEquals(87, BossKillData.getPrereqs("Kraken").skills.get(0).level);
			assertEquals(75, BossKillData.getPrereqs("Grotesque Guardians").skills.get(0).level);
			assertEquals(95, BossKillData.getPrereqs("Alchemical Hydra").skills.get(0).level);
			assertEquals(92, BossKillData.getPrereqs("Araxxor").skills.get(0).level);
			for (String b : List.of("Abyssal Sire", "Cerberus", "Kraken",
				"Grotesque Guardians", "Alchemical Hydra", "Araxxor"))
			{
				assertEquals(Skill.SLAYER,
					BossKillData.getPrereqs(b).skills.get(0).skill,
					b + " should be slayer-gated");
			}
		}

		@Test
		@DisplayName("Nex requires 1 KC in each GWD room (Frozen Door)")
		void nexKeyPieces()
		{
			BossPrereqs p = BossKillData.getPrereqs("Nex");
			assertNotNull(p);
			assertEquals(4, p.bossKills.size());
			List<String> bossNames = p.bossKills.stream()
				.map(b -> b.bossName).collect(Collectors.toList());
			assertTrue(bossNames.contains("Kree'arra"));
			assertTrue(bossNames.contains("General Graardor"));
			assertTrue(bossNames.contains("Commander Zilyana"));
			assertTrue(bossNames.contains("K'ril Tsutsaroth"));
			p.bossKills.forEach(b -> assertEquals(1, b.killCount));
		}

		@Test
		@DisplayName("TzKal-Zuk requires a TzTok-Jad kill (Fight Caves)")
		void zukRequiresJad()
		{
			BossPrereqs p = BossKillData.getPrereqs("TzKal-Zuk");
			assertNotNull(p);
			assertEquals(1, p.bossKills.size());
			assertEquals("TzTok-Jad", p.bossKills.get(0).bossName);
			assertEquals(1, p.bossKills.get(0).killCount);
			assertTrue(p.itemReqs.isEmpty(),
				"Zuk should gate on Jad kill, not a Fire cape item");
		}

		@Test
		@DisplayName("The Fractured Archive is a known raid with its completion varp, pet and While Guthix Sleeps")
		void fracturedArchive()
		{
			// Released 2026-10-20; RuneLite 1.13.3 gameval
			// VarPlayerID.TOTAL_COMPLETED_THEFRACTUREDARCHIVE = 5894, pet Orion 34646.
			String raid = "The Fractured Archive";
			assertTrue(BossKillData.isKnownBoss(raid));
			assertEquals(5894, BossKillData.getVarpId(raid));
			assertEquals(34646, BossKillData.getPetItemId(raid));
			BossPrereqs p = BossKillData.getPrereqs(raid);
			assertNotNull(p);
			assertEquals(List.of(Quest.WHILE_GUTHIX_SLEEPS), p.quests);
			assertTrue(p.skills.isEmpty() && p.bossKills.isEmpty() && p.itemReqs.isEmpty());
			// Collection log name equals the boss name: no alias row needed.
			assertEquals(List.of(raid), BossKillData.resolveCollectionLogName(raid));
		}

		@Test
		@DisplayName("Amoxliatl requires The Heart of Darkness")
		void amoxliatlQuest()
		{
			BossPrereqs p = BossKillData.getPrereqs("Amoxliatl");
			assertNotNull(p);
			assertTrue(p.quests.contains(Quest.THE_HEART_OF_DARKNESS));
		}

		@Test
		@DisplayName("Yama requires A Kingdom Divided")
		void yamaQuest()
		{
			BossPrereqs p = BossKillData.getPrereqs("Yama");
			assertNotNull(p);
			assertTrue(p.quests.contains(Quest.A_KINGDOM_DIVIDED));
		}

		@Test
		@DisplayName("DT2 awakened variants require base kill AND Awakener's orb")
		void dt2AwakenedPrereqs()
		{
			String[] awakened = {
				"Duke (Awake)", "Leviathan (Awake)",
				"Whisperer (Awake)", "Vardorvis (Awake)"
			};
			String[] baseKillRequired = {
				"Duke Sucellus", "The Leviathan",
				"The Whisperer", "Vardorvis"
			};
			for (int i = 0; i < awakened.length; i++)
			{
				BossPrereqs p = BossKillData.getPrereqs(awakened[i]);
				assertNotNull(p, awakened[i]);
				assertEquals(1, p.bossKills.size(), awakened[i]);
				assertEquals(baseKillRequired[i], p.bossKills.get(0).bossName);
				assertEquals(1, p.itemReqs.size(), awakened[i]);
				assertEquals("Awakener's orb", p.itemReqs.get(0).displayName);
				assertTrue(p.quests.contains(
					Quest.DESERT_TREASURE_II__THE_FALLEN_EMPIRE));
			}
		}

		@Test
		@DisplayName("awakened variants' boss-kill prereqs reference real bosses")
		void awakenedBossRefsResolve()
		{
			for (String awake : List.of("Duke (Awake)", "Leviathan (Awake)",
				"Whisperer (Awake)", "Vardorvis (Awake)"))
			{
				BossPrereqs p = BossKillData.getPrereqs(awake);
				for (BossReq br : p.bossKills)
				{
					assertTrue(BossKillData.isKnownBoss(br.bossName),
						awake + " references unknown boss " + br.bossName);
				}
			}
		}

		@Test
		@DisplayName("Item-gated bosses carry their entry ticket")
		void itemGatedBosses()
		{
			assertEquals("Dark totem",
				BossKillData.getPrereqs("Skotizo").itemReqs.get(0).displayName);
			assertEquals("Giant key",
				BossKillData.getPrereqs("Obor").itemReqs.get(0).displayName);
			assertEquals("Bryophyta's essence",
				BossKillData.getPrereqs("Bryophyta").itemReqs.get(0).displayName);
			assertEquals("Brittle key",
				BossKillData.getPrereqs("Grotesque Guardians").itemReqs.get(0).displayName);
		}

		@Test
		@DisplayName("Dagannoth Kings have no hard prereq (multiple access paths)")
		void dksHaveNoHardPrereq()
		{
			// Waterbirth Island is reachable via fairy ring (AJR),
			// Lunar teleport, OR Jarvald's ferry (Fremennik Trials).
			// No single hard gate — getPrereqs returns null.
			for (String dk : List.of("Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme"))
			{
				assertNull(BossKillData.getPrereqs(dk),
					dk + " should have no declared prereq");
			}
		}

		@Test
		@DisplayName("Zalcano requires 70/70/70 + Song of the Elves")
		void zalcanoCompositePrereqs()
		{
			BossPrereqs p = BossKillData.getPrereqs("Zalcano");
			assertNotNull(p);
			assertEquals(3, p.skills.size());
			assertTrue(p.quests.contains(Quest.SONG_OF_THE_ELVES));
		}

		@Test
		@DisplayName("ToB HM requires a completed standard ToB")
		void tobHardModeBossPrereq()
		{
			BossPrereqs p = BossKillData.getPrereqs("ToB (HM)");
			assertNotNull(p);
			assertEquals(1, p.bossKills.size());
			assertEquals("ToB", p.bossKills.get(0).bossName);
			assertTrue(p.quests.contains(Quest.A_TASTE_OF_HOPE));
		}

		@Test
		@DisplayName("Phosani's Nightmare requires base Nightmare kill")
		void phosaniRequiresNightmare()
		{
			BossPrereqs p = BossKillData.getPrereqs("Phosani's Nightmare");
			assertNotNull(p);
			assertEquals(1, p.bossKills.size());
			assertEquals("The Nightmare", p.bossKills.get(0).bossName);
		}

		@Test
		@DisplayName("every BossReq prereq in the table resolves to a known boss")
		void allBossRefsResolve()
		{
			for (String boss : BossKillData.getBossNames())
			{
				BossPrereqs p = BossKillData.getPrereqs(boss);
				if (p == null) continue;
				for (BossReq br : p.bossKills)
				{
					assertTrue(BossKillData.isKnownBoss(br.bossName),
						boss + " has unknown bossKill prereq: " + br.bossName);
				}
			}
		}
	}

	@Nested
	@DisplayName("Missing-boss expansion")
	class MissingBossExpansionTests
	{
		@Test
		@DisplayName("The Gauntlet and Corrupted Gauntlet require Song of the Elves")
		void gauntletRequiresSotE()
		{
			BossPrereqs gauntlet = BossKillData.getPrereqs("The Gauntlet");
			assertNotNull(gauntlet);
			assertTrue(gauntlet.quests.contains(Quest.SONG_OF_THE_ELVES));

			BossPrereqs corrupted = BossKillData.getPrereqs("Corrupted Gauntlet");
			assertNotNull(corrupted);
			assertTrue(corrupted.quests.contains(Quest.SONG_OF_THE_ELVES));
			assertEquals(1, corrupted.bossKills.size(),
				"Corrupted Gauntlet should require a standard Gauntlet kill");
			assertEquals("The Gauntlet", corrupted.bossKills.get(0).bossName);
		}

		@Test
		@DisplayName("Shellbane Gryphon requires Troubled Tortugans")
		void gryphonQuest()
		{
			BossPrereqs p = BossKillData.getPrereqs("Shellbane Gryphon");
			assertNotNull(p);
			assertTrue(p.quests.contains(Quest.TROUBLED_TORTUGANS));
		}

		@Test
		@DisplayName("Fortis Colosseum (Waves) requires Children of the Sun")
		void fortisWavesQuest()
		{
			BossPrereqs p = BossKillData.getPrereqs("Fortis Colosseum (Waves)");
			assertNotNull(p);
			assertTrue(p.quests.contains(Quest.CHILDREN_OF_THE_SUN));
		}

		@Test
		@DisplayName("all three Moons + chest aggregate require Perilous Moons quest")
		void moonsQuest()
		{
			for (String moon : List.of("Blue Moon", "Blood Moon",
				"Eclipse Moon", "Perilous Moons Chests"))
			{
				BossPrereqs p = BossKillData.getPrereqs(moon);
				assertNotNull(p, moon);
				assertTrue(p.quests.contains(Quest.PERILOUS_MOONS),
					moon + " should require PERILOUS_MOONS");
			}
		}

		@Test
		@DisplayName("Demonic Brutus requires a standard Brutus kill AND Defender of Varrock")
		void demonicBrutusChain()
		{
			BossPrereqs brutus = BossKillData.getPrereqs("Brutus");
			assertNotNull(brutus);
			assertTrue(brutus.quests.contains(Quest.DEFENDER_OF_VARROCK));

			BossPrereqs demonic = BossKillData.getPrereqs("Demonic Brutus");
			assertNotNull(demonic);
			assertTrue(demonic.quests.contains(Quest.DEFENDER_OF_VARROCK));
			assertEquals(1, demonic.bossKills.size());
			assertEquals("Brutus", demonic.bossKills.get(0).bossName);
		}

		@Test
		@DisplayName("all nine DoM levels are registered with per-level varps")
		void allDoMLevelsRegistered()
		{
			for (String level : List.of(
				"Doom of Mokhaiotl (L1)", "Doom of Mokhaiotl (L2)",
				"Doom of Mokhaiotl (L3)", "Doom of Mokhaiotl (L4)",
				"Doom of Mokhaiotl (L5)", "Doom of Mokhaiotl (L6)",
				"Doom of Mokhaiotl (L7)", "Doom of Mokhaiotl (L8)",
				"Doom of Mokhaiotl (L8+)"))
			{
				assertTrue(BossKillData.isKnownBoss(level),
					level + " should be registered in BOSSES");
				assertTrue(BossKillData.getVarpId(level) > 0,
					level + " should have a valid varp id");
			}
		}

		@Test
		@DisplayName("Barrows is known but has no hard prereq")
		void barrowsNoPrereq()
		{
			assertTrue(BossKillData.isKnownBoss("Barrows"));
			assertNull(BossKillData.getPrereqs("Barrows"));
		}

		@Test
		@DisplayName("collection-log aliases resolve every variant to a known boss")
		void colLogAliasesResolve()
		{
			for (String aliased : List.of("Perilous Moons", "Fortis Colosseum",
				"The Gauntlet", "Brutus", "Doom of Mokhaiotl"))
			{
				List<String> variants = BossKillData.resolveCollectionLogName(aliased);
				assertFalse(variants.isEmpty(), aliased + " should resolve to variants");
				for (String v : variants)
				{
					assertTrue(BossKillData.isKnownBoss(v),
						aliased + " points to unknown boss " + v);
				}
			}
		}
	}
}
