package com.goalplanner.share;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.List;

/**
 * Encodes a {@link ShareBundle} to (and from) a compact, copy-safe string:
 *
 * <pre>  ShareBundle → Gson JSON → gzip → URL-safe Base64, prefixed "GPSHARE1:"</pre>
 *
 * <p>The {@code GPSHARE&lt;n&gt;:} prefix is a magic marker + schema version: it
 * lets {@link #decode(String)} reject arbitrary pasted text and incompatible
 * versions before doing any work. The same encoded string is the payload for
 * a copy/paste export.
 *
 * <p>Gzip matters because goal data is highly repetitive (enum names, level
 * targets) and chat clients cap message size - compression keeps a
 * typical section inside a single message.
 *
 * <p>Takes an injected {@link Gson}; the plugin hub rejects {@code new Gson()},
 * so production wires the shared client instance. Pure and side-effect free.
 */
public final class ShareCodec
{
	/** Magic marker + schema version (current, multi-section). */
	static final String PREFIX_V2 = "GPSHARE" + ShareBundle.SCHEMA_VERSION + ":";

	/** Legacy single-section marker - still decoded, and still emitted for
	 *  plain single-section bundles so older plugin builds can import them. */
	static final String PREFIX = "GPSHARE" + ShareBundle.SCHEMA_VERSION_V1 + ":";

	private final Gson gson;

	public ShareCodec(Gson gson)
	{
		this.gson = gson;
	}

	/**
	 * Encode a bundle to a prefixed, gzipped, URL-safe-Base64 string. The wire
	 * version is chosen from the content: multi-section or default-target
	 * bundles emit {@code GPSHARE2:}; plain single-section bundles down-convert
	 * to the legacy {@code GPSHARE1:} shape so older plugin builds import them.
	 */
	public String encode(ShareBundle bundle)
	{
		if (bundle == null)
		{
			throw new ShareFormatException("nothing to share");
		}
		ShareBundle wire = normalizeForWire(bundle);
		String prefix = wire.getV() == ShareBundle.SCHEMA_VERSION ? PREFIX_V2 : PREFIX;
		byte[] json = gson.toJson(wire).getBytes(StandardCharsets.UTF_8);
		byte[] gz = gzip(json);
		return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(gz);
	}

	/**
	 * Build the version-correct wire copy of {@code bundle}: v2 keeps only
	 * {@code sections}; v1 keeps only the legacy single-section fields. The
	 * input bundle is never mutated.
	 */
	static ShareBundle normalizeForWire(ShareBundle bundle)
	{
		List<SectionShareDto> secs = bundle.effectiveSections();
		ShareBundle wire = new ShareBundle();
		wire.setSharedBy(bundle.getSharedBy());
		if (bundle.needsV2())
		{
			wire.setV(ShareBundle.SCHEMA_VERSION);
			wire.setKind(null);
			wire.setSectionName(null);
			wire.setGoals(null);
			wire.setSections(secs);
			wire.setCrossEdges(bundle.getCrossEdges() != null && !bundle.getCrossEdges().isEmpty()
				? bundle.getCrossEdges() : null);
			return wire;
		}
		SectionShareDto only = secs.get(0);
		wire.setV(ShareBundle.SCHEMA_VERSION_V1);
		wire.setKind(only.getName() != null ? ShareBundle.Kind.SECTION : ShareBundle.Kind.GOALS);
		wire.setSectionName(only.getName());
		wire.setSectionColorRgb(only.getColorRgb());
		wire.setGoals(only.getGoals());
		wire.setSections(null);
		wire.setCrossEdges(null); // single section - nothing to cross
		return wire;
	}

	/**
	 * Decode a string produced by {@link #encode(ShareBundle)}.
	 *
	 * @throws ShareFormatException if the input is null/blank, lacks the
	 *         {@link #PREFIX} (wrong tool or version), or is corrupt.
	 */
	public ShareBundle decode(String text)
	{
		if (text == null || text.trim().isEmpty())
		{
			throw new ShareFormatException("empty share code");
		}
		// The code may be embedded in surrounding instruction text - e.g. a
		// "get the plugin to import this: <code>" chat/Discord line - so locate
		// the marker and read the base64url token that follows it, rather than
		// requiring the code to sit alone. v2 is checked first; both decode.
		String prefix = PREFIX_V2;
		int marker = text.indexOf(PREFIX_V2);
		if (marker < 0)
		{
			prefix = PREFIX;
			marker = text.indexOf(PREFIX);
		}
		if (marker < 0)
		{
			throw new ShareFormatException("unrecognised or wrong-version share code");
		}
		// Collect the Base64url body, SKIPPING embedded whitespace. A long code
		// pasted from chat often wraps across lines; without skipping, the scan
		// would stop at the first newline/space and decode a truncated payload
		// ("That doesn't look like a valid share code"). Stop at the first real
		// non-Base64url, non-whitespace char (trailing prose after the code).
		int start = marker + prefix.length();
		StringBuilder body = new StringBuilder(text.length() - start);
		for (int i = start; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (isBase64Url(c))
			{
				body.append(c);
			}
			else if (!Character.isWhitespace(c))
			{
				break;
			}
		}
		String b64 = body.toString();

		byte[] gz;
		try
		{
			gz = Base64.getUrlDecoder().decode(b64);
		}
		catch (IllegalArgumentException e)
		{
			throw new ShareFormatException("corrupt share code", e);
		}

		String json = new String(gunzip(gz), StandardCharsets.UTF_8);

		ShareBundle bundle;
		try
		{
			bundle = gson.fromJson(json, ShareBundle.class);
		}
		catch (JsonSyntaxException e)
		{
			throw new ShareFormatException("invalid share payload", e);
		}
		if (bundle == null)
		{
			throw new ShareFormatException("empty share payload");
		}
		int expected = prefix.equals(PREFIX_V2)
			? ShareBundle.SCHEMA_VERSION : ShareBundle.SCHEMA_VERSION_V1;
		if (bundle.getV() != expected)
		{
			throw new ShareFormatException(
				"share code is from an incompatible plugin version (v" + bundle.getV() + ")");
		}
		if (expected == ShareBundle.SCHEMA_VERSION)
		{
			if (bundle.getSections() == null || bundle.getSections().isEmpty())
			{
				throw new ShareFormatException("empty share payload");
			}
			for (SectionShareDto s : bundle.getSections())
			{
				if (s == null || s.getGoals() == null)
				{
					throw new ShareFormatException("invalid share payload");
				}
			}
		}
		else if (bundle.getGoals() == null)
		{
			throw new ShareFormatException("empty share payload");
		}
		return bundle;
	}

	/** URL-safe Base64 alphabet (no padding): {@code [A-Za-z0-9_-]}. */
	private static boolean isBase64Url(char c)
	{
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
			|| (c >= '0' && c <= '9') || c == '-' || c == '_';
	}

	private static byte[] gzip(byte[] data)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (GZIPOutputStream gz = new GZIPOutputStream(out))
		{
			gz.write(data);
		}
		catch (IOException e)
		{
			// In-memory streams don't do real IO; treat as unexpected.
			throw new ShareFormatException("failed to compress share payload", e);
		}
		return out.toByteArray();
	}

	private static byte[] gunzip(byte[] data)
	{
		try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data)))
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = gz.read(buf)) != -1)
			{
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new ShareFormatException("corrupt share code", e);
		}
	}
}
