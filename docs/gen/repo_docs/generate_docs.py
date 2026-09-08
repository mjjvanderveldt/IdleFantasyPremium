import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import yaml

from docs.gen.repo_docs import TEMPLATES, ASSETS, ROOT, FASTLANE_META_PATH, RESOURCES, METADATA_FILE


@dataclass
class DocInfo:
    title: str
    output_file: Path
    generate: Callable[[], str] | NotImplemented

    @property
    def relative_path(self):
        return self.output_file.relative_to(ROOT).as_posix()[1:]


# ---------------------------------------------------------------------------
# Shared Constants
# ---------------------------------------------------------------------------

SKILLS = [
    ("Gathering", ["Mining", "Fishing", "Woodcutting", "Farming", "Thieving"]),
    ("Crafting", ["Smithing", "Cooking", "Fletching", "Crafting", "Firemaking", "Runecrafting", "Herblore", "Construction"]),
    ("Support", ["Prayer", "Mercantile", "Agility"]),
    ("Combat", ["Slayer", "Attack", "Strength", "Defense", "Ranged", "Magic", "Hitpoints"])
]

# ---------------------------------------------------------------------------
# Document listings
# ---------------------------------------------------------------------------

DOCUMENT_DIRECTORY: dict[str, DocInfo] = {}

def add_static_docs():
    DOCUMENT_DIRECTORY.update({
        "readme": DocInfo("Readme", ROOT / "README.md", gen_readme),
        "fastlane_desc": DocInfo("Fastlane Description", FASTLANE_META_PATH / "full_description.txt", gen_fastlane_desc),
        "security": DocInfo("Security", ROOT / "SECURITY.md", gen_security),
    })

# ---------------------------------------------------------------------------
# Main functions
# ---------------------------------------------------------------------------

def get_docs() -> dict[Path, str]:
    return {d.output_file: d.generate() for d in DOCUMENT_DIRECTORY.values()}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def get_template(name: str) -> str:
    """Gets a template file by name"""
    try:
        with open(TEMPLATES / f"{name}", encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError as e:
        print(f"Error: The requested template '{name}' does not exist")
        raise e


def load(rel_path: str | Path, prefix_assets: bool = True) -> dict | list:
    path = (ASSETS / rel_path) if prefix_assets else Path(rel_path)
    return json.loads(path.read_text(encoding="utf-8"))


def link(page_id: str, display_name: str | None = None):
    page = DOCUMENT_DIRECTORY[page_id]
    return f"[{page.title if display_name is None else display_name}]({page.relative_path})"


def html_link(page_id: str, display_name: str | None = None) -> str:
    """HTML anchor for use inside raw HTML blocks where Markdown links are not parsed."""
    page = DOCUMENT_DIRECTORY[page_id]
    name = page.title if display_name is None else display_name
    return f'<a href="{page.relative_path}">{name}</a>'


def image_link(image: Path, alt_tag: str | None = None) -> str:
    return f"![{image.name if alt_tag is None else alt_tag}]({image.relative_to(ROOT).as_posix()[1:]})"


def html_image_link(image: Path, alt_tag: str | None = None, classes: str | None = None) -> str:
    return (f"<img src='{image.relative_to(ROOT).as_posix()}' alt='{image.name if alt_tag is None else alt_tag}'"
            f"{f" class='{classes}'" if classes else ""}>")


def format_english_list(items: list[str]) -> str:
    if not items:
        return ""
    if len(items) == 1:
        return items[0]
    if len(items) == 2:
        return f"{items[0]} and {items[1]}"
    return f"{', '.join(items[:-1])}, and {items[-1]}"


def make_latex_safe(content: str, escape_levels: int = 1) -> str:
    """Escape braces inside inline LaTeX (``$...$``) so ``str.format`` leaves them intact.

    Expressions such as ``$\\dfrac{a}{b}$`` contain braces that ``str.format`` would
    otherwise treat as replacement fields. Braces are doubled once per escape level
    (level 1 → ``{{`` / ``}}``, level 2 → ``{{{{`` / ``}}}}``, and so on).

    :param content: Text that may contain inline LaTeX maths.
    :param escape_levels: Number of ``.format`` passes the content will go through.
    :return: Content with LaTeX braces escaped for the given format depth.
    """
    open_brace = "{" * (2 ** escape_levels)
    close_brace = "}" * (2 ** escape_levels)

    def _escape_math(match: re.Match[str]) -> str:
        body = match.group(1).replace("{", open_brace).replace("}", close_brace)
        return f"${body}$"

    return re.sub(r"\$([^$]+)\$", _escape_math, content)


def get_current_version() -> str:
    with open(METADATA_FILE) as f:
        return yaml.safe_load(f)["CurrentVersion"]


# ---------------------------------------------------------------------------
# Page Creation
# ---------------------------------------------------------------------------

def gen_readme() -> str:
    return get_template("readme.md").format(
        skill_count=sum(len(skill_group[1]) for skill_group in SKILLS),
        skill_list="\n".join(f"- **{skill_group[0]}** ({len(skill_group[1])}): {format_english_list(skill_group[1])}" for skill_group in SKILLS),
        dungeon_count=len([x for x in (ASSETS / "dungeons").glob("*.json")]),
        quest_count=len(load(ASSETS / "quests.json")),
        guild_count=len(set(x["guild"] for x in load(ASSETS / "guild_quests.json").values())),
        language_count=len([x for x in RESOURCES.glob("values*/")]),
    )


def gen_fastlane_desc() -> str:
    return get_template("fastlane_full_description.txt").format(
        skill_count=sum(len(skill_group[1]) for skill_group in SKILLS),
        all_skills=format_english_list([skill for skill_group in SKILLS for skill in skill_group[1]]),
        dungeon_count=len([x for x in (ASSETS / "dungeons").glob("*.json")]),
        quest_count=len(load(ASSETS / "quests.json")),
        language_count=len([x for x in RESOURCES.glob("values*/")]),
    )


def gen_security() -> str:
    return get_template("security.md").format(
        version=get_current_version()
    )


# ---------------------------------------------------------------------------
# Adding documents to the directory
# ---------------------------------------------------------------------------

add_static_docs()
