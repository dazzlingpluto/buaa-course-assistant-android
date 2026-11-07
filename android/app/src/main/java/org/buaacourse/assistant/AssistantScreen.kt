package org.buaacourse.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal val Ink = Color(0xFF193D65)
internal val SoftInk = Color(0xFF526F83)
internal val Blue = Color(0xFF2173BD)
internal val DeepBlue = Color(0xFF205D98)
internal val Paper = Color(0xFFF5F9FC)
internal val Line = Color(0xFFCCDFEF)
internal val Tint = Color(0xFFEAF5FF)
private const val SOURCE_PROJECT_URL = "https://github.com/dazzlingpluto/buaa-course-assistant-android"

/** Callbacks keep rendering testable without a live school account or Android Keystore. */
data class AssistantActions(
    val username: (String) -> Unit = {},
    val password: (String) -> Unit = {},
    val captcha: (String) -> Unit = {},
    val refreshCaptcha: () -> Unit = {},
    val mode: (NetworkMode) -> Unit = {},
    val language: () -> Unit = {},
    val remember: (Boolean) -> Unit = {},
    val autoLogin: (Boolean) -> Unit = {},
    val forget: () -> Unit = {},
    val login: () -> Unit = {},
    val logout: () -> Unit = {},
    val week: (Int) -> Unit = {},
    val currentWeek: () -> Unit = {},
    val refresh: () -> Unit = {},
    val academicTerm: (String) -> Unit = {},
    val sign: (Course) -> Unit = {},
    val dismissMessage: () -> Unit = {},
    val dismissError: () -> Unit = {},
)

@Composable
internal fun AssistantScreen(viewModel: AssistantViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AssistantContent(
        state,
        AssistantActions(
            username = viewModel::setUsername,
            password = viewModel::setPassword,
            captcha = viewModel::setCaptcha,
            refreshCaptcha = viewModel::refreshCaptcha,
            mode = viewModel::setMode,
            language = viewModel::toggleLanguage,
            remember = viewModel::setRemember,
            autoLogin = viewModel::setAutoLogin,
            forget = viewModel::clearSavedCredentials,
            login = viewModel::login,
            logout = viewModel::logout,
            week = viewModel::selectWeek,
            currentWeek = viewModel::currentWeek,
            refresh = viewModel::refresh,
            academicTerm = viewModel::selectAcademicTerm,
            sign = viewModel::signCourse,
            dismissMessage = viewModel::dismissMessage,
            dismissError = viewModel::dismissError,
        ),
    )
}

@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue,
            onPrimary = Color.White,
            primaryContainer = Tint,
            onPrimaryContainer = Ink,
            secondary = DeepBlue,
            background = Paper,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Color(0xFFEFF6FA),
            onSurfaceVariant = SoftInk,
            outline = Line,
            error = Color(0xFFAD3939),
        ),
        content = content,
    )
}

@Composable
fun AssistantContent(state: AssistantUiState, actions: AssistantActions = AssistantActions()) {
    AssistantTheme {
        if (state.user != null) {
            TimetableContent(state, actions)
        } else {
            val words = UiWords(state.language)
            val snackbar = remember { SnackbarHostState() }
            val scrollState = rememberLazyListState()
            var logOpen by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(state.message, state.error) {
                (state.error ?: state.message)?.let {
                    snackbar.showSnackbar(it, duration = if (state.error != null) SnackbarDuration.Long else SnackbarDuration.Short)
                    if (state.error == null) actions.dismissMessage()
                }
            }
            Scaffold(
                modifier = Modifier.fillMaxSize().imePadding(), containerColor = Paper,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = { LogTray(state.logs, logOpen, { logOpen = !logOpen }, words) },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).testTag("assistant_scroll"),
                    state = scrollState, contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item(key = "brand") { CampusHeader(state, actions, words) }
                    item(key = "login") { LoginPanel(state, actions, words, Modifier.pageWidth().padding(top = 2.dp)) }
                    item(key = "welcome") {
                        Text(words.text("登录后查看你的课程", "Sign in to view your courses"),
                            modifier = Modifier.padding(top = 20.dp), color = SoftInk, style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.busy) {
                        item(key = "progress") {
                            Row(Modifier.pageWidth().padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text(state.busyMessage.ifBlank { words.text("处理中，请稍候…", "Working, please wait…") }, color = SoftInk)
                            }
                        }
                    }
                    state.error?.let { error ->
                        item(key = "error") {
                            Surface(modifier = Modifier.pageWidth().padding(top = 16.dp).testTag("error_message"),
                                color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                                    TextButton(onClick = actions.dismissError, modifier = Modifier.align(Alignment.End)) { Text(words.text("关闭", "Dismiss")) }
                                }
                            }
                        }
                    }
                    item(key = "source_project") {
                        val uriHandler = LocalUriHandler.current
                        val scope = rememberCoroutineScope()
                        TextButton(
                            onClick = {
                                try {
                                    uriHandler.openUri(SOURCE_PROJECT_URL)
                                } catch (_: IllegalArgumentException) {
                                    scope.launch {
                                        snackbar.showSnackbar(words.text("无法打开项目链接", "Could not open the project link"))
                                    }
                                }
                            },
                            modifier = Modifier.pageWidth().testTag("source_project_link"),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(words.text("开源项目 · GitHub", "Open source project · GitHub"))
                                Text(SOURCE_PROJECT_URL.removePrefix("https://"),
                                    style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.pageWidth(): Modifier = widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp)

@Composable
internal fun AcademicPanel(academic: AcademicComparison?, busy: Boolean, selectTerm: (String) -> Unit, words: UiWords, studentType: StudentType) {
    var termMenu by remember { mutableStateOf(false) }
    var showExcluded by remember { mutableStateOf(false) }
    var showReason by remember(academic?.termCode, academic?.status, academic?.message) { mutableStateOf(false) }
    LaunchedEffect(academic?.termCode, academic?.status) {
        termMenu = false
        showExcluded = false
    }
    Surface(
        modifier = Modifier.pageWidth().padding(top = 12.dp).testTag("academic_panel"),
        shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Line), color = Color.White,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (studentType) {
                StudentType.UNDERGRADUATE -> words.text("本科生教务课表", "Undergraduate timetable")
                StudentType.GRADUATE -> words.text("研究生教务课表", "Graduate timetable")
                StudentType.AUTO -> words.text("正式课表 · 身份未识别", "Official timetable · Identity not identified")
            }, style = MaterialTheme.typography.labelLarge, color = Ink)
            if (!academic?.terms.isNullOrEmpty()) {
                Box {
                    OutlinedButton(
                        onClick = { termMenu = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("academic_term"),
                        shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Line),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            academic?.termName?.takeIf(String::isNotBlank)
                                ?: words.text("选择教务学期", "Choose academic term"),
                            Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text("⌄", Modifier.padding(start = 8.dp))
                    }
                    DropdownMenu(expanded = termMenu, onDismissRequest = { termMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        academic?.terms.orEmpty().forEach { term ->
                            DropdownMenuItem(
                                text = { Text(term.name.ifBlank { term.code }) }, enabled = !busy,
                                onClick = { termMenu = false; selectTerm(term.code) },
                                modifier = Modifier.testTag("academic_term_${term.code}"),
                            )
                        }
                    }
                }
            }
            val status = when (academic?.status) {
                AcademicStatus.VERIFIED -> words.text(
                    "已加载正式课表，${academic.uncertainCount} 门暂未关联 iClass 签到。",
                    "Official timetable loaded. ${academic.uncertainCount} classes have no linked iClass attendance.",
                )
                AcademicStatus.SELECT_TERM -> words.text(
                    "请选择要查看的教务学期。完成正式课表核对前暂不可签到。",
                    "Choose the academic term to view. Attendance is unavailable until the official timetable is checked.",
                )
                AcademicStatus.UNAVAILABLE -> words.text(
                    "教务课表暂未读取成功，现有课程仅供查看。完成正式课表核对前暂不可签到。",
                    "The official timetable is unavailable. Existing classes are read-only until the official timetable is checked.",
                )
                AcademicStatus.UNMATCHED -> words.text(
                    "两边课程暂未可靠对应，现有课程仅供查看。完成正式课表核对前暂不可签到。",
                    "Classes could not be reliably matched. Existing classes are read-only until the official timetable is checked.",
                )
                null -> words.text(
                    "尚未加载正式课表。",
                    "The official timetable has not loaded yet.",
                )
            }
            Text(status, Modifier.testTag("academic_status"), style = MaterialTheme.typography.bodySmall, color = SoftInk)
            if (academic != null && academic.status in setOf(AcademicStatus.UNAVAILABLE, AcademicStatus.UNMATCHED) && academic.message.isNotBlank()) {
                TextButton(
                    onClick = { showReason = !showReason },
                    modifier = Modifier.align(Alignment.End).testTag("academic_reason_toggle"),
                ) {
                    Text(if (showReason) words.text("收起原因", "Hide reason") else words.text("查看原因", "View reason"))
                }
                if (showReason) {
                    Text(
                        academic.message, Modifier.testTag("academic_reason"),
                        style = MaterialTheme.typography.bodySmall, color = SoftInk,
                    )
                }
            }
            if (!academic?.excluded.isNullOrEmpty()) {
                TextButton(onClick = { showExcluded = true }, modifier = Modifier.align(Alignment.End).testTag("academic_excluded")) {
                    Text(words.text("查看已过滤排课", "View filtered entries"))
                }
            }
        }
    }
    if (showExcluded && academic != null && academic.excluded.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showExcluded = false },
            title = { Text(words.text("已过滤的排课", "Filtered entries")) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp).testTag("academic_excluded_list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text(words.text("以下 iClass 排课未与本周正式课程建立唯一对应，未用于本周课表与签到。这不代表已退课。", "These iClass entries have no unique match to this week's official classes and are not used for this week's timetable or attendance. This does not imply withdrawal."))
                    }
                    items(academic.excluded) { course ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(course.courseName, fontWeight = FontWeight.SemiBold)
                            Text(words.text("课程号：", "Course: ") + course.courseNum.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                            if (course.classBeginTime.isNotBlank()) Text(course.classBeginTime, style = MaterialTheme.typography.bodySmall, color = SoftInk)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExcluded = false }, modifier = Modifier.testTag("close_academic_excluded")) {
                    Text(words.text("关闭", "Close"))
                }
            },
        )
    }
}

@Composable
private fun CampusHeader(state: AssistantUiState, actions: AssistantActions, words: UiWords) {
    val loggedIn = state.user != null
    val compact = LocalConfiguration.current.screenHeightDp < 700
    Box(Modifier.fillMaxWidth().heightIn(min = if (loggedIn || compact) 126.dp else 194.dp)) {
        Image(
            painterResource(R.drawable.campus_watercolor), contentDescription = null,
            modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop,
            alignment = Alignment.BottomEnd,
        )
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color.White.copy(alpha = .60f), Color.Transparent))))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Paper.copy(alpha = .20f), Paper))))
        Column(Modifier.pageWidth().align(Alignment.TopCenter).padding(top = if (loggedIn) 10.dp else 18.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Image(
                        painterResource(R.drawable.buaa_wordmark), contentDescription = "BUAA",
                        modifier = Modifier.width(if (loggedIn) 117.dp else 174.dp).height(if (loggedIn) 43.dp else 61.dp),
                        contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                    )
                    Text(words.text("课程助手", "Course Assistant"), fontWeight = FontWeight.Bold, fontSize = if (loggedIn) 15.sp else 19.sp, color = Ink)
                }
                if (loggedIn) {
                    HeaderButton(words.text("退出登录", "Sign out"), !state.busy, actions.logout, "logout")
                }
                HeaderButton(words.text("EN", "中"), !state.busy, actions.language, "language")
            }
            state.user?.let { user ->
                Text(
                    "UID: ${user.userId}" + user.userName.takeIf(String::isNotBlank)?.let { " ($it)" }.orEmpty(),
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = SoftInk,
                )
            }
        }
    }
}

@Composable
private fun HeaderButton(label: String, enabled: Boolean, action: () -> Unit, tag: String) {
    OutlinedButton(
        onClick = action, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp).testTag(tag),
        shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Line),
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 8.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White.copy(alpha = .88f)),
    ) { Text(label, fontSize = 12.sp) }
}

@Composable
private fun LoginPanel(state: AssistantUiState, actions: AssistantActions, words: UiWords, modifier: Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    var passwordVisible by remember { mutableStateOf(false) }
    val canLogin = !state.busy && state.username.isNotBlank() && state.password.isNotEmpty() && (state.captcha == null || state.captchaInput.isNotBlank())
    Surface(modifier, shape = RoundedCornerShape(23.dp), color = Color.White, border = BorderStroke(1.dp, Line), shadowElevation = 1.dp) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFEFF6FA)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                ModeButton(words.text("校园直连", "Direct"), state.mode == NetworkMode.DIRECT, !state.busy, { actions.mode(NetworkMode.DIRECT) }, Modifier.weight(1f).testTag("mode_direct"))
                ModeButton(words.text("校外网络", "WebVPN"), state.mode == NetworkMode.VPN, !state.busy, { actions.mode(NetworkMode.VPN) }, Modifier.weight(1f).testTag("mode_vpn"))
            }
            OutlinedTextField(
                value = state.username, onValueChange = actions.username,
                modifier = Modifier.fillMaxWidth().testTag("username"), enabled = !state.busy, singleLine = true,
                label = { Text(if (state.mode == NetworkMode.DIRECT) words.text("学号", "Student ID") else words.text("账号", "Username")) },
                placeholder = { Text(words.text("统一认证账号", "SSO username")) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                shape = RoundedCornerShape(12.dp),
            )
            OutlinedTextField(
                value = state.password, onValueChange = actions.password,
                modifier = Modifier.fillMaxWidth().testTag("password"), enabled = !state.busy, singleLine = true,
                label = { Text(words.text("密码", "Password")) },
                placeholder = { Text(words.text("统一认证密码", "SSO password")) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canLogin) { keyboard?.hide(); actions.login() } }),
                trailingIcon = {
                    TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = !state.busy, modifier = Modifier.testTag("show_password")) {
                        Text(if (passwordVisible) words.text("隐藏", "Hide") else words.text("显示", "Show"), fontSize = 12.sp)
                    }
                },
                shape = RoundedCornerShape(12.dp),
            )
            state.captcha?.let { challenge ->
                val picture = remember(challenge) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(challenge.imageBytes, 0, challenge.imageBytes.size, bounds)
                    if (bounds.outWidth in 1..2048 && bounds.outHeight in 1..1024)
                        BitmapFactory.decodeByteArray(challenge.imageBytes, 0, challenge.imageBytes.size)?.asImageBitmap() else null
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = state.captchaInput, onValueChange = actions.captcha,
                        modifier = Modifier.weight(1f).testTag("captcha_input"), enabled = !state.busy, singleLine = true,
                        label = { Text(words.text("验证码", "Code")) }, shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (canLogin) { keyboard?.hide(); actions.login() } }))
                    Surface(Modifier.width(120.dp).height(58.dp).testTag("captcha_image").clickable(enabled = !state.busy, onClick = actions.refreshCaptcha),
                        shape = RoundedCornerShape(10.dp), color = Tint) {
                        if (picture != null) Image(picture, words.text("验证码图片，点击刷新", "Verification image, tap to refresh"), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        else Box(contentAlignment = Alignment.Center) { Text(words.text("点击刷新", "Refresh"), color = Blue) }
                    }
                }
                TextButton(onClick = actions.refreshCaptcha, enabled = !state.busy, modifier = Modifier.align(Alignment.End).testTag("captcha_refresh")) {
                    Text(words.text("看不清？点击刷新验证码", "Refresh verification image"), fontSize = 12.sp)
                }
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoginCheck(words.text("记住密码", "Remember password"), state.remember, !state.busy, actions.remember,
                        Modifier.weight(1f).testTag("remember_credentials"), words)
                    LoginCheck(words.text("自动登录", "Auto sign in"), state.autoLogin, !state.busy, actions.autoLogin,
                        Modifier.weight(1f).testTag("auto_login"), words)
                }
                Text(words.text("仅加密保存在此设备，下次自动填入。", "Encrypted on this device. Filled in next time."), color = SoftInk, style = MaterialTheme.typography.bodySmall)
                if (state.hasSavedCredentials) {
                    TextButton(onClick = actions.forget, enabled = !state.busy, modifier = Modifier.align(Alignment.End).testTag("forget_credentials")) {
                        Text(words.text("清除已保存信息", "Forget saved details"))
                    }
                }
            }
            Button(
                onClick = { keyboard?.hide(); actions.login() }, enabled = canLogin,
                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("login"),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (state.mode == NetworkMode.VPN) words.text("VPN 登录", "VPN Login") else words.text("登 录", "Sign In"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LoginCheck(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier, words: UiWords) {
    Row(modifier.heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 7.dp), fontSize = if (words.english) 12.sp else 15.sp, maxLines = 2)
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, enabled: Boolean, action: () -> Unit, modifier: Modifier) {
    Button(
        onClick = action, enabled = enabled, modifier = modifier.heightIn(min = 48.dp).semantics { this.selected = selected }, shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Blue else Color.Transparent,
            contentColor = if (selected) Color.White else SoftInk,
            disabledContainerColor = if (selected) Blue.copy(alpha = .45f) else Color.Transparent,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
internal fun LogTray(logs: List<AppLog>, expanded: Boolean, toggle: () -> Unit, words: UiWords) {
    Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp), color = Color(0xFFFBFDFE), border = BorderStroke(1.dp, Line), shadowElevation = 2.dp,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 10.dp).testTag("log_toggle")
                    .semantics { contentDescription = if (expanded) words.text("收起运行日志", "Collapse activity log") else words.text("展开运行日志", "Expand activity log") },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val last = logs.firstOrNull()
                Text(
                    last?.let { "[${it.time.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] ${it.message}" } ?: words.text("就绪。", "Ready."),
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (last?.level == "error") MaterialTheme.colorScheme.error else SoftInk, style = MaterialTheme.typography.bodySmall,
                )
                Text(if (expanded) "⌄" else "⌃", color = Blue, fontSize = 20.sp)
            }
            if (expanded) {
                HorizontalDivider(color = Line)
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).background(Color(0xFFF2F7FB)).testTag("activity_log"),
                    contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (logs.isEmpty()) item { Text(words.text("暂无运行记录", "No activity yet"), color = SoftInk) }
                    items(logs) { log ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(log.time.format(DateTimeFormatter.ofPattern("HH:mm:ss")), color = SoftInk, style = MaterialTheme.typography.labelSmall)
                            Text(log.message, color = when (log.level) { "error" -> MaterialTheme.colorScheme.error; "warning" -> Color(0xFF9A672C); "success" -> Blue; else -> Ink }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

internal class UiWords(internal val language: AppLanguage) {
    val english: Boolean get() = language == AppLanguage.EN
    fun text(zh: String, en: String) = if (english) en else zh
    fun studentLabel(type: StudentType): String = when (type) {
        StudentType.UNDERGRADUATE -> text("本科生", "Undergraduate")
        StudentType.GRADUATE -> text("研究生", "Graduate")
        StudentType.AUTO -> text("身份未识别", "Identity not identified")
    }
    fun weekday(date: LocalDate): String {
        val index = date.dayOfWeek.value - 1
        return if (english) listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[index]
        else listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[index]
    }
}
