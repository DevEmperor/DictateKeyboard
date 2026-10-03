/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.dictate.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two OpenCode Go facts a preset can get wrong silently: the session header the service routes by
 * (a request without one is refused after auth), and the capability line — Go has no speech-to-text
 * endpoint, so it must never be offered for dictation.
 */
class OpenCodeGoPresetTest {

    @Test
    fun `the preset carries the session header Go refuses requests without`() {
        assertEquals(
            "dictate-opencode-go",
            ProviderRegistry.OPENCODE_GO.extraHeaders["x-opencode-session"],
        )
    }

    @Test
    fun `Go rewords and never dictates`() {
        assertTrue(ProviderRegistry.OPENCODE_GO.capabilities.chat)
        assertFalse(ProviderRegistry.OPENCODE_GO.capabilities.transcription)
    }
}
