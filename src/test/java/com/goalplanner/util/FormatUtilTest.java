package com.goalplanner.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FormatUtilTest
{
	@Test
	public void testFormatNumberSmall()
	{
		assertEquals("0", FormatUtil.formatNumber(0));
		assertEquals("999", FormatUtil.formatNumber(999));
		assertEquals("1", FormatUtil.formatNumber(1));
	}

	@Test
	public void testFormatNumberThousands()
	{
		assertEquals("1K", FormatUtil.formatNumber(1_000));
		assertEquals("50K", FormatUtil.formatNumber(50_000));
		assertEquals("1000K", FormatUtil.formatNumber(999_999));
		assertEquals("500K", FormatUtil.formatNumber(500_000));
	}

	@Test
	public void testFormatNumberMillions()
	{
		assertEquals("1.0M", FormatUtil.formatNumber(1_000_000));
		assertEquals("13.0M", FormatUtil.formatNumber(13_034_431));
		assertEquals("200.0M", FormatUtil.formatNumber(200_000_000));
	}

	@Test
	public void testFormatXp()
	{
		assertEquals("0", FormatUtil.formatXp(0));
		assertEquals("1,234", FormatUtil.formatXp(1_234));
		assertEquals("13,034,431", FormatUtil.formatXp(13_034_431));
	}

	@Test
	public void testTruncate()
	{
		assertEquals("Hello", FormatUtil.truncate("Hello", 10));
		assertEquals("Hello W...", FormatUtil.truncate("Hello World", 10));
		assertEquals("", FormatUtil.truncate(null, 10));
		assertEquals("A", FormatUtil.truncate("A", 1));
	}

	@Test
	public void testFormatProgressPercentFloorsInsteadOfRounding()
	{
		// 126/127 Miscellania favour = 99.2% - used to round to a misleading "100%".
		assertEquals("99%", FormatUtil.formatProgressPercent(126, 127));
		// 99.6% floors to 99, not 100.
		assertEquals("99%", FormatUtil.formatProgressPercent(996, 1000));
		assertEquals("0%", FormatUtil.formatProgressPercent(9, 1000));
		assertEquals("50%", FormatUtil.formatProgressPercent(1, 2));
	}

	@Test
	public void testFormatProgressPercentNeverClaimsCompletion()
	{
		// Callers only render this for goals that are NOT complete.
		assertEquals("99%", FormatUtil.formatProgressPercent(127, 127));
		assertEquals("99%", FormatUtil.formatProgressPercent(500, 100));
	}

	@Test
	public void testFormatProgressPercentEdgeCases()
	{
		assertEquals("0%", FormatUtil.formatProgressPercent(50, 0));
		assertEquals("0%", FormatUtil.formatProgressPercent(-5, 100));
	}

	@Test
	public void testEscapeHtml()
	{
		assertEquals("hello", FormatUtil.escapeHtml("hello"));
		assertEquals("a &amp; b", FormatUtil.escapeHtml("a & b"));
		assertEquals("&lt;script&gt;", FormatUtil.escapeHtml("<script>"));
		assertEquals("", FormatUtil.escapeHtml(null));
	}
}
