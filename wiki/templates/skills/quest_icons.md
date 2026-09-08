# Quest Indicators

Quest indicators appear as small superscripted icons next to skill names showing active quest progress. They help players quickly identify which type of quests are active for each skill.

## Quest Indicator Icons

Each quest category uses a distinct emoji for visual identification.

| Icon | Quest Category           | Description                                                                                                             |
|------|--------------------------|-------------------------------------------------------------------------------------------------------------------------|
| ⏰    | Daily quests             | Complete daily quests for coins. Chance to receive Dwarven gear when missing pieces exist (resetting on each drop).     |
| 📅   | Weekly quests            | Complete weekly quests for bonus rewards including Divine gear. Resets every Monday at the configured daily reset hour. |
| 🎯   | Seasonal event bounties  | Complete bounties during active seasonal events to earn event tokens and rewards. Displays the event's theme icon (e.g., ☀️ for Sunspire Solstice, 🎃 for Gloomharvest) with 🎯 as a fallback. |
| ⚒️   | Guild daily quests       | Complete guild daily quests through the Guild Hall to earn reputation and unlock new tiers.                             |
| 🏰   | Guild progression quests | Progress through guild step quests to unlock new tiers and gain guild reputation.                                       |
| 📜   | Main quests              | Progress through the main questline to unlock new mechanics, areas, and features.                                       |

## Usage in the UI

- **Quest indicators** appear as superscripted counts next to skill names (e.g., ⏰<sup>3</sup> shows 3 active daily quests)
- Quest indicators are dimmed (38% opacity) when no quests of that category are completable
- When multiple quests of the same category target the same skill, a superscript shows the count (e.g., 📅<sup>2</sup> for 2 weekly quests)

## See Also

- {skills_link} - Main skills documentation with skill icons
- {quests_link} - Main quest list
- {guilds_link} - Guild daily and progression quests
- {seasonal_events_link} - Seasonal events and bounty board