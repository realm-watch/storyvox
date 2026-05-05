package `in`.jphe.storyvox.wear.playback

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

/**
 * Watch-side counterpart of [in.jphe.storyvox.playback.wear.PhoneWearBridge].
 *
 * - Subscribes to `/playback/state` DataItem updates from the phone, decodes the
 *   JSON-encoded [PlaybackState], and exposes it as a [StateFlow].
 * - Sends transport commands to the phone via [com.google.android.gms.wearable.MessageClient].
 *
 * Lifecycle bound to [in.jphe.storyvox.wear.WearApp] / its NowPlaying composable.
 */
class WearPlaybackBridge(private val context: Context) : DataClient.OnDataChangedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun start() {
        dataClient.addListener(this, android.net.Uri.parse("wear://*/playback"), DataClient.FILTER_PREFIX)
        // Hydrate immediately from the latest cached DataItem
        scope.launch {
            runCatching {
                dataClient.dataItems.await().forEach { item ->
                    if (item.uri.path == PhoneWearBridge.PATH_STATE) consume(item)
                }
            }
        }
    }

    fun stop() {
        dataClient.removeListener(this)
        scope.cancel()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.dataItem.uri.path == PhoneWearBridge.PATH_STATE) consume(e.dataItem)
        }
    }

    private fun consume(item: DataItem) {
        val map = DataMapItem.fromDataItem(item).dataMap
        val raw = map.getString("state") ?: return
        runCatching { _state.value = json.decodeFromString<PlaybackState>(raw) }
    }

    suspend fun send(path: String) {
        runCatching {
            val nodes = nodeClient.connectedNodes.await()
            val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return
            messageClient.sendMessage(phone.id, path, null).await()
        }
    }
}
