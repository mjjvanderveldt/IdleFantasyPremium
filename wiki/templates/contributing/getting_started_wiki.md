# Getting started - Wiki contributions

Great! You want to know how you can contribute to the wiki and make it better over time. While this wiki functions a little differently from most you may have worked on in the past, if you have a little bit of programming knowledge you'll find it easy to work with, and even if you don't have programming knowledge there are still some ways you can help out.

If you want to make changes to the game itself, check out {game_contribution_link}.

If you find examples much easier to work with, there are pull request guides available [here](#pull-request-guides) where you can see an actual example of editing the wiki.

{table_of_contents}

## How the wiki works

Unlike most wikis which have a collection of pages that require constant maintenance from the community whenever a game is updated, Idle Fantasy relies on a dynamically generated wiki. This uses actual game data to generate the content within the individual pages reducing necessary maintenance of the wiki.

It uses Markdown to define templates with Python being responsible for the compilation and generation of dynamic content.

Some content still requires manual maintenance such as adding new mechanics, strategy guides and some text content however a number of changes in the game is reflected in the wiki.

## Core terminology

To best understand how you can work with the wiki codebase (and also understand the jargon in the docs), it's helpful to go over some core terminology:

### Page Directory

The page directory is a directory describing all the metadata about the pages in the wiki. It gets populated with information about all pages before the actual generation of any content and allows you to refer to other pages when defining page content through the use of a page ID (e.g. for links).

Pages are added to the page directory in `pages.py` under the `Page Listings` section.

### Page Hierarchy

The page hierarchy describes the hierarchical navigation used within the sidebar and on the home page. It lets you have precise control over the items in the navigation bar including how items are ordered. When adding pages to the page hierarchy, you can do so by "merging" it with a list using the `PageHierarchy.merge()` method. You can see examples of this `Page Listings` section where some dynamic pages are merged.

Like the directory, pages are added to the page hierarchy in `pages.py` under the `Page Listings` section.

### Generator functions

Generator functions are responsible for the actual content generation itself. They work by reading game data and filling out a template file with the appropriate Markdown. There are two main ways that generator functions are defined depending on whether you are defining static pages or dynamically-generated pages (see {page_types_link}).

These generator functions are defined in `pages.py` in the page generation section.

### Templates

Templates provide the base formatting for pages within the wiki. Whenever they refer to game content, they'll usually have a field defined like `{{{{field}}}}` that is then filled in using the `string.format()` method in Python. These are filled in by the generator functions described previously.

### Static/Dynamically-generated pages

Whenever we refer to static vs dynamically-generated pages, this refers not to the content within pages but how the pages are defined. If pages are "hardcoded" in the `add_static_pages` function, these are static, whereas generated pages will produce an unknown amount of pages like with each individual boss page for which the number of pages change as bosses are added to the game.

For more information, see {page_types_link}.

## Building from source

To build the wiki, you'll need to set up an appropriate Python environment in the root directory. For information about setting up virtual environments, see [this tutorial](https://www.geeksforgeeks.org/python/create-virtual-environment-using-venv-python/). 
*Make sure to call your virtual environment `.venv` to have it be gitignored).*

### 1. Activate the virtual environment
- If you are using Mac or Gnu/Linux you are probably POSIX. 
- If your shell is missing, see the [Python venv documentation](https://docs.python.org/3/library/venv.html#how-venvs-work).

| Platform | Shell      | Command to activate virtual environment   |
| -------- | ---------- | ----------------------------------------- |
| POSIX    | bash/zsh   | ```$ source .venv/bin/activate```        |
| Windows  | cmd.exe    | ```C:\> .venv\Scripts\activate.bat```    |
| Windows  | Powershell | ```PS C:\> .venv\Scripts\Activate.ps1``` |

### 2. Install dependencies / required packages
```bash
pip install -r wiki/requirements.txt
```
Make sure you've installed all packages shown in `wiki/requirements.txt`. You can do this by running the above command in root folder for the repository.

### 3. Usage

If you have sucesfully completed the above instructions, you should now be ready to modify and compile the wiki. 

This section contains instruction and detail around the various commands you can use to assist you with this, you can also always see the available commands, and what they do from the command line with the following command:
```bash
python -m wiki.src -h
```

#### Compiling the wiki
```bash
python -m wiki.src write-html
```
This is the main command you'll need use, it creates the HTML version of the wiki in `out/IdleFantasy-site`, and you will need to re-run it every time you make any changes to the source files to update the output. You can then open any of the HTML pages and the website should come up.

If you're using Pycharm, you can open it using the live preview option, which should update things more seamlessly.

#### Validating the wiki
```bash
python -m wiki.src validity
```
You can use the above command to validate the wiki and perform several tests that can help you pick up errors.

## Wiki code structure

The following code files are used as follows in the wiki:

- `__main__.py` - The main entry point to the wiki program responsible for parsing arguments and top-level management.
- `pages.py` - The primary code responsible for generating all the Markdown pages. This also contains a number of helper functions which you should use where relevant such as `link()`, etc.
- `game_data.py` - A set of helper functions which retrieve data from the game such as json files and game strings
- `site.py` - The code responsible for generating the Idle Fantasy wiki website based upon the generated Markdown files.
- `page_hierarchy.py` - A simple file defining the page hierarchy structure.

## Contributing process

For small contributions (e.g. fixing small mistakes, etc), you can simply fork the repository, make the relevant change and then create a pull request.

If you notice larger issues/inaccuracies that might take some time to fix, you should create an issue beforehand and mention that you're planning on making the changes. This makes sure that there aren't multiple people working on the same issue (without knowing) and also means that the issue can be referenced/tracked.

If you want to make a number of new pages or significant changes (especially dynamically-generated ones) in order to improve the wiki, then you should open a discussion to give the opportunity for community involvement.

Additionally, you might notice a number of #Todo items in the wiki code. These are also great places to look for things that need doing that we maybe just haven't got around to sorting out yet.

### Pull request guides/examples

The guides and examples below will show you an example of editing the wiki which you might find helpful to wrap your head around how things work:

- {editing_a_page_link} - In this detailed guide, we go over editing a couple pages in the wiki. This is probably the most common level of detail you'll need to edit a page.
- {adding_quest_icons_page} - This pull request shows an example of adding a simple static page to the wiki

## Additional topics

- {page_types_link} - More information about the different page types
