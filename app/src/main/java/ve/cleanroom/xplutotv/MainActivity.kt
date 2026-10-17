package ve.cleanroom.xplutotv

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class LiveCatalogResponse(val channels: List<ChannelItem> = emptyList())

@Serializable
data class ChannelItem(
    val id: String,
    val name: String,
    val cat: String,
    val origin: String,
    val logo: String = "",
    val url: String
)

@Serializable
data class MoviesCatalogResponse(val movies: List<MovieItem> = emptyList())

@Serializable
data class MovieItem(
    val id: String,
    val title: String,
    val year: Int = 2020,
    val cat: String,
    val origin: String,
    val poster: String = "",
    val url: String
)

@Serializable
data class SeriesCatalogResponse(val series: List<SeriesIndexItem> = emptyList())

@Serializable
data class SeriesIndexItem(
    val id: String,
    val title: String,
    val cat: String,
    val origin: String,
    val poster: String = "",
    val detail_url: String
)

@Serializable
data class SeriesDetailResponse(
    val id: String,
    val title: String,
    val origin: String,
    val synopsis: String = "",
    val seasons: List<SeasonItem> = emptyList()
)

@Serializable
data class SeasonItem(
    val season: Int,
    val episodes: List<EpisodeItem> = emptyList()
)

@Serializable
data class EpisodeItem(
    val id: String,
    val num: Int,
    val title: String,
    val url: String
)

enum class MainTab(val title: String) {
    LIVE("TV en Vivo"),
    MOVIES("Películas VOD"),
    SERIES("Series VOD")
}

class MainActivity : ComponentActivity() {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        val isTelevision = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        val lowRamImageLoader = ImageLoader.Builder(applicationContext)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .okHttpClient(httpClient)
            .build()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFC107),
                    secondary = Color(0xFF00E5FF),
                    background = Color(0xFF0D0F14),
                    surface = Color(0xFF181B24)
                )
            ) {
                HybridStreamingApp(
                    isTelevision = isTelevision,
                    httpClient = httpClient,
                    jsonParser = jsonParser,
                    imageLoader = lowRamImageLoader
                )
            }
        }
    }
}

@Composable
fun HybridStreamingApp(
    isTelevision: Boolean,
    httpClient: OkHttpClient,
    jsonParser: Json,
    imageLoader: ImageLoader
) {
    val scope = rememberCoroutineScope()
    val catalogBaseUrl = "https://tu-usuario.github.io/catalogo-tv"

    var selectedTab by remember { mutableStateOf(MainTab.LIVE) }
    var selectedOrigin by remember { mutableStateOf("TODOS") }
    val origins = listOf("TODOS", "VE", "LATAM", "ES", "US-ES")

    var channels by remember { mutableStateOf<List<ChannelItem>>(emptyList()) }
    var movies by remember { mutableStateOf<List<MovieItem>>(emptyList()) }
    var seriesList by remember { mutableStateOf<List<SeriesIndexItem>>(emptyList()) }
    var activeSeriesDetail by remember { mutableStateOf<SeriesDetailResponse?>(null) }

    var activeStreamUrl by remember { mutableStateOf<String?>(null) }
    var activeStreamTitle by remember { mutableStateOf("") }
    var activeChannelIndex by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val liveReq = Request.Builder().url("$catalogBaseUrl/live_es.json").build()
                httpClient.newCall(liveReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        channels = jsonParser.decodeFromString<LiveCatalogResponse>(body).channels
                        if (channels.isNotEmpty()) {
                            activeStreamUrl = channels.first().url
                            activeStreamTitle = channels.first().name
                        }
                    }
                }
                val movReq = Request.Builder().url("$catalogBaseUrl/vod_movies_es.json").build()
                httpClient.newCall(movReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        movies = jsonParser.decodeFromString<MoviesCatalogResponse>(body).movies
                    }
                }
                val serReq = Request.Builder().url("$catalogBaseUrl/vod_series_es.json").build()
                httpClient.newCall(serReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        seriesList = jsonParser.decodeFromString<SeriesCatalogResponse>(body).series
                    }
                }
            } catch (_: Exception) {
            } finally {
                isLoading = false
            }
        }
    }

    val filteredChannels = remember(channels, selectedOrigin) {
        if (selectedOrigin == "TODOS") channels else channels.filter { it.origin == selectedOrigin }
    }
    val filteredMovies = remember(movies, selectedOrigin) {
        if (selectedOrigin == "TODOS") movies else movies.filter { it.origin == selectedOrigin }
    }
    val filteredSeries = remember(seriesList, selectedOrigin) {
        if (selectedOrigin == "TODOS") seriesList else seriesList.filter { it.origin == selectedOrigin }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && selectedTab == MainTab.LIVE && filteredChannels.isNotEmpty()) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                            activeChannelIndex = (activeChannelIndex - 1 + filteredChannels.size) % filteredChannels.size
                            activeStreamUrl = filteredChannels[activeChannelIndex].url
                            activeStreamTitle = filteredChannels[activeChannelIndex].name
                            false
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            activeChannelIndex = (activeChannelIndex + 1) % filteredChannels.size
                            activeStreamUrl = filteredChannels[activeChannelIndex].url
                            activeStreamTitle = filteredChannels[activeChannelIndex].name
                            false
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        TopNavigationAndFilters(
            selectedTab = selectedTab,
            onTabSelected = {
                selectedTab = it
                activeSeriesDetail = null
            },
            origins = origins,
            selectedOrigin = selectedOrigin,
            onOriginSelected = { selectedOrigin = it }
        )

        if (isTelevision) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(0.55f)
                        .fillMaxHeight()
                        .padding(8.dp)
                ) {
                    Media3HlsPlayer(
                        streamUrl = activeStreamUrl,
                        streamTitle = activeStreamTitle,
                        isTelevision = true
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(0.45f)
                        .fillMaxHeight()
                        .padding(8.dp)
                ) {
                    CatalogContentArea(
                        selectedTab = selectedTab,
                        isLoading = isLoading,
                        channels = filteredChannels,
                        movies = filteredMovies,
                        seriesList = filteredSeries,
                        activeSeriesDetail = activeSeriesDetail,
                        imageLoader = imageLoader,
                        onSelectStream = { url, title, idx ->
                            activeStreamUrl = url
                            activeStreamTitle = title
                            if (idx >= 0) activeChannelIndex = idx
                        },
                        onSelectSeries = { item ->
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val req = Request.Builder().url(item.detail_url).build()
                                    httpClient.newCall(req).execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            val body = resp.body?.string().orEmpty()
                                            activeSeriesDetail = jsonParser.decodeFromString<SeriesDetailResponse>(body)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        },
                        onCloseSeriesDetail = { activeSeriesDetail = null }
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                ) {
                    Media3HlsPlayer(
                        streamUrl = activeStreamUrl,
                        streamTitle = activeStreamTitle,
                        isTelevision = false
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    CatalogContentArea(
                        selectedTab = selectedTab,
                        isLoading = isLoading,
                        channels = filteredChannels,
                        movies = filteredMovies,
                        seriesList = filteredSeries,
                        activeSeriesDetail = activeSeriesDetail,
                        imageLoader = imageLoader,
                        onSelectStream = { url, title, idx ->
                            activeStreamUrl = url
                            activeStreamTitle = title
                            if (idx >= 0) activeChannelIndex = idx
                        },
                        onSelectSeries = { item ->
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val req = Request.Builder().url(item.detail_url).build()
                                    httpClient.newCall(req).execute().use { resp ->
                                        if (resp.isSuccessful) {
                                            val body = resp.body?.string().orEmpty()
                                            activeSeriesDetail = jsonParser.decodeFromString<SeriesDetailResponse>(body)
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        },
                        onCloseSeriesDetail = { activeSeriesDetail = null }
                    )
                }
            }
        }
    }
}

@Composable
fun TopNavigationAndFilters(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    origins: List<String>,
    selectedOrigin: String,
    onOriginSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                var isFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF252A38))
                        .border(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable()
                        .clickable { onTabSelected(tab) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = tab.title,
                        color = if (isSelected) Color.Black else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(origins) { origin ->
                val active = selectedOrigin == origin
                var isFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) MaterialTheme.colorScheme.secondary else Color(0xFF1E222D))
                        .border(
                            width = if (isFocused) 2.dp else 0.dp,
                            color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable()
                        .clickable { onOriginSelected(origin) }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "[$origin]",
                        color = if (active) Color.Black else Color.LightGray,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun CatalogContentArea(
    selectedTab: MainTab,
    isLoading: Boolean,
    channels: List<ChannelItem>,
    movies: List<MovieItem>,
    seriesList: List<SeriesIndexItem>,
    activeSeriesDetail: SeriesDetailResponse?,
    imageLoader: ImageLoader,
    onSelectStream: (url: String, title: String, channelIndex: Int) -> Unit,
    onSelectSeries: (SeriesIndexItem) -> Unit,
    onCloseSeriesDetail: () -> Unit
) {
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    when (selectedTab) {
        MainTab.LIVE -> {
            val grouped = remember(channels) { channels.groupBy { it.cat } }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                grouped.forEach { (category, catChannels) ->
                    item(key = "header-$category") {
                        Text(
                            text = category.uppercase(),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(catChannels, key = { it.id }) { channel ->
                        var isFocused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(
                                    width = if (isFocused) 2.dp else 0.dp,
                                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .onFocusChanged { isFocused = it.isFocused }
                                .focusable()
                                .clickable {
                                    val idx = channels.indexOf(channel)
                                    onSelectStream(channel.url, channel.name, idx)
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                AsyncImage(
                                    model = channel.logo,
                                    imageLoader = imageLoader,
                                    contentDescription = channel.name,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = channel.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OriginBadge(origin = channel.origin)
                        }
                    }
                }
            }
        }

        MainTab.MOVIES -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(movies, key = { it.id }) { movie ->
                    VodPosterCard(
                        title = "${movie.title} (${movie.year})",
                        origin = movie.origin,
                        posterUrl = movie.poster,
                        imageLoader = imageLoader,
                        onClick = { onSelectStream(movie.url, movie.title, -1) }
                    )
                }
            }
        }

        MainTab.SERIES -> {
            if (activeSeriesDetail != null) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Button(onClick = onCloseSeriesDetail) {
                            Text("Volver a Series")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = activeSeriesDetail.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        activeSeriesDetail.seasons.forEach { season ->
                            item(key = "season-${season.season}") {
                                Text(
                                    text = "Temporada ${season.season}",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                            items(season.episodes, key = { it.id }) { ep ->
                                var isFocused by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .border(
                                            width = if (isFocused) 2.dp else 0.dp,
                                            color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .focusable()
                                        .clickable {
                                            onSelectStream(
                                                ep.url,
                                                "${activeSeriesDetail.title} - T${season.season}:E${ep.num} ${ep.title}",
                                                -1
                                            )
                                        }
                                        .padding(10.dp)
                                ) {
                                    Text(
                                        text = "E${ep.num} - ${ep.title}",
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(seriesList, key = { it.id }) { series ->
                        VodPosterCard(
                            title = series.title,
                            origin = series.origin,
                            posterUrl = series.poster,
                            imageLoader = imageLoader,
                            onClick = { onSelectSeries(series) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VodPosterCard(
    title: String,
    origin: String,
    posterUrl: String,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(6.dp))
        ) {
            AsyncImage(
                model = posterUrl,
                imageLoader = imageLoader,
                contentDescription = title,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                OriginBadge(origin = origin)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun OriginBadge(origin: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF263238))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = origin,
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
fun Media3HlsPlayer(
    streamUrl: String?,
    streamTitle: String,
    isTelevision: Boolean
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                if (isTelevision) 35_000 else 25_000,
                1_500,
                3_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(HlsMediaSource.Factory(dataSourceFactory).setAllowChunklessPreparation(true))
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .build()
            .apply {
                playWhenReady = true
            }
    }

    LaunchedEffect(streamUrl) {
        if (!streamUrl.isNullOrBlank()) {
            val mediaItem = MediaItem.fromUri(streamUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    controllerAutoShow = false
                    keepScreenOn = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (streamTitle.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = streamTitle,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
