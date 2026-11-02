/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.ime.smartbar.quickaction

import dev.patrickgold.florisboard.R
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe

/**
 * Every action a user can put in the Smartbar has a tooltip. Forward delete had none, and a long press
 * on it showed "!! invalid !!".
 */
class QuickActionTooltipTest : FunSpec({

    test("every action of the default arrangement has a tooltip") {
        val default = QuickActionArrangement.Default
        val actions = listOfNotNull(default.stickyAction) + default.dynamicActions + default.hiddenActions
        for (action in actions.filterIsInstance<QuickAction.InsertKey>()) {
            withClue(action.data.label) {
                tooltipResFor(action.data.code, showDragHelpers = false) shouldNotBe R.string.general__invalid_fatal
            }
        }
    }
})
