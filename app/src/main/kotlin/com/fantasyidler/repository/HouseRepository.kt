package com.fantasyidler.repository

import com.fantasyidler.data.json.HouseCostTier
import com.fantasyidler.data.json.HouseTileDef
import com.fantasyidler.data.model.HouseBlueprint
import com.fantasyidler.data.model.HouseData
import com.fantasyidler.data.model.HouseDraft
import com.fantasyidler.data.model.HousePlacement
import com.fantasyidler.data.model.HouseRoom
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.Skills
import javax.inject.Inject
import javax.inject.Singleton

enum class HouseActionResult {
    SUCCESS,
    INSUFFICIENT_LEVEL,
    INSUFFICIENT_COINS,
    INSUFFICIENT_MATERIALS,
    INVALID_SPOT,
    MAX_ROOMS,
    MAX_ROOM_SIZE,
    MIN_ROOM_SIZE,
    ALREADY_PLACED,
    LAST_ROOM,
}

enum class HouseDirection { NORTH, SOUTH, EAST, WEST }

/** One priced line of the build bill. */
data class HouseBillLine(
    val kind: Kind,
    val itemKey: String? = null,
    /** 1-based draft room number, for room lines. */
    val roomNumber: Int = 0,
    /** Item count for ITEM lines, cell count for EXPAND/SHRINK_CREDIT, 1 for NEW_ROOM. */
    val units: Int = 1,
    val coins: Long = 0L,
    val materials: Map<String, Int> = emptyMap(),
    val xp: Long = 0L,
    val level: Int = 1,
) {
    enum class Kind { NEW_ROOM, EXPAND, SHRINK_CREDIT, ITEM }
}

/** Everything the pending build will cost (and credit) at purchase time. */
data class HouseBill(
    val lines: List<HouseBillLine> = emptyList(),
    val coins: Long = 0L,
    val materials: Map<String, Int> = emptyMap(),
    val creditCoins: Long = 0L,
    val creditMaterials: Map<String, Int> = emptyMap(),
    val xp: Long = 0L,
    val requiredLevel: Int = 1,
) {
    val isEmpty: Boolean get() = lines.isEmpty()
    val netCoins: Long get() = coins - creditCoins

    /** Positive = consumed at purchase, negative = returned. */
    fun netMaterials(): Map<String, Int> =
        (materials.keys + creditMaterials.keys).associateWith {
            (materials[it] ?: 0) - (creditMaterials[it] ?: 0)
        }.filterValues { it != 0 }
}

/**
 * Player housing: rooms and furnishings are drafted freely in the editor and paid for in one
 * bill via [purchaseBuild]; nothing touches the real house (PlayerFlags.house) until then.
 * The draft and saved blueprints live in PlayerFlags alongside the house itself.
 */
@Singleton
class HouseRepository @Inject constructor(
    private val playerRepo: PlayerRepository,
    private val gameData: GameDataRepository,
    private val boostRepo: BoostRepository,
) {
    companion object {
        /** The house grid is GRID_SIZE x GRID_SIZE cells; rooms must fit inside it. */
        // 18x18 lets all six rooms reach max size (6 x 9x9 fits with room to arrange);
        // the canvas pans and zooms, so the grid no longer has to fit the screen at 1x.
        const val GRID_SIZE = 18
        const val STARTER_ROOM_SIZE = 4
        const val NEW_ROOM_SIZE = 3
        const val MAX_ROOM_DIM = 9
        /** Construction level that unlocks the brick floor style. */
        const val BRICK_FLOOR_LEVEL = 10

        const val BLUEPRINT_SLOTS = 3

        /**
         * Placement-grid subdivisions per room cell: placements snap to half cells.
         * Rooms stay on the full-cell grid; all placement coordinates are half-cell units.
         */
        const val SUB = 2

        /** Placement key prefix for earned seasonal banners: "banner:" + drawable name. */
        const val BANNER_PREFIX = "banner:"

        /** Synthetic def for seasonal banners: free wall decor, one cell, no atlas sprite. */
        val BANNER_DEF = HouseTileDef(
            x = 0, y = 0, w = 16, h = 29,
            footprintW = 1, footprintH = 1,
            wallMounted = true, category = "banner",
            levelRequired = 1, coinCost = 0L, materials = emptyMap(), xp = 0L,
        )

        fun roomsOverlap(a: HouseRoom, x: Int, y: Int, w: Int, h: Int): Boolean =
            a.x < x + w && x < a.x + a.w && a.y < y + h && y < a.y + a.h

        /** True when the rect shares at least one full cell edge with [room]. */
        fun sharesEdge(room: HouseRoom, x: Int, y: Int, w: Int, h: Int): Boolean {
            val horizontalOverlap = room.x < x + w && x < room.x + room.w
            val verticalOverlap   = room.y < y + h && y < room.y + room.h
            val touchesVertically   = (room.y + room.h == y || y + h == room.y) && horizontalOverlap
            val touchesHorizontally = (room.x + room.w == x || x + w == room.x) && verticalOverlap
            return touchesVertically || touchesHorizontally
        }

        fun roomContaining(rooms: List<HouseRoom>, x: Int, y: Int, w: Int, h: Int): HouseRoom? =
            rooms.firstOrNull { x >= it.x && y >= it.y && x + w <= it.x + it.w && y + h <= it.y + it.h }

        /**
         * Room containing a footprint given in placement (half-cell) units. A footprint may
         * ride half a cell above the room's top row so furniture can nestle against the
         * north wall the way tall sprites (beds, shelves) visually do.
         */
        fun roomContainingP(rooms: List<HouseRoom>, px: Int, py: Int, fw: Int, fh: Int): HouseRoom? =
            rooms.firstOrNull {
                px >= it.x * SUB && py >= it.y * SUB - 1 &&
                    px + fw * SUB <= (it.x + it.w) * SUB && py + fh * SUB <= (it.y + it.h) * SUB
            }

        /**
         * True when every half-cell of the footprint lies inside SOME room, letting
         * furniture straddle the seam between adjacent rooms. Each room also grants the
         * half-cell just above its top row (the lean-against-the-north-wall allowance).
         */
        fun footprintCovered(rooms: List<HouseRoom>, px: Int, py: Int, fw: Int, fh: Int): Boolean =
            (px until px + fw * SUB).all { cx ->
                (py until py + fh * SUB).all { cy ->
                    rooms.any { r ->
                        cx >= r.x * SUB && cx < (r.x + r.w) * SUB &&
                            cy >= r.y * SUB - 1 && cy < (r.y + r.h) * SUB
                    }
                }
            }

        /** [hasWallAbove] for a footprint given in placement (half-cell) units. */
        fun hasWallAboveP(rooms: List<HouseRoom>, room: HouseRoom, px: Int, fw: Int): Boolean {
            val startCol = px / SUB
            val endCol = (px + fw * SUB - 1) / SUB
            return hasWallAbove(rooms, room, startCol, endCol - startCol + 1)
        }

        /**
         * True when every column of a footprint on [room]'s top row has a visible north wall:
         * walls render only on the northernmost room of each column, so any other room lying
         * anywhere north of this one in that column removes the wall.
         */
        fun hasWallAbove(rooms: List<HouseRoom>, room: HouseRoom, x: Int, fw: Int): Boolean =
            (x until x + fw).all { cx ->
                rooms.none { other ->
                    other !== room && cx >= other.x && cx < other.x + other.w &&
                        other.y < room.y
                }
            }
    }

    /**
     * Room a placement belongs to. Edge-anchored walls render on their cell's top/left
     * grid line, so they may also hug a room's south/east side with their footprint
     * just outside it; the shifted check covers that.
     */
    fun containingRoomFor(house: HouseData, def: HouseTileDef, x: Int, y: Int): HouseRoom? {
        roomContainingP(house.rooms, x, y, def.footprintW, def.footprintH)?.let { return it }
        return when (def.anchor) {
            "top_edge" -> roomContainingP(house.rooms, x, y - def.footprintH * SUB, def.footprintW, def.footprintH)
            "left_edge" -> roomContainingP(house.rooms, x - def.footprintW * SUB, y, def.footprintW, def.footprintH)
            else -> null
        }
    }

    fun tileDef(key: String): HouseTileDef? =
        if (key.startsWith(BANNER_PREFIX)) BANNER_DEF else gameData.houseTiles.items[key]

    /** Cost of the next room purchase, or null when all rooms are owned. */
    fun nextRoomCost(roomsOwned: Int): HouseCostTier? =
        gameData.houseTiles.rooms.getOrNull(roomsOwned - 1)

    /** Per-new-cell expansion cost for the room at [roomIndex]. */
    fun expansionCost(roomIndex: Int): HouseCostTier =
        gameData.houseTiles.expansion.let { it.getOrNull(roomIndex) ?: it.last() }

    /** Creates the free starter room on first visit, and migrates coordinate scale. */
    suspend fun ensureStarterRoom() = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val house = flags.house
        if (house == null) {
            val offset = (GRID_SIZE - STARTER_ROOM_SIZE) / 2
            val starter = HouseRoom(offset, offset, STARTER_ROOM_SIZE, STARTER_ROOM_SIZE)
            playerRepo.updateFlagsUnlocked(flags.copy(
                house = HouseData(rooms = listOf(starter), coordScale = SUB)))
        } else if (house.coordScale < SUB) {
            // Legacy full-cell placement coordinates become half-cell units.
            val f = SUB / house.coordScale
            playerRepo.updateFlagsUnlocked(flags.copy(house = house.copy(
                placements = house.placements.map { it.copy(x = it.x * f, y = it.y * f) },
                coordScale = SUB,
            )))
        }
    }

    // ------------------------------------------------------------------ draft lifecycle

    /** Identity draft over the built house, used when no draft exists yet. */
    private fun draftFor(flags: PlayerFlags): HouseDraft? {
        val built = flags.house ?: return null
        return flags.houseDraft ?: HouseDraft(built, List(built.rooms.size) { it })
    }

    private suspend fun saveDraft(draft: HouseDraft) {
        val flags = playerRepo.getFlagsUnlocked()
        playerRepo.updateFlagsUnlocked(flags.copy(houseDraft = draft))
    }

    /** Ensures a persisted draft exists when the editor opens. */
    suspend fun beginEdit() = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        if (flags.houseDraft == null) draftFor(flags)?.let { saveDraft(it) }
    }

    /** Throws away all unpurchased changes. */
    suspend fun discardDraft() = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        if (flags.houseDraft != null) playerRepo.updateFlagsUnlocked(flags.copy(houseDraft = null))
    }

    /** True when the draft layout differs from the built house. */
    fun hasLayoutChanges(built: HouseData, layout: HouseData): Boolean =
        layout.rooms != built.rooms || layout.placements != built.placements ||
            layout.ground != built.ground

    // ------------------------------------------------------------------ draft edit ops

    suspend fun buyRoom(x: Int, y: Int): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout
        // Level requirements gate the purchase, not the draft.
        nextRoomCost(layout.rooms.size) ?: return@withLock HouseActionResult.MAX_ROOMS
        val size = NEW_ROOM_SIZE

        if (x < 0 || y < 0 || x + size > GRID_SIZE || y + size > GRID_SIZE)
            return@withLock HouseActionResult.INVALID_SPOT
        if (layout.rooms.any { roomsOverlap(it, x, y, size, size) })
            return@withLock HouseActionResult.INVALID_SPOT
        if (layout.rooms.none { sharesEdge(it, x, y, size, size) })
            return@withLock HouseActionResult.INVALID_SPOT

        saveDraft(draft.copy(
            layout = layout.copy(rooms = layout.rooms + HouseRoom(x, y, size, size)),
            builtRoomIndex = draft.builtRoomIndex + null,
        ))
        HouseActionResult.SUCCESS
    }

    suspend fun expandRoom(roomIndex: Int, dir: HouseDirection): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout
        val room = layout.rooms.getOrNull(roomIndex) ?: return@withLock HouseActionResult.INVALID_SPOT

        val (nx, ny, nw, nh) = when (dir) {
            HouseDirection.NORTH -> listOf(room.x, room.y - 1, room.w, room.h + 1)
            HouseDirection.SOUTH -> listOf(room.x, room.y, room.w, room.h + 1)
            HouseDirection.WEST  -> listOf(room.x - 1, room.y, room.w + 1, room.h)
            HouseDirection.EAST  -> listOf(room.x, room.y, room.w + 1, room.h)
        }
        if (nw > MAX_ROOM_DIM || nh > MAX_ROOM_DIM) return@withLock HouseActionResult.MAX_ROOM_SIZE
        if (nx < 0 || ny < 0 || nx + nw > GRID_SIZE || ny + nh > GRID_SIZE)
            return@withLock HouseActionResult.INVALID_SPOT
        if (layout.rooms.filterIndexed { i, _ -> i != roomIndex }
                .any { roomsOverlap(it, nx, ny, nw, nh) })
            return@withLock HouseActionResult.INVALID_SPOT
        val updatedRooms = layout.rooms.toMutableList()
        updatedRooms[roomIndex] = room.copy(x = nx, y = ny, w = nw, h = nh)
        saveDraft(draft.copy(layout = layout.copy(rooms = updatedRooms)))
        HouseActionResult.SUCCESS
    }

    /**
     * Shrinks a room by one row/column from the [dir] side. Cells removed below the built
     * size become a credit line on the bill; furnishings in the removed strip leave the draft.
     */
    suspend fun shrinkRoom(roomIndex: Int, dir: HouseDirection): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout
        val room = layout.rooms.getOrNull(roomIndex) ?: return@withLock HouseActionResult.INVALID_SPOT

        val (nx, ny, nw, nh) = when (dir) {
            HouseDirection.NORTH -> listOf(room.x, room.y + 1, room.w, room.h - 1)
            HouseDirection.SOUTH -> listOf(room.x, room.y, room.w, room.h - 1)
            HouseDirection.WEST  -> listOf(room.x + 1, room.y, room.w - 1, room.h)
            HouseDirection.EAST  -> listOf(room.x, room.y, room.w - 1, room.h)
        }
        if (nw < NEW_ROOM_SIZE || nh < NEW_ROOM_SIZE) return@withLock HouseActionResult.MIN_ROOM_SIZE

        val newRoom = room.copy(x = nx, y = ny, w = nw, h = nh)
        val keptPlacements = layout.placements.filter { p ->
            !placementInRoom(p, room) || placementInRoom(p, newRoom)
        }
        val updatedRooms = layout.rooms.toMutableList()
        updatedRooms[roomIndex] = newRoom
        saveDraft(draft.copy(layout = dropOrphanedPlacements(
            layout.copy(rooms = updatedRooms, placements = keptPlacements))))
        HouseActionResult.SUCCESS
    }

    /** True when the placement's footprint (half-cell units) lies inside [room]. */
    private fun placementInRoom(p: HousePlacement, room: HouseRoom): Boolean {
        val def = tileDef(p.item) ?: return false
        fun inRect(x: Int, y: Int): Boolean =
            // Matches roomContainingP: half-cell overhang into the north wall is allowed.
            x >= room.x * SUB && y >= room.y * SUB - 1 &&
                x + def.footprintW * SUB <= (room.x + room.w) * SUB &&
                y + def.footprintH * SUB <= (room.y + room.h) * SUB
        if (inRect(p.x, p.y)) return true
        // Edge-anchored walls hugging the room's south/east side belong to it too.
        return when (def.anchor) {
            "top_edge" -> inRect(p.x, p.y - def.footprintH * SUB)
            "left_edge" -> inRect(p.x - def.footprintW * SUB, p.y)
            else -> false
        }
    }

    /**
     * Moves a room and everything in it to a new top-left cell. Free. Wall decor anywhere
     * in the house that loses its wall (a room now sits directly above it) leaves the draft.
     */
    suspend fun moveRoom(roomIndex: Int, nx: Int, ny: Int): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout
        val room = layout.rooms.getOrNull(roomIndex) ?: return@withLock HouseActionResult.INVALID_SPOT

        if (nx < 0 || ny < 0 || nx + room.w > GRID_SIZE || ny + room.h > GRID_SIZE)
            return@withLock HouseActionResult.INVALID_SPOT
        val others = layout.rooms.filterIndexed { i, _ -> i != roomIndex }
        if (others.any { roomsOverlap(it, nx, ny, room.w, room.h) })
            return@withLock HouseActionResult.INVALID_SPOT
        if (others.isNotEmpty() && others.none { sharesEdge(it, nx, ny, room.w, room.h) })
            return@withLock HouseActionResult.INVALID_SPOT

        val dx = (nx - room.x) * SUB
        val dy = (ny - room.y) * SUB
        val movedRooms = layout.rooms.toMutableList()
        movedRooms[roomIndex] = room.copy(x = nx, y = ny)
        val moved = layout.copy(
            rooms = movedRooms,
            placements = layout.placements.map { p ->
                if (placementInRoom(p, room)) p.copy(x = p.x + dx, y = p.y + dy) else p
            },
        )
        saveDraft(draft.copy(layout = dropOrphanedPlacements(moved)))
        HouseActionResult.SUCCESS
    }

    /**
     * Demolishes a room from the draft: its furnishings leave the layout (built pieces
     * return to storage at purchase), the room's build cost is not refunded. The last
     * room cannot be demolished.
     */
    suspend fun removeRoom(roomIndex: Int): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout
        if (layout.rooms.size <= 1) return@withLock HouseActionResult.LAST_ROOM
        val room = layout.rooms.getOrNull(roomIndex) ?: return@withLock HouseActionResult.INVALID_SPOT

        val keptPlacements = layout.placements.filter { p -> !placementInRoom(p, room) }
        saveDraft(draft.copy(
            layout = dropOrphanedPlacements(layout.copy(
                rooms = layout.rooms.filterIndexed { i, _ -> i != roomIndex },
                placements = keptPlacements,
            )),
            builtRoomIndex = draft.builtRoomIndex.filterIndexed { i, _ -> i != roomIndex },
        ))
        HouseActionResult.SUCCESS
    }

    /**
     * Drops placements whose support disappeared: wall decor without a wall (a room moved
     * above it) and table decorations without a table underneath. Built pieces dropped here
     * come back as storage at purchase via the count diff.
     */
    private fun dropOrphanedPlacements(house: HouseData): HouseData {
        val kept = house.placements.filter { p ->
            val def = tileDef(p.item) ?: return@filter true
            when {
                def.wallMounted -> {
                    val room = roomContainingP(house.rooms, p.x, p.y, def.footprintW, def.footprintH)
                    room != null && p.y == room.y * SUB &&
                        hasWallAboveP(house.rooms, room, p.x, def.footprintW)
                }
                def.tableOnly -> tableCovered(house, p.x, p.y, def.footprintW, def.footprintH)
                else -> containingRoomFor(house, def, p.x, p.y) != null ||
                    footprintCovered(house.rooms, p.x, p.y, def.footprintW, def.footprintH)
            }
        }
        return house.copy(placements = kept)
    }

    suspend fun setFloor(roomIndex: Int, style: String): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout
        val room = layout.rooms.getOrNull(roomIndex) ?: return@withLock HouseActionResult.INVALID_SPOT
        if (style == "brick" && constructionLevel() < BRICK_FLOOR_LEVEL)
            return@withLock HouseActionResult.INSUFFICIENT_LEVEL
        val updatedRooms = layout.rooms.toMutableList()
        updatedRooms[roomIndex] = room.copy(floor = style)
        saveDraft(draft.copy(layout = layout.copy(rooms = updatedRooms)))
        HouseActionResult.SUCCESS
    }

    /**
     * Drafts [key] with its footprint's top-left cell at (x, y). Free while drafting; the
     * bill charges only for copies beyond what is already built or stored.
     */
    suspend fun placeItem(key: String, x: Int, y: Int): HouseActionResult = playerRepo.withLock {
        val def = tileDef(key) ?: return@withLock HouseActionResult.INVALID_SPOT
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout

        val room = containingRoomFor(layout, def, x, y)
        if (def.wallMounted) {
            if (room == null || y != room.y * SUB || !hasWallAboveP(layout.rooms, room, x, def.footprintW))
                return@withLock HouseActionResult.INVALID_SPOT
        } else if (room == null && !footprintCovered(layout.rooms, x, y, def.footprintW, def.footprintH)) {
            return@withLock HouseActionResult.INVALID_SPOT
        }
        if (def.tableOnly && !tableCovered(layout, x, y, def.footprintW, def.footprintH))
            return@withLock HouseActionResult.INVALID_SPOT
        if (collides(layout, def, x, y)) return@withLock HouseActionResult.INVALID_SPOT

        if (key.startsWith(BANNER_PREFIX)) {
            // Earned seasonal banners are free trophies: one placement per banner, no cost.
            val icon = key.removePrefix(BANNER_PREFIX)
            if (flags.seasonalBannersEarned.none { it.bannerIcon == icon })
                return@withLock HouseActionResult.INVALID_SPOT
            if (layout.placements.any { it.item == key })
                return@withLock HouseActionResult.ALREADY_PLACED
        }
        // Item level requirements gate the purchase (bill.requiredLevel), not the draft.

        saveDraft(draft.copy(layout = layout.copy(
            placements = layout.placements + HousePlacement(key, x, y))))
        HouseActionResult.SUCCESS
    }

    /** Moves the placement at [index] to (x, y). Free. */
    suspend fun moveItem(index: Int, x: Int, y: Int): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout
        val placement = layout.placements.getOrNull(index) ?: return@withLock HouseActionResult.INVALID_SPOT
        val def = tileDef(placement.item) ?: return@withLock HouseActionResult.INVALID_SPOT

        val room = containingRoomFor(layout, def, x, y)
        if (def.wallMounted) {
            if (room == null || y != room.y * SUB || !hasWallAboveP(layout.rooms, room, x, def.footprintW))
                return@withLock HouseActionResult.INVALID_SPOT
        } else if (room == null && !footprintCovered(layout.rooms, x, y, def.footprintW, def.footprintH)) {
            return@withLock HouseActionResult.INVALID_SPOT
        }
        if (def.tableOnly && !tableCovered(layout, x, y, def.footprintW, def.footprintH))
            return@withLock HouseActionResult.INVALID_SPOT
        if (collides(layout, def, x, y, excludeIndex = index)) return@withLock HouseActionResult.INVALID_SPOT

        val updated = layout.placements.toMutableList()
        updated[index] = placement.copy(x = x, y = y)
        saveDraft(draft.copy(layout = dropOrphanedPlacements(layout.copy(placements = updated))))
        HouseActionResult.SUCCESS
    }

    /** Swaps the placement at [index] to its rotated variant, keeping its top-left cell. */
    suspend fun rotateItem(index: Int): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout
        val placement = layout.placements.getOrNull(index) ?: return@withLock HouseActionResult.INVALID_SPOT
        val rotatedKey = tileDef(placement.item)?.rotatesTo ?: return@withLock HouseActionResult.INVALID_SPOT
        val def = tileDef(rotatedKey) ?: return@withLock HouseActionResult.INVALID_SPOT

        val room = containingRoomFor(layout, def, placement.x, placement.y)
        if (def.wallMounted) {
            if (room == null || placement.y != room.y * SUB ||
                !hasWallAboveP(layout.rooms, room, placement.x, def.footprintW))
                return@withLock HouseActionResult.INVALID_SPOT
        } else if (room == null &&
            !footprintCovered(layout.rooms, placement.x, placement.y, def.footprintW, def.footprintH)) {
            return@withLock HouseActionResult.INVALID_SPOT
        }
        if (def.tableOnly && !tableCovered(layout, placement.x, placement.y, def.footprintW, def.footprintH))
            return@withLock HouseActionResult.INVALID_SPOT
        if (collides(layout, def, placement.x, placement.y, excludeIndex = index))
            return@withLock HouseActionResult.INVALID_SPOT

        val updated = layout.placements.toMutableList()
        updated[index] = placement.copy(item = rotatedKey)
        saveDraft(draft.copy(layout = dropOrphanedPlacements(layout.copy(placements = updated))))
        HouseActionResult.SUCCESS
    }

    /** Sets the outdoor ground texture in the draft. Free, cosmetic. */
    suspend fun setGround(key: String): HouseActionResult = playerRepo.withLock {
        if (key !in gameData.houseTiles.grounds) return@withLock HouseActionResult.INVALID_SPOT
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        saveDraft(draft.copy(layout = draft.layout.copy(ground = key)))
        HouseActionResult.SUCCESS
    }

    /** Removes the placement at [index] from the draft; built pieces return to storage at purchase. */
    suspend fun removeItem(index: Int): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val draft = draftFor(flags) ?: return@withLock HouseActionResult.INVALID_SPOT
        val layout = draft.layout
        if (layout.placements.getOrNull(index) == null) return@withLock HouseActionResult.INVALID_SPOT
        saveDraft(draft.copy(layout = dropOrphanedPlacements(layout.copy(
            placements = layout.placements.filterIndexed { i, _ -> i != index }))))
        HouseActionResult.SUCCESS
    }

    // ------------------------------------------------------------------ bill + purchase

    /** Copies of [key] the draft can still place for free (built plus stored, minus drafted). */
    fun freeUnits(built: HouseData, layout: HouseData, key: String): Int =
        built.placements.count { it.item == key } + (built.storage[key] ?: 0) -
            layout.placements.count { it.item == key }

    /** Draft-side storage view: what the storage panel should show while editing. */
    fun draftStorageView(built: HouseData, layout: HouseData): Map<String, Int> {
        val keys = built.storage.keys + built.placements.map { it.item }
        return keys.filterNot { it.startsWith(BANNER_PREFIX) }
            .associateWith { freeUnits(built, layout, it) }
            .filterValues { it > 0 }
    }

    /** Indices of draft placements that are unpurchased (rendered ghosted). */
    fun ghostPlacements(built: HouseData, layout: HouseData): Set<Int> {
        val remaining = mutableMapOf<String, Int>()
        val ghosts = mutableSetOf<Int>()
        layout.placements.forEachIndexed { i, p ->
            if (p.item.startsWith(BANNER_PREFIX)) return@forEachIndexed
            val left = remaining.getOrPut(p.item) {
                built.placements.count { it.item == p.item } + (built.storage[p.item] ?: 0)
            }
            if (left <= 0) ghosts += i else remaining[p.item] = left - 1
        }
        return ghosts
    }

    /** Room-cell rects that are unpurchased draft area (rendered tinted). */
    fun draftRoomTints(built: HouseData, draft: HouseDraft): List<HouseRoom> =
        draft.layout.rooms.withIndex().flatMap { (i, r) ->
            val b = draft.builtRoomIndex.getOrNull(i)?.let { built.rooms.getOrNull(it) }
            when {
                b == null -> listOf(r)
                r.x == b.x && r.y == b.y -> buildList {
                    // Same anchor: tint only the strips outside the built rect.
                    val ix2 = minOf(r.x + r.w, b.x + b.w)
                    val iy2 = minOf(r.y + r.h, b.y + b.h)
                    if (r.y + r.h > iy2) add(HouseRoom(r.x, iy2, r.w, r.y + r.h - iy2))
                    if (r.x + r.w > ix2) add(HouseRoom(ix2, r.y, r.x + r.w - ix2, iy2 - r.y))
                }
                r.w * r.h > b.w * b.h -> listOf(r) // moved and grown: tint the whole room
                else -> emptyList()
            }
        }

    /**
     * Prices the diff between the built house and [draft]. Pure: callable from UI state with
     * the player's current Construction level and builder discount.
     */
    fun computeBill(built: HouseData, draft: HouseDraft, level: Int, perMille: Int): HouseBill {
        val lines = mutableListOf<HouseBillLine>()
        fun tierLine(kind: HouseBillLine.Kind, tier: HouseCostTier, units: Int,
                     itemKey: String? = null, roomNumber: Int = 0) = HouseBillLine(
            kind = kind, itemKey = itemKey, roomNumber = roomNumber, units = units,
            coins = TownRepository.discountedCoins(tier.coins * units, level, perMille),
            materials = TownRepository.discountedMaterials(
                tier.materials.mapValues { it.value * units }, level, perMille),
            xp = tier.xp * units,
            level = tier.level,
        )

        // Rooms: kept ones may have grown or shrunk; extra ones are bought sequentially.
        val keptBuilt = draft.builtRoomIndex.filterNotNull().toSet()
        var owned = built.rooms.size - (built.rooms.indices.count { it !in keptBuilt })
        draft.layout.rooms.forEachIndexed { i, room ->
            val builtIdx = draft.builtRoomIndex.getOrNull(i)
            val builtRoom = builtIdx?.let { built.rooms.getOrNull(it) }
            if (builtRoom == null) {
                val tier = nextRoomCost(owned) ?: gameData.houseTiles.rooms.last()
                lines += tierLine(HouseBillLine.Kind.NEW_ROOM, tier, 1, roomNumber = i + 1)
                owned++
            } else {
                val delta = room.w * room.h - builtRoom.w * builtRoom.h
                if (delta > 0) {
                    lines += tierLine(HouseBillLine.Kind.EXPAND, expansionCost(builtIdx), delta, roomNumber = i + 1)
                } else if (delta < 0) {
                    // Mirrors the pre-draft shrink refund: same per-cell rate, as a credit.
                    lines += tierLine(HouseBillLine.Kind.SHRINK_CREDIT, expansionCost(builtIdx), -delta, roomNumber = i + 1)
                }
            }
        }

        // Items: pay only for copies beyond built + stored, per key.
        draft.layout.placements.map { it.item }.distinct()
            .filterNot { it.startsWith(BANNER_PREFIX) }
            .sorted()
            .forEach { key ->
                val buys = -freeUnits(built, draft.layout, key)
                if (buys <= 0) return@forEach
                val def = tileDef(key) ?: return@forEach
                lines += tierLine(
                    HouseBillLine.Kind.ITEM,
                    HouseCostTier(def.levelRequired, def.coinCost, def.materials, def.xp),
                    buys, itemKey = key,
                )
            }

        val charges = lines.filter { it.kind != HouseBillLine.Kind.SHRINK_CREDIT }
        val credits = lines.filter { it.kind == HouseBillLine.Kind.SHRINK_CREDIT }
        fun List<HouseBillLine>.mergedMaterials(): Map<String, Int> =
            flatMap { it.materials.entries }.groupingBy { it.key }.fold(0) { acc, e -> acc + e.value }
        return HouseBill(
            lines = lines,
            coins = charges.sumOf { it.coins },
            materials = charges.mergedMaterials(),
            creditCoins = credits.sumOf { it.coins },
            creditMaterials = credits.mergedMaterials(),
            xp = charges.sumOf { it.xp },
            requiredLevel = charges.maxOfOrNull { it.level } ?: 1,
        )
    }

    /**
     * Pays the whole bill atomically and replaces the built house with the draft layout.
     * Built pieces no longer placed in the draft land in storage; drafted copies consume
     * storage stock before any are bought.
     */
    suspend fun purchaseBuild(): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val built = flags.house ?: return@withLock HouseActionResult.INVALID_SPOT
        val draft = flags.houseDraft ?: return@withLock HouseActionResult.INVALID_SPOT
        val level = constructionLevel()
        val perMille = boostRepo.builderDiscountPerMille(flags)
        val bill = computeBill(built, draft, level, perMille)

        if (level < bill.requiredLevel) return@withLock HouseActionResult.INSUFFICIENT_LEVEL
        val netCoins = bill.netCoins
        val player = playerRepo.getOrCreatePlayer()
        if (netCoins > 0 && player.coins < netCoins) return@withLock HouseActionResult.INSUFFICIENT_COINS
        val netMaterials = bill.netMaterials()
        val inventory = playerRepo.getInventoryUnlocked()
        for ((item, qty) in netMaterials) {
            if (qty > 0 && (inventory[item] ?: 0) < qty) return@withLock HouseActionResult.INSUFFICIENT_MATERIALS
        }

        val consumed = netMaterials.filterValues { it > 0 }
        val returned = netMaterials.filterValues { it < 0 }.mapValues { -it.value }
        if (consumed.isNotEmpty()) playerRepo.consumeItemsUnlocked(consumed)
        if (netCoins > 0) playerRepo.spendCoinsUnlocked(netCoins)
        if (bill.xp > 0 || returned.isNotEmpty() || netCoins < 0) {
            playerRepo.applyMultiSkillResultsUnlocked(
                xpPerSkill = if (bill.xp > 0) mapOf(Skills.CONSTRUCTION to bill.xp) else emptyMap(),
                itemsGained = returned,
                coinsGained = if (netCoins < 0) -netCoins else 0L,
            )
        }

        // Leftover free units per key become the new storage.
        val keys = built.storage.keys + built.placements.map { it.item }
        val storage = keys.filterNot { it.startsWith(BANNER_PREFIX) }
            .associateWith { freeUnits(built, draft.layout, it) }
            .filterValues { it > 0 }

        val latest = playerRepo.getFlagsUnlocked()
        playerRepo.updateFlagsUnlocked(latest.copy(
            house = draft.layout.copy(storage = storage, coordScale = SUB),
            houseDraft = null,
        ))
        HouseActionResult.SUCCESS
    }

    // ------------------------------------------------------------------ blueprints

    /** Saves the current draft layout (or the built house when the editor is clean) to [slot]. */
    suspend fun saveBlueprint(slot: Int, name: String): HouseActionResult = playerRepo.withLock {
        if (slot !in 0 until BLUEPRINT_SLOTS) return@withLock HouseActionResult.INVALID_SPOT
        val flags = playerRepo.getFlagsUnlocked()
        val layout = (flags.houseDraft?.layout ?: flags.house)?.copy(storage = emptyMap())
            ?: return@withLock HouseActionResult.INVALID_SPOT
        val blueprints = flags.houseBlueprints.filter { it.slot != slot } +
            HouseBlueprint(slot, name, layout)
        playerRepo.updateFlagsUnlocked(flags.copy(houseBlueprints = blueprints.sortedBy { it.slot }))
        HouseActionResult.SUCCESS
    }

    /**
     * Loads the blueprint at [slot] into the draft, replacing any unpurchased changes.
     * Built rooms are matched to blueprint rooms (exact rect first, then best overlap) so the
     * bill only charges for what is genuinely new. Returns the number of placements dropped
     * because their item or banner no longer exists, or null when the slot is empty.
     */
    suspend fun loadBlueprint(slot: Int): Int? = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        val built = flags.house ?: return@withLock null
        val bp = flags.houseBlueprints.firstOrNull { it.slot == slot } ?: return@withLock null

        val earned = flags.seasonalBannersEarned.mapNotNull { it.bannerIcon }.toSet()
        val seenBanners = mutableSetOf<String>()
        val placements = bp.layout.placements.filter { p ->
            if (tileDef(p.item) == null) return@filter false
            if (!p.item.startsWith(BANNER_PREFIX)) return@filter true
            val icon = p.item.removePrefix(BANNER_PREFIX)
            icon in earned && seenBanners.add(icon)
        }
        val dropped = bp.layout.placements.size - placements.size
        val layout = dropOrphanedPlacements(
            bp.layout.copy(placements = placements, storage = emptyMap(), coordScale = SUB))
        playerRepo.updateFlagsUnlocked(flags.copy(
            houseDraft = HouseDraft(layout, matchRooms(built.rooms, layout.rooms))))
        dropped
    }

    suspend fun deleteBlueprint(slot: Int): HouseActionResult = playerRepo.withLock {
        val flags = playerRepo.getFlagsUnlocked()
        playerRepo.updateFlagsUnlocked(flags.copy(
            houseBlueprints = flags.houseBlueprints.filter { it.slot != slot }))
        HouseActionResult.SUCCESS
    }

    /** Built-room index for each draft room: exact rect matches first, then best overlap. */
    private fun matchRooms(builtRooms: List<HouseRoom>, draftRooms: List<HouseRoom>): List<Int?> {
        val result = arrayOfNulls<Int>(draftRooms.size)
        val used = mutableSetOf<Int>()
        draftRooms.forEachIndexed { i, r ->
            val exact = builtRooms.indices.firstOrNull { b ->
                b !in used && builtRooms[b].x == r.x && builtRooms[b].y == r.y &&
                    builtRooms[b].w == r.w && builtRooms[b].h == r.h
            }
            if (exact != null) { result[i] = exact; used += exact }
        }
        draftRooms.forEachIndexed { i, r ->
            if (result[i] != null) return@forEachIndexed
            val best = builtRooms.indices.filter { it !in used }.maxByOrNull { b ->
                val o = builtRooms[b]
                val w = minOf(r.x + r.w, o.x + o.w) - maxOf(r.x, o.x)
                val h = minOf(r.y + r.h, o.y + o.h) - maxOf(r.y, o.y)
                if (w > 0 && h > 0) w * h else 0
            }?.takeIf { b ->
                val o = builtRooms[b]
                val w = minOf(r.x + r.w, o.x + o.w) - maxOf(r.x, o.x)
                val h = minOf(r.y + r.h, o.y + o.h) - maxOf(r.y, o.y)
                w > 0 && h > 0
            }
            if (best != null) { result[i] = best; used += best }
        }
        // Any still-unmatched built rooms pair up with unmatched draft rooms arbitrarily:
        // an owned room should never be "demolished" while a same-count draft buys a new one.
        val leftoverBuilt = builtRooms.indices.filter { it !in used }.toMutableList()
        result.forEachIndexed { i, v ->
            if (v == null && leftoverBuilt.isNotEmpty()) {
                result[i] = leftoverBuilt.removeAt(0)
            }
        }
        return result.toList()
    }

    /**
     * Collision layer: floor furniture (1), wall decor (2), and each divider orientation
     * (3, 4) collide only among themselves — dividers occupy grid lines, not cell space,
     * so furniture can stand beside them and the two orientations can meet in corners.
     */
    private fun layerOf(def: HouseTileDef): Int = when {
        def.wallMounted -> 2
        def.anchor == "left_edge" -> 3
        def.anchor == "top_edge" -> 4
        def.tableOnly -> 5
        else -> 1
    }

    /** True when every half-cell of the footprint is covered by a placed table. */
    fun tableCovered(house: HouseData, x: Int, y: Int, fw: Int, fh: Int): Boolean {
        val tables = house.placements.filter { it.item.startsWith("table_") }
        return (x until x + fw * SUB).all { cx ->
            (y until y + fh * SUB).all { cy ->
                tables.any { t ->
                    val td = tileDef(t.item) ?: return@any false
                    cx >= t.x && cx < t.x + td.footprintW * SUB &&
                        cy >= t.y && cy < t.y + td.footprintH * SUB
                }
            }
        }
    }

    /** True when the footprint would overlap another placement on the same layer. */
    fun collides(house: HouseData, def: HouseTileDef, x: Int, y: Int, excludeIndex: Int? = null): Boolean =
        house.placements.withIndex().any { (i, other) ->
            if (i == excludeIndex) return@any false
            val otherDef = tileDef(other.item) ?: return@any false
            if (layerOf(otherDef) != layerOf(def)) return@any false
            other.x < x + def.footprintW * SUB && x < other.x + otherDef.footprintW * SUB &&
                other.y < y + def.footprintH * SUB && y < other.y + otherDef.footprintH * SUB
        }

    private suspend fun constructionLevel(): Int =
        playerRepo.getSkillLevels()[Skills.CONSTRUCTION] ?: 1
}
