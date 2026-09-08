# How to add your guide to the wiki

Adding your own strategy guides to the wiki is a great way to share what you've learned and help other players develop strategies that everyone can use.

{table_of_contents}

## Writing good guides

Writing a good guide takes some effort, and there's a lot that goes into one — but a few key ideas can make a real difference.

### Research

The most important thing is to do your research and try a few different strategies before you start writing. That doesn't mean everything has to be perfect, but the more you've tested and understood, the better your guide will be.

If you know how to read the game code, that's a great way to understand how mechanics actually work. LLMs like Claude can also help you explore the codebase, though you should always sanity-check what they tell you against what you see in-game. And remember, sometimes less is more. If a code snippet doesn't really belong in the main guide, you can always tuck it into a section at the end.

### Adding pictures and game data

People tend to tire of long blocks of text, so visuals and data references are a great way to break things up and keep readers engaged.

[Custom generators](#advanced-custom-generators) let you pull live game data into your guide, but they're more involved to set up. If that's not your thing, that's fine - Excel tables, graphs, markdown tables, and screenshots all work well too.

### Getting feedback

Before you publish, it's worth getting a second opinion on your guide. You could do this by sharing it in GitHub discussions, or posting it in the dedicated strategy guide thread on the game's Discord to get some feedback and refine your work. If you'd like to join the Discord, you can do so [here](https://discord.gg/vRxtXsBwQU). A bit of feedback upfront can sometimes save a lot of rework later.

## Adding guides to the wiki

So you've written your guide and (hopefully) used some feedback to polish it - nice work! The next step is adding it to the wiki itself, which starts with creating a fork of the repo. If you're not familiar with forking and pull requests, [GitHub's quickstart guide](https://docs.github.com/en/pull-requests/get-started/pull-request-quickstart) is a good place to start, or you can find a how-to video that walks through the process.

Once you've forked the repo, you'll have two options for making your wiki guide to choose from:

- **[Standard approach](#getting-started)** - for guides with images and links to other wiki pages
- **[Advanced approach](#advanced-custom-generators)** - for guides that incorporate live game data. These can do anything in the standard approach

### Getting started

Generally, adding a guide to the wiki only takes a few steps. First, add any images you want to use into `wiki/images/guides`.

Then create a new markdown file under `wiki/templates/guides` with your guide content. You don't need to include the title header or the related links section at the bottom as the standard template handles both of those for you. For a working example, take a look at `wiki/templates/guides/the_infinite_tower.md`.

Next, replace your image references with template fields like `{{{{tower_bestiary}}}}`. When naming a field, drop everything after the first dot in the filename — for example, `tower_bestiary.png` becomes `{{{{tower_bestiary}}}}`. For links to other wiki pages, you can use normal markdown links, but it's better to use a field with the page's ID. These IDs are defined in `pages.py` which most in the [`add_static_pages()`](https://github.com/tristinbaker/IdleFantasy/blob/da2c82a048bc6f0e416d9514421eadaf958a407c/wiki/src/pages.py#L64-L126) function.

Finally, register your guide in `wiki/templates/guides/guides.yml`. The guide ID is the top-level key, and the parameters below fill in the rest:

| Parameter        | Description                                                                                                                                                              | Required?                   |
|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------|
| title            | The title shown at the top of the page.                                                                                                                                  | Yes                         |
| author           | That's you! If multiple people contributed, feel free to list more than one name.                                                                                        | Yes                         |
| last_updated     | When the guide was last updated (YYYY-MM-DD). This helps players know how current the advice is, especially as mechanics change over time.                                 | Yes                         |
| images           | A list of image filenames used as template fields in your guide. Any image you convert to a field needs to be listed here with its full filename.                       | No                          |
| page_links       | Page IDs for links within the guide body. To link to another guide, prefix its ID with `guide_` — e.g. `guide_the_infinite_tower` for the Infinite Tower guide.          | No                          |
| related_pages    | Page IDs shown in the 'Related pages' section at the bottom. It's worth adding related in-game pages here as it helps players discover content they might not have found. | No                          |
| custom_generator | Used for guides that incorporate live game data. See [advanced setup](#advanced-custom-generators).                                                                      | N/A (advanced guides only)  |

> Want a table of contents? Just add `{{{{table_of_contents}}}}` at the top of your page and it'll be generated automatically.

### Advanced: Custom generators

Custom generators are a great way to take your guide further by incorporating actual game data or more advanced formatting. Before diving in, it's worth reading {getting_started_wiki_link}. Creating guide generators isn't quite the same as standard wiki pages, but the concepts overlap a lot and it'll give you a solid foundation.

To get started, follow the steps in [Getting started](#getting-started) to create your guide the usual way. The difference is that custom-generated guides can't use the `images` or `page_links` parameters. Instead, set `custom_generator` to a unique name of your choice.

Then add your generator function in the custom player guides section of `pages.py`. It should take your guide template as a parameter and return the fully formatted content of the guide. Add a reference to it in `PLAYER_GUIDE_GENERATORS`, build the wiki, and you should see your guide appear in the sidebar.

#### Using helper functions

When writing custom generators, try to use the existing helper functions wherever you can. They keep formatting consistent across the wiki and help avoid duplicating code. You'll find a number of useful helpers in the helper section of `pages.py`.

For images, keep storing them in `wiki/images/guides` as usual, but reference them with `html_image` and make sure they use the `guide-img` class.