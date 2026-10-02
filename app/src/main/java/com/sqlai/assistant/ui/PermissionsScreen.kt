package com.sqlai.assistant.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sqlai.assistant.core.PermissionHelper
import com.sqlai.assistant.core.PermissionStatus
import com.sqlai.assistant.ui.theme.SqlSuccess
import com.sqlai.assistant.ui.theme.SqlWarn
import kotlinx.coroutines.delay

@Composable
fun PermissionsScreen() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            tick++
            delay(2000)
        }
    }

    val statuses = remember(tick) { PermissionHelper.statuses(context) }
    val grantedCount = statuses.count { it.granted }

    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(6.dp))
        Text("Access & Permissions", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Grant every access so the assistant can control the whole phone",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        LinearProgressIndicator(
            progress = { grantedCount.toFloat() / statuses.size.coerceAtLeast(1) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        )
        Text(
            "$grantedCount of ${statuses.size} enabled",
            fontSize = 12.sp,
            color = if (grantedCount == statuses.size) SqlSuccess else SqlWarn,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(statuses) { item ->
                PermissionCard(
                    item = item,
                    onGrant = {
                        if (item.id == "mic") {
                            runtimeLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.RECORD_AUDIO,
                                    android.Manifest.permission.POST_NOTIFICATIONS
                                )
                            )
                        } else if (item.id == "notifications_perm") {
                            runtimeLauncher.launch(
                                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)
                            )
                        } else if (item.id == "contacts") {
                            runtimeLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_CONTACTS,
                                    android.Manifest.permission.READ_PHONE_STATE,
                                    android.Manifest.permission.CAMERA
                                )
                            )
                        } else if (item.id == "phone") {
                            runtimeLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_PHONE_STATE,
                                    android.Manifest.permission.CALL_PHONE,
                                    android.Manifest.permission.ANSWER_PHONE_CALLS,
                                    android.Manifest.permission.READ_CONTACTS
                                )
                            )
                        } else if (item.id == "storage") {
                            runtimeLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_MEDIA_IMAGES,
                                    android.Manifest.permission.READ_MEDIA_AUDIO,
                                    android.Manifest.permission.READ_MEDIA_VIDEO
                                )
                            )
                        } else {
                            PermissionHelper.open(context, item.id)
                            tick++
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(item: PermissionStatus, onGrant: () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = surface)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (item.granted) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = if (item.granted) SqlSuccess else SqlWarn
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(item.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    item.description,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (item.granted) {
                Text("Granted", color = SqlSuccess, fontSize = 12.sp)
            } else {
                OutlinedButton(onClick = onGrant) {
                    Text("Grant", fontSize = 12.sp)
                }
            }
        }
    }
}
