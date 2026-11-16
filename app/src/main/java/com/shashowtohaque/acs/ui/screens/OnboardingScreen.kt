package com.shashowtohaque.acs.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Immutable
data class PermissionStatusItem(
    val id: String,
    val title: String,
    val description: String,
    val isGranted: Boolean,
    val onRequest: () -> Unit
)

private val GrantedGreen = Color(0xFF10B981)
private val MissingRed = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    permissionList: List<PermissionStatusItem>,
    allPermissionsGranted: Boolean,
    onProceedClicked: () -> Unit,
    onExitClicked: () -> Unit
) {
    BackHandler(enabled = !allPermissionsGranted) {
        onExitClicked()
    }

    val listState = rememberLazyListState()
    val grantedCount = remember(permissionList) { permissionList.count { it.isGranted } }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "App Permission Requirements",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "$grantedCount of ${permissionList.size} Permissions Verified",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (allPermissionsGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onExitClicked,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Close App",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Button(
                            onClick = onProceedClicked,
                            enabled = allPermissionsGranted,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "Open Dashboard",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "advisory_banner", contentType = "banner") {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "To guarantee scheduled calls execute reliably without background interruptions, all system permissions listed below are required.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            items(
                items = permissionList,
                key = { it.id },
                contentType = { "perm_card" }
            ) { item ->
                PermissionCard(item = item)
            }

            item(key = "bottom_spacer", contentType = "spacer") {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun PermissionCard(item: PermissionStatusItem) {
    val cardBorderColor = if (item.isGranted) {
        GrantedGreen.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
    }
    OutlinedCard(
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, cardBorderColor),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (item.isGranted) GrantedGreen else MissingRed,
                modifier = Modifier.size(26.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier.width(76.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!item.isGranted) {
                    FilledTonalButton(
                        onClick = item.onRequest,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(text = "Grant", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Surface(
                        shape = CircleShape,
                        color = GrantedGreen.copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Ready",
                            color = GrantedGreen,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
}
}