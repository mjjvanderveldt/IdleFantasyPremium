package com.fantasyidler.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.data.json.HouseCostTier
import com.fantasyidler.data.json.HouseTileDef
import com.fantasyidler.data.model.HouseBlueprint
import com.fantasyidler.data.model.HouseData
import com.fantasyidler.data.model.HouseRoom
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.SeasonalBannerEarned
import com.fantasyidler.data.model.Skills
import com.fantasyidler.repository.BoostRepository
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.HouseActionResult
import com.fantasyidler.repository.HouseBill
import com.fantasyidler.repository.HouseDirection
import com.fantasyidler.repository.HouseRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.TownRepository
import com.fantasyidler.util.GameStrings
import com.fantasyidler.util.formatCoins
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** What a tap/drag on the grid currently does. */
sealed class HouseEditMode {
    object Select : HouseEditMode()
    data class PlaceItem(val key: String) : HouseEditMode()
    object PlaceRoom : HouseEditMode()
    data class MoveRoom(val index: Int) : HouseEditMode()
}

data class HouseUiState(
    val isLoading: Boolean = true,
    val house: HouseData = HouseData(),
    val constructionLevel: Int = 1,
    val coins: Long = 0L,
    val inventory: Map<String, Int> = emptyMap(),
    val discountPerMille: Int = 0,
    val mode: HouseEditMode = HouseEditMode.Select,
    /** Placement index selected in Select mode, or null. */
    val selectedPlacement: Int? = null,
    /** Room index selected in Select mode (tap on empty floor), or null. */
    val selectedRoom: Int? = null,
    /** Placement index being nudged with the on-canvas arrow pad, or null. */
    val nudgeIndex: Int? = null,
    /** Seasonal event banners earned by this character (placeable wall trophies). */
    val earnedBanners: List<SeasonalBannerEarned> = emptyList(),
    /** True while the outdoor-ground picker sheet is open (tap empty ground to open). */
    val groundPickerOpen: Boolean = false,
    /** True while the editor is open; [house] is then the draft layout, not the built house. */
    val editing: Boolean = false,
    /** True when the draft layout differs from the built house. */
    val hasDraftChanges: Boolean = false,
    /** Priced diff between built house and draft; null outside the editor. */
    val bill: HouseBill? = null,
    /** Draft placement indices that are unpurchased (rendered ghosted). */
    val ghostPlacements: Set<Int> = emptySet(),
    /** Room-cell rects of unpurchased draft area (rendered tinted). */
    val draftRoomTints: List<HouseRoom> = emptyList(),
    val blueprints: List<HouseBlueprint> = emptyList(),
    val billSheetOpen: Boolean = false,
    val blueprintSheetOpen: Boolean = false,
    val discardConfirmOpen: Boolean = false,
    /** Character appearance, for the resident shown in view mode. */
    val characterRace: String = "",
    val characterSkinTone: Int = 1,
    val characterHairStyle: Int = 1,
    val characterHairColor: String = "a",
    val characterEyeStyle: Int = 1,
    val characterBeardStyle: Int = 0,
    val characterBeardColor: String = "a",
    val snackbarMessage: String? = null,
) {
    val roomCount: Int get() = house.rooms.size
}

@HiltViewModel
class HouseViewModel @Inject constructor(
    val gameData: GameDataRepository,
    val houseRepo: HouseRepository,
    private val boostRepo: BoostRepository,
    private val playerRepo: PlayerRepository,
    @ApplicationContext private val context: Context,
    private val json: Json,
) : ViewModel() {

    private val _extra = MutableStateFlow(HouseUiState())

    init {
        viewModelScope.launch { houseRepo.ensureStarterRoom() }
    }

    val uiState: StateFlow<HouseUiState> = combine(
        playerRepo.playerFlow,
        _extra,
    ) { player, extra ->
        if (player == null) return@combine extra.copy(isLoading = true)
        val flags: PlayerFlags          = json.decodeFromString(player.flags)
        val levels: Map<String, Int>    = json.decodeFromString(player.skillLevels)
        val inventory: Map<String, Int> = json.decodeFromString(player.inventory)
        val built = flags.house ?: HouseData()
        val draft = flags.houseDraft
        val level = levels[Skills.CONSTRUCTION] ?: 1
        val perMille = boostRepo.builderDiscountPerMille(flags)
        val showDraft = extra.editing && draft != null
        extra.copy(
            isLoading         = flags.house == null,
            house             = if (showDraft) draft!!.layout.copy(
                                    storage = houseRepo.draftStorageView(built, draft.layout))
                                else built,
            hasDraftChanges   = draft != null && houseRepo.hasLayoutChanges(built, draft.layout),
            bill              = if (showDraft) houseRepo.computeBill(built, draft!!, level, perMille) else null,
            ghostPlacements   = if (showDraft) houseRepo.ghostPlacements(built, draft!!.layout) else emptySet(),
            draftRoomTints    = if (showDraft) houseRepo.draftRoomTints(built, draft!!) else emptyList(),
            blueprints        = flags.houseBlueprints,
            constructionLevel = level,
            coins             = player.coins,
            inventory         = inventory,
            discountPerMille  = perMille,
            earnedBanners     = flags.seasonalBannersEarned.filter { it.bannerIcon != null },
            characterRace     = flags.characterRace,
            characterSkinTone = flags.characterSkinTone,
            characterHairStyle = flags.characterHairStyle,
            characterHairColor = flags.characterHairColor,
            characterEyeStyle = flags.characterEyeStyle,
            characterBeardStyle = flags.characterBeardStyle,
            characterBeardColor = flags.characterBeardColor,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HouseUiState())

    // ------------------------------------------------------------------ costs

    /** Applies the builder discount so the UI shows exactly what will be paid. */
    fun discountedTier(tier: HouseCostTier, cells: Int, state: HouseUiState): HouseCostTier {
        val level = state.constructionLevel
        return tier.copy(
            coins = TownRepository.discountedCoins(tier.coins * cells, level, state.discountPerMille),
            materials = TownRepository.discountedMaterials(
                tier.materials.mapValues { it.value * cells }, level, state.discountPerMille),
        )
    }

    fun itemCost(def: HouseTileDef, state: HouseUiState): HouseCostTier =
        discountedTier(HouseCostTier(def.levelRequired, def.coinCost, def.materials, def.xp), 1, state)

    fun canAfford(tier: HouseCostTier, state: HouseUiState): Boolean =
        state.coins >= tier.coins &&
            tier.materials.all { (k, v) -> (state.inventory[k] ?: 0) >= v }

    // ------------------------------------------------------------------ edit modes

    fun enterPlaceItem(key: String) = _extra.update {
        it.copy(mode = HouseEditMode.PlaceItem(key), selectedPlacement = null, selectedRoom = null, nudgeIndex = null)
    }

    fun enterPlaceRoom() = _extra.update {
        it.copy(mode = HouseEditMode.PlaceRoom, selectedPlacement = null, selectedRoom = null, nudgeIndex = null)
    }

    fun cancelMode() = _extra.update {
        it.copy(mode = HouseEditMode.Select, selectedPlacement = null, selectedRoom = null, nudgeIndex = null)
    }

    fun select(placement: Int?, room: Int?) = _extra.update {
        // A tap on empty ground (both null) keeps the nudge pad up; picking
        // another piece or a room moves focus there and dismisses it.
        val keepNudge = placement == null && room == null
        it.copy(
            selectedPlacement = placement,
            selectedRoom      = room,
            nudgeIndex        = if (keepNudge) it.nudgeIndex else null,
        )
    }

    fun setGroundPickerOpen(open: Boolean) = _extra.update { it.copy(groundPickerOpen = open) }

    // ------------------------------------------------------------------ draft + bill

    fun setEditing(editing: Boolean) {
        _extra.update {
            it.copy(
                editing = editing, mode = HouseEditMode.Select,
                selectedPlacement = null, selectedRoom = null, nudgeIndex = null,
                billSheetOpen = false, blueprintSheetOpen = false, discardConfirmOpen = false,
            )
        }
        if (editing) viewModelScope.launch { houseRepo.beginEdit() }
    }

    fun setBillSheetOpen(open: Boolean) = _extra.update { it.copy(billSheetOpen = open) }
    fun setBlueprintSheetOpen(open: Boolean) = _extra.update { it.copy(blueprintSheetOpen = open) }
    fun setDiscardConfirmOpen(open: Boolean) = _extra.update { it.copy(discardConfirmOpen = open) }

    /** Whether the whole bill is purchasable: level requirement met, coins and materials covered. */
    fun canAffordBill(bill: HouseBill, state: HouseUiState): Boolean =
        state.constructionLevel >= bill.requiredLevel &&
            state.coins >= bill.netCoins &&
            bill.netMaterials().all { (k, v) -> v <= 0 || (state.inventory[k] ?: 0) >= v }

    fun purchaseBuild() {
        val hadCost = uiState.value.bill?.isEmpty == false
        viewModelScope.launch {
            report(houseRepo.purchaseBuild()) {
                _extra.update {
                    it.copy(
                        billSheetOpen = false, discardConfirmOpen = false,
                        snackbarMessage = context.getString(
                            if (hadCost) R.string.house_purchase_success else R.string.house_changes_applied),
                    )
                }
            }
        }
    }

    fun discardDraft() {
        viewModelScope.launch {
            houseRepo.discardDraft()
            houseRepo.beginEdit()
            _extra.update { it.copy(billSheetOpen = false, discardConfirmOpen = false) }
        }
    }

    // ------------------------------------------------------------------ blueprints

    fun saveBlueprint(slot: Int, name: String) {
        viewModelScope.launch {
            report(houseRepo.saveBlueprint(slot, name)) {
                _extra.update { it.copy(snackbarMessage = context.getString(R.string.house_blueprint_saved)) }
            }
        }
    }

    fun loadBlueprint(slot: Int) {
        viewModelScope.launch {
            val dropped = houseRepo.loadBlueprint(slot)
            val msg = when {
                dropped == null -> null
                dropped > 0 -> context.getString(R.string.house_blueprint_skipped, dropped)
                else -> context.getString(R.string.house_blueprint_loaded)
            }
            _extra.update { it.copy(blueprintSheetOpen = false, snackbarMessage = msg ?: it.snackbarMessage) }
        }
    }

    fun deleteBlueprint(slot: Int) {
        viewModelScope.launch { report(houseRepo.deleteBlueprint(slot)) {} }
    }

    // ------------------------------------------------------------------ actions

    fun placeAt(x: Int, y: Int) {
        val mode = _extra.value.mode
        viewModelScope.launch {
            when (mode) {
                is HouseEditMode.PlaceItem -> report(houseRepo.placeItem(mode.key, x, y)) {
                    // Stay in place mode so several copies can be placed in a row.
                }
                is HouseEditMode.PlaceRoom -> report(houseRepo.buyRoom(x, y)) {
                    _extra.update { it.copy(mode = HouseEditMode.Select) }
                }
                is HouseEditMode.MoveRoom -> report(houseRepo.moveRoom(mode.index, x, y)) {
                    _extra.update { it.copy(mode = HouseEditMode.Select) }
                }
                HouseEditMode.Select -> {}
            }
        }
    }

    fun removeSelected() {
        val index = _extra.value.selectedPlacement ?: return
        viewModelScope.launch {
            report(houseRepo.removeItem(index)) {
                _extra.update { it.copy(selectedPlacement = null) }
            }
        }
    }

    fun moveItem(index: Int, x: Int, y: Int) {
        viewModelScope.launch { report(houseRepo.moveItem(index, x, y)) {} }
    }

    fun enterNudge() {
        val index = _extra.value.selectedPlacement ?: return
        _extra.update { it.copy(selectedPlacement = null, selectedRoom = null, nudgeIndex = index) }
    }

    fun exitNudge() = _extra.update { it.copy(nudgeIndex = null) }

    /** Moves the nudged piece one placement unit; invalid targets are ignored like invalid drops. */
    fun nudgeSelected(dx: Int, dy: Int) {
        val index = _extra.value.nudgeIndex ?: return
        val placement = uiState.value.house.placements.getOrNull(index) ?: return
        val (tx, ty) = placement.x + dx to placement.y + dy
        if (canMoveTo(index, tx, ty)) moveItem(index, tx, ty)
    }

    fun rotateSelected() {
        val index = _extra.value.selectedPlacement ?: return
        viewModelScope.launch { report(houseRepo.rotateItem(index)) {} }
    }

    fun setGround(key: String) {
        viewModelScope.launch { report(houseRepo.setGround(key)) {} }
    }

    /** Ghost-preview validity when dragging the placement at [index] to half-cell (x, y). */
    fun canMoveTo(index: Int, x: Int, y: Int): Boolean {
        val house = uiState.value.house
        val placement = house.placements.getOrNull(index) ?: return false
        val def = houseRepo.tileDef(placement.item) ?: return false
        val room = houseRepo.containingRoomFor(house, def, x, y)
        if (def.wallMounted) {
            if (room == null || y != room.y * HouseRepository.SUB ||
                !HouseRepository.hasWallAboveP(house.rooms, room, x, def.footprintW)) return false
        } else if (room == null &&
            !HouseRepository.footprintCovered(house.rooms, x, y, def.footprintW, def.footprintH)) {
            return false
        }
        if (def.tableOnly && !houseRepo.tableCovered(house, x, y, def.footprintW, def.footprintH))
            return false
        return !houseRepo.collides(house, def, x, y, excludeIndex = index)
    }

    fun expandRoom(dir: HouseDirection) {
        val index = _extra.value.selectedRoom ?: return
        viewModelScope.launch { report(houseRepo.expandRoom(index, dir)) {} }
    }

    fun shrinkRoom(dir: HouseDirection) {
        val index = _extra.value.selectedRoom ?: return
        viewModelScope.launch { report(houseRepo.shrinkRoom(index, dir)) {} }
    }

    fun enterMoveRoom() {
        val index = _extra.value.selectedRoom ?: return
        _extra.update {
            it.copy(mode = HouseEditMode.MoveRoom(index), selectedPlacement = null, selectedRoom = null)
        }
    }

    fun demolishSelectedRoom() {
        val index = _extra.value.selectedRoom ?: return
        viewModelScope.launch {
            report(houseRepo.removeRoom(index)) {
                _extra.update { it.copy(selectedRoom = null) }
            }
        }
    }

    /** Ghost-preview validity when moving room [index] to (x, y). */
    fun canMoveRoomTo(index: Int, x: Int, y: Int): Boolean {
        val house = uiState.value.house
        val room = house.rooms.getOrNull(index) ?: return false
        if (x < 0 || y < 0 || x + room.w > HouseRepository.GRID_SIZE || y + room.h > HouseRepository.GRID_SIZE)
            return false
        val others = house.rooms.filterIndexed { i, _ -> i != index }
        if (others.any { HouseRepository.roomsOverlap(it, x, y, room.w, room.h) }) return false
        return others.isEmpty() || others.any { HouseRepository.sharesEdge(it, x, y, room.w, room.h) }
    }

    fun setFloor(style: String) {
        val index = _extra.value.selectedRoom ?: return
        viewModelScope.launch { report(houseRepo.setFloor(index, style)) {} }
    }

    /** Ghost-preview validity for furnishing placement at half-cell (x, y). */
    fun canPlaceItemAt(key: String, x: Int, y: Int): Boolean {
        val def = houseRepo.tileDef(key) ?: return false
        val house = uiState.value.house
        if (key.startsWith(HouseRepository.BANNER_PREFIX) && house.placements.any { it.item == key })
            return false
        val room = houseRepo.containingRoomFor(house, def, x, y)
        if (def.wallMounted) {
            if (room == null || y != room.y * HouseRepository.SUB ||
                !HouseRepository.hasWallAboveP(house.rooms, room, x, def.footprintW)) return false
        } else if (room == null &&
            !HouseRepository.footprintCovered(house.rooms, x, y, def.footprintW, def.footprintH)) {
            return false
        }
        if (def.tableOnly && !houseRepo.tableCovered(house, x, y, def.footprintW, def.footprintH))
            return false
        return !houseRepo.collides(house, def, x, y)
    }

    /** Ghost-preview validity for a new room at (x, y). */
    fun canPlaceRoomAt(x: Int, y: Int): Boolean {
        val house = uiState.value.house
        val s = HouseRepository.NEW_ROOM_SIZE
        if (x < 0 || y < 0 || x + s > HouseRepository.GRID_SIZE || y + s > HouseRepository.GRID_SIZE)
            return false
        if (house.rooms.any { HouseRepository.roomsOverlap(it, x, y, s, s) }) return false
        return house.rooms.any { HouseRepository.sharesEdge(it, x, y, s, s) }
    }

    fun snackbarConsumed() = _extra.update { it.copy(snackbarMessage = null) }

    fun itemDisplayName(key: String): String {
        // Hidden rotation variants share their base item's display name.
        houseRepo.tileDef(key)?.nameKey?.let { base ->
            if (base != key) return itemDisplayName(base)
        }
        if (key.startsWith(HouseRepository.BANNER_PREFIX)) {
            val icon = key.removePrefix(HouseRepository.BANNER_PREFIX)
            val banner = uiState.value.earnedBanners.firstOrNull { it.bannerIcon == icon }
            // The name frozen at earn time is only a fallback for events whose data was later
            // removed; live events re-resolve so translation updates apply (issue #1546).
            val currentName = banner?.let { b ->
                gameData.seasonalEvents[b.eventId]?.let { event ->
                    GameStrings.seasonalEventName(context, b.eventId, event.displayName)
                }
            }
            return currentName
                ?: banner?.eventDisplayName?.takeIf { it.isNotBlank() }
                ?: banner?.displayText
                ?: context.getString(R.string.house_cat_banner)
        }
        return GameStrings.houseItemName(context, key)
    }

    /** Human-readable full cost line for the mode banner: coins and materials by name. */
    fun costSummary(key: String, state: HouseUiState): String {
        if (key.startsWith(HouseRepository.BANNER_PREFIX)) return context.getString(R.string.house_free)
        if ((state.house.storage[key] ?: 0) > 0)
            return context.getString(R.string.house_stored_count, state.house.storage[key])
        val def = houseRepo.tileDef(key) ?: return ""
        return tierSummary(itemCost(def, state))
    }

    /** Discounted cost line for the next room purchase, shown while placing a room. */
    fun roomCostSummary(state: HouseUiState): String {
        val tier = houseRepo.nextRoomCost(state.roomCount) ?: return ""
        return tierSummary(discountedTier(tier, 1, state))
    }

    private fun tierSummary(cost: HouseCostTier): String {
        val parts = buildList {
            if (cost.coins > 0) add(context.getString(R.string.house_cost_coins, cost.coins.formatCoins()))
            cost.materials.forEach { (k, v) -> add("$v ${GameStrings.itemName(context, k)}") }
        }
        return parts.joinToString(", ")
    }

    private inline fun report(result: HouseActionResult, onSuccess: () -> Unit) {
        if (result == HouseActionResult.SUCCESS) {
            onSuccess()
            return
        }
        val msg = when (result) {
            HouseActionResult.INSUFFICIENT_LEVEL     -> context.getString(R.string.house_error_level)
            HouseActionResult.INSUFFICIENT_COINS     -> context.getString(R.string.house_error_coins)
            HouseActionResult.INSUFFICIENT_MATERIALS -> context.getString(R.string.house_error_materials)
            HouseActionResult.INVALID_SPOT           -> context.getString(R.string.house_error_spot)
            HouseActionResult.MAX_ROOMS              -> context.getString(R.string.house_error_max_rooms)
            HouseActionResult.MAX_ROOM_SIZE          -> context.getString(R.string.house_error_max_size)
            HouseActionResult.ALREADY_PLACED         -> context.getString(R.string.house_error_already_placed)
            HouseActionResult.LAST_ROOM              -> context.getString(R.string.house_error_last_room)
            HouseActionResult.MIN_ROOM_SIZE          -> context.getString(R.string.house_error_min_size)
            HouseActionResult.SUCCESS                -> return
        }
        _extra.update { it.copy(snackbarMessage = msg) }
    }
}
