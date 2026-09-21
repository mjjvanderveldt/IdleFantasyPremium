package com.fantasyidler.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fantasyidler.R
import com.fantasyidler.data.json.CarnivalPrize
import com.fantasyidler.data.model.EquipSlot
import com.fantasyidler.data.model.OwnedPet
import com.fantasyidler.data.model.PlayerFlags
import com.fantasyidler.data.model.QueuedAction
import com.fantasyidler.data.model.Skills
import com.fantasyidler.repository.BoostRepository
import com.fantasyidler.repository.CarnivalRepository
import com.fantasyidler.repository.GameDataRepository
import com.fantasyidler.repository.PlayerRepository
import com.fantasyidler.repository.QueuedSessionStarter
import com.fantasyidler.repository.TownRepository
import com.fantasyidler.repository.resolveCapeMultiplier
import com.fantasyidler.simulator.SkillSimulator
import com.fantasyidler.util.GameStrings
import com.fantasyidler.util.formatXp
import com.fantasyidler.util.withAppLocale
import com.fantasyidler.util.xpMultiplierBreakdown
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
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Difficulty { NORMAL, HARD }

sealed class ActiveGameState {
    object Ready : ActiveGameState()
    object TimingActive : ActiveGameState()
    data class SequenceShowing(val sequence: List<Int>, val currentIndex: Int) : ActiveGameState()
    data class SequenceInput(val sequence: List<Int>, val userInput: List<Int>) : ActiveGameState()
    data class AppraisalPlaying(val quadIndex: Int) : ActiveGameState()
    data class OnCooldown(val resumesAtMs: Long) : ActiveGameState()
    data class ShellGameShowing(val cupCount: Int, val gemPos: Int) : ActiveGameState()
    data class ShellGameSwapping(val cupCount: Int, val gemPos: Int, val swaps: List<Pair<Int,Int>>) : ActiveGameState()
    data class ShellGamePicking(val cupCount: Int, val gemPos: Int) : ActiveGameState()
    data class HigherOrLowerPlaying(val numbers: List<Int>, val currentIdx: Int, val correctCount: Int) : ActiveGameState()
    data class HigherOrLowerResult(val lastNumber: Int, val totalCorrect: Int, val totalRounds: Int, val tickets: Int, val resumesAtMs: Long) : ActiveGameState()
}

data class AppraisalPair(val itemA: String, val itemB: String, val correctIsA: Boolean)

data class AppraisalQuad(val items: List<String>, val correctIdx: Int)

data class CarnivalUiState(
    val isLoading: Boolean = true,
    val ticketBalance: Int = 0,
    val selectedTab: Int = 0,
    val tabInitialized: Boolean = false,
    val skillLevels: Map<String, Int> = emptyMap(),
    val skillXp: Map<String, Long> = emptyMap(),
    val tierBonus: Float = 0f,
    val capeMultiplier: Float = 1f,
    val queueSize: Int = 0,
    val maxQueueSize: Int = 8,
    val ownedPrizeKeys: Set<String> = emptySet(),
    val snackbarMessage: String? = null,
    val ringTossState: ActiveGameState = ActiveGameState.Ready,
    val hammerStrikeState: ActiveGameState = ActiveGameState.Ready,
    val potionSequenceState: ActiveGameState = ActiveGameState.Ready,
    val itemAppraisalState: ActiveGameState = ActiveGameState.Ready,
    val shellGameState: ActiveGameState = ActiveGameState.Ready,
    val higherLowerState: ActiveGameState = ActiveGameState.Ready,
    val currentAppraisalPair: AppraisalPair? = null,
    val currentAppraisalQuad: AppraisalQuad? = null,
    val pendingLampPrizeKey: String? = null,
    val ringTossDifficulty: Difficulty = Difficulty.NORMAL,
    val hammerStrikeDifficulty: Difficulty = Difficulty.NORMAL,
    val potionSequenceDifficulty: Difficulty = Difficulty.NORMAL,
    val itemAppraisalDifficulty: Difficulty = Difficulty.NORMAL,
    val shellGameDifficulty: Difficulty = Difficulty.NORMAL,
    val higherLowerDifficulty: Difficulty = Difficulty.NORMAL,
    val carnivalGameCount: Int = 4,
    val carnivalCooldownMs: Long = 10L * 60_000L,
)

@HiltViewModel
class CarnivalViewModel @Inject constructor(
    private val boostRepo: BoostRepository,
    private val playerRepo: PlayerRepository,
    private val carnivalRepo: CarnivalRepository,
    val gameData: GameDataRepository,
    private val townRepo: TownRepository,
    private val queuedSessionStarter: QueuedSessionStarter,
    @ApplicationContext private val context: Context,
    private val json: Json,
) : ViewModel() {

    val prizes: List<CarnivalPrize> by lazy { carnivalRepo.prizes.values.toList() }
    val prizesMap: Map<String, CarnivalPrize> by lazy { carnivalRepo.prizes }

    private val APPRAISAL_PAIRS = listOf(
        AppraisalPair("divine_sword", "iron_sword", true),
        AppraisalPair("magic_log", "oak_log", true),
        AppraisalPair("raw_shark", "raw_trout", true),
        AppraisalPair("diamond", "sapphire", true),
        AppraisalPair("runite_ore", "iron_ore", true),
        AppraisalPair("yew_log", "willow_log", true),
        AppraisalPair("gold_bar", "bronze_bar", true),
        AppraisalPair("raw_lobster", "raw_herring", true),
        AppraisalPair("mithril_ore", "copper_ore", true),
        AppraisalPair("iron_sword", "divine_sword", false),
        AppraisalPair("oak_log", "magic_log", false),
        AppraisalPair("raw_trout", "raw_shark", false),
        AppraisalPair("copper_ore", "mithril_ore", false),
        AppraisalPair("willow_log", "yew_log", false),
        AppraisalPair("sapphire", "diamond", false),
    )

    // Hard mode: 4-item quads — correctIdx = index of the most valuable item
    private val APPRAISAL_QUADS = listOf(
        AppraisalQuad(listOf("divine_sword", "runite_sword", "adamantite_sword", "iron_sword"), 0),
        AppraisalQuad(listOf("magic_log", "yew_log", "maple_log", "oak_log"), 0),
        AppraisalQuad(listOf("raw_shark", "raw_lobster", "raw_trout", "raw_herring"), 0),
        AppraisalQuad(listOf("diamond", "ruby", "sapphire", "emerald"), 0),
        AppraisalQuad(listOf("runite_ore", "adamantite_ore", "mithril_ore", "iron_ore"), 0),
        AppraisalQuad(listOf("dragon_bone", "giant_bones", "big_bones", "bones"), 0),
        AppraisalQuad(listOf("runite_bar", "adamantite_bar", "steel_bar", "bronze_bar"), 0),
        AppraisalQuad(listOf("yew_log", "maple_log", "willow_log", "oak_log"), 0),
        AppraisalQuad(listOf("gold_bar", "steel_bar", "iron_bar", "bronze_bar"), 0),
        AppraisalQuad(listOf("runite_sword", "adamantite_sword", "mithril_sword", "iron_sword"), 0),
    )

    private val _extra = MutableStateFlow(CarnivalUiState())

    init {
        viewModelScope.launch {
            val flags = playerRepo.getFlags()
            val now = System.currentTimeMillis()
            fun cooldownState(at: Long): ActiveGameState? =
                if (at > now) ActiveGameState.OnCooldown(at) else null
            val diffs = playerRepo.getFlags().carnivalDifficulties
            if (diffs.isNotEmpty()) {
                fun diff(key: String) = diffs[key]?.uppercase()?.let { runCatching { Difficulty.valueOf(it) }.getOrNull() }
                _extra.update { s -> s.copy(
                    ringTossDifficulty       = diff("ring_toss")       ?: s.ringTossDifficulty,
                    hammerStrikeDifficulty   = diff("hammer_strike")   ?: s.hammerStrikeDifficulty,
                    potionSequenceDifficulty = diff("potion_sequence") ?: s.potionSequenceDifficulty,
                    itemAppraisalDifficulty  = diff("item_appraisal")  ?: s.itemAppraisalDifficulty,
                    shellGameDifficulty      = diff("shell_game")      ?: s.shellGameDifficulty,
                    higherLowerDifficulty    = diff("higher_lower")    ?: s.higherLowerDifficulty,
                    ringTossState            = cooldownState(flags.carnivalRingTossCooldownAt)       ?: s.ringTossState,
                    hammerStrikeState        = cooldownState(flags.carnivalHammerStrikeCooldownAt)   ?: s.hammerStrikeState,
                    potionSequenceState      = cooldownState(flags.carnivalPotionSequenceCooldownAt) ?: s.potionSequenceState,
                    itemAppraisalState       = cooldownState(flags.carnivalItemAppraisalCooldownAt)  ?: s.itemAppraisalState,
                    shellGameState           = cooldownState(flags.carnivalShellGameCooldownAt)      ?: s.shellGameState,
                    higherLowerState         = cooldownState(flags.carnivalHigherLowerCooldownAt)    ?: s.higherLowerState,
                ) }
            }
        }
    }

    val uiState: StateFlow<CarnivalUiState> = combine(
        playerRepo.playerFlow,
        _extra,
    ) { player, extra ->
        if (player == null) extra.copy(isLoading = true)
        else {
            val inventory: Map<String, Int> = json.decodeFromString(player.inventory)
            val equipped: Map<String, String?> = json.decodeFromString(player.equipped)
            val flags: PlayerFlags = json.decodeFromString(player.flags)
            val levels: Map<String, Int> = json.decodeFromString(player.skillLevels)
            val xpMap: Map<String, Long> = json.decodeFromString(player.skillXp)
            val pets: List<OwnedPet> = try { json.decodeFromString(player.pets) } catch (_: Exception) { emptyList() }
            val ownedPetIds = pets.map { it.id }.toSet()
            val ownedPrizeKeys = prizes
                .filter { it.type == "equipment" || it.type == "pet" }
                .filter { (inventory[it.key] ?: 0) > 0 || it.key in ownedPetIds }
                .map { it.key }
                .toSet()
            val now = System.currentTimeMillis()
            val capeMult = resolveCapeMultiplier(
                skillName         = "carnival",
                equippedCape      = equipped[EquipSlot.CAPE]?.let { gameData.equipment[it] },
                inventoryKeys     = inventory.keys,
                townBuildingTiers = flags.townBuildingTiers,
                capeScaling       = boostRepo.capeScalingBySkill(flags),
                allEquipment      = gameData.equipment,
                ironman           = flags.ironman,
            )
            fun resolveState(state: ActiveGameState, cooldownAt: Long): ActiveGameState =
                if (cooldownAt > now) ActiveGameState.OnCooldown(cooldownAt) else state
            extra.copy(
                isLoading           = false,
                ticketBalance       = inventory["carnival_ticket"] ?: 0,
                skillLevels         = levels,
                skillXp             = xpMap,
                tierBonus           = townRepo.idleTicketBonusChance(flags),
                capeMultiplier      = capeMult,
                queueSize           = flags.sessionQueue.size,
                maxQueueSize        = playerRepo.maxQueueSize(flags),
                ownedPrizeKeys      = ownedPrizeKeys,
                selectedTab         = if (!extra.tabInitialized) flags.carnivalTab else extra.selectedTab,
                tabInitialized      = true,
                ringTossState       = resolveState(extra.ringTossState, flags.carnivalRingTossCooldownAt),
                hammerStrikeState   = resolveState(extra.hammerStrikeState, flags.carnivalHammerStrikeCooldownAt),
                potionSequenceState = resolveState(extra.potionSequenceState, flags.carnivalPotionSequenceCooldownAt),
                itemAppraisalState  = resolveState(extra.itemAppraisalState, flags.carnivalItemAppraisalCooldownAt),
                shellGameState      = resolveState(extra.shellGameState, flags.carnivalShellGameCooldownAt),
                higherLowerState    = if (extra.higherLowerState is ActiveGameState.HigherOrLowerResult) extra.higherLowerState
                                      else resolveState(extra.higherLowerState, flags.carnivalHigherLowerCooldownAt),
                carnivalGameCount   = townRepo.carnivalGameCount(flags),
                carnivalCooldownMs  = townRepo.carnivalCooldownMs(flags),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CarnivalUiState())

    fun selectTab(index: Int) {
        _extra.update { it.copy(selectedTab = index, tabInitialized = true) }
        viewModelScope.launch {
            val flags = playerRepo.getFlags()
            playerRepo.updateFlags(flags.copy(carnivalTab = index))
        }
    }

    fun snackbarConsumed() = _extra.update { it.copy(snackbarMessage = null) }

    private fun withCape(tickets: Int): Int {
        val capeMult = uiState.value.capeMultiplier
        return (tickets * capeMult).roundToInt().coerceAtLeast(max(tickets, 0))
    }

    // ── Difficulty selectors ───────────────────────────────────────────────────

    private fun persistDifficulty(key: String, d: Difficulty) {
        viewModelScope.launch {
            val flags = playerRepo.getFlags()
            playerRepo.updateFlags(flags.copy(carnivalDifficulties = flags.carnivalDifficulties + (key to d.name.lowercase())))
        }
    }

    fun setRingTossDifficulty(d: Difficulty)       { _extra.update { it.copy(ringTossDifficulty = d) };       persistDifficulty("ring_toss", d) }
    fun setHammerStrikeDifficulty(d: Difficulty)   { _extra.update { it.copy(hammerStrikeDifficulty = d) };   persistDifficulty("hammer_strike", d) }
    fun setPotionSequenceDifficulty(d: Difficulty) { _extra.update { it.copy(potionSequenceDifficulty = d) }; persistDifficulty("potion_sequence", d) }
    fun setItemAppraisalDifficulty(d: Difficulty)  { _extra.update { it.copy(itemAppraisalDifficulty = d) };  persistDifficulty("item_appraisal", d) }
    fun setShellGameDifficulty(d: Difficulty)      { _extra.update { it.copy(shellGameDifficulty = d) };      persistDifficulty("shell_game", d) }
    fun setHigherLowerDifficulty(d: Difficulty)    { _extra.update { it.copy(higherLowerDifficulty = d) };    persistDifficulty("higher_lower", d) }

    // ── Idle game queueing ─────────────────────────────────────────────────────

    fun queueIdleGame(activityKey: String, displayName: String) {
        viewModelScope.launch {
            val player = playerRepo.getOrCreatePlayer()
            val agility = (json.decodeFromString<Map<String, Int>>(player.skillLevels))[Skills.AGILITY] ?: 1
            val carnivalFlags: PlayerFlags = json.decodeFromString(player.flags)
            val enqueued = playerRepo.enqueueAction(
                QueuedAction(
                    skillName           = "carnival",
                    activityKey         = activityKey,
                    skillDisplayName    = displayName,
                    estimatedDurationMs = SkillSimulator.sessionDurationMs(agility, boostRepo.sessionFloorReductionMin(carnivalFlags), townRepo.playerSessionDurationMultiplier(carnivalFlags)),
                )
            )
            if (enqueued) queuedSessionStarter.startNextQueued()
            _extra.update {
                it.copy(snackbarMessage = if (enqueued)
                    context.withAppLocale().getString(R.string.carnival_queue_added, displayName)
                else
                    context.withAppLocale().getString(R.string.carnival_queue_full))
            }
        }
    }

    // ── Active game: Ring Toss (timing) ────────────────────────────────────────

    fun startRingToss() {
        if (_extra.value.ringTossState !is ActiveGameState.Ready) return
        _extra.update { it.copy(ringTossState = ActiveGameState.TimingActive) }
    }

    fun submitRingToss(position: Float) {
        if (_extra.value.ringTossState !is ActiveGameState.TimingActive) return
        val diff = _extra.value.ringTossDifficulty
        val won = if (diff == Difficulty.HARD) position in 0.52f..0.57f else position in 0.45f..0.55f
        val tickets = withCape(if (won) (if (diff == Difficulty.HARD) 7 else 2) else 0)
        viewModelScope.launch {
            if (tickets > 0) carnivalRepo.awardTickets(tickets)
            val cooldownMs = uiState.value.carnivalCooldownMs
            val resumesAt = System.currentTimeMillis() + cooldownMs
            playerRepo.updateFlags(playerRepo.getFlags().copy(carnivalRingTossCooldownAt = resumesAt))
            _extra.update {
                it.copy(
                    ringTossState   = ActiveGameState.OnCooldown(resumesAt),
                    snackbarMessage = if (won)
                        context.withAppLocale().getString(R.string.carnival_ring_won, tickets)
                    else
                        context.withAppLocale().getString(R.string.carnival_ring_missed),
                )
            }
        }
    }

    // ── Active game: Hammer Strike (timing) ────────────────────────────────────

    fun startHammerStrike() {
        if (_extra.value.hammerStrikeState !is ActiveGameState.Ready) return
        _extra.update { it.copy(hammerStrikeState = ActiveGameState.TimingActive) }
    }

    fun submitHammerStrike(position: Float) {
        if (_extra.value.hammerStrikeState !is ActiveGameState.TimingActive) return
        val diff = _extra.value.hammerStrikeDifficulty
        val tickets = withCape(if (diff == Difficulty.HARD) {
            when {
                position >= 0.87f -> 8
                position >= 0.60f -> 6
                else              -> 0
            }
        } else {
            when {
                position >= 0.80f -> 2
                position >= 0.50f -> 1
                else              -> 0
            }
        })
        viewModelScope.launch {
            if (tickets > 0) carnivalRepo.awardTickets(tickets)
            val cooldownMs = uiState.value.carnivalCooldownMs
            val resumesAt = System.currentTimeMillis() + cooldownMs
            playerRepo.updateFlags(playerRepo.getFlags().copy(carnivalHammerStrikeCooldownAt = resumesAt))
            _extra.update {
                it.copy(
                    hammerStrikeState = ActiveGameState.OnCooldown(resumesAt),
                    snackbarMessage   = when {
                        tickets >= 6 -> context.withAppLocale().getString(R.string.carnival_hammer_perfect, tickets)
                        tickets > 0  -> context.withAppLocale().getString(R.string.carnival_hammer_good, tickets)
                        else         -> context.withAppLocale().getString(R.string.carnival_hammer_miss)
                    },
                )
            }
        }
    }

    // ── Active game: Potion Sequence (memory) ──────────────────────────────────

    fun startPotionSequence() {
        if (_extra.value.potionSequenceState !is ActiveGameState.Ready) return
        val diff = _extra.value.potionSequenceDifficulty
        val colorCount = 6
        val seqLength  = if (diff == Difficulty.HARD) 8 else 6
        val seq = List(seqLength) { Random.nextInt(colorCount) }
        _extra.update { it.copy(potionSequenceState = ActiveGameState.SequenceShowing(seq, 0)) }
    }

    fun advancePotionSequence() {
        val state = _extra.value.potionSequenceState
        if (state !is ActiveGameState.SequenceShowing) return
        val nextIdx = state.currentIndex + 1
        _extra.update {
            it.copy(potionSequenceState = if (nextIdx < state.sequence.size)
                ActiveGameState.SequenceShowing(state.sequence, nextIdx)
            else
                ActiveGameState.SequenceInput(state.sequence, emptyList()))
        }
    }

    fun submitPotionInput(colorIndex: Int) {
        val state = _extra.value.potionSequenceState
        if (state !is ActiveGameState.SequenceInput) return
        val newInput = state.userInput + colorIndex
        val expectedSoFar = state.sequence.take(newInput.size)
        if (newInput != expectedSoFar) {
            viewModelScope.launch {
                val cooldownMs = uiState.value.carnivalCooldownMs
                val resumesAt = System.currentTimeMillis() + cooldownMs
                playerRepo.updateFlags(playerRepo.getFlags().copy(carnivalPotionSequenceCooldownAt = resumesAt))
                _extra.update {
                    it.copy(
                        potionSequenceState = ActiveGameState.OnCooldown(resumesAt),
                        snackbarMessage     = context.withAppLocale().getString(R.string.carnival_sequence_wrong),
                    )
                }
            }
            return
        }
        if (newInput.size == state.sequence.size) {
            val diff = _extra.value.potionSequenceDifficulty
            val tickets = withCape(if (diff == Difficulty.HARD) 7 else 2)
            viewModelScope.launch {
                carnivalRepo.awardTickets(tickets)
                val cooldownMs = uiState.value.carnivalCooldownMs
                val resumesAt = System.currentTimeMillis() + cooldownMs
                playerRepo.updateFlags(playerRepo.getFlags().copy(carnivalPotionSequenceCooldownAt = resumesAt))
                _extra.update {
                    it.copy(
                        potionSequenceState = ActiveGameState.OnCooldown(resumesAt),
                        snackbarMessage     = context.withAppLocale().getString(R.string.carnival_sequence_correct, tickets),
                    )
                }
            }
        } else {
            _extra.update { it.copy(potionSequenceState = state.copy(userInput = newInput)) }
        }
    }

    // ── Active game: Item Appraisal (choice) ───────────────────────────────────

    fun startItemAppraisal() {
        if (_extra.value.itemAppraisalState !is ActiveGameState.Ready) return
        val diff = _extra.value.itemAppraisalDifficulty
        if (diff == Difficulty.HARD) {
            val raw = APPRAISAL_QUADS[Random.nextInt(APPRAISAL_QUADS.size)]
            val shuffled = raw.items.shuffled()
            val quad = raw.copy(items = shuffled, correctIdx = shuffled.indexOf(raw.items[raw.correctIdx]))
            _extra.update {
                it.copy(
                    itemAppraisalState   = ActiveGameState.AppraisalPlaying(quad.correctIdx),
                    currentAppraisalQuad = quad,
                    currentAppraisalPair = null,
                )
            }
        } else {
            val raw = APPRAISAL_PAIRS[Random.nextInt(APPRAISAL_PAIRS.size)]
            val pair = if (Random.nextBoolean()) raw
                       else AppraisalPair(raw.itemB, raw.itemA, !raw.correctIsA)
            _extra.update {
                it.copy(
                    itemAppraisalState   = ActiveGameState.AppraisalPlaying(if (pair.correctIsA) 0 else 1),
                    currentAppraisalPair = pair,
                    currentAppraisalQuad = null,
                )
            }
        }
    }

    fun submitAppraisalAnswer(chosenIdx: Int) {
        val state = _extra.value.itemAppraisalState
        if (state !is ActiveGameState.AppraisalPlaying) return
        val diff = _extra.value.itemAppraisalDifficulty
        val won: Boolean
        val correctName: String
        if (diff == Difficulty.HARD) {
            val quad = _extra.value.currentAppraisalQuad ?: return
            won = chosenIdx == quad.correctIdx
            correctName = quad.items[quad.correctIdx]
        } else {
            val pair = _extra.value.currentAppraisalPair ?: return
            won = (chosenIdx == 0) == pair.correctIsA
            correctName = if (pair.correctIsA) pair.itemA else pair.itemB
        }
        val correctDisplayName = GameStrings.itemName(context.withAppLocale(), correctName)
        val tickets = withCape(if (won) (if (diff == Difficulty.HARD) 7 else 2) else 0)
        viewModelScope.launch {
            if (tickets > 0) carnivalRepo.awardTickets(tickets)
            val cooldownMs = uiState.value.carnivalCooldownMs
            val resumesAt = System.currentTimeMillis() + cooldownMs
            playerRepo.updateFlags(playerRepo.getFlags().copy(carnivalItemAppraisalCooldownAt = resumesAt))
            _extra.update {
                it.copy(
                    itemAppraisalState  = ActiveGameState.OnCooldown(resumesAt),
                    snackbarMessage     = if (won)
                        context.withAppLocale().getString(R.string.carnival_appraisal_correct, tickets)
                    else
                        context.withAppLocale().getString(R.string.carnival_appraisal_wrong, correctDisplayName),
                )
            }
        }
    }

    // ── Active game: Pick-a-Cup (shell game) ───────────────────────────────────

    fun startShellGame() {
        if (_extra.value.shellGameState !is ActiveGameState.Ready) return
        val diff = _extra.value.shellGameDifficulty
        val cupCount = if (diff == Difficulty.HARD) 4 else 3
        val gemPos   = Random.nextInt(cupCount)
        _extra.update { it.copy(shellGameState = ActiveGameState.ShellGameShowing(cupCount, gemPos)) }
    }

    fun advanceShellGame() {
        val s = _extra.value.shellGameState
        if (s !is ActiveGameState.ShellGameShowing) return
        val swapCount = s.cupCount * 2 + Random.nextInt(s.cupCount)
        val swapPairs = (0 until swapCount).map {
            val a = Random.nextInt(s.cupCount)
            val b = (a + 1 + Random.nextInt(s.cupCount - 1)) % s.cupCount
            Pair(a, b)
        }
        _extra.update { it.copy(shellGameState = ActiveGameState.ShellGameSwapping(s.cupCount, s.gemPos, swapPairs)) }
    }

    fun finishShellGame(newGemPos: Int) {
        val s = _extra.value.shellGameState
        if (s !is ActiveGameState.ShellGameSwapping) return
        _extra.update { it.copy(shellGameState = ActiveGameState.ShellGamePicking(s.cupCount, newGemPos)) }
    }

    fun submitShellGuess(pickedPos: Int) {
        val s = _extra.value.shellGameState
        if (s !is ActiveGameState.ShellGamePicking) return
        val diff = _extra.value.shellGameDifficulty
        val won = pickedPos == s.gemPos
        val tickets = withCape(if (won) (if (diff == Difficulty.HARD) 7 else 4) else 0)
        viewModelScope.launch {
            if (tickets > 0) carnivalRepo.awardTickets(tickets)
            val cooldownMs = uiState.value.carnivalCooldownMs
            val resumesAt = System.currentTimeMillis() + cooldownMs
            playerRepo.updateFlags(playerRepo.getFlags().copy(carnivalShellGameCooldownAt = resumesAt))
            _extra.update {
                it.copy(
                    shellGameState  = ActiveGameState.OnCooldown(resumesAt),
                    snackbarMessage = if (won)
                        context.withAppLocale().getString(R.string.carnival_shell_won, tickets)
                    else
                        context.withAppLocale().getString(R.string.carnival_shell_missed, s.gemPos + 1),
                )
            }
        }
    }

    // ── Active game: Higher or Lower ───────────────────────────────────────────

    fun startHigherOrLower() {
        if (_extra.value.higherLowerState !is ActiveGameState.Ready) return
        val diff = _extra.value.higherLowerDifficulty
        val totalRounds = if (diff == Difficulty.HARD) 7 else 5
        val numbers = buildList {
            add(Random.nextInt(1, 11))
            repeat(totalRounds) {
                var n: Int
                do { n = Random.nextInt(1, 11) } while (n == last())
                add(n)
            }
        }
        _extra.update { it.copy(higherLowerState = ActiveGameState.HigherOrLowerPlaying(numbers, 0, 0)) }
    }

    fun submitHigherOrLower(guessHigher: Boolean) {
        val state = _extra.value.higherLowerState
        if (state !is ActiveGameState.HigherOrLowerPlaying) return
        val diff = _extra.value.higherLowerDifficulty
        val current = state.numbers[state.currentIdx]
        val next    = state.numbers[state.currentIdx + 1]
        val correct = when {
            next > current -> guessHigher
            next < current -> !guessHigher
            else           -> false // tie = wrong
        }
        val newCorrect = state.correctCount + if (correct) 1 else 0
        val newIdx     = state.currentIdx + 1
        val totalRounds = state.numbers.size - 1
        if (newIdx >= totalRounds) {
            val tickets = withCape(if (diff == Difficulty.HARD) {
                when {
                    newCorrect >= 6 -> 8
                    newCorrect >= 5 -> 4
                    newCorrect >= 4 -> 1
                    else            -> 0
                }
            } else {
                when {
                    newCorrect >= 4 -> 5
                    newCorrect >= 3 -> 2
                    else            -> 0
                }
            })
            viewModelScope.launch {
                if (tickets > 0) carnivalRepo.awardTickets(tickets)
                val cooldownMs = uiState.value.carnivalCooldownMs
                val resumesAt = System.currentTimeMillis() + cooldownMs
                playerRepo.updateFlags(playerRepo.getFlags().copy(carnivalHigherLowerCooldownAt = resumesAt))
                _extra.update {
                    it.copy(higherLowerState = ActiveGameState.HigherOrLowerResult(next, newCorrect, totalRounds, tickets, resumesAt))
                }
            }
        } else {
            _extra.update {
                it.copy(higherLowerState = ActiveGameState.HigherOrLowerPlaying(state.numbers, newIdx, newCorrect))
            }
        }
    }

    fun confirmHigherOrLowerResult() {
        val s = _extra.value.higherLowerState
        if (s !is ActiveGameState.HigherOrLowerResult) return
        _extra.update { it.copy(higherLowerState = ActiveGameState.OnCooldown(s.resumesAtMs)) }
    }

    // Called by the Screen's periodic ticker when a cooldown has expired
    fun clearCooldownIfExpired(gameKey: String) {
        val now = System.currentTimeMillis()
        _extra.update { s ->
            when (gameKey) {
                "ring_toss" -> {
                    val gs = s.ringTossState
                    if (gs is ActiveGameState.OnCooldown && now >= gs.resumesAtMs) s.copy(ringTossState = ActiveGameState.Ready) else s
                }
                "hammer_strike" -> {
                    val gs = s.hammerStrikeState
                    if (gs is ActiveGameState.OnCooldown && now >= gs.resumesAtMs) s.copy(hammerStrikeState = ActiveGameState.Ready) else s
                }
                "potion_sequence" -> {
                    val gs = s.potionSequenceState
                    if (gs is ActiveGameState.OnCooldown && now >= gs.resumesAtMs) s.copy(potionSequenceState = ActiveGameState.Ready) else s
                }
                "item_appraisal" -> {
                    val gs = s.itemAppraisalState
                    if (gs is ActiveGameState.OnCooldown && now >= gs.resumesAtMs) s.copy(itemAppraisalState = ActiveGameState.Ready) else s
                }
                "shell_game" -> {
                    val gs = s.shellGameState
                    if (gs is ActiveGameState.OnCooldown && now >= gs.resumesAtMs) s.copy(shellGameState = ActiveGameState.Ready) else s
                }
                "higher_lower" -> {
                    val gs = s.higherLowerState
                    if (gs is ActiveGameState.OnCooldown && now >= gs.resumesAtMs) s.copy(higherLowerState = ActiveGameState.Ready) else s
                }
                else -> s
            }
        }
    }

    // ── Prize shop ─────────────────────────────────────────────────────────────

    fun redeem(prizeKey: String) {
        viewModelScope.launch {
            val prize = prizesMap[prizeKey] ?: return@launch
            when (prize.type) {
                "equipment", "pet" -> {
                    val success = carnivalRepo.redeemForItem(prizeKey, prize.ticketCost)
                    _extra.update {
                        it.copy(snackbarMessage = if (success)
                            context.withAppLocale().getString(R.string.carnival_redeemed, prize.displayName)
                        else
                            context.withAppLocale().getString(R.string.carnival_not_enough_tickets))
                    }
                }
                "xp_lamp" -> _extra.update { it.copy(pendingLampPrizeKey = prizeKey) }
            }
        }
    }

    fun redeemLamp(skillKey: String) {
        val prizeKey = _extra.value.pendingLampPrizeKey ?: return
        val prize    = prizesMap[prizeKey] ?: return
        _extra.update { it.copy(pendingLampPrizeKey = null) }
        viewModelScope.launch {
            val result = carnivalRepo.redeemForXp(skillKey, prize.xpAmount, prize.ticketCost)
            _extra.update {
                it.copy(snackbarMessage = if (result.success) {
                    val b = result.breakdown!!
                    val skillDisplay = GameStrings.skillName(context, skillKey)
                    val suffix = xpMultiplierBreakdown(b.baseXp, b.boostFactor, b.blessingMult, b.prestigeXpPct)?.let { s -> " $s" } ?: ""
                    context.withAppLocale().getString(R.string.carnival_lamp_redeemed, b.finalXp.formatXp(), skillDisplay) + suffix
                } else
                    context.withAppLocale().getString(R.string.carnival_not_enough_tickets))
            }
        }
    }

    fun dismissLampPicker() = _extra.update { it.copy(pendingLampPrizeKey = null) }
}
