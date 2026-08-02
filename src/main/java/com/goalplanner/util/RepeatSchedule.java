package com.goalplanner.util;

import com.goalplanner.ResetBoundary;
import com.goalplanner.model.RepeatPeriod;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Pure clock math for repeatable goals: which period a moment falls in, and
 * when the current period ends.
 *
 * <p>The model is a <em>period key</em> - a plain integer bucket for the
 * current period. A goal stores the key it last reset in; when the computed
 * key differs, it resets. Nothing subtracts timestamps, which is what makes
 * the hard cases fall out for free: five days offline is one key change rather
 * than five, and DST is {@code java.time}'s problem rather than ours.
 *
 * <p>{@link #nextBoundary} and {@link #periodKey} are deliberately derived
 * from the same {@link #boundaryDate}. The countdown shown in the panel and
 * the reset check MUST come from this one class - a countdown that reaches
 * zero while nothing resets is the worst failure this feature has.
 *
 * <p>No Swing, no RuneLite client, no {@code System.currentTimeMillis()} -
 * callers pass the instant in, so every case here is testable.
 */
public final class RepeatSchedule
{
	private RepeatSchedule() {}

	/**
	 * Epoch day 0 (1970-01-01) was a Thursday, so the first Wednesday is epoch
	 * day 6. Weekly periods are anchored to Wednesday to match the OSRS weekly
	 * reset rather than to the epoch's arbitrary Thursday.
	 */
	private static final long WEDNESDAY_EPOCH_DAY = 6;

	/**
	 * The date a moment belongs to, once the boundary hour is taken into
	 * account. With a 5am boundary, 03:00 on the 10th still belongs to the 9th.
	 */
	public static LocalDate boundaryDate(Instant now, ZoneId zone, int boundaryHour)
	{
		return now.atZone(zone).minusHours(clampHour(boundaryHour)).toLocalDate();
	}

	/**
	 * The integer bucket for {@code date} under {@code period}. Equal keys mean
	 * "same period, no reset due"; any difference means one is due.
	 *
	 * <p>Each period has its own rule - there is no shared divisor, because
	 * months are not a fixed number of days.
	 */
	public static long periodKey(RepeatPeriod period, LocalDate date)
	{
		if (period == null || date == null)
		{
			return 0L;
		}
		switch (period)
		{
			case DAILY:
				return date.toEpochDay();
			case WEEKLY:
				return Math.floorDiv(date.toEpochDay() - WEDNESDAY_EPOCH_DAY, 7);
			case MONTHLY:
				// The year term is load-bearing: a bare month value collides
				// every January.
				return date.getYear() * 12L + date.getMonthValue();
			case NONE:
			default:
				return 0L;
		}
	}

	/** The first date of the period containing {@code date}. */
	public static LocalDate periodStart(RepeatPeriod period, LocalDate date)
	{
		if (period == null || date == null)
		{
			return date;
		}
		switch (period)
		{
			case WEEKLY:
				return date.minusDays(
					Math.floorMod(date.toEpochDay() - WEDNESDAY_EPOCH_DAY, 7));
			case MONTHLY:
				return date.withDayOfMonth(1);
			case DAILY:
			case NONE:
			default:
				return date;
		}
	}

	/**
	 * The instant at which the period containing {@code now} ends and the key
	 * changes. Returns null for {@link RepeatPeriod#NONE}, which never rolls.
	 */
	public static Instant nextBoundary(RepeatPeriod period, Instant now, ZoneId zone,
		int boundaryHour)
	{
		if (period == null || !period.isRepeating() || now == null)
		{
			return null;
		}
		int hour = clampHour(boundaryHour);
		LocalDate start = periodStart(period, boundaryDate(now, zone, hour));
		LocalDate next;
		switch (period)
		{
			case WEEKLY:
				next = start.plusWeeks(1);
				break;
			case MONTHLY:
				next = start.plusMonths(1);
				break;
			case DAILY:
			default:
				next = start.plusDays(1);
				break;
		}
		// atZone resolves a DST gap forward, which is the behaviour we want:
		// on a spring-forward day the boundary lands at the first instant that
		// actually exists.
		return next.atTime(hour, 0).atZone(zone).toInstant();
	}

	/** The zone a boundary mode measures days in. */
	public static ZoneId zoneFor(ResetBoundary boundary)
	{
		return boundary == ResetBoundary.GAME_RESET
			? ZoneOffset.UTC
			: ZoneId.systemDefault();
	}

	/** The hour-of-day a boundary mode rolls over at; only CUSTOM_HOUR is not 0. */
	public static int hourFor(ResetBoundary boundary, int configuredHour)
	{
		return boundary == ResetBoundary.CUSTOM_HOUR ? clampHour(configuredHour) : 0;
	}

	/**
	 * Human countdown for the section header, e.g. {@code "3h 42m"} or
	 * {@code "2d 03h"}. Deliberately not {@code HH:MM} - a monthly goal can be
	 * 30 days out, and "03:42" reads as minutes and seconds to half of people.
	 *
	 * <p>ASCII only: this string is rendered by Swing, where symbol glyphs tofu
	 * on macOS Tahoe.
	 */
	public static String formatCountdown(long millisRemaining)
	{
		long secs = Math.max(0, millisRemaining) / 1000L;
		long days = secs / 86_400L;
		long hours = (secs % 86_400L) / 3600L;
		long mins = (secs % 3600L) / 60L;
		if (days > 0)
		{
			return days + "d " + String.format("%02dh", hours);
		}
		if (hours > 0)
		{
			return hours + "h " + String.format("%02dm", mins);
		}
		return mins + "m";
	}

	private static int clampHour(int hour)
	{
		return Math.max(0, Math.min(23, hour));
	}
}
