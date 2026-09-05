/*
 * Copyright (C) 2021-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.app.settings.dictionary

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.settings.search.settingsSearchAnchor
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import org.florisboard.lib.compose.stringRes

@Composable
fun DictionaryScreen() = FlorisScreen {
    title = stringRes(R.string.settings__dictionary__title)
    previewFieldVisible = true

    val navController = LocalNavController.current

    content {
        SwitchPreference(
            prefs.dictionary.enableSystemUserDictionary,
            modifier = Modifier.settingsSearchAnchor("pref__dictionary__enable_system_user_dictionary__label"),
            title = stringRes(R.string.pref__dictionary__enable_system_user_dictionary__label),
            summary = stringRes(R.string.pref__dictionary__enable_system_user_dictionary__summary),
        )
        Preference(
            modifier = Modifier.settingsSearchAnchor("pref__dictionary__manage_system_user_dictionary__label"),
            title = stringRes(R.string.pref__dictionary__manage_system_user_dictionary__label),
            summary = stringRes(R.string.pref__dictionary__manage_system_user_dictionary__summary),
            onClick = { navController.navigate(Routes.Settings.UserDictionary(UserDictionaryType.SYSTEM)) },
            enabledIf = { prefs.dictionary.enableSystemUserDictionary isEqualTo true },
        )
        SwitchPreference(
            prefs.dictionary.enableFlorisUserDictionary,
            modifier = Modifier.settingsSearchAnchor("pref__dictionary__enable_internal_user_dictionary__label"),
            title = stringRes(R.string.pref__dictionary__enable_internal_user_dictionary__label),
            summary = stringRes(R.string.pref__dictionary__enable_internal_user_dictionary__summary),
        )
        Preference(
            modifier = Modifier.settingsSearchAnchor("pref__dictionary__manage_floris_user_dictionary__label"),
            title = stringRes(R.string.pref__dictionary__manage_floris_user_dictionary__label),
            summary = stringRes(R.string.pref__dictionary__manage_floris_user_dictionary__summary),
            onClick = { navController.navigate(Routes.Settings.UserDictionary(UserDictionaryType.FLORIS)) },
            enabledIf = { prefs.dictionary.enableFlorisUserDictionary isEqualTo true },
        )
        // Issue #318. The switch hangs off the internal dictionary rather than standing alone, because
        // promotion writes into exactly that dictionary — with it off, a learned word could never graduate
        // and the feature would half-work in a way nobody could diagnose.
        SwitchPreference(
            prefs.suggestion.learnTypedWords,
            modifier = Modifier.settingsSearchAnchor("pref__dictionary__learn_typed_words__label"),
            title = stringRes(R.string.pref__dictionary__learn_typed_words__label),
            summary = stringRes(R.string.pref__dictionary__learn_typed_words__summary),
            enabledIf = { prefs.dictionary.enableFlorisUserDictionary isEqualTo true },
        )
        Preference(
            modifier = Modifier.settingsSearchAnchor("pref__dictionary__manage_learned_words__label"),
            title = stringRes(R.string.pref__dictionary__manage_learned_words__label),
            summary = stringRes(R.string.pref__dictionary__manage_learned_words__summary),
            onClick = { navController.navigate(Routes.Settings.LearnedWords) },
        )
    }
}
