from pathlib import Path

ROOT = Path(__file__).parents[3]
TEMPLATES = Path(__file__).parents[1] / "templates"
FASTLANE_META_PATH = ROOT / "fastlane" / "metadata" / "android" / "en-US"
ASSETS     = ROOT / "app" / "src" / "main" / "assets" / "data"
RESOURCES  = ROOT / "app" / "src" / "main" / "res"
METADATA_FILE = ROOT / "metadata" / "com.tristinbaker.idlefantasy.yml"