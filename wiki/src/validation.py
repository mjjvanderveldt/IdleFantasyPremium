from __future__ import annotations

import logging
import re
import traceback
from sys import stdout

from wiki.src.pages import PAGE_HIERARCHY, PAGE_DIRECTORY, get_image_directory
from wiki.src.wiki_logs import LOGGER


def check_wiki_validity():
    print("Starting wiki validation")

    # Check image icons exist
    print("Checking images...")
    _validate_images_exist()
    stdout.flush()

    # Ensure all pages can generate content
    print("Checking page content...")
    _validate_page_gen()
    stdout.flush()

    print("Ensuring pages link correctly...")
    _check_page_links()
    stdout.flush()

    print("Validation complete")


def _validate_images_exist():
    for icon in get_image_directory().keys():
        if not icon.is_file():
            LOGGER.warn_by_id(f"image:{icon}", f"Image '{icon}' is used in the pages but does not exist", logging.CRITICAL)


def _validate_page_gen():
    for page_id, page_info in PAGE_DIRECTORY.items():
        # Ensure generator exists
        if page_info.generate is NotImplemented:
            LOGGER.warn_by_id(f"page_gen:{page_id}", f"Page '{page_id}' does not contain a method to create content", logging.CRITICAL)
            continue
        # Attempt to generate
        try:
            page_info.generate()
        except:
            LOGGER.warn_by_id(f"page_gen:{page_id}", f"Creating content for page '{page_id}' failed due to the below error:\n"
                                                     f"\033[93m{traceback.format_exc()}\033[00m", logging.CRITICAL)


def _pages_in_hierarchy() -> set[str]:
    """Gets the list of all pages in the hierarchy"""
    pages = set()
    listing_items = [PAGE_HIERARCHY]
    while len(listing_items) > 0:
        item = listing_items.pop(0)
        if isinstance(item, str):
            pages.add(item)
        else:
            listing_items += [x for x in item]
    return pages


def _check_page_links():
    """Checks that all pages are appropriately linked in the wiki by searching all pages from the home page and hierarchy"""
    def _get_linked_page_url(page: str, content: str) -> set[str]:
        # Get all markdown links from page content (exclude images)
        links_in_page = set(re.findall(r"(?<!!)\[(?:[^\]]*)]\(([^)]+)\)", content))
        # Get all HTML link hrefs from page content
        links_in_page.update(re.findall(r"<a[^>]*href=['\"]([^'\"]+)['\"][^>]*>", content))
        page_names = set()
        # Remove external links, pure headers, and normalise wiki links
        links_in_page = {x.split("#", 1)[0].removeprefix("./").removesuffix(".md") for x in links_in_page if "://" not in x and x[0] != "#"}
        for link in links_in_page:
            if link not in [x.url.removeprefix("./").removesuffix(".md") for x in PAGE_DIRECTORY.values()]:  # Link is not a page link
                LOGGER.warn_by_id(f"invalid_page:{page}:{link}", f"The page `{page}` references a non-existent page: {link}.md", logging.CRITICAL)
                continue
            # Find appropriate page
            page_names.update(k for k, v in PAGE_DIRECTORY.items() if v.url.removeprefix("./").removesuffix(".md") == link)
        return page_names

    # Create page graph
    checked_pages = set()
    pages_to_check = {"home", "sidebar"}.union(_pages_in_hierarchy())
    while len(pages_to_check) > 0:
        # Pop next page to check and add to checked pages
        next_page = pages_to_check.pop()
        checked_pages.add(next_page)
        # Add any page links that have not already been checked
        linked_pages = _get_linked_page_url(next_page, PAGE_DIRECTORY[next_page].generate()).difference(checked_pages)
        pages_to_check = pages_to_check.union(linked_pages)

    # Mention pages with no links
    missing_pages = set(PAGE_DIRECTORY.keys()).difference(checked_pages)
    for page in missing_pages:
        LOGGER.warn_by_id(f"missing_page:{page}", f"The page `{page}` is not referenced by any page nor is on the sidebar/hierarchy", logging.CRITICAL)
        # Analyse missing page links as well
        _get_linked_page_url(page, PAGE_DIRECTORY[page].generate())
