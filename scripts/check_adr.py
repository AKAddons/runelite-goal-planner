#!/usr/bin/env python3
"""Architecture-decision-record structure guard.

Decisions live as MADR files in docs/decisions/ (see ADR-0001). A decision log
only earns its keep if you can trust it: a stale index, a typo'd status, or a
"superseded by" pointing at nothing all turn the log back into prose you have
to read end-to-end. This guard holds the invariants that make it skimmable.

Checks:
  * filenames are NNNN-kebab-title.md, numbers unique
  * frontmatter parses and carries a known `status` and an ISO `date`
  * "superseded by ADR-NNNN" resolves to a file that exists
  * the three load-bearing sections are present
  * DECISIONS.md indexes every ADR, with a matching status

Run directly or via `./gradlew checkAdr` (part of `preSubmit`).
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DECISIONS_DIR = os.path.join(ROOT, "docs", "decisions")
INDEX = os.path.join(ROOT, "DECISIONS.md")

# 0000 is the template: placeholder status/date by design, not indexed.
TEMPLATE_NUM = "0000"

FILENAME = re.compile(r"^(\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
SIMPLE_STATUS = {"proposed", "rejected", "accepted", "deprecated"}
SUPERSEDED = re.compile(r"^superseded by ADR-(\d{4})$")
ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

REQUIRED_SECTIONS = [
	"## Context and Problem Statement",
	"## Considered Options",
	"## Decision Outcome",
]


def parse_frontmatter(text):
	"""Minimal YAML-frontmatter reader: flat `key: value` pairs only.

	Deliberately not PyYAML — the other gates are stdlib-only so they run on a
	bare python3, and ADR frontmatter is flat by convention.
	"""
	if not text.startswith("---\n"):
		return None
	end = text.find("\n---", 4)
	if end == -1:
		return None
	fields = {}
	for line in text[4:end].splitlines():
		if not line.strip() or line.lstrip().startswith("#"):
			continue
		key, sep, value = line.partition(":")
		if sep:
			fields[key.strip()] = value.strip().strip('"').strip("'")
	return fields


def main():
	if not os.path.isdir(DECISIONS_DIR):
		print(f"✗ No {os.path.relpath(DECISIONS_DIR, ROOT)}/ directory.")
		return 1

	errors = []
	adrs = {}  # number -> (filename, status, title)

	for name in sorted(os.listdir(DECISIONS_DIR)):
		if not name.endswith(".md"):
			continue
		m = FILENAME.match(name)
		if not m:
			errors.append(f"{name}: filename is not NNNN-kebab-title.md")
			continue
		num = m.group(1)
		if num in adrs:
			errors.append(f"{name}: duplicate ADR number {num} (also {adrs[num][0]})")
			continue

		with open(os.path.join(DECISIONS_DIR, name), encoding="utf-8") as fh:
			text = fh.read()

		if num == TEMPLATE_NUM:
			adrs[num] = (name, None, None)
			continue

		fields = parse_frontmatter(text)
		if fields is None:
			errors.append(f"{name}: missing or unterminated YAML frontmatter")
			continue

		status = fields.get("status")
		if not status:
			errors.append(f"{name}: no `status` in frontmatter")
		elif status not in SIMPLE_STATUS and not SUPERSEDED.match(status):
			known = ", ".join(sorted(SIMPLE_STATUS))
			errors.append(f"{name}: unknown status {status!r} (expected one of {known}, "
						  "or 'superseded by ADR-NNNN')")

		date = fields.get("date")
		if not date:
			errors.append(f"{name}: no `date` in frontmatter")
		elif not ISO_DATE.match(date):
			errors.append(f"{name}: date {date!r} is not YYYY-MM-DD")

		title = ""
		for line in text.splitlines():
			if line.startswith("# "):
				title = line[2:].strip()
				break
		if not title:
			errors.append(f"{name}: no `# Title` heading")

		for section in REQUIRED_SECTIONS:
			if section not in text:
				errors.append(f"{name}: missing section '{section}'")

		adrs[num] = (name, status, title)

	# Supersession targets must exist.
	for num, (name, status, _) in sorted(adrs.items()):
		if not status:
			continue
		m = SUPERSEDED.match(status)
		if m and m.group(1) not in adrs:
			errors.append(f"{name}: superseded by ADR-{m.group(1)}, which does not exist")

	# The index must not drift from the directory.
	if not os.path.isfile(INDEX):
		errors.append("DECISIONS.md is missing (it is the index into docs/decisions/)")
	else:
		with open(INDEX, encoding="utf-8") as fh:
			index_text = fh.read()
		for num, (name, status, _) in sorted(adrs.items()):
			if num == TEMPLATE_NUM:
				continue
			if name not in index_text:
				errors.append(f"DECISIONS.md: no index row linking {name}")
			elif status:
				row = next((ln for ln in index_text.splitlines() if name in ln), "")
				if status not in row:
					errors.append(f"DECISIONS.md: index row for ADR-{num} does not show "
								  f"status {status!r} (stale index)")

	real = [n for n in adrs if n != TEMPLATE_NUM]
	if errors:
		print(f"✗ ADR structure ({len(errors)} problem(s)):\n")
		for err in errors:
			print(f"  {err}")
		return 1
	print(f"✓ {len(real)} ADRs well-formed and indexed.")
	return 0


if __name__ == "__main__":
	sys.exit(main())
