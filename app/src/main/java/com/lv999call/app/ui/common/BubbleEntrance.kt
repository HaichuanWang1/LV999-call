package com.lv999call.app.ui.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 记录「哪些气泡已经播过入场动画」。
 *
 * 为什么需要它：LazyColumn 会把滚出屏幕的 item 销毁、滚回来时重建，
 * 如果入场动画只靠 item 自己的 `remember` 判断，用户每次上下滚动
 * 都会看到整屏气泡重新飞入一遍 —— 很廉价。
 *
 * 这里刻意用**普通 HashSet 而不是 SnapshotState**：标记「已播过」不需要
 * 触发任何重组（动画由 item 自己的 remembered 状态驱动），用可观察状态
 * 反而会让每条气泡多重组一次。
 *
 * 线程约束：只在主线程（Composition / LaunchedEffect）访问。
 */
@Stable
class BubbleEntranceTracker {

    private val played = HashSet<Any>()

    /** 该 key 是否还需要播入场动画 */
    fun shouldAnimate(key: Any): Boolean = key !in played

    /** 标记该 key 已播过（由 item 在首次组合后回调） */
    fun markPlayed(key: Any) {
        played.add(key)
    }
}

@Composable
fun rememberBubbleEntranceTracker(): BubbleEntranceTracker = remember { BubbleEntranceTracker() }

/**
 * 气泡入场过渡：淡入 + 轻微放大 + 从下方滑入。
 *
 * 用 [graphicsLayer] 而不是 `AnimatedVisibility`：后者会让 item 的高度从 0 长出来，
 * 列表里几十条气泡同时播放时滚动位置会跳，而 graphicsLayer 只影响绘制、
 * 不参与布局，列表是稳的。
 *
 * @param animate 是否需要动画（false = 直接呈现最终态）
 * @param onPlayed 首次组合后回调，供 [BubbleEntranceTracker] 记账
 */
@Composable
fun Modifier.bubbleEntrance(
    animate: Boolean,
    onPlayed: () -> Unit = {}
): Modifier {
    // 初值只在首次组合时取一次：之后父层重组传入的 animate 变化不会打断正在跑的动画
    var entered by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) {
        // 无论这一帧是否播动画，都算「已经露过面」——
        // 否则「生成中气泡原地转成正式气泡」这种不播动画的路径不会被记账，
        // 等它被列表回收再滚回来时会突然重播一次。
        onPlayed()
        entered = true
    }
    // 用 State 而不是解包值：状态读取落在 draw 阶段，每帧只重绘不重组
    val progress = animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "bubbleEntrance"
    )
    return this.graphicsLayer {
        val p = progress.value
        alpha = p
        val s = 0.94f + 0.06f * p
        scaleX = s
        scaleY = s
        translationY = (1f - p) * 16.dp.toPx()
    }
}
