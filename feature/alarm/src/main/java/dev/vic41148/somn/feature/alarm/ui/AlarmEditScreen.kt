package dev.vic41148.somn.feature.alarm.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.StrokeCap
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import dev.vic41148.somn.feature.alarm.AlarmViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    alarmId: Long = 0L,
    onSaved: () -> Unit,
    viewModel: AlarmViewModel = hiltViewModel()
) {
    val editingAlarm by viewModel.editingAlarm.collectAsState()
    val context = LocalContext.current
    
    // UI state
    var label by rememberSaveable { mutableStateOf("") }
    var wakeWindow by rememberSaveable { mutableStateOf(30f) }
    var soundUri by rememberSaveable { mutableStateOf("") }
    var isInitialized by remember { mutableStateOf(false) }
    val labelBringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()

    val soundTitle = remember(soundUri) {
        getRingtoneTitle(context, soundUri)
    }

    val ringtonePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            soundUri = pickedUri?.toString() ?: ""
        }
    }

    LaunchedEffect(alarmId) {
        if (alarmId > 0) {
            viewModel.loadAlarmForEditing(alarmId)
        } else {
            viewModel.setEditingAlarm(null)
            soundUri = ""
            isInitialized = true
        }
    }

        var repeatDays by remember { mutableStateOf<Set<Int>>(emptySet()) }

        // Hoisted to screen level, outside AnimatedVisibility. rememberTimePickerState used
        // to live inside the AnimatedVisibility content, so the dial's state (including its
        // needle-animation clock) was tied to the enter/exit subcomposition, after tapping
        // AM/PM and dragging, the needle froze while the time still moved. It survives now.
        //
        // The AM/PM toggle inside the Material3 picker mutates `isAfternoon` on the *same*
        // state object, whose internal needle Animatable is then left pointing at a stale
        // angle, the first drag after the flip can orphan the draw layer so the hand stops
        // drawing until the screen reopens. Rebuild the picker with a fresh state (and a
        // fresh needle animation) whenever AM/PM flips and no finger is down, the policy
        // lives in AlarmTimePickerState.kt and is unit-tested.
        val initialPickerState = rememberTimePickerState(initialHour = 7, initialMinute = 0)
        var timePickerState by remember { mutableStateOf<TimePickerState>(initialPickerState) }
        var activePointers by remember { mutableIntStateOf(0) }
        var lastNoon by remember { mutableStateOf(if (timePickerState.hour >= 12) 1 else 0) }

        LaunchedEffect(timePickerState.hour >= 12, activePointers) {
            val nowPm = timePickerState.hour >= 12
            when {
                alarmPickerShouldRebuild(
                    pointerDown = activePointers > 0,
                    nowPm = nowPm,
                    lastPm = lastNoon == 1
                ) -> {
                    lastNoon = if (nowPm) 1 else 0
                    timePickerState = rebuiltAlarmPickerState(timePickerState)
                }
                else -> lastNoon = if (nowPm) 1 else 0
            }
        }

        LaunchedEffect(editingAlarm) {
            editingAlarm?.let {
                label = it.label
                wakeWindow = it.wakeWindowMinutes.toFloat()
                repeatDays = it.repeatDays
                soundUri = it.soundUri
                // The picker used to snapshot editingAlarm?.hour at first composition (null →
                // 7:00) and never sync, so editing an alarm always opened showing 7:00 AM.
                timePickerState.hour = it.hour
                timePickerState.minute = it.minute
                isInitialized = true
            }
        }

        // The spinner fades out as the form fades in, so an edit screen opening from the
        // alarm list does not hard-cut between two surfaces (MOTION-04 entrance).
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !isInitialized,
                modifier = Modifier.fillMaxSize(),
                exit = fadeOut(tween(200))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            AnimatedVisibility(
                visible = isInitialized,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(300)) +
                    scaleIn(initialScale = 0.98f, animationSpec = tween(300))
            ) {
        // Keep the save action reachable, but let the dial and all alarm details share one
        // scroll surface. The numeric time selector is duplicated as a sticky header so the
        // dial can disappear underneath it while the selected time remains visible.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 72.dp)
                        .imePadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                Text(
                    text = if (alarmId > 0) "Edit Alarm" else "New Alarm",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 24.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                when (event.type) {
                                    PointerEventType.Press -> activePointers += 1
                                    PointerEventType.Release ->
                                        activePointers = (activePointers - 1).coerceAtLeast(0)
                                    PointerEventType.Move ->
                                        if (activePointers > 0 && event.changes.none { it.pressed }) {
                                            activePointers = 0
                                        }
                                    else -> {}
                                }
                            }
                        }
                    }
                ) {
                    // The arc is deliberately behind the Material3 picker so it remains a
                    // visual cue and does not compete with the picker's time-drag gesture.
                    val accent = MaterialTheme.colorScheme.primary
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val durationDegrees = wakeWindow * 6f
                        val alarmDegrees =
                            ((timePickerState.hour % 12) + timePickerState.minute / 60f) * 30f
                        val arcStart = -90f + alarmDegrees - durationDegrees
                        val arcSize = size.minDimension
                        val arcBounds = androidx.compose.ui.geometry.Rect(
                            left = (size.width - arcSize) / 2f + 10.dp.toPx(),
                            top = (size.height - arcSize) / 2f + 10.dp.toPx(),
                            right = (size.width + arcSize) / 2f - 10.dp.toPx(),
                            bottom = (size.height + arcSize) / 2f - 10.dp.toPx()
                        )
                        drawArc(
                            color = accent.copy(alpha = 0.10f),
                            startAngle = arcStart,
                            sweepAngle = durationDegrees,
                            useCenter = false,
                            topLeft = arcBounds.topLeft,
                            size = arcBounds.size,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 16.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        )
                        drawArc(
                            color = accent.copy(alpha = 0.34f),
                            startAngle = arcStart,
                            sweepAngle = durationDegrees,
                            useCenter = false,
                            topLeft = arcBounds.topLeft,
                            size = arcBounds.size,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 6.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        )
                    }
                    TimePicker(state = timePickerState)
                }

                Spacer(modifier = Modifier.height(16.dp))

            // Repeat Days
            Text(
                text = "Repeat on",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Single-row round day toggles in the pill language: selected days ride
            // primary/onPrimary, the rest sit on surfaceContainerHigh. One Row, equal
            // weights, so all seven always fit instead of wrapping 5+2 like the old chips.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val days = listOf(
                    java.util.Calendar.MONDAY to "M",
                    java.util.Calendar.TUESDAY to "T",
                    java.util.Calendar.WEDNESDAY to "W",
                    java.util.Calendar.THURSDAY to "T",
                    java.util.Calendar.FRIDAY to "F",
                    java.util.Calendar.SATURDAY to "S",
                    java.util.Calendar.SUNDAY to "S"
                )
                days.forEach { (calendarDay, initial) ->
                    val isSelected = repeatDays.contains(calendarDay)
                    val container by animateColorAsState(
                        targetValue = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                        label = "dayToggle"
                    )
                    val content by animateColorAsState(
                        targetValue = if (isSelected)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "dayToggleContent"
                    )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            // 48dp minimum touch target: the visible circle stays
                            // compact so all seven fit, the tap area doesn't.
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .clip(CircleShape)
                            .clickable(
                                onClickLabel = if (isSelected) "Remove $initial" else "Repeat on $initial",
                                role = androidx.compose.ui.semantics.Role.Checkbox
                            ) {
                                repeatDays = if (isSelected) {
                                    repeatDays - calendarDay
                                } else {
                                    repeatDays + calendarDay
                                }
                            }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(container)
                        ) {
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = content
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(labelBringIntoViewRequester)
                    .onFocusEvent { focusState: FocusState ->
                        if (focusState.isFocused) {
                            coroutineScope.launch {
                                labelBringIntoViewRequester.bringIntoView()
                            }
                        }
                    },
                label = { Text("Label (optional)") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Wake window slider
            Text(
                text = "Smart Wake Window: ${wakeWindow.toInt()} min",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = wakeWindow,
                onValueChange = { wakeWindow = it },
                valueRange = 10f..45f,
                steps = 6,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "The alarm will try to wake you during light sleep within this window before your set time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Alarm sound / ringtone customization
            OutlinedCard(
                onClick = {
                    val currentUri = soundUri.takeIf { it.isNotBlank() }?.let {
                        try { Uri.parse(it) } catch (e: Exception) { null }
                    } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select alarm sound")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, currentUri)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    }
                    ringtonePickerLauncher.launch(intent)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Alarm Sound",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = soundTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(1f),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(timePickerState) {
                                var dragAccumulator = 0f
                                var scrubHour = true
                                val dragPixelsPerStep = 12f
                                detectVerticalDragGestures(
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        dragAccumulator -= dragAmount
                                        while (dragAccumulator >= dragPixelsPerStep) {
                                            if (scrubHour) {
                                                timePickerState.hour = (timePickerState.hour + 1) % 24
                                            } else {
                                                timePickerState.minute = (timePickerState.minute + 1) % 60
                                            }
                                            dragAccumulator -= dragPixelsPerStep
                                        }
                                        while (dragAccumulator <= -dragPixelsPerStep) {
                                            if (scrubHour) {
                                                timePickerState.hour = (timePickerState.hour + 23) % 24
                                            } else {
                                                timePickerState.minute = (timePickerState.minute + 59) % 60
                                            }
                                            dragAccumulator += dragPixelsPerStep
                                        }
                                    },
                                    onDragStart = { offset ->
                                        dragAccumulator = 0f
                                        scrubHour = offset.x < size.width / 2f
                                    },
                                    onDragEnd = { dragAccumulator = 0f },
                                    onDragCancel = { dragAccumulator = 0f }
                                )
                            }
                    ) {
                        TimeInput(
                            state = timePickerState,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (alarmId > 0 && editingAlarm != null) {
                            viewModel.updateAlarm(
                                editingAlarm!!.copy(
                                    hour = timePickerState.hour,
                                    minute = timePickerState.minute,
                                    label = label,
                                    repeatDays = repeatDays,
                                    wakeWindowMinutes = wakeWindow.toInt(),
                                    soundUri = soundUri
                                )
                            )
                        } else {
                            viewModel.createAlarm(
                                hour = timePickerState.hour,
                                minute = timePickerState.minute,
                                label = label,
                                repeatDays = repeatDays,
                                wakeWindowMinutes = wakeWindow.toInt(),
                                soundUri = soundUri
                            )
                        }
                        onSaved()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (alarmId > 0) "Update Alarm" else "Save Alarm")
                }
            }
            }
        }
    }
}

private fun getRingtoneTitle(context: Context, uriString: String): String {
    if (uriString.isBlank()) {
        return "Default alarm sound"
    }
    return try {
        val uri = Uri.parse(uriString)
        val ringtone = RingtoneManager.getRingtone(context, uri)
        ringtone?.getTitle(context) ?: "Default alarm sound"
    } catch (e: Exception) {
        "Default alarm sound"
    }
}
