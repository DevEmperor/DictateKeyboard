/*
 * Copyright (C) 2025 The FlorisBoard Contributors
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

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.ime.text.keyboard.TextKeyData
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

class QuickActionArrangementTest : FunSpec({
    context("contains behavior") {
        withData(
            Triple(
                QuickActionArrangement(
                    stickyAction = null,
                    dynamicActions = listOf(),
                    hiddenActions = listOf(),
                ),
                QuickAction.InsertKey(TextKeyData.SETTINGS),
                false,
            ),
            Triple(
                QuickActionArrangement(
                    stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                    dynamicActions = listOf(),
                    hiddenActions = listOf(),
                ),
                QuickAction.InsertKey(TextKeyData.SETTINGS),
                true,
            ),
            Triple(
                QuickActionArrangement(
                    stickyAction = null,
                    dynamicActions = listOf(QuickAction.InsertKey(TextKeyData.SETTINGS)),
                    hiddenActions = listOf(),
                ),
                QuickAction.InsertKey(TextKeyData.SETTINGS),
                true,
            ),
            Triple(
                QuickActionArrangement(
                    stickyAction = null,
                    dynamicActions = listOf(),
                    hiddenActions = listOf(QuickAction.InsertKey(TextKeyData.SETTINGS)),
                ),
                QuickAction.InsertKey(TextKeyData.SETTINGS),
                true,
            ),
        ) { (arrangement, action, expectedContains) ->
            arrangement.contains(action) shouldBe expectedContains
        }
    }

    context("distinct behavior") {
        withData(
            QuickActionArrangement(
                stickyAction = null,
                dynamicActions = listOf(),
                hiddenActions = listOf(),
            ) to QuickActionArrangement(
                stickyAction = null,
                dynamicActions = listOf(),
                hiddenActions = listOf(),
            ),
            QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                dynamicActions = listOf(),
                hiddenActions = listOf(),
            ) to QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                dynamicActions = listOf(),
                hiddenActions = listOf(),
            ),
            QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.SETTINGS),
                ),
                hiddenActions = listOf(),
            ) to QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                dynamicActions = listOf(),
                hiddenActions = listOf(),
            ),
            QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.SETTINGS),
                    QuickAction.InsertKey(TextKeyData.SETTINGS),
                ),
                hiddenActions = listOf(),
            ) to QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                dynamicActions = listOf(),
                hiddenActions = listOf(),
            ),
            QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                ),
                hiddenActions = listOf(
                    QuickAction.InsertKey(TextKeyData.VIEW_SYMBOLS),
                ),
            ) to QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.SETTINGS),
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                ),
                hiddenActions = listOf(
                    QuickAction.InsertKey(TextKeyData.VIEW_SYMBOLS),
                ),
            ),
            QuickActionArrangement(
                stickyAction = null,
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                ),
                hiddenActions = listOf(
                    QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                    QuickAction.InsertKey(TextKeyData.VIEW_SYMBOLS),
                ),
            ) to QuickActionArrangement(
                stickyAction = null,
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                ),
                hiddenActions = listOf(
                    QuickAction.InsertKey(TextKeyData.VIEW_SYMBOLS),
                ),
            ),
            QuickActionArrangement(
                stickyAction = null,
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                ),
                hiddenActions = listOf(
                    QuickAction.InsertKey(TextKeyData.VIEW_SYMBOLS),
                    QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                ),
            ) to QuickActionArrangement(
                stickyAction = null,
                dynamicActions = listOf(
                    QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL),
                ),
                hiddenActions = listOf(
                    QuickAction.InsertKey(TextKeyData.VIEW_SYMBOLS),
                ),
            ),
        ) { (beforeDistinct, afterDistinct) ->
            beforeDistinct.distinct() shouldBe afterDistinct
        }
    }

    // How a newly shipped action reaches someone who already has a stored arrangement — the editing
    // panel (issue #386) is only discoverable because of this, and a panel nobody can open is no panel.
    context("a stored arrangement gains actions that were added after it was saved") {
        test("a brand new action is appended to the visible actions") {
            val stored = QuickActionArrangement(
                stickyAction = QuickAction.InsertKey(TextKeyData.IME_UI_MODE_DICTATE),
                dynamicActions = listOf(QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL)),
                hiddenActions = listOf(),
            )
            val restored = QuickActionArrangement.Serializer.deserialize(
                QuickActionArrangement.Serializer.serialize(stored)
            )
            restored.dynamicActions.first() shouldBe QuickAction.InsertKey(TextKeyData.CLIPBOARD_SELECT_ALL)
            restored.contains(QuickAction.InsertKey(TextKeyData.IME_UI_MODE_EDITING)) shouldBe true
            // Scan text (issue #390) is the same story one release later: it ships switched off nowhere,
            // it is simply new, and an existing arrangement has to grow to include it.
            restored.contains(QuickAction.InsertKey(TextKeyData.IME_UI_MODE_SCAN)) shouldBe true
            // And translation (issue #424) after it.
            restored.contains(QuickAction.InsertKey(TextKeyData.TRANSLATE)) shouldBe true
            // And the transcription provider picker (issue #431).
            restored.contains(QuickAction.InsertKey(TextKeyData.DICTATE_SWITCH_PROVIDER)) shouldBe true
        }

        test("an action already in the arrangement is not added a second time") {
            val stored = QuickActionArrangement(
                stickyAction = null,
                dynamicActions = listOf(),
                hiddenActions = listOf(QuickAction.InsertKey(TextKeyData.IME_UI_MODE_EDITING)),
            )
            val restored = QuickActionArrangement.Serializer.deserialize(
                QuickActionArrangement.Serializer.serialize(stored)
            )
            restored.dynamicActions.count {
                it == QuickAction.InsertKey(TextKeyData.IME_UI_MODE_EDITING)
            } shouldBe 0
        }
    }
})
