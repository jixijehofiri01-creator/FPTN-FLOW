package com.example.feature.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.config.FptnConstants
import com.example.core.clipboard.ClipboardHelper
import com.example.core.haptics.HapticEngine
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

private const val FPTN_SITE_URL = FptnConstants.OFFICIAL_SITE_URL
private const val FPTN_GITHUB_URL = FptnConstants.OFFICIAL_ANDROID_GITHUB_URL
private const val FPTN_FORK_GITHUB_URL = FptnConstants.FORK_GITHUB_URL
private const val FPTN_FORK_RELEASES_URL = FptnConstants.FORK_RELEASES_URL
private const val FPTN_BOT_URL = FptnConstants.OFFICIAL_TELEGRAM_BOT_URL

@Composable
fun ShareScreen(
    hapticEngine: HapticEngine,
    bottomNavInset: Dp,
    onShowMessage: (String) -> Unit,
    applyNavBarsInset: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val qrBitmap = remember { generateQrBitmap(FPTN_FORK_GITHUB_URL, sizePx = 512) }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .let { m -> if (applyNavBarsInset) m.navigationBarsPadding() else m },
    ) {
        val isTwoPane = maxWidth >= 760.dp

        val forkNoticeCard: @Composable () -> Unit = {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("share_fork_notice_card"),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ForkRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(22.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.share_fork_badge),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = stringResource(R.string.share_fork_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f),
                        )
                    }
                }
            }
        }

        val qrCard: @Composable () -> Unit = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (qrBitmap != null) {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = Color.White,
                            tonalElevation = 2.dp,
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.share_qr_caption),
                                modifier = Modifier
                                    .size(if (isTwoPane) 220.dp else 204.dp)
                                    .padding(12.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.share_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                hapticEngine.performConfirm(view)
                                ClipboardHelper.copyText(context, "FPTN Flow", FPTN_FORK_GITHUB_URL)
                                onShowMessage(context.getString(R.string.share_link_copied, FPTN_FORK_GITHUB_URL))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("share_copy_link_button"),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.share_copy_link), maxLines = 1)
                        }

                        Button(
                            onClick = {
                                hapticEngine.performConfirm(view)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        context.getString(R.string.share_system_message, FPTN_FORK_GITHUB_URL),
                                    )
                                }
                                runCatching {
                                    context.startActivity(
                                        Intent.createChooser(shareIntent, context.getString(R.string.share_title)),
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("share_system_button"),
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.share_system_share), maxLines = 1)
                        }
                    }
                }
            }
        }

        val officialLinksCard: @Composable () -> Unit = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ShareLinkButton(
                        icon = Icons.Rounded.ForkRight,
                        label = stringResource(R.string.share_fork_github_repo),
                        testTag = "share_fork_github_button",
                        onClick = {
                            hapticEngine.performSegmentTick(view)
                            openExternalUrl(context, FPTN_FORK_GITHUB_URL)
                        },
                    )
                    ShareLinkButton(
                        icon = Icons.Rounded.Download,
                        label = stringResource(R.string.share_fork_releases),
                        testTag = "share_fork_releases_button",
                        onClick = {
                            hapticEngine.performSegmentTick(view)
                            openExternalUrl(context, FPTN_FORK_RELEASES_URL)
                        },
                    )
                    ShareLinkButton(
                        icon = Icons.AutoMirrored.Rounded.Send,
                        label = stringResource(R.string.share_telegram_bot),
                        testTag = "share_telegram_button",
                        onClick = {
                            hapticEngine.performSegmentTick(view)
                            openExternalUrl(context, FPTN_BOT_URL)
                        },
                    )
                    ShareLinkButton(
                        icon = Icons.Rounded.Code,
                        label = stringResource(R.string.share_github_repo),
                        testTag = "share_github_button",
                        onClick = {
                            hapticEngine.performSegmentTick(view)
                            openExternalUrl(context, FPTN_GITHUB_URL)
                        },
                    )
                    ShareLinkButton(
                        icon = Icons.Rounded.Language,
                        label = stringResource(R.string.share_official_site),
                        testTag = "share_site_button",
                        onClick = {
                            hapticEngine.performSegmentTick(view)
                            openExternalUrl(context, FPTN_SITE_URL)
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = if (isTwoPane) 28.dp else 20.dp,
                    end = if (isTwoPane) 28.dp else 20.dp,
                    top = 16.dp,
                    bottom = bottomNavInset + 16.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = if (isTwoPane) 960.dp else 600.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.share_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isTwoPane) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(0.48f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            qrCard()
                        }
                        Column(
                            modifier = Modifier.weight(0.52f),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            forkNoticeCard()
                            officialLinksCard()
                        }
                    }
                } else {
                    forkNoticeCard()
                    qrCard()
                    officialLinksCard()
                }
            }
        }
    }
}

@Composable
private fun ShareLinkButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag(testTag),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, modifier = Modifier.weight(1f))
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
    return runCatching {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val rowOffset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[rowOffset + x] = if (bitMatrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()
}
