#!/usr/bin/env python3
"""Rank source files by comment density and flag likely commented-out code.

This is a triage tool: it tells you where to look first, not what to delete.
The commented-out-code detection is heuristic and will produce false positives.

Usage:
    python scan_comments.py <path> [--top N] [--min-lines N] [--json]
"""

from __future__ import annotations

import argparse
import ast
import io
import json
import re
import sys
import tokenize
from dataclasses import dataclass, field
from pathlib import Path

LINE_COMMENT_MARKER_BY_EXTENSION = {
    **dict.fromkeys([".py", ".rb", ".sh", ".bash", ".r", ".pl"], "#"),
    **dict.fromkeys(
        [".c", ".h", ".cpp", ".hpp", ".cc", ".cs", ".java", ".js", ".jsx",
         ".ts", ".tsx", ".go", ".rs", ".kt", ".swift", ".scala", ".php", ".dart"],
        "//",
    ),
    **dict.fromkeys([".sql", ".lua", ".hs"], "--"),
}

EXTENSIONS_WITH_BLOCK_COMMENTS = {
    ext for ext, marker in LINE_COMMENT_MARKER_BY_EXTENSION.items() if marker == "//"
}

SKIPPED_DIRECTORIES = {
    ".git", ".hg", ".svn", "node_modules", "venv", ".venv", "env", "__pycache__",
    "dist", "build", "target", "vendor", "third_party", ".next", ".tox", "migrations",
}

TOOL_DIRECTIVE = re.compile(
    r"^(-\*-|type:|noqa|pragma|pylint:|fmt:|mypy:|ruff:|isort:|eslint|@ts-|"
    r"prettier-ignore|istanbul|nolint|go:|#region|#endregion|region\b|endregion\b)",
    re.IGNORECASE,
)

# Each pattern is a separate, independently weak signal. A comment only has to
# match one, so this deliberately favours recall over precision: a human
# reviews every hit anyway, and missing dead code is the costlier mistake.
CODE_LIKE_PATTERNS = [
    re.compile(r"[;{}]\s*$"),
    re.compile(r"^(if|for|while|return|def|class|import|from|const|let|var|"
               r"function|elif|else|try|except|catch|public|private|static)\b.*[(:=;{]"),
    re.compile(r"^[\w.\[\]]+\s*[+\-*/]?=\s*[^=\s]"),
    re.compile(r"^[\w.]+\(.*\)\s*;?$"),
]


@dataclass
class FileReport:
    path: str
    code_lines: int = 0
    comment_lines: int = 0
    doc_lines: int = 0
    suspected_dead_code_lines: list[int] = field(default_factory=list)

    @property
    def comment_ratio(self) -> float:
        counted_lines = self.code_lines + self.comment_lines
        return self.comment_lines / counted_lines if counted_lines else 0.0

    def record_comment(self, line_number: int, comment_text: str) -> None:
        self.comment_lines += 1
        if looks_like_code(comment_text):
            self.suspected_dead_code_lines.append(line_number)

    def to_dict(self) -> dict:
        return {
            "path": self.path,
            "code_lines": self.code_lines,
            "comment_lines": self.comment_lines,
            "doc_lines": self.doc_lines,
            "comment_ratio": round(self.comment_ratio, 3),
            "suspected_dead_code_lines": self.suspected_dead_code_lines,
        }


def looks_like_code(comment_text: str) -> bool:
    return any(pattern.search(comment_text) for pattern in CODE_LIKE_PATTERNS)


def is_tool_directive(comment_text: str) -> bool:
    return bool(TOOL_DIRECTIVE.match(comment_text))


def find_python_docstring_lines(source: str) -> set[int]:
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return set()

    docstring_lines: set[int] = set()
    nodes_that_can_have_docstrings = (
        ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef
    )
    for node in ast.walk(tree):
        if not isinstance(node, nodes_that_can_have_docstrings) or not node.body:
            continue
        first_statement = node.body[0]
        is_docstring = (
            isinstance(first_statement, ast.Expr)
            and isinstance(first_statement.value, ast.Constant)
            and isinstance(first_statement.value.value, str)
        )
        if is_docstring:
            docstring_lines.update(
                range(first_statement.lineno, first_statement.end_lineno + 1)
            )
    return docstring_lines


def analyse_python(path: Path, source: str) -> FileReport:
    # The tokenizer is used instead of line matching because it knows the
    # difference between a real comment and a '#' inside a string literal.
    report = FileReport(str(path))
    docstring_lines = find_python_docstring_lines(source)
    code_line_numbers: set[int] = set()
    non_code_token_types = {
        tokenize.COMMENT, tokenize.NL, tokenize.NEWLINE, tokenize.INDENT,
        tokenize.DEDENT, tokenize.ENDMARKER, tokenize.ENCODING,
    }

    try:
        for token in tokenize.generate_tokens(io.StringIO(source).readline):
            line_number = token.start[0]
            if token.type == tokenize.COMMENT:
                is_full_line_comment = token.line.strip().startswith("#")
                comment_text = token.string.lstrip("#").strip()
                is_shebang = line_number == 1 and token.string.startswith("#!")
                if is_full_line_comment and not is_shebang and not is_tool_directive(comment_text):
                    report.record_comment(line_number, comment_text)
            elif token.type not in non_code_token_types:
                code_line_numbers.update(range(token.start[0], token.end[0] + 1))
    except (tokenize.TokenError, SyntaxError):
        return analyse_by_line(path, source, marker="#", has_block_comments=False)

    report.doc_lines = len(docstring_lines)
    report.code_lines = len(code_line_numbers - docstring_lines)
    return report


def analyse_by_line(path: Path, source: str, marker: str, has_block_comments: bool) -> FileReport:
    # A line-based heuristic: it only recognises comments at the start of a
    # line, so trailing comments and comment markers inside strings are
    # ignored. Good enough for ranking files, which is all this is for.
    report = FileReport(str(path))
    inside_block_comment = False
    block_is_documentation = False

    for line_number, raw_line in enumerate(source.splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue

        starts_block_comment = has_block_comments and line.startswith("/*")
        if inside_block_comment or starts_block_comment:
            if starts_block_comment:
                block_is_documentation = line.startswith("/**")
            inside_block_comment = "*/" not in line
            if block_is_documentation:
                report.doc_lines += 1
            else:
                comment_text = line.strip("/* ").strip()
                if comment_text:
                    report.record_comment(line_number, comment_text)
            continue

        if line_number == 1 and line.startswith("#!"):
            continue

        if line.startswith(marker):
            comment_text = line[len(marker):].strip()
            # '///' (Rust, C#) and '//!' (Rust) are documentation comments.
            is_doc_comment = marker == "//" and comment_text[:1] in ("/", "!")
            if is_doc_comment:
                report.doc_lines += 1
            elif not is_tool_directive(comment_text):
                report.record_comment(line_number, comment_text)
            continue

        report.code_lines += 1

    return report


def analyse_file(path: Path) -> FileReport | None:
    try:
        source = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None

    extension = path.suffix.lower()
    if extension == ".py":
        return analyse_python(path, source)
    return analyse_by_line(
        path,
        source,
        marker=LINE_COMMENT_MARKER_BY_EXTENSION[extension],
        has_block_comments=extension in EXTENSIONS_WITH_BLOCK_COMMENTS,
    )


def find_source_files(root: Path):
    if root.is_file():
        yield root
        return
    for path in sorted(root.rglob("*")):
        relative_parts = path.relative_to(root).parts
        if any(part in SKIPPED_DIRECTORIES for part in relative_parts):
            continue
        if path.is_file() and path.suffix.lower() in LINE_COMMENT_MARKER_BY_EXTENSION:
            yield path


def summarise_line_numbers(line_numbers: list[int], limit: int = 12) -> str:
    shown = ", ".join(map(str, line_numbers[:limit]))
    hidden_count = len(line_numbers) - limit
    return f"{shown} (+{hidden_count} more)" if hidden_count > 0 else shown


def print_table(reports: list[FileReport], total_files_scanned: int) -> None:
    if not reports:
        print("No files met the --min-lines threshold.")
        return

    print(f"{'comment %':>9}  {'comments':>8}  {'code':>6}  {'docs':>5}  {'dead?':>5}  file")
    for report in reports:
        print(
            f"{report.comment_ratio:>8.0%}  {report.comment_lines:>8}  "
            f"{report.code_lines:>6}  {report.doc_lines:>5}  "
            f"{len(report.suspected_dead_code_lines):>5}  {report.path}"
        )

    reports_with_dead_code = [r for r in reports if r.suspected_dead_code_lines]
    if reports_with_dead_code:
        print("\nPossible commented-out code (verify each one):")
        for report in reports_with_dead_code:
            print(f"  {report.path}: lines {summarise_line_numbers(report.suspected_dead_code_lines)}")

    total_comments = sum(r.comment_lines for r in reports)
    total_code = sum(r.code_lines for r in reports)
    overall_ratio = total_comments / (total_comments + total_code) if total_code else 0.0
    print(f"\nShowing {len(reports)} of {total_files_scanned} files scanned. "
          f"Overall comment share of shown files: {overall_ratio:.0%}.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("path", type=Path, help="file or directory to scan")
    parser.add_argument("--top", type=int, default=20, help="number of files to show (default 20)")
    parser.add_argument("--min-lines", type=int, default=10,
                        help="skip files with fewer code lines than this (default 10), "
                             "since a 3-line file with one comment isn't a real problem")
    parser.add_argument("--json", action="store_true", help="output JSON instead of a table")
    args = parser.parse_args()

    if not args.path.exists():
        print(f"Path not found: {args.path}", file=sys.stderr)
        return 1

    all_reports = [r for r in map(analyse_file, find_source_files(args.path)) if r is not None]
    large_enough_reports = [r for r in all_reports if r.code_lines >= args.min_lines]
    ranked_reports = sorted(
        large_enough_reports, key=lambda r: r.comment_ratio, reverse=True
    )[: args.top]

    if args.json:
        print(json.dumps([r.to_dict() for r in ranked_reports], indent=2))
    else:
        print_table(ranked_reports, total_files_scanned=len(all_reports))
    return 0


if __name__ == "__main__":
    sys.exit(main())
