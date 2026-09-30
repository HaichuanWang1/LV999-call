package com.lv999call.app.ui.memory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lv999call.app.domain.model.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆库页面的接线层：把 [MemoryViewModel] 的状态摊平成参数，并把导出用的
 * `ACTION_CREATE_DOCUMENT` 启动器挂在这里。
 *
 * 为什么启动器不放 [MemoryScreen] 里：它需要 [android.content.Context] 与协程作用域，
 * 属于"接线"而不是"渲染"。拆开后 [MemoryScreen] 仍然是纯参数进、事件出的可预览组件，
 * 与项目里其它页面（HomeScreen / HistoryScreen）的写法一致。
 */
@Composable
fun MemoryRoute(
    viewModel: MemoryViewModel,
    onBack: () -> Unit
) {
    val memories by viewModel.memories.collectAsState()
    val filterChips by viewModel.filterChips.collectAsState()
    val selectedFilter by viewModel.filter.collectAsState()
    val counts by viewModel.counts.collectAsState()
    val characterNames by viewModel.characterNames.collectAsState()
    val sessionCreatedAt by viewModel.sessionCreatedAt.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    val isSummarizing by viewModel.isSummarizing.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    MemoryScreen(
        memories = memories,
        filterChips = filterChips,
        selectedFilter = selectedFilter,
        counts = counts,
        characterNames = characterNames,
        sessionCreatedAt = sessionCreatedAt,
        pendingCount = pendingCount,
        isSummarizing = isSummarizing,
        statusMessage = statusMessage,
        onSelectFilter = { viewModel.selectFilter(it) },
        onDeleteMemory = { viewModel.deleteMemory(it) },
        onClearAll = { viewModel.clearAll() },
        onSummarizePending = { viewModel.summarizePending() },
        buildExportText = { viewModel.buildExportText() },
        exportFileName = { viewModel.exportFileName() },
        onStatusConsumed = { viewModel.consumeStatusMessage() },
        onBack = onBack
    )
}

/**
 * 记忆库（plan4 §6.2）。
 *
 * 页面存在的意义不是"好看"，而是 R8/R10 的合规底线：用户必须能自己看见 App 记了什么、
 * 能删、能清空、能带走（导出）。所以列表、筛选、删除三者缺一不可。
 *
 * @param counts 用于顶栏总数与 `未注入` 提示（带全库口径，不受筛选影响）
 * @param characterNames / [sessionCreatedAt] 只用于把角色键与会话 id 显示成人能认的字
 * @param buildExportText 生成导出正文（要读库，所以是挂起函数）
 * @param exportFileName SAF 的默认文件名（用户仍可在系统选择器里改）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MemoryScreen(
    memories: List<Memory>,
    filterChips: List<MemoryFilterChip>,
    selectedFilter: MemoryFilter,
    counts: MemoryCounts,
    characterNames: Map<String, String>,
    sessionCreatedAt: Map<String, Long>,
    pendingCount: Int,
    isSummarizing: Boolean,
    statusMessage: String?,
    onSelectFilter: (MemoryFilter) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onClearAll: () -> Unit,
    onSummarizePending: () -> Unit,
    buildExportText: suspend () -> String,
    exportFileName: () -> String,
    onStatusConsumed: () -> Unit,
    onBack: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 长按 → 二次确认，与首页删除自定义方案完全同一套交互（不引入新习惯）
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    // ViewModel 的一次性提示（整理结果）。先消费再展示：showSnackbar 会挂起到用户看完，
    // 中途离开页面的话协程被取消，消息就不会被清掉、回来还会再弹一次
    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        onStatusConsumed()
        snackbarHostState.showSnackbar(message)
    }

    /**
     * 导出：`ACTION_CREATE_DOCUMENT`（让用户自己选位置）。
     *
     * 用 `ActivityResultContracts.CreateDocument` 而不是手搓 Intent —— 它内部就是这条 action，
     * 还顺带处理了返回的 uri 授权。不需要新权限、也不需要新依赖。
     *
     * 失败分三种，都要给用户一句话：用户取消（uri == null）、拿不到输出流（"无法写入所选位置"）、
     * 写入抛异常（供应商/磁盘问题）。任何一条都不能只是静默返回 —— 那看起来就像"点不动"。
     */
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) {
            scope.launch { snackbarHostState.showSnackbar("已取消导出") }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val text = buildExportText()
            val error = withContext(Dispatchers.IO) {
                try {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: return@withContext "无法写入所选位置"
                    // 显式 UTF-8：导出的是中文备忘，跟随平台默认编码会变乱码
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            snackbarHostState.showSnackbar(if (error == null) "已导出全部记忆" else "导出失败：$error")
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ===== 顶栏：标题 + 总条数 =====
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colors.onSurface)
                }
                Text(
                    text = "记忆库",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${counts.total} 条",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }

            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.3f))

            // ===== 角色筛选：与设置页 ASR 那排 FilterChip 同一套配色 =====
            // 横向可滚：角色多了以后（内置角色会增长）一行放不下
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filterChips.forEach { chip ->
                    FilterChip(
                        selected = chip.filter == selectedFilter,
                        onClick = { onSelectFilter(chip.filter) },
                        label = { Text(if (chip.count > 0) "${chip.label} ${chip.count}" else chip.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primary.copy(alpha = 0.2f),
                            selectedLabelColor = colors.primary
                        )
                    )
                }
            }

            // ===== 待整理提示：N = 0 时整块不出现（plan4 §5.5）=====
            if (pendingCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "有 $pendingCount 通对话还没整理",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSummarizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("整理中…", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    } else {
                        TextButton(onClick = onSummarizePending) { Text("立即整理") }
                    }
                }
            }

            // `未注入` 的含义不说清楚，用户只会看到一排看不懂的小标
            if (counts.flaggedTotal > 0) {
                Text(
                    text = "有 ${counts.flaggedTotal} 条含指令式措辞，已标「未注入」：" +
                        "留在库里可读可删，但不会进入对话上下文。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            // ===== 列表 =====
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(memories, key = { it.id }) { memory ->
                    MemoryCard(
                        memory = memory,
                        characterName = characterNames[memory.characterId] ?: memory.characterId,
                        sessionAt = sessionCreatedAt[memory.sessionId],
                        timeFormat = timeFormat,
                        onLongPress = { pendingDelete = memory.id }
                    )
                }

                if (memories.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                // 空库与"这个角色还没记忆"是两件事，分开说才知道下一步该做什么
                                text = if (counts.total == 0) {
                                    "还没有记忆\n打完一通值得记住的电话，它就会出现在这里"
                                } else {
                                    "这个角色还没有记忆"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // ===== 底部三个按钮 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showClearDialog = true },
                    enabled = counts.total > 0,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = shapes.medium,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("一键清空", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { exportLauncher.launch(exportFileName()) },
                    enabled = counts.total > 0,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = shapes.medium,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("导出", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onSummarizePending,
                    enabled = !isSummarizing,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = shapes.medium,
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary)
                ) {
                    if (isSummarizing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("立即整理", fontWeight = FontWeight.Bold)
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)
        )
    }

    // ===== 删除二次确认（与首页删除方案同一套弹窗写法）=====
    pendingDelete?.let { memoryId ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记忆") },
            text = { Text("删除后角色不会再想起这件事。那通电话的记录仍然保留。") },
            confirmButton = {
                TextButton(onClick = { onDeleteMemory(memoryId); pendingDelete = null }) {
                    Text("删除", color = colors.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空记忆库") },
            text = { Text("将删除全部 ${counts.total} 条记忆，且无法恢复。电话记录不受影响 —— 想留个底可以先导出一份。") },
            confirmButton = {
                TextButton(onClick = { onClearAll(); showClearDialog = false }) {
                    Text("清空", color = colors.error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }
}

/**
 * 一条记忆。
 *
 * 长按删除与首页的预设卡片是同一套交互（长按 → 弹窗确认），并在卡片里明写「长按删除」——
 * 手势本身不可发现，不写出来就等于没有这个功能。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryCard(
    memory: Memory,
    characterName: String,
    sessionAt: Long?,
    timeFormat: SimpleDateFormat,
    onLongPress: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val flagged = memory.category == Memory.CATEGORY_SUMMARY_FLAGGED

    var expanded by remember(memory.id) { mutableStateOf(false) }
    // 只有真的被截断过才需要"展开"：否则点正文什么都不发生，会以为是没反应
    var overflows by remember(memory.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                lineHeight = 20.sp,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        // 被截断的正文自己接管手势：Compose 的子节点会先消费按下事件，
                        // 若这里只挂 clickable（不挂长按），长按正文区就再也传不到卡片上，
                        // "长按删除"在正文上会失效。所以展开/收起与长按删除必须挂在同一处。
                        if (overflows || expanded) {
                            Modifier.combinedClickable(
                                onClick = { expanded = !expanded },
                                onLongClick = onLongPress
                            )
                        } else {
                            Modifier
                        }
                    )
            )
            if (overflows || expanded) {
                Text(
                    text = if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        // 同样带上长按：卡片里明写了「长按删除」，那就得整张卡片都作数
                        .combinedClickable(onClick = { expanded = !expanded }, onLongClick = onLongPress)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildString {
                        append(characterName)
                        append(" · ").append(timeFormat.format(Date(memory.createdAt)))
                        append(" · ").append(
                            // 会话被删时记忆会一起级联消失，正常不该走到 else 分支
                            sessionAt?.let { "来自 ${timeFormat.format(Date(it))} 的通话" } ?: "来源通话已不在"
                        )
                        append(" · 重要度 ").append(memory.importance)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (flagged) {
                    Spacer(modifier = Modifier.width(6.dp))
                    UninjectedBadge()
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "长按删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        }
    }
}

/** `summary_flagged` 的小标：它没被用上，但依然可读可删（plan4 §3.2 Step 6） */
@Composable
private fun UninjectedBadge() {
    val colors = MaterialTheme.colorScheme
    Text(
        text = "未注入",
        style = MaterialTheme.typography.labelSmall,
        color = colors.error,
        modifier = Modifier
            .border(1.dp, colors.error.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** 折叠态的行数（plan4 §6.2：默认最多 3 行） */
private const val COLLAPSED_LINES = 3
