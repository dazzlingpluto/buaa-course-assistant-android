package org.buaacourse.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
internal fun TimetableContent(state: AssistantUiState, actions: AssistantActions) {
    val words = UiWords(state.language)
    val directAttendance = state.attendanceMode == AttendanceMode.ICLASS
    val snackbar = remember { SnackbarHostState() }
    var logOpen by rememberSaveable { mutableStateOf(false) }
    var academicOpen by rememberSaveable(state.attendanceMode) { mutableStateOf(false) }
    var selectedKey by rememberSaveable(state.user?.userId, state.week, state.attendanceMode) { mutableStateOf<String?>(null) }
    var overlapKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastTerm by remember { mutableStateOf(state.schedule?.academic?.termCode) }
    val gridVertical = rememberScrollState()
    LaunchedEffect(state.week, state.attendanceMode) { gridVertical.scrollTo(0) }
    val entries: List<Pair<LocalDate?, Course>> = state.schedule?.days.orEmpty().flatMap { day -> day.courses.map { day.date to it } } +
        state.schedule?.unscheduled.orEmpty().map { null to it }
    val selected = entries.find { TimetableLayout.key(it.first, it.second) == selectedKey }
    val overlaps = entries.filter { TimetableLayout.key(it.first, it.second) in overlapKeys }
    LaunchedEffect(state.user?.userId, state.week, state.attendanceMode, state.schedule?.academic?.termCode) {
        overlapKeys = emptyList()
        state.schedule?.academic?.termCode?.let { term ->
            if (lastTerm != null && term != lastTerm) selectedKey = null
            lastTerm = term
        }
    }
    LaunchedEffect(selectedKey, selected) { if (selected == null) selectedKey = null }
    LaunchedEffect(state.message, state.error, directAttendance) {
        val snackbarError = state.error.takeUnless { directAttendance }
        (snackbarError ?: state.message)?.let {
            snackbar.showSnackbar(it, duration = if (snackbarError != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (snackbarError == null) actions.dismissMessage()
        }
    }
    BackHandler(enabled = selected != null || overlapKeys.isNotEmpty()) {
        selectedKey = null
        overlapKeys = emptyList()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(), containerColor = Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { LogTray(state.logs, logOpen, { logOpen = !logOpen }, words) },
    ) { padding ->
        if (selected != null) {
            CourseDetail(selected.first, selected.second, state, actions, words, { selectedKey = null }, Modifier.padding(padding))
        } else {
            Column(Modifier.fillMaxSize().padding(padding).testTag("timetable_page")) {
                TimetableHeader(state, actions, words)
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (!directAttendance) TextButton(onClick = { academicOpen = !academicOpen }, modifier = Modifier.fillMaxWidth().testTag("academic_toggle"), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.schedule?.academic?.termName?.takeIf { it.isNotBlank() } ?: words.text("正式课表", "Official timetable"),
                                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                            val status = when (state.schedule?.academic?.status) {
                                AcademicStatus.VERIFIED -> if ((state.schedule?.academic?.uncertainCount ?: 0) > 0) words.text("正式课表 · 部分课程未关联签到", "Official timetable · Some attendance unlinked") else words.text("✓ 正式课表已加载", "✓ Official timetable loaded")
                                AcademicStatus.SELECT_TERM -> words.text("请选择教务学期", "Choose academic term")
                                AcademicStatus.UNAVAILABLE -> words.text("教务读取失败 · 点击查看", "Academic data unavailable")
                                AcademicStatus.UNMATCHED -> words.text("课程待核对 · 点击查看", "Classes need checking")
                                null -> words.text("尚未加载", "Not yet loaded")
                            }
                            Text(status, Modifier.fillMaxWidth().testTag("academic_summary"), textAlign = TextAlign.Center, fontSize = 10.sp, color = SoftInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (academicOpen) "⌃" else "⌄")
                    }
                    Text(if (state.schedule?.attendanceMessage?.contains("正在") == true) words.text("正在读取签到统计…", "Loading attendance totals…") else
                        words.text("已签 ${state.schedule?.attendanceSigned ?: 0} / 共 ${state.schedule?.attendanceTotal ?: 0} 节需签到", "${state.schedule?.attendanceSigned ?: 0} / ${state.schedule?.attendanceTotal ?: 0} attendance records signed"),
                        Modifier.padding(bottom = 6.dp).testTag("attendance_totals"), textAlign = TextAlign.Center, fontSize = 11.sp, color = SoftInk)
                }
                if (!directAttendance && academicOpen) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 250.dp).verticalScroll(rememberScrollState())) {
                        AcademicPanel(state.schedule?.academic, state.busy, actions.academicTerm, words, resolvedStudentType(state))
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("timetable_progress"))
                }
                state.schedule?.attendanceMessage?.takeIf { !directAttendance && it.isNotBlank() }?.let { message ->
                    Text(if (message.contains("正在")) words.text("正在关联签到…", "Linking attendance…") else words.text("签到暂不可用 · 可继续查看课表", "Attendance is temporarily unavailable. Your timetable is ready."),
                        Modifier.fillMaxWidth().background(Tint).padding(horizontal = 14.dp, vertical = 7.dp).testTag("attendance_status"), color = SoftInk, fontSize = 12.sp)
                }
                state.error?.takeUnless { directAttendance }?.let {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, Modifier.weight(1f).testTag("error_message"), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        TextButton(onClick = actions.dismissError) { Text(words.text("关闭", "Dismiss")) }
                    }
                }
                if (state.schedule?.days.isNullOrEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        val prompt = when {
                            directAttendance && state.busy -> words.text("正在读取 iClass 排课…", "Loading iClass classes…")
                            directAttendance && state.error != null -> words.text("请查看底部日志，或点击刷新。", "See the activity log or tap Refresh.")
                            directAttendance -> words.text("暂无 iClass 排课，请点击刷新。", "No iClass classes. Tap Refresh.")
                            state.busy -> words.text("正在加载课表…", "Loading timetable…")
                            !state.schedule?.academic?.terms.isNullOrEmpty() ->
                                words.text("请展开正式课表并选择教务学期。", "Expand Official timetable and choose a term.")
                            else -> words.text("暂无课表，请点击刷新。", "No timetable. Tap Refresh.")
                        }
                        Text(prompt, color = SoftInk)
                    }
                } else if (directAttendance) {
                    key(state.week) {
                        DirectAttendanceList(state, actions, words, Modifier.weight(1f)) { date, course ->
                            selectedKey = TimetableLayout.key(date, course)
                        }
                    }
                } else {
                    WeeklyGrid(state, words, Modifier.weight(1f), gridVertical, onCourse = { date, course -> selectedKey = TimetableLayout.key(date, course) },
                        onOverlap = { date, courses -> overlapKeys = courses.map { TimetableLayout.key(date, it) } })
                }
            }
        }
    }
    if (!directAttendance && selected == null && overlaps.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { overlapKeys = emptyList() },
            title = { Text(words.text("这个时段有多门课程", "Overlapping classes")) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp).testTag("overlap_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(overlaps) { (date, course) ->
                        Surface(Modifier.fillMaxWidth().testTag("overlap_${course.id}").clickable {
                            selectedKey = TimetableLayout.key(date, course); overlapKeys = emptyList()
                        }, shape = RoundedCornerShape(12.dp), color = Tint) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(course.courseName, fontWeight = FontWeight.SemiBold)
                                Text(timeRange(course), fontSize = 12.sp, color = SoftInk)
                                Text(location(course, words), fontSize = 12.sp, color = SoftInk)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { overlapKeys = emptyList() }) { Text(words.text("关闭", "Close")) } },
        )
    }
}

@Composable
private fun TimetableHeader(state: AssistantUiState, actions: AssistantActions, words: UiWords) {
    var weekMenu by remember { mutableStateOf(false) }
    val directAttendance = state.attendanceMode == AttendanceMode.ICLASS
    val weeks = state.schedule?.weeks?.map { it.number }?.distinct()?.takeIf { it.isNotEmpty() } ?: (1..18).toList()
    val weekIndex = weeks.indexOf(state.week)
    val previous = weeks.getOrNull(weekIndex - 1)
    val next = if (weekIndex >= 0) weeks.getOrNull(weekIndex + 1) else null
    Box(Modifier.fillMaxWidth()) {
        Image(painterResource(R.drawable.campus_watercolor), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, alpha = .24f)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (directAttendance) words.text("iClass 签到", "iClass attendance") else words.text("我的课表", "My timetable"),
                        fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text(if (directAttendance) words.text("仅显示 iClass 排课", "Showing classes from iClass")
                        else words.studentLabel(resolvedStudentType(state)) + words.text(" · 点击课程查看详情", " · Tap a class for details"),
                        modifier = Modifier.testTag("timetable_identity"), fontSize = 11.sp, color = SoftInk)
                }
                TextButton(onClick = actions.language, enabled = !state.busy, modifier = Modifier.testTag("language"), contentPadding = PaddingValues(8.dp)) { Text(words.text("EN", "中")) }
                TextButton(
                    onClick = { actions.attendanceMode(if (directAttendance) AttendanceMode.TIMETABLE else AttendanceMode.ICLASS) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("attendance_mode_switch").semantics {
                        contentDescription = if (directAttendance) words.text("切换到课表签到", "Switch to timetable attendance")
                        else words.text("切换到 iClass 签到", "Switch to iClass attendance")
                    },
                    contentPadding = PaddingValues(6.dp),
                ) { Text(words.text("切换", "Switch"), fontSize = 12.sp) }
                TextButton(onClick = actions.logout, enabled = !state.busy, modifier = Modifier.testTag("logout"), contentPadding = PaddingValues(6.dp)) { Text(words.text("退出", "Sign out"), fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { previous?.let(actions.week) }, enabled = !state.busy && previous != null,
                    modifier = Modifier.width(40.dp).testTag("previous_week").semantics { contentDescription = words.text("上一周", "Previous week") }, contentPadding = PaddingValues(0.dp)) { Text("‹", fontSize = 28.sp) }
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { weekMenu = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("week_select"), contentPadding = PaddingValues(0.dp)) {
                        Text(words.text("第 ${state.week} 周", "Week ${state.week}"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text("⌄", Modifier.padding(start = 5.dp))
                    }
                    DropdownMenu(expanded = weekMenu, onDismissRequest = { weekMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                        weeks.forEach { week -> DropdownMenuItem(text = { Text(words.text("第 $week 周", "Week $week")) }, enabled = !state.busy,
                            modifier = Modifier.testTag("week_option_$week"), onClick = { weekMenu = false; actions.week(week) }) }
                    }
                }
                TextButton(onClick = { next?.let(actions.week) }, enabled = !state.busy && next != null,
                    modifier = Modifier.width(40.dp).testTag("next_week").semantics { contentDescription = words.text("下一周", "Next week") }, contentPadding = PaddingValues(0.dp)) { Text("›", fontSize = 28.sp) }
                TextButton(onClick = actions.currentWeek, enabled = !state.busy, modifier = Modifier.testTag("current_week"), contentPadding = PaddingValues(horizontal = 7.dp)) { Text(words.text("本周", "Today"), fontSize = 12.sp) }
                TextButton(onClick = actions.refresh, enabled = !state.busy, modifier = Modifier.testTag("refresh"), contentPadding = PaddingValues(horizontal = 7.dp)) { Text(words.text("刷新", "Refresh"), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun DirectAttendanceList(
    state: AssistantUiState,
    actions: AssistantActions,
    words: UiWords,
    modifier: Modifier,
    onCourse: (LocalDate, Course) -> Unit,
) {
    LazyColumn(
        modifier.fillMaxWidth().testTag("iclass_attendance_list"),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.schedule?.days.orEmpty(), key = { it.date }) { day ->
            Surface(
                Modifier.fillMaxWidth().testTag("iclass_day_${day.date}"),
                shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, Line),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${day.date} · ${words.weekday(day.date)}", fontWeight = FontWeight.Bold, color = Ink)
                    when {
                        day.failed -> Text(words.text("当天排课读取失败，请刷新重试。", "Could not load classes for this date. Tap Refresh."),
                            Modifier.testTag("failed_day_${day.date}"), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        day.courses.isEmpty() -> Text(words.text("当天没有 iClass 排课", "No iClass classes for this date"),
                            Modifier.testTag("empty_day_${day.date}"), color = SoftInk, fontSize = 12.sp)
                        else -> day.courses.forEach { course ->
                            val canAttend = canSignDirectly(course)
                            HorizontalDivider(color = Line)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(
                                    Modifier.weight(1f).clickable { onCourse(day.date, course) }
                                        .padding(vertical = 6.dp).testTag("course_${course.id}"),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(course.courseName, fontWeight = FontWeight.SemiBold, color = Ink)
                                    Text(timeRange(course), fontSize = 12.sp, color = DeepBlue)
                                    Text(location(course, words), fontSize = 12.sp, color = SoftInk)
                                }
                                Button(
                                    onClick = { actions.sign(course) }, enabled = !state.busy && canAttend && !course.signed,
                                    modifier = Modifier.testTag("sign_${course.id}"), shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    Text(when {
                                        course.signed -> words.text("已签到", "Signed")
                                        !canAttend -> words.text("暂不可签到", "Unavailable")
                                        else -> words.text("签到", "Sign")
                                    }, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun canSignDirectly(course: Course) =
    course.attendanceAvailable && !course.attendanceNotRequired && course.realIds.isNotEmpty() && course.attendanceSessions.isEmpty()

@Composable
private fun WeeklyGrid(state: AssistantUiState, words: UiWords, modifier: Modifier, vertical: ScrollState, onCourse: (LocalDate?, Course) -> Unit, onOverlap: (LocalDate, List<Course>) -> Unit) {
    val days = TimetableLayout.days(state)
    val periods = TimetableLayout.periods(state.schedule)
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("weekly_grid")) {
        val gridWidth = maxWidth
        val axis = if (maxWidth < 340.dp) 38.dp else 44.dp
        val column = (gridWidth - axis) / 7
        val rowHeight = 64.dp
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(44.dp)) {
                Box(Modifier.width(axis).fillMaxHeight(), contentAlignment = Alignment.Center) { Text(words.text("节次", "Period"), color = SoftInk, fontSize = 10.sp) }
                days.forEach { day ->
                    val today = day.date == LocalDate.now()
                    Column(Modifier.width(column).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(if (today) Tint else Color.Transparent).testTag("day_header_${day.date}"),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (words.english) day.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar(Char::uppercase) else words.weekday(day.date), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (today) Blue else Ink)
                        Text("${day.date.monthValue}/${day.date.dayOfMonth}", fontSize = 10.sp, color = SoftInk)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(vertical).testTag("timetable_scroll")) {
                if (!state.schedule?.unscheduled.isNullOrEmpty()) {
                    Text(words.text("未安排时间", "Time not scheduled"), Modifier.padding(14.dp).testTag("unscheduled_heading"), color = Ink, fontWeight = FontWeight.SemiBold)
                    state.schedule?.unscheduled.orEmpty().forEach { course ->
                        Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("course_${course.id}").clickable { onCourse(null, course) }, color = Tint, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(course.courseName, fontWeight = FontWeight.SemiBold)
                                Text(words.text("日期未安排", "Date not scheduled") + " · " + location(course, words), fontSize = 12.sp, color = SoftInk)
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(rowHeight * periods.size).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                    Canvas(Modifier.matchParentSize()) {
                        val axisPx = axis.toPx()
                        val colPx = column.toPx()
                        for (line in 0..periods.size) drawLine(Line.copy(alpha = .55f), Offset(axisPx, rowHeight.toPx() * line), Offset(size.width, rowHeight.toPx() * line))
                        for (line in 0..7) drawLine(Line.copy(alpha = .55f), Offset(axisPx + colPx * line, 0f), Offset(axisPx + colPx * line, size.height))
                    }
                    Column(Modifier.width(axis)) {
                        periods.forEach { period ->
                            Column(Modifier.fillMaxWidth().height(rowHeight), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${period.number}", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(period.start.ifBlank { "—" }, color = SoftInk, fontSize = 9.sp, lineHeight = 12.sp)
                                Text(period.end.ifBlank { "—" }, color = SoftInk, fontSize = 9.sp, lineHeight = 12.sp)
                            }
                        }
                    }
                    days.forEachIndexed { dayIndex, day ->
                        if (day.failed) {
                            Text(words.text("加载\n失败", "Load\nfailed"), Modifier.offset(x = axis + column * dayIndex, y = 12.dp).width(column).testTag("failed_day_${day.date}"),
                                textAlign = TextAlign.Center, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        } else if (day.courses.isEmpty()) {
                            Text(words.text("无课", "Free"), Modifier.offset(x = axis + column * dayIndex, y = 16.dp).width(column).testTag("empty_day_${day.date}"), textAlign = TextAlign.Center, fontSize = 10.sp, color = SoftInk.copy(alpha = .65f))
                        }
                        TimetableLayout.clusters(day.courses, periods).forEach { cluster ->
                            val course = cluster.courses.first()
                            val multiple = cluster.courses.size > 1
                            val palette = listOf(Color(0xFF277BBE), Color(0xFF3976A6), Color(0xFF318FA8), Color(0xFF526FA3), Color(0xFF245C92))
                            val color = palette[Math.floorMod((course.courseNum.ifBlank { course.courseName }).hashCode(), palette.size)]
                            Surface(
                                Modifier.offset(x = axis + column * dayIndex + 1.dp, y = rowHeight * cluster.span.first + 2.dp)
                                    .width(column - 2.dp).height(rowHeight * (cluster.span.last - cluster.span.first + 1) - 4.dp)
                                    .testTag(if (multiple) "cluster_${day.date}_${cluster.span.first}" else "course_${course.id}")
                                    .clickable { if (multiple) onOverlap(day.date, cluster.courses) else onCourse(day.date, course) }
                                    .semantics { contentDescription = if (multiple) words.text("${cluster.courses.size} 门课程重叠，点击选择", "${cluster.courses.size} overlapping classes, tap to choose") else "${course.courseName} ${timeRange(course)}" },
                                color = if ((course.signed || course.attendanceNotRequired) && !multiple) color.copy(alpha = .15f) else color, shape = RoundedCornerShape(8.dp),
                            ) {
                                Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    val foreground = if ((course.signed || course.attendanceNotRequired) && !multiple) color else Color.White
                                    if (multiple) Text(words.text("${cluster.courses.size}门", "${cluster.courses.size} classes"), color = foreground, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    else {
                                        if (course.signed || course.attendanceNotRequired) Text("✓", color = foreground, fontSize = 11.sp)
                                        if (course.attendanceSessions.size > 1) Text(course.attendanceProgress.removePrefix("✓ "), color = foreground, fontSize = 9.sp)
                                    }
                                    Text(course.courseName, color = foreground, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
                                        maxLines = maxOf(1, (cluster.span.last - cluster.span.first + 1) * 3 - 1), overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    if (multiple) Text(words.text("点击选择", "Choose"), color = foreground.copy(alpha = .85f), fontSize = 9.sp, textAlign = TextAlign.Center)
                                    else Text("@" + course.classroomName.ifBlank { words.text("待定", "TBA") }, color = foreground.copy(alpha = .86f), fontSize = 10.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                val other = days.flatMap { day -> day.courses.filter { TimetableLayout.span(it, periods) == null }.map { day.date to it } }
                if (other.isNotEmpty()) {
                    Text(words.text("其他时间的课程", "Classes outside standard periods"), Modifier.padding(14.dp), color = Ink, fontWeight = FontWeight.SemiBold)
                    other.forEach { (date, course) ->
                        Surface(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("course_${course.id}").clickable { onCourse(date, course) }, color = Tint, shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(course.courseName, fontWeight = FontWeight.SemiBold)
                                Text("${words.weekday(date)} · ${timeRange(course)} · ${location(course, words)}", fontSize = 12.sp, color = SoftInk)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun CourseDetail(date: LocalDate?, course: Course, state: AssistantUiState, actions: AssistantActions, words: UiWords, back: () -> Unit, modifier: Modifier) {
    val directAttendance = state.attendanceMode == AttendanceMode.ICLASS
    val hasTime = date != null && TimetableLayout.time(course.classBeginTime) != null && TimetableLayout.time(course.classEndTime) != null
    val canAttend = if (directAttendance) canSignDirectly(course) else course.attendanceAvailable && hasTime
    Column(modifier.fillMaxSize().testTag("course_detail")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back, modifier = Modifier.testTag("detail_back").semantics {
                contentDescription = if (directAttendance) words.text("返回签到列表", "Back to attendance list")
                    else words.text("返回课表", "Back to timetable")
            }) { Text("‹", fontSize = 30.sp) }
            Text(words.text("课程详情", "Class details"), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = actions.refresh, enabled = !state.busy, modifier = Modifier.testTag("detail_refresh")) { Text(words.text("刷新", "Refresh"), fontSize = 12.sp) }
        }
        LazyColumn(Modifier.fillMaxSize().testTag("detail_scroll"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Surface(color = Tint, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(course.courseName, fontSize = 25.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold, color = Ink)
                        Text(words.text("课程代码：", "Course code: ") + course.courseNum.ifBlank { "—" }, color = DeepBlue, fontSize = 14.sp)
                    }
                }
            }
            item {
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, Line)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
                        Text(words.text("基本信息", "Basic information"), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        DetailRow(words.text("上课日期", "Date"), date?.let { "$it · ${words.weekday(it)}" } ?: words.text("未安排时间", "Time not scheduled"))
                        DetailRow(words.text("上课时间", "Time"), timeRange(course))
                        if (!directAttendance) {
                            val sections = TimetableLayout.periods(state.schedule)
                            val span = TimetableLayout.span(course, sections)
                            DetailRow(words.text("节次", "Periods"), span?.let { words.text("第 ${sections[it.first].number}–${sections[it.last].number} 节", "${sections[it.first].number}–${sections[it.last].number}") } ?: words.text("非标准节次 / 时间待确认", "Non-standard / unconfirmed time"))
                        }
                        DetailRow(words.text("上课地点", "Location"), location(course, words))
                        DetailRow(words.text("任课教师", "Teacher"), course.teachers.filter(String::isNotBlank).joinToString(" · ").ifBlank { words.text("待定", "TBA") })

                    }
                }
            }
            item {
                val academic = state.schedule?.academic
                val official = academic?.status == AcademicStatus.VERIFIED
                Surface(color = Color.White, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Line)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(words.text("课程签到", "Attendance"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            Text(if (course.attendanceNotRequired) words.text("✓ 无需签到", "✓ Not required") else if (!canAttend) words.text("暂不可用", "Unavailable") else if (course.attendanceSessions.size > 1) course.attendanceProgress else if (course.signed) words.text("已签到", "Signed") else words.text("未签到", "Pending"), color = Blue)
                        }
                        if (!directAttendance && !official) Text(words.text("教务待核对 · 请确认仍在正式课表中", "Unverified · Check your official timetable"),
                            Modifier.testTag("academic_pending_${course.id}"), fontSize = 12.sp, color = SoftInk)
                        if (!canAttend && !course.attendanceNotRequired) {
                            Text(when {
                                directAttendance -> words.text("该条 iClass 排课暂不可签到", "Attendance is unavailable for this iClass class.")
                                !hasTime -> words.text("课程日期或时间未确认，暂不可签到", "The class date or time is unconfirmed. Attendance is unavailable.")
                                official -> words.text("未找到对应的 iClass 排课，暂不可签到", "No corresponding iClass class was found. Attendance is unavailable.")
                                else -> words.text("完成正式课表核对前暂不可签到", "Attendance is unavailable until the official timetable is checked.")
                            },
                                Modifier.testTag("attendance_unavailable_${course.id}"), fontSize = 13.sp, color = SoftInk)
                        }
                        Text(words.text("请根据实际出勤情况操作。\n结果以智慧北航APP“智慧教室”为准。", "Submit for your actual attendance.\nCheck the Smart Classroom records in the Smart BUAA app for the result."), fontSize = 12.sp, color = SoftInk)
                        if (course.attendanceNotRequired) {
                            Text(words.text("iClass 本周未查到该课程排课，无需在 iClass 签到。", "No iClass schedule was found for this class this week. Attendance is not required."),
                                Modifier.testTag("attendance_not_required_${course.id}"), fontSize = 13.sp, color = SoftInk)
                        } else if (course.attendanceSessions.size > 1) {
                            Text(words.text("按实际排课分别签到", "Sign each session separately"), fontSize = 12.sp, color = SoftInk)
                            course.attendanceSessions.forEachIndexed { index, session ->
                                val slotSpan = TimetableLayout.span(session, TimetableLayout.periods(state.schedule))
                                val label = slotSpan?.let {
                                    words.text("第 ${TimetableLayout.periods(state.schedule)[it.first].number}–${TimetableLayout.periods(state.schedule)[it.last].number} 节", "Periods ${TimetableLayout.periods(state.schedule)[it.first].number}–${TimetableLayout.periods(state.schedule)[it.last].number}")
                                } ?: words.text("第 ${index + 1} 场", "Session ${index + 1}")
                                Column(Modifier.fillMaxWidth().testTag("attendance_session_${session.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("$label · ${timeRange(session)}", fontWeight = FontWeight.Medium)
                                    Button(onClick = { actions.sign(session) }, enabled = !state.busy && canAttend && session.attendanceAvailable && !session.signed,
                                        modifier = Modifier.fillMaxWidth().testTag("sign_${session.id}"), shape = RoundedCornerShape(12.dp)) {
                                        Text(if (session.signed) words.text("✓ 已签到", "✓ Signed") else words.text("签到", "Sign"))
                                    }
                                }
                            }
                        } else Button(onClick = { actions.sign(course.attendanceSessions.singleOrNull() ?: course) }, enabled = !state.busy && !course.signed && canAttend,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("sign_${course.id}"), shape = RoundedCornerShape(12.dp)) {
                            if (state.busy) { CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(if (!canAttend) words.text("暂不可签到", "Attendance unavailable") else if (course.signed) words.text("✓ 已完成签到", "✓ Signed") else if (state.busy) words.text("处理中…", "Working…") else words.text("签到", "Sign"), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, Modifier.width(82.dp), color = SoftInk, fontSize = 14.sp)
        Text(value, Modifier.weight(1f), color = Ink, fontSize = 14.sp)
    }
}

private fun timeRange(course: Course): String {
    val date = Regex("\\d{4}-\\d{2}-\\d{2}")
    val beginDate = date.find(course.classBeginTime)?.value
    val endDate = date.find(course.classEndTime)?.value
    if (beginDate != null && endDate != null && beginDate != endDate) return "${course.classBeginTime} — ${course.classEndTime}"
    val start = TimetableLayout.time(course.classBeginTime)
    val end = TimetableLayout.time(course.classEndTime)
    return if (start == null && end == null) "—" else "${start ?: "—"} — ${end ?: "—"}"
}
private fun resolvedStudentType(state: AssistantUiState): StudentType =
    state.schedule?.studentType?.takeUnless { it == StudentType.AUTO } ?: state.studentType
private fun location(course: Course, words: UiWords) = listOf(course.teachBuildName, course.storeyName, course.classroomName)
    .map(String::trim).filter { it.isNotEmpty() && it != "null" }.distinct().joinToString(" ").ifBlank { words.text("地点待定", "Location TBA") }
