package com.otobusreklam.player.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Senkron ile oynatici arasindaki tek yonlu haber kanali (ayni surecteyiz).
 * Sayac her degistiginde oynatici listesini yeniden degerlendirir -
 * ama DEGISIMI ICERIK SINIRINDA uygular, videonun ortasinda degil.
 */
object PlaylistBus {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision

    fun notifyChanged() {
        _revision.value = _revision.value + 1
    }
}
