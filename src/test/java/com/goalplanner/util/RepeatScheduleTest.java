package com.goalplanner.util;

import com.goalplanner.ResetBoundary;
import com.goalplanner.model.RepeatPeriod;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure clock math for repeatable goals. No Swing, no client - every case here
 * is a date or an instant in, an integer or an instant out.
 */
class RepeatScheduleTest
{
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private static long dayKey(LocalDate d)
	{
		return RepeatSchedule.periodKey(RepeatPeriod.DAILY, d);
	}

	private static long weekKey(LocalDate d)
	{
		return RepeatSchedule.periodKey(RepeatPeriod.WEEKLY, d);
	}

	private static long monthKey(LocalDate d)
	{
		return RepeatSchedule.periodKey(RepeatPeriod.MONTHLY, d);
	}

	@Nested
	@DisplayName("daily period key")
	class Daily
	{
		@Test
		@DisplayName("consecutive days advance the key by exactly one")
		void consecutiveDays()
		{
			assertEquals(dayKey(LocalDate.of(2026, 7, 29)) + 1,
				dayKey(LocalDate.of(2026, 7, 30)));
		}

		@Test
		@DisplayName("the same date is the same period")
		void sameDay()
		{
			assertEquals(dayKey(LocalDate.of(2026, 7, 29)),
				dayKey(LocalDate.of(2026, 7, 29)));
		}

		@Test
		@DisplayName("five days offline is a five-key jump, not five separate rollovers")
		void fiveDaysOffline()
		{
			assertEquals(dayKey(LocalDate.of(2026, 7, 24)) + 5,
				dayKey(LocalDate.of(2026, 7, 29)));
		}
	}

	@Nested
	@DisplayName("weekly period key")
	class Weekly
	{
		@Test
		@DisplayName("the week rolls over on Wednesday, matching OSRS")
		void rollsOverOnWednesday()
		{
			LocalDate tuesday = LocalDate.of(2026, 7, 28);
			LocalDate wednesday = LocalDate.of(2026, 7, 29);
			LocalDate thursday = LocalDate.of(2026, 7, 30);
			// Pin the calendar assumption this test rests on.
			assertEquals(DayOfWeek.TUESDAY, tuesday.getDayOfWeek());
			assertEquals(DayOfWeek.WEDNESDAY, wednesday.getDayOfWeek());

			assertEquals(weekKey(tuesday) + 1, weekKey(wednesday),
				"Tuesday to Wednesday must cross a week boundary");
			assertEquals(weekKey(wednesday), weekKey(thursday),
				"Wednesday to Thursday must stay in the same week");
		}

		@Test
		@DisplayName("seven days on advances exactly one week")
		void sevenDays()
		{
			LocalDate d = LocalDate.of(2026, 7, 29);
			assertEquals(weekKey(d) + 1, weekKey(d.plusDays(7)));
		}

		@Test
		@DisplayName("keys before the 1970 epoch still advance monotonically")
		void beforeEpoch()
		{
			LocalDate d = LocalDate.of(1969, 5, 14);
			assertEquals(weekKey(d) + 1, weekKey(d.plusDays(7)));
		}
	}

	@Nested
	@DisplayName("monthly period key")
	class Monthly
	{
		@Test
		@DisplayName("a 31-day month advances the key by exactly one")
		void longMonth()
		{
			assertEquals(monthKey(LocalDate.of(2026, 1, 1)) + 1,
				monthKey(LocalDate.of(2026, 2, 1)));
		}

		@Test
		@DisplayName("a 30-day month advances the key by exactly one")
		void thirtyDayMonth()
		{
			assertEquals(monthKey(LocalDate.of(2026, 4, 30)) + 1,
				monthKey(LocalDate.of(2026, 5, 1)));
		}

		@Test
		@DisplayName("February in a leap year advances the key by exactly one")
		void leapFebruary()
		{
			assertEquals(LocalDate.of(2028, 2, 29).getDayOfMonth(), 29, "2028 is a leap year");
			assertEquals(monthKey(LocalDate.of(2028, 2, 29)) + 1,
				monthKey(LocalDate.of(2028, 3, 1)));
		}

		@Test
		@DisplayName("December to January advances by one across the year boundary")
		void yearRollover()
		{
			assertEquals(monthKey(LocalDate.of(2026, 12, 31)) + 1,
				monthKey(LocalDate.of(2027, 1, 1)));
		}

		@Test
		@DisplayName("the same month in different years is NOT the same period")
		void januarysDoNotCollide()
		{
			// Regression guard: a bare getMonthValue() would make these equal.
			assertNotEquals(monthKey(LocalDate.of(2026, 1, 15)),
				monthKey(LocalDate.of(2027, 1, 15)));
		}

		@Test
		@DisplayName("every day within a month shares one key")
		void withinMonth()
		{
			assertEquals(monthKey(LocalDate.of(2026, 7, 1)),
				monthKey(LocalDate.of(2026, 7, 31)));
		}
	}

	@Nested
	@DisplayName("boundary date")
	class BoundaryDate
	{
		@Test
		@DisplayName("before the custom hour, the moment still belongs to the previous day")
		void beforeCustomHour()
		{
			Instant at3am = LocalDate.of(2026, 7, 29).atTime(3, 0).atZone(NEW_YORK).toInstant();
			assertEquals(LocalDate.of(2026, 7, 28),
				RepeatSchedule.boundaryDate(at3am, NEW_YORK, 5));
		}

		@Test
		@DisplayName("after the custom hour, the moment belongs to the current day")
		void afterCustomHour()
		{
			Instant at6am = LocalDate.of(2026, 7, 29).atTime(6, 0).atZone(NEW_YORK).toInstant();
			assertEquals(LocalDate.of(2026, 7, 29),
				RepeatSchedule.boundaryDate(at6am, NEW_YORK, 5));
		}

		@Test
		@DisplayName("UTC and a western zone disagree about the date late in the evening")
		void zoneMatters()
		{
			// 22:00 New York on the 29th is already the 30th in UTC.
			Instant evening = LocalDate.of(2026, 7, 29).atTime(22, 0).atZone(NEW_YORK).toInstant();
			assertEquals(LocalDate.of(2026, 7, 29),
				RepeatSchedule.boundaryDate(evening, NEW_YORK, 0));
			assertEquals(LocalDate.of(2026, 7, 30),
				RepeatSchedule.boundaryDate(evening, ZoneOffset.UTC, 0));
		}
	}

	@Nested
	@DisplayName("next boundary")
	class NextBoundary
	{
		@Test
		@DisplayName("NONE never rolls over")
		void noneIsNull()
		{
			assertNull(RepeatSchedule.nextBoundary(
				RepeatPeriod.NONE, Instant.now(), ZoneOffset.UTC, 0));
		}

		@Test
		@DisplayName("daily lands on the next midnight UTC")
		void dailyBoundary()
		{
			Instant now = LocalDate.of(2026, 7, 29).atTime(13, 0).atZone(ZoneOffset.UTC).toInstant();
			Instant expected = LocalDate.of(2026, 7, 30).atStartOfDay(ZoneOffset.UTC).toInstant();
			assertEquals(expected,
				RepeatSchedule.nextBoundary(RepeatPeriod.DAILY, now, ZoneOffset.UTC, 0));
		}

		@Test
		@DisplayName("weekly lands on the next Wednesday")
		void weeklyBoundary()
		{
			// Thursday the 30th - the next Wednesday is 5 August.
			Instant now = LocalDate.of(2026, 7, 30).atTime(13, 0).atZone(ZoneOffset.UTC).toInstant();
			Instant boundary =
				RepeatSchedule.nextBoundary(RepeatPeriod.WEEKLY, now, ZoneOffset.UTC, 0);
			LocalDate landed = boundary.atZone(ZoneOffset.UTC).toLocalDate();
			assertEquals(DayOfWeek.WEDNESDAY, landed.getDayOfWeek());
			assertEquals(LocalDate.of(2026, 8, 5), landed);
		}

		@Test
		@DisplayName("monthly lands on the 1st of the next month, even from the 31st")
		void monthlyBoundary()
		{
			Instant now = LocalDate.of(2026, 7, 31).atTime(23, 0).atZone(ZoneOffset.UTC).toInstant();
			Instant expected = LocalDate.of(2026, 8, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
			assertEquals(expected,
				RepeatSchedule.nextBoundary(RepeatPeriod.MONTHLY, now, ZoneOffset.UTC, 0));
		}

		@Test
		@DisplayName("monthly can fall sooner than weekly, so deadline order is not fixed")
		void monthlyCanBeatWeekly()
		{
			// 31 July 2026 is a Friday: the month rolls tomorrow, the week waits for Wednesday.
			LocalDate d = LocalDate.of(2026, 7, 31);
			assertEquals(DayOfWeek.FRIDAY, d.getDayOfWeek());
			Instant now = d.atTime(12, 0).atZone(ZoneOffset.UTC).toInstant();

			Instant monthly = RepeatSchedule.nextBoundary(RepeatPeriod.MONTHLY, now, ZoneOffset.UTC, 0);
			Instant weekly = RepeatSchedule.nextBoundary(RepeatPeriod.WEEKLY, now, ZoneOffset.UTC, 0);

			assertTrue(monthly.isBefore(weekly),
				"monthly must be able to precede weekly - the UI cannot order groups by enum");
		}

		@Test
		@DisplayName("a spring-forward DST day still advances exactly one day")
		void dstSpringForward()
		{
			// 8 March 2026, 02:00 does not exist in New York.
			Instant now = LocalDate.of(2026, 3, 7).atTime(12, 0).atZone(NEW_YORK).toInstant();
			Instant boundary =
				RepeatSchedule.nextBoundary(RepeatPeriod.DAILY, now, NEW_YORK, 0);
			assertEquals(LocalDate.of(2026, 3, 8),
				boundary.atZone(NEW_YORK).toLocalDate());
		}
	}

	@Nested
	@DisplayName("countdown and key stay consistent")
	class CountdownInvariant
	{
		/**
		 * The invariant the whole feature rests on: the moment the countdown
		 * reaches zero is exactly the moment the period key changes. If these
		 * ever drift, the panel shows "resets in 0m" while nothing resets.
		 */
		@Test
		@DisplayName("the key is unchanged just before the boundary and changed at it")
		void keyFlipsExactlyAtBoundary()
		{
			for (RepeatPeriod period : new RepeatPeriod[]{
				RepeatPeriod.DAILY, RepeatPeriod.WEEKLY, RepeatPeriod.MONTHLY})
			{
				for (int hour : new int[]{0, 5, 23})
				{
					Instant now = LocalDate.of(2026, 7, 29)
						.atTime(13, 0).atZone(NEW_YORK).toInstant();
					Instant boundary =
						RepeatSchedule.nextBoundary(period, now, NEW_YORK, hour);

					long keyNow = RepeatSchedule.periodKey(period,
						RepeatSchedule.boundaryDate(now, NEW_YORK, hour));
					long keyJustBefore = RepeatSchedule.periodKey(period,
						RepeatSchedule.boundaryDate(boundary.minusMillis(1), NEW_YORK, hour));
					long keyAt = RepeatSchedule.periodKey(period,
						RepeatSchedule.boundaryDate(boundary, NEW_YORK, hour));

					assertEquals(keyNow, keyJustBefore,
						period + " at hour " + hour + ": key must hold until the boundary");
					assertNotEquals(keyNow, keyAt,
						period + " at hour " + hour + ": key must change at the boundary");
				}
			}
		}
	}

	@Nested
	@DisplayName("changing the boundary")
	class BoundaryChange
	{
		/**
		 * Two consequences of one fact. Useful: changing the reset config is
		 * how you force a rollover on demand instead of waiting for midnight.
		 * Awkward: it means a user who changes the setting mid-period gets one
		 * unearned reset. Both follow from the key depending on the hour.
		 */
		@Test
		@DisplayName("the same instant falls in different periods under different boundary hours")
		void boundaryHourShiftsTheKey()
		{
			Instant t = LocalDate.of(2026, 7, 27).atTime(12, 0).atZone(ZoneOffset.UTC).toInstant();

			long atMidnight = RepeatSchedule.periodKey(RepeatPeriod.DAILY,
				RepeatSchedule.boundaryDate(t, ZoneOffset.UTC, 0));
			long atSix = RepeatSchedule.periodKey(RepeatPeriod.DAILY,
				RepeatSchedule.boundaryDate(t, ZoneOffset.UTC, 18));

			assertNotEquals(atMidnight, atSix,
				"noon belongs to the 27th under a midnight boundary but to the 26th under an 18:00 one");
		}
	}

	@Nested
	@DisplayName("boundary mode mapping")
	class BoundaryMode
	{
		@Test
		@DisplayName("game reset measures days in UTC at hour zero")
		void gameReset()
		{
			assertEquals(ZoneOffset.UTC, RepeatSchedule.zoneFor(ResetBoundary.GAME_RESET));
			assertEquals(0, RepeatSchedule.hourFor(ResetBoundary.GAME_RESET, 9));
		}

		@Test
		@DisplayName("local midnight ignores the configured hour")
		void localMidnight()
		{
			assertEquals(0, RepeatSchedule.hourFor(ResetBoundary.LOCAL_MIDNIGHT, 9));
		}

		@Test
		@DisplayName("custom hour uses the configured hour, clamped to 0-23")
		void customHour()
		{
			assertEquals(9, RepeatSchedule.hourFor(ResetBoundary.CUSTOM_HOUR, 9));
			assertEquals(23, RepeatSchedule.hourFor(ResetBoundary.CUSTOM_HOUR, 99));
			assertEquals(0, RepeatSchedule.hourFor(ResetBoundary.CUSTOM_HOUR, -3));
		}
	}

	@Nested
	@DisplayName("countdown formatting")
	class Countdown
	{
		@Test
		@DisplayName("under an hour shows minutes only")
		void minutesOnly()
		{
			assertEquals("42m", RepeatSchedule.formatCountdown(42 * 60_000L));
		}

		@Test
		@DisplayName("under a day shows hours and minutes")
		void hoursAndMinutes()
		{
			assertEquals("3h 42m", RepeatSchedule.formatCountdown((3 * 3600 + 42 * 60) * 1000L));
		}

		@Test
		@DisplayName("over a day shows days and hours, so monthly reads sensibly")
		void daysAndHours()
		{
			assertEquals("29d 03h",
				RepeatSchedule.formatCountdown((29 * 86_400L + 3 * 3600L) * 1000L));
		}

		@Test
		@DisplayName("zero and negative clamp to 0m rather than going negative")
		void nonNegative()
		{
			assertEquals("0m", RepeatSchedule.formatCountdown(0));
			assertEquals("0m", RepeatSchedule.formatCountdown(-5000));
		}

		@Test
		@DisplayName("output is ASCII only - Swing tofus symbol glyphs on macOS")
		void asciiOnly()
		{
			String s = RepeatSchedule.formatCountdown((2 * 86_400L + 5 * 3600L) * 1000L);
			assertTrue(s.chars().allMatch(c -> c < 128), "non-ASCII in countdown: " + s);
		}
	}

	@Nested
	@DisplayName("deadline stamp")
	class Deadline
	{
		private Instant boundary(RepeatPeriod period, java.time.LocalDate from)
		{
			return RepeatSchedule.nextBoundary(period,
				from.atTime(12, 0).atZone(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC, 0);
		}

		@Test
		@DisplayName("a daily shows only a clock time")
		void daily()
		{
			assertEquals("00:00", RepeatSchedule.formatDeadline(
				boundary(RepeatPeriod.DAILY, LocalDate.of(2026, 7, 29)),
				ZoneOffset.UTC, RepeatPeriod.DAILY));
		}

		@Test
		@DisplayName("a weekly names the day it is due")
		void weekly()
		{
			assertEquals("Wed 00:00", RepeatSchedule.formatDeadline(
				boundary(RepeatPeriod.WEEKLY, LocalDate.of(2026, 7, 30)),
				ZoneOffset.UTC, RepeatPeriod.WEEKLY));
		}

		@Test
		@DisplayName("a monthly gives the date, since a weekday would be useless a month out")
		void monthly()
		{
			assertEquals("1 Aug", RepeatSchedule.formatDeadline(
				boundary(RepeatPeriod.MONTHLY, LocalDate.of(2026, 7, 31)),
				ZoneOffset.UTC, RepeatPeriod.MONTHLY));
		}

		@Test
		@DisplayName("a custom boundary hour is reflected, not assumed to be midnight")
		void customHour()
		{
			Instant now = LocalDate.of(2026, 7, 29).atTime(12, 0).atZone(ZoneOffset.UTC).toInstant();
			Instant b = RepeatSchedule.nextBoundary(RepeatPeriod.DAILY, now, ZoneOffset.UTC, 18);
			assertEquals("18:00",
				RepeatSchedule.formatDeadline(b, ZoneOffset.UTC, RepeatPeriod.DAILY));
		}

		@Test
		@DisplayName("a non-repeating goal has no deadline")
		void none()
		{
			assertEquals("", RepeatSchedule.formatDeadline(
				Instant.now(), ZoneOffset.UTC, RepeatPeriod.NONE));
			assertEquals("", RepeatSchedule.formatDeadline(null, ZoneOffset.UTC, RepeatPeriod.DAILY));
		}

		@Test
		@DisplayName("output is ASCII only")
		void ascii()
		{
			for (RepeatPeriod p : new RepeatPeriod[]{
				RepeatPeriod.DAILY, RepeatPeriod.WEEKLY, RepeatPeriod.MONTHLY})
			{
				String out = RepeatSchedule.formatDeadline(
					boundary(p, LocalDate.of(2026, 7, 29)), ZoneOffset.UTC, p);
				assertTrue(out.chars().allMatch(c -> c < 128), "non-ASCII: " + out);
			}
		}
	}
}
