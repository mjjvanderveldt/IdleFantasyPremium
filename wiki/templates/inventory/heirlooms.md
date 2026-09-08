# Heirlooms

Heirlooms are unique, one-of-a-kind tools and weapons that grow alongside you. Unlike normal equipment, heirlooms have **no level requirements**: you can equip one the moment you find it, no matter what level you are.

## How heirlooms grow

Every heirloom has its own **item level**, from 1 to 99, using the same XP curve as regular skills. While an heirloom is equipped, it gains item XP equal to the skill XP you earn in its **governing skill**.

- At **item level 1**, an heirloom is roughly on par with iron-tier gear.
- At **item level 99**, it is around 50% stronger than the best regular equipment in the game.

## The level 85 skill gate

An heirloom's power is also gated by your own level in its governing skill. The gate scales linearly and is fully open at **level {gate_level}**, so an heirloom only reaches its full potential once you are level {gate_level} or higher in that skill.

Putting both together, an heirloom's effective stats are:

$`Effective\ stat = base + \dfrac{(max - base) \times (item\ level - 1)}{98} \times \dfrac{min(skill\ level, {gate_level})}{{gate_level}}`$

Prestiging the governing skill temporarily weakens the heirloom (your skill level drops below the gate again), but the heirloom's item level and XP are never lost, so it returns to full strength as you level the skill back up.

## Obtaining heirlooms

Heirlooms are rare drops from raid bosses. Each boss rolls its heirloom drops once per victory, and **duplicate protection** applies: a raid boss will never drop an heirloom you already own.

{drop_table}

## Tool heirlooms

Tool heirlooms mirror XP from the gathering, crafting, or support skill they serve, and their efficiency multiplier grows with item level.

{tool_table}

## Combat heirlooms

Combat heirloom weapons mirror XP from the skill matching their combat style, and their offensive stats grow with item level.

{combat_table}

*Note: the {equipment_link} page lists heirlooms at their full potential (item level 99 with the skill gate fully open).*
