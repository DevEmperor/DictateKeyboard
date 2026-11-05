/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.app.settings.about

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.SendToMobile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shop
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.florisboard.lib.util.launchUrl
import org.florisboard.lib.compose.stringRes

/**
 * The developer's other apps, reached from About and from the one-time SayOVA nudge in the Smartbar
 * (`DictateController.maybePromptSayova`): SayOVA first, since the nudge is about it, then TunerToGo.
 *
 * Google Play counts a "More apps" page in the menu as cross-promotion, not as ads; the Smartbar nudge that
 * points here is what makes Dictate's "Contains ads" declaration a question.
 */
@Composable
fun MoreAppsScreen() = FlorisScreen {
    title = stringRes(R.string.about__more_apps__title)

    val context = LocalContext.current

    content {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = stringRes(R.string.about__more_apps__intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            AppCard(
                name = "SayOVA",
                banner = R.drawable.sayova_feature_graphic,
                tagline = stringRes(R.string.about__more_apps__sayova_tagline),
                description = stringRes(R.string.about__more_apps__sayova_description),
                features = listOf(
                    Icons.Outlined.Translate to stringRes(R.string.about__more_apps__sayova_feature_interpreter),
                    Icons.Outlined.Timer to stringRes(R.string.about__more_apps__sayova_feature_tasks),
                    Icons.AutoMirrored.Outlined.SendToMobile to stringRes(R.string.about__more_apps__sayova_feature_phone),
                    Icons.Outlined.Lock to stringRes(R.string.about__more_apps__sayova_feature_privacy),
                ),
                requirements = stringRes(R.string.about__more_apps__sayova_requirements),
                onPlay = { context.launchUrl(R.string.sayova__play_url) },
                secondIcon = Icons.Outlined.Public,
                secondLabel = stringRes(R.string.about__website__title),
                onSecond = { context.launchUrl(R.string.sayova__website_url) },
            )
            Spacer(Modifier.height(16.dp))
            // TunerToGo has no website of its own; its README on GitHub is the page about it.
            AppCard(
                name = "TunerToGo",
                banner = R.drawable.tunertogo_feature_graphic,
                tagline = stringRes(R.string.about__more_apps__tunertogo_tagline),
                description = stringRes(R.string.about__more_apps__tunertogo_description),
                features = listOf(
                    Icons.Outlined.MusicNote to stringRes(R.string.about__more_apps__tunertogo_feature_instruments),
                    Icons.Outlined.GraphicEq to stringRes(R.string.about__more_apps__tunertogo_feature_chromatic),
                    Icons.Outlined.Vibration to stringRes(R.string.about__more_apps__tunertogo_feature_vibration),
                    Icons.Outlined.CloudOff to stringRes(R.string.about__more_apps__tunertogo_feature_privacy),
                ),
                requirements = stringRes(R.string.about__more_apps__tunertogo_requirements),
                onPlay = { context.launchUrl(R.string.tunertogo__play_url) },
                secondIcon = Icons.Default.Code,
                secondLabel = "GitHub",
                onSecond = { context.launchUrl(R.string.tunertogo__repo_url) },
            )
        }
    }
}

@Composable
private fun AppCard(
    name: String,
    @DrawableRes banner: Int,
    tagline: String,
    description: String,
    features: List<Pair<ImageVector, String>>,
    requirements: String,
    onPlay: () -> Unit,
    secondIcon: ImageVector,
    secondLabel: String,
    onSecond: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        // The app's store banner, in English like the apps' own pictures; the text below is translated.
        Image(
            painter = painterResource(banner),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1024f / 500f),
        )
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = tagline,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            features.forEach { (icon, text) -> Feature(icon, text) }
            Spacer(Modifier.height(12.dp))
            Text(
                text = requirements,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Shop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Google Play")
                }
                OutlinedButton(onClick = onSecond, modifier = Modifier.weight(1f)) {
                    Icon(secondIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(secondLabel)
                }
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
