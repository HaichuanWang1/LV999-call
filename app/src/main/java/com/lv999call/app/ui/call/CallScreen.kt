package com.lv999call.app.ui.call

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lv999call.app.domain.model.CallState
import com.lv999call.app.domain.model.ChatMessage
import com.lv999call.app.domain.model.BuiltInCharacter
import com.lv999call.app.ui.live2d.Live2DStatus
import com.lv999call.app.ui.live2d.Live2DView
import com.lv999call.app.ui.live2d.rememberLive2DController
import com.lv999call.app.ui.common.Live2DAuthorCredit
import com.lv999call.app.ui.common.bubbleEntrance
import com.lv999call.app.ui.common.rememberBubbleEntranceTracker
import com.lv999call.app.ui.theme.UltraFlowTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 通话状态 → Live2D 状态标识 */
private fun CallState.toLive2DState(): String = when (this) {
    CallState.IDLE -> "idle"
    CallState.LISTENING -> "listening"
    CallState.THINKING -> "thinking"
    CallState.SPEAKING -> "speaking"
    CallState.ENDED -> "ended"
}

/**
 * LLM 情绪表情的保持时长。
 *
 * 不按「触发后固定 N 秒」计时 —— 标签是在**生成阶段**就到的，而 TTS 要等整段回复
 * 生成完、再合成参考音色，慢的时候好几秒。固定计时器会在角色刚开口时正好到期，
 * 玩家看到的就是「表情闪一下就没了」，跟没做一样。
 *
 * 因此改成跟着这一轮走：保持到状态回到聆听态（本轮说完）为止，
 * 另加最短/最长兜底 —— 最短保证「刚到就结束」也有个可感的停留，
 * 最长防止状态机卡住时表情永久糊在脸上。
 */
private const val EXPRESSION_MIN_HOLD_MS = 1_500L
private const val EXPRESSION_MAX_HOLD_MS = 30_000L

/**
 * 进入说话态后，表情最多再保持多久（毫秒）
 *
 * 表情从标签到达（生成阶段）就挂上，原先一直挂到本轮说完。但 `04 晕 / 07 星星眼 /
 * 06 0.0` 这类夸张脸挂满一整段十几秒的话会显得很傻 —— 真人也不会一边说话一边
 * 维持定格表情。所以进入说话态后再保持这么久就回落到普通脸。
 * 嫌表情太短就调大，嫌傻就调小。
 */
private const val EXPRESSION_SPEAKING_HOLD_MS = 3_500L

/**
 * 挂断过场时长（毫秒）
 *
 * ENDED 时 NavGraph 会立刻跳转历史页、Live2DView 也会被暂停，不延迟的话
 * "还原变身"根本看不到。这个值必须和 NavGraph 跳转前的等待一致，故抽成常量。
 */
const val HANGUP_TRANSFORM_MS = 2_300L

// ===================== 板块外观 =====================

/**
 * 「主板块」的圆角 —— Live2D 舞台与对话框共同装在这一个容器里。
 *
 * 做成一个整块（而不是上下两张卡片）是有意的：plan1 四-1 要的是
 * 「模型和对话框都装进板块里」，一个容器天然就没有两块卡片之间的
 * 缝隙、错位与互相遮挡问题，边界只需要表达一次。
 */
private val STAGE_SHAPE = RoundedCornerShape(28.dp)

/**
 * 舞台板块里模型相对板块的填充比例。
 *
 * 小于 1 是刻意的：留一圈安全边距，角色的头发/脚不会贴着圆角边线，
 * 也就不会出现"模型被板块切掉一块"的观感（plan1 四-2：不要超出边界）。
 */
private const val STAGE_FILL_RATIO = 0.94f

@Composable
fun CallScreen(
    callState: CallState,
    messages: List<ChatMessage>,
    currentResponse: String,
    audioLevel: Float,
    avatarUri: String?,
    avatarResId: Int? = null,
    backgroundUri: String? = null,
    backgroundResId: Int? = null,
    onHangUp: () -> Unit,
    onToggleMute: () -> Unit,
    onSendText: (String) -> Unit,
    isMuted: Boolean = false,
    /** 是否启用 Live2D 形象（加载失败时自动回退到静态头像） */
    live2dEnabled: Boolean = true,
    /** 是否播放接通/挂断的"变身"过场（纯演出，关掉不影响待机动作、表情与口型） */
    transformEnabled: Boolean = true,
    /**
     * 当前内置角色（自定义预设通话时为 null）。
     *
     * 决定 Live2D 模型路径与形象档位、静态头像兜底、舞台左下角署名。
     * 传 null 时全部走通用兜底 —— 本组件不认识任何具体角色。
     */
    character: BuiltInCharacter? = null,
    /** LLM 触发的情绪表情，null 表示无 */
    expressionCue: ExpressionCue? = null,
    /**
     * 是否处于「思考中占位」阶段：语音识别结束 → 首个可见字到达之间。
     *
     * 由 CallViewModel 显式给出，而不是让 UI 看 `currentResponse.isEmpty()` 去猜 ——
     * 否则首字到达前那段空白里 UI 不知道要不要先摆一个转圈气泡。
     */
    isThinkingResponse: Boolean = false,
    /**
     * 「没听清」提示的计数器（[CallViewModel.asrRetryHint]）。
     *
     * 识别为空时不该伪造一条用户消息写进历史，但也不能毫无反馈 ——
     * 这里在状态胶囊上短暂顶一句提示，然后自然回到「聆听中…」。
     * 用自增计数而非布尔：连续两次没听清时 LaunchedEffect 才会重新触发。
     */
    asrRetryHint: Int = 0
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val ext = UltraFlowTheme.extendedColors
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    // 记住哪些气泡已经播过入场动画（列表回收重建时不重播）
    val entranceTracker = rememberBubbleEntranceTracker()

    // 「没听清」提示：计数变化时短暂顶替状态胶囊文案，之后自动恢复
    var showAsrRetryHint by remember { mutableStateOf(false) }
    LaunchedEffect(asrRetryHint) {
        if (asrRetryHint > 0) {
            showAsrRetryHint = true
            kotlinx.coroutines.delay(2000)
            showAsrRetryHint = false
        }
    }

    // ===================== Live2D 形象 =====================
    val l2d = rememberLive2DController()
    var l2dStatus by remember { mutableStateOf(Live2DStatus.LOADING) }
    // 只有启用且未出错时才用 Live2D 渲染，否则回退到静态头像
    val live2dActive = live2dEnabled && l2dStatus != Live2DStatus.ERROR

    // 过场只在"角色真的有这套演出"且用户没关掉时才播。
    //
    // character 为 null 表示自定义预设（形象由 bridge.js 默认档位提供，即银狼），
    // 这一档是有过场的 —— 所以未知角色按 true 处理，保持改造前自定义预设的观感，
    // 而不是把过场一起"优化"掉。DeepSeek 酱这类明确声明没有过场的角色才关。
    val transformActive = transformEnabled && (character?.hasTransform ?: true)

    // 舞台板块的实际尺寸：模型要按「板块」而不是「屏幕」重新适配，
    // 否则从全屏铺底改成板块内嵌后，按宽度缩放会把角色的头脚裁掉。
    var stageSize by remember { mutableStateOf(IntSize.Zero) }

    // 状态联动：等模型就绪后再下发，避免指令丢失
    LaunchedEffect(callState, l2dStatus) {
        if (l2dStatus != Live2DStatus.READY) return@LaunchedEffect
        l2d.setState(callState.toLive2DState())
        l2d.setMouthEnabled(callState == CallState.SPEAKING)
    }

    // 接通过场：模型就绪后播一次变身（进入→还原，约 4.7s），
    // 正好盖住"等首句"的那段空白。只播一次 —— 上面的 LaunchedEffect
    // 每次状态切换都会重跑，触发放在那里会变成每轮对话都变身。
    var entrancePlayed by remember { mutableStateOf(false) }
    LaunchedEffect(l2dStatus) {
        if (l2dStatus == Live2DStatus.READY && !entrancePlayed) {
            entrancePlayed = true
            if (transformActive) l2d.playTransform("full")
        }
    }

    // 挂断过场：播"还原"（约 2.3s）。跳转前的等待在 NavGraph，用的是同一个常量，
    // 且那边同样只在 transformActive 时才等。
    var hangupAnimating by remember { mutableStateOf(false) }
    LaunchedEffect(callState) {
        if (callState == CallState.ENDED && transformActive) {
            hangupAnimating = true
            l2d.playTransform("out")
            delay(HANGUP_TRANSFORM_MS)
            hangupAnimating = false
        }
    }

    // 模型适配舞台板块。
    //
    // 全屏铺底时 bridge.js 用 fitBy='width'，那是为「模型下半身被对话框盖住」
    // 的旧布局调的；现在模型独占舞台板块，视口又矮又窄，继续按宽度会上下裁切。
    // 所以这里按板块宽高比动态选边：板块比内容更"宽扁"就按高度适配，
    // 否则按宽度，效果等价于 contain —— 角色完整落在板块内，不越界。
    LaunchedEffect(stageSize, l2dStatus, l2d.modelInfo) {
        if (l2dStatus != Live2DStatus.READY || stageSize.width == 0 || stageSize.height == 0) {
            return@LaunchedEffect
        }
        val contentAspect = parseContentAspect(l2d.modelInfo?.optString("content"))
        val stageAspect = stageSize.width.toFloat() / stageSize.height.toFloat()
        val fitBy = when {
            contentAspect == null -> "contain"
            contentAspect > stageAspect -> "width"
            else -> "height"
        }
        l2d.setLayout(
            fillRatio = STAGE_FILL_RATIO,
            offsetX = 0f,
            // 模型（Q 版角色）内容中心略偏上更自然，给下方气泡留出呼吸空间
            offsetY = 0.02f,
            fitBy = fitBy
        )
    }

    // 口型驱动：每帧把最新音量推给控制器（控制器内部已限流到 ~60fps）
    SideEffect {
        if (callState == CallState.SPEAKING) l2d.setMouth(audioLevel)
    }

    // 情绪表情联动：LLM 触发后保持到这一轮说完，再回落到状态默认表情。
    // 同时依赖 l2dStatus —— 模型晚于标签就绪时，这里会补一次下发。
    //
    // 必须走 rememberUpdatedState：callState 在这里是普通参数（快照状态在 NavGraph
    // 那层就读掉了），直接写 snapshotFlow { callState } 只会捕获协程启动那一刻的值，
    // 状态一变它永远不会重新 emit —— 实测表现就是表情一直挂着不复位。
    val latestCallState by rememberUpdatedState(callState)
    LaunchedEffect(expressionCue?.seq, l2dStatus) {
        val cue = expressionCue ?: return@LaunchedEffect
        if (l2dStatus != Live2DStatus.READY) return@LaunchedEffect
        l2d.setExpression(cue.modelName)

        val shownFrom = SystemClock.uptimeMillis()
        // 分两段保持：
        //   1. 生成 / 等待期 —— 一直挂着（这是"它在酝酿回复"的表情）
        //   2. 进入说话态之后 —— 最多再挂 EXPRESSION_SPEAKING_HOLD_MS
        // 为什么第二段要收：像"04 晕 / 07 星星眼 / 10 吹泡泡"这种夸张脸，从生成
        // 一路挂到一整段话说完（十几秒）会显得很傻；真人也不会一边说话一边维持
        // 定格表情。注意第二段是从**开始说话**才计时 —— 当初"固定 N 秒"的坑就是
        // 把计时起点放在标签到达（生成阶段），结果开口前就到期、表情白挂。
        withTimeoutOrNull(EXPRESSION_MAX_HOLD_MS) {
            snapshotFlow { latestCallState }.first {
                it == CallState.SPEAKING || it == CallState.LISTENING || it == CallState.ENDED
            }
        }
        if (latestCallState == CallState.SPEAKING) {
            withTimeoutOrNull(EXPRESSION_SPEAKING_HOLD_MS) {
                snapshotFlow { latestCallState }.first {
                    it == CallState.LISTENING || it == CallState.ENDED
                }
            }
        }
        val shownMs = SystemClock.uptimeMillis() - shownFrom
        if (shownMs < EXPRESSION_MIN_HOLD_MS) delay(EXPRESSION_MIN_HOLD_MS - shownMs)

        // 新一轮的 cue 会取消本协程，因此这里只可能是「本轮正常结束」或「超时兜底」
        l2d.setExpression(null)
    }

    // ---------------- 气泡列表（正式消息 + 1 个"当前气泡"） ----------------
    //
    // 正在生成的那一条**不是**独立的 item 类型，而是列表最后一条的两种形态：
    // 转圈占位 → 流式文本。两者用同一个 item 承载，所以切换时气泡不会
    // 消失再重建成两个（plan1 一-5「加载动画与最终输出的衔接」）。

    // 流式气泡「转正」的识别。
    //
    // 回复落地时，流式气泡（key = live-response）消失、列表里换成一条正式的
    // 助手消息（key = assistant-<ts>）。这里记下最后一段流式文本，用来认出
    // 「刚落地的这条助手消息就是它」，从而让交接在同一帧内完成、且不重播入场动画。
    var lastLiveText by remember { mutableStateOf("") }
    LaunchedEffect(currentResponse) {
        if (currentResponse.isNotEmpty()) lastLiveText = currentResponse
    }
    // 新一轮开始时清掉记忆，避免两轮回复内容恰好相同时误判成同一条
    LaunchedEffect(isThinkingResponse) {
        if (isThinkingResponse) lastLiveText = ""
    }
    val streamContinuationKey = messages.lastOrNull()
        ?.takeIf { it.role == "assistant" && lastLiveText.isNotEmpty() && it.content == lastLiveText }
        ?.let { "${it.role}-${it.timestamp}" }

    val showPlaceholder = isThinkingResponse && currentResponse.isEmpty() &&
        callState != CallState.ENDED
    // 已落地成正式消息时立刻收掉流式气泡：ViewModel 是「先写消息、再清空流式文本」
    // 两次独立更新，不这样收会在中间那一帧里同一段话出现两个气泡（闪一下）。
    val showStreaming = currentResponse.isNotEmpty() && streamContinuationKey == null
    val hasLiveBubble = showPlaceholder || showStreaming

    val itemCount = messages.size + if (hasLiveBubble) 1 else 0

    // 新气泡出现：平滑滚到底
    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            delay(80)
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    // 流式文本变长：如果视线本来就在底部，就瞬时跟随。
    // 这里刻意不用 animateScrollToItem —— 它每个 chunk 都会被取消重来，
    // 既抖动又白费动画开销；用户手动往上翻时不打扰（只看最后一屏是否可见）。
    LaunchedEffect(currentResponse) {
        if (currentResponse.isEmpty() || itemCount == 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= itemCount - 1) listState.scrollToItem(itemCount - 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ---------- 背景图 ----------
        when {
            backgroundResId != null -> {
                Image(
                    painter = painterResource(id = backgroundResId),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(6.dp),
                    contentScale = ContentScale.Crop,
                    alpha = 0.3f
                )
            }
            !backgroundUri.isNullOrEmpty() -> {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(backgroundUri).crossfade(true).build(),
                    contentDescription = null, modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, alpha = 0.25f
                )
            }
        }

        // ---------- 整体渐变遮罩 ----------
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(
                        colors.background.copy(alpha = 0.8f),
                        colors.background.copy(alpha = 0.6f),
                        colors.background.copy(alpha = 0.9f)
                    )
                )
            )
        )

        // ---------- 内容层：状态条 / 主板块（舞台 + 对话）/ 控制 ----------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            CallStatusIndicator(callState, showAsrRetryHint)
            Spacer(modifier = Modifier.height(8.dp))

            // ===== 主板块：Live2D 舞台 + 对话框，装在同一个容器里 =====
            //
            // 关于"弱边界"：用一层极低透明度的底色 + 1dp 低透明度描边表达容器感，
            // 而不是实心卡片 —— 目的是让人看出"这里是一块"，又不切断背景氛围。
            //
            // 关于 clip：装着 WebView 的容器**不能**做圆角裁剪。AndroidView 是真实
            // View，Compose 的圆角裁剪对它的硬件层处理不一致，实测会导致模型整块
            // 不上屏（见 index.html 里那段 canvas 合成层注释）。
            // 所以"不超出边界"靠布局本身保证：舞台与对话上下分区、互不重叠，
            // 模型按板块宽高比适配并留 6% 安全边距，而不是靠把越界部分裁掉。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(ext.stageSurface, STAGE_SHAPE)
                    .border(BorderStroke(1.dp, ext.stageBorder), STAGE_SHAPE)
            ) {
                // ---- 舞台区（Live2D）----
                // 顶部两角圆角：板块的圆角靠底色体现，而这里给的是"上半块"的形状
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onSizeChanged { stageSize = it }
                ) {
                    if (live2dActive) {
                        Live2DView(
                            controller = l2d,
                            modifier = Modifier.fillMaxSize(),
                            // 按角色选模型与形象档位（待机通道 / 布局 / 呼吸 / 是否变身）
                            modelPath = character?.modelPath,
                            profileId = character?.live2dProfileId,
                            // 挂断过场期间不能暂停渲染，否则"还原"会停在半路；
                            // 过场结束后（或没有历史记录、不跳转时）照旧暂停省电
                            paused = callState == CallState.ENDED && !hangupAnimating,
                            onStatusChange = { l2dStatus = it }
                        )
                    } else {
                        StaticAvatar(callState, avatarUri, avatarResId)
                    }

                    // 模型作者署名：钉在舞台区左下角，点击跳转作者主页。
                    //
                    // 必须写在 Live2DView **之后**（即叠在它上层）：WebView 是真实
                    // View，会被绘制在所有 Compose 内容之上，反过来放就会被完全盖住。
                    // 舞台区是独立分区、不与下方对话区重叠，所以不会压到气泡。
                    character?.credit?.let { credit ->
                        Live2DAuthorCredit(
                            label = credit.label,
                            url = credit.url,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 12.dp, bottom = 8.dp)
                        )
                    }
                }

                // 分区线：两个区域之间唯一的视觉分隔，比给各自画边框更轻
                HorizontalDivider(color = ext.panelBorder, thickness = 1.dp)

                // ---- 对话区（气泡 + 输入栏）----
                // 底部两角用圆角背景，让对话区自身的浅底色不会把板块的圆角顶成直角
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1.2f)
                        .background(
                            color = ext.panelSurface,
                            shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
                        )
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            items(
                                items = messages,
                                // 稳定 key：LazyColumn 靠它把「同一条消息」认成同一个槽位，
                                // 否则滚动回收后重组会错位，入场动画也会串到别的气泡上
                                key = { "${it.role}-${it.timestamp}" }
                            ) { message ->
                                val key = "${message.role}-${message.timestamp}"
                                MessageBubble(
                                    message = message,
                                    avatarResId = avatarResId,
                                    // 流式刚落地的那条不播入场动画：它上一帧还以流式气泡的
                                    // 形态在屏幕上，重播一次会闪（见 streamContinuationKey）
                                    animateIn = key != streamContinuationKey &&
                                        entranceTracker.shouldAnimate(key),
                                    onEntrancePlayed = { entranceTracker.markPlayed(key) }
                                )
                            }
                            if (hasLiveBubble) {
                                val liveKey = "live-${messages.size}"
                                item(key = "live-response") {
                                    LiveResponseBubble(
                                        text = currentResponse,
                                        thinking = showPlaceholder,
                                        avatarResId = avatarResId,
                                        animateIn = entranceTracker.shouldAnimate(liveKey),
                                        onEntrancePlayed = { entranceTracker.markPlayed(liveKey) }
                                    )
                                }
                            }
                        }

                        // 文字输入栏（放在对话区内，视觉上属于"这个对话框"）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text("输入消息…", color = colors.onSurfaceVariant.copy(alpha = 0.5f))
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.primary,
                                    unfocusedBorderColor = colors.outline.copy(alpha = 0.4f),
                                    focusedTextColor = colors.onSurface,
                                    unfocusedTextColor = colors.onSurface,
                                    cursorColor = colors.tertiary
                                ),
                                shape = shapes.large,
                                enabled = callState != CallState.ENDED
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    if (inputText.isNotBlank()) {
                                        onSendText(inputText.trim())
                                        inputText = ""
                                    }
                                },
                                enabled = inputText.isNotBlank() && callState != CallState.ENDED,
                                modifier = Modifier.size(44.dp).clip(CircleShape).background(
                                    if (inputText.isNotBlank()) colors.primary else colors.surfaceVariant
                                )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send, "发送",
                                    tint = if (inputText.isNotBlank()) colors.onPrimary else colors.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ===== 底部控制栏 =====
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(ext.panelBorder)
                ) {
                    Icon(
                        if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        if (isMuted) "取消静音" else "静音",
                        tint = if (isMuted) colors.error else colors.onSurface,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.width(28.dp))
                IconButton(
                    onClick = onHangUp,
                    modifier = Modifier.size(68.dp).clip(CircleShape).background(ext.callEnd)
                ) {
                    Icon(Icons.Default.CallEnd, "挂断", tint = colors.onError, modifier = Modifier.size(34.dp))
                }
                Spacer(modifier = Modifier.width(28.dp))
                Spacer(modifier = Modifier.size(52.dp))
            }
        }
    }
}

/**
 * 解析 bridge.js 上报的内容包围盒（形如 `"2160x3760@-1080,-1880"`），返回宽高比。
 *
 * 解析失败返回 null，调用方退回 `contain` —— 宁可小一点，也不要裁到角色。
 */
private fun parseContentAspect(content: String?): Float? {
    val size = content?.substringBefore('@') ?: return null
    val parts = size.split('x')
    if (parts.size != 2) return null
    val w = parts[0].toFloatOrNull() ?: return null
    val h = parts[1].toFloatOrNull() ?: return null
    if (w <= 0f || h <= 0f) return null
    return w / h
}

/**
 * 一条正式消息气泡。
 *
 * 样式：中性灰半透明 + 1dp 弱描边（plan1 一-4）。
 * 之所以不做真·毛玻璃（背景模糊）：Compose 没有 backdrop blur，`Modifier.blur`
 * 模糊的是**自身内容**，拿来实现毛玻璃只会把文字一起糊掉。
 */
@Composable
private fun MessageBubble(
    message: ChatMessage,
    avatarResId: Int? = null,
    animateIn: Boolean = false,
    onEntrancePlayed: () -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val ext = UltraFlowTheme.extendedColors
    val isUser = message.role == "user"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bubbleEntrance(animate = animateIn, onPlayed = onEntrancePlayed)
            .padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            AssistantAvatar(avatarResId)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Card(
            shape = shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) ext.userBubble else ext.aiBubble
            ),
            border = BorderStroke(1.dp, ext.bubbleBorder),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                lineHeight = 20.sp
            )
        }
        if (isUser) Spacer(modifier = Modifier.width(8.dp))
    }
}

/**
 * 「当前正在生成」的那一个气泡：转圈占位 ⇄ 流式文本。
 *
 * 两种形态共用一个气泡外壳与同一个 item key，所以：
 * - 首字到达时不会「旧气泡消失 + 新气泡出现」，只是气泡内部换了内容；
 * - 用 [Crossfade] 交接，转圈淡出、文字淡入，没有硬切。
 */
@Composable
private fun LiveResponseBubble(
    text: String,
    thinking: Boolean,
    avatarResId: Int? = null,
    animateIn: Boolean = false,
    onEntrancePlayed: () -> Unit = {}
) {
    val ext = UltraFlowTheme.extendedColors
    val shapes = MaterialTheme.shapes

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bubbleEntrance(animate = animateIn, onPlayed = onEntrancePlayed)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        AssistantAvatar(avatarResId)
        Spacer(modifier = Modifier.width(8.dp))
        Card(
            shape = shapes.large,
            colors = CardDefaults.cardColors(containerColor = ext.aiBubble),
            border = BorderStroke(1.dp, ext.bubbleBorder),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Crossfade(
                targetState = thinking,
                animationSpec = tween(180),
                label = "thinkingToText"
            ) { isThinking ->
                if (isThinking) {
                    ThinkingDots(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                } else {
                    StreamingText(
                        text = text,
                        streaming = true,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}

/** 助手侧头像（Live2D 不可用或预设模式下的兜底形象） */
@Composable
private fun AssistantAvatar(avatarResId: Int?) {
    Image(
        painter = painterResource(id = avatarResId ?: com.lv999call.app.R.drawable.touxiang),
        contentDescription = null,
        modifier = Modifier.size(32.dp).clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}

/**
 * 气泡内的小转圈 —— 「模型正在思考」的占位动画。
 *
 * 用自绘的三点脉冲而不是 CircularProgressIndicator：
 * 前者是「对方正在输入」的语义，比进度环更贴合聊天语境，也不会让人以为
 * 在下载什么东西。三个点错峰起落，肉眼看着就是一个转圈的节奏。
 */
@Composable
private fun ThinkingDots(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "thinkingDots")
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 480, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    // 错峰：每个点比前一个晚 160ms 起跳
                    initialStartOffset = StartOffset(index * 160)
                ),
                label = "dot$index"
            )
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(colors.onSurfaceVariant)
            )
        }
    }
}

/**
 * 文本展示：流式时在末尾跟一个闪烁光标。
 *
 * 光标用 [androidx.compose.foundation.text.BasicText] 的 AnnotatedString 拼不进去，
 * 所以这里用「文本 + 光标」并排的写法，让 Row 的基线对齐把它摆在最后一个字的右下角。
 */
@Composable
private fun StreamingText(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            lineHeight = 20.sp
        )
        if (streaming) {
            Spacer(modifier = Modifier.width(3.dp))
            BlinkingCaret()
        }
    }
}

/** 流式输出末尾的闪烁光标（模拟"还在打字"） */
@Composable
private fun BlinkingCaret() {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "caret")
    val caretAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "caretAlpha"
    )
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(16.dp)
            .graphicsLayer { alpha = caretAlpha }
            .clip(RoundedCornerShape(1.dp))
            .background(colors.tertiary)
    )
}

/**
 * 静态头像（Live2D 不可用时的降级形态）
 * 保留原有的呼吸缩放与状态光晕效果。
 */
@Composable
private fun StaticAvatar(callState: CallState, avatarUri: String?, avatarResId: Int?) {
    val colors = MaterialTheme.colorScheme
    val ext = UltraFlowTheme.extendedColors

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val infiniteTransition = rememberInfiniteTransition(label = "breathing")
        val breathScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (callState == CallState.SPEAKING) 1.08f else 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathScale"
        )
        Box(
            modifier = Modifier.scale(breathScale).size(140.dp).clip(CircleShape).background(
                Brush.radialGradient(
                    colors = when (callState) {
                        CallState.LISTENING -> listOf(ext.listening.copy(alpha = 0.4f), ext.listening.copy(alpha = 0.1f))
                        CallState.THINKING -> listOf(ext.thinking.copy(alpha = 0.4f), ext.thinking.copy(alpha = 0.1f))
                        CallState.SPEAKING -> listOf(ext.speaking.copy(alpha = 0.4f), ext.speaking.copy(alpha = 0.1f))
                        else -> listOf(colors.primary.copy(alpha = 0.3f), colors.primary.copy(alpha = 0.1f))
                    }
                )
            ),
            contentAlignment = Alignment.Center
        ) {
            when {
                avatarResId != null -> Image(
                    painter = painterResource(id = avatarResId),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                !avatarUri.isNullOrEmpty() -> AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(avatarUri).crossfade(true).build(),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                else -> Text(
                    text = "✦",
                    fontSize = 40.sp,
                    color = colors.tertiary
                )
            }
        }
    }
}

/** 通话状态指示（胶囊） */
@Composable
private fun CallStatusIndicator(callState: CallState, showAsrRetryHint: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val ext = UltraFlowTheme.extendedColors

    val (label, tint) = if (showAsrRetryHint) {
        // 「没听清」优先于常规状态文案：这是用户当下最需要知道的信息
        "没听清，再说一次～" to ext.thinking
    } else when (callState) {
        CallState.LISTENING -> "聆听中…" to ext.listening
        CallState.THINKING -> "思考中…" to ext.thinking
        CallState.SPEAKING -> "说话中…" to ext.speaking
        CallState.ENDED -> "通话已结束" to ext.callEnd
        CallState.IDLE -> "" to colors.primary
    }

    AnimatedVisibility(
        visible = label.isNotEmpty(),
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 2 } + scaleIn(initialScale = 0.9f),
        exit = fadeOut(tween(160))
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ext.panelSurface)
                    .border(BorderStroke(1.dp, ext.panelBorder), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 状态点：呼吸式脉动
                val transition = rememberInfiniteTransition(label = "statusDot")
                val dotAlpha by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dotAlpha"
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .graphicsLayer { alpha = dotAlpha }
                        .clip(CircleShape)
                        .background(tint)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurface.copy(alpha = 0.85f),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
