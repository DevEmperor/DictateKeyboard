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

package dev.patrickgold.florisboard.ime.window

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.min
import dev.patrickgold.florisboard.shouldBeGreaterThan
import dev.patrickgold.florisboard.shouldBeGreaterThanOrEqualTo
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll

class ImeWindowConstraintsTest : FunSpec({
    val tolerance = 1e-3f.dp

    // A keyboard the user can actually see and hit. Every form factor asks for more than this by
    // design (the smallest default is 137dp, one-handed on a phone in landscape), so it is a floor no
    // real baseline screen can trip over — only a missing one can.
    val visibleKeyboardHeight = 120.dp

    context("baseline screens") {
        test("every form factor has a baseline screen to scale from") {
            // Every keyboard dimension is a fraction of the baseline screen, so a zero baseline is not
            // an untuned form factor but an invisible keyboard (issue #114).
            ImeFormFactor.Type.entries.forAll { type ->
                val baselineScreen = ImeWindowConstraints.BaselineScreens.getValue(type)
                assertSoftly {
                    baselineScreen.width.shouldBeGreaterThan(0.dp)
                    baselineScreen.height.shouldBeGreaterThan(0.dp)
                }
            }
        }

        test("a 14.6 inch tablet in landscape gets a keyboard") {
            // The device from issue #114: a 2960x1848 panel, held in landscape, at a display size that
            // puts it past the extra-large width breakpoint.
            val rootInsets = with(Density(1.5f)) { ImeInsets.Root.of(IntRect(0, 0, 2960, 1848)) }
            rootInsets.formFactor.typeGuess shouldBe ImeFormFactor.Type.DESKTOP

            val constraints = ImeWindowConstraints.of(rootInsets, ImeWindowMode.Fixed.NORMAL)
            assertSoftly {
                constraints.defKeyboardHeight.shouldBeGreaterThan(0.dp)
                constraints.defaultProps.keyboardHeight.shouldBeGreaterThan(0.dp)
            }
        }
    }

    context("for all root insets and fixed modes") {
        test("default props are fully visible") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Fixed>()) { rootInsets, fixedMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)
                val defaultProps = constraints.defaultProps

                assertSoftly {
                    defaultProps.shouldBeConstrainedTo(constraints, tolerance)
                }
            }
        }

        test("0.dp <= min <= def <= max keyboard width") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Fixed>()) { rootInsets, fixedMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minKeyboardWidth)
                    constraints.minKeyboardWidth.shouldBeLessThanOrEqualTo(constraints.defKeyboardWidth)
                    constraints.defKeyboardWidth.shouldBeLessThanOrEqualTo(constraints.maxKeyboardWidth)
                }
            }
        }

        test("0.dp <= min <= def <= max keyboard height") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Fixed>()) { rootInsets, fixedMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minKeyboardHeight)
                    constraints.minKeyboardHeight.shouldBeLessThanOrEqualTo(constraints.defKeyboardHeight)
                    constraints.defKeyboardHeight.shouldBeLessThanOrEqualTo(constraints.maxKeyboardHeight)
                }
            }
        }

        test("the default keyboard is never invisible") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Fixed>()) { rootInsets, fixedMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)
                // A window can be smaller than any sensible keyboard — then the window is the limit.
                val floor = min(visibleKeyboardHeight, rootInsets.boundsDp.height)

                constraints.defKeyboardHeight.shouldBeGreaterThanOrEqualTo(floor, tolerance)
            }
        }

        test("0.dp <= min <= def <= max padding horizontal") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Fixed>()) { rootInsets, fixedMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, fixedMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minPaddingHorizontal)
                    constraints.minPaddingHorizontal.shouldBeLessThanOrEqualTo(constraints.defPaddingHorizontal)
                    constraints.defPaddingHorizontal.shouldBeLessThanOrEqualTo(constraints.maxPaddingHorizontal)
                }
            }
        }
    }

    context("for all root insets and floating modes") {
        test("default props are fully visible") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Floating>()) { rootInsets, floatingMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, floatingMode)
                val defaultProps = constraints.defaultProps

                assertSoftly {
                    defaultProps.shouldBeConstrainedTo(constraints, tolerance)
                }
            }
        }

        test("0.dp <= min <= def <= max keyboard width") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Floating>()) { rootInsets, floatingMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, floatingMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minKeyboardWidth)
                    constraints.minKeyboardWidth.shouldBeLessThanOrEqualTo(constraints.defKeyboardWidth)
                    constraints.defKeyboardWidth.shouldBeLessThanOrEqualTo(constraints.maxKeyboardWidth)
                }
            }
        }

        test("0.dp <= min <= def <= max keyboard height") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Floating>()) { rootInsets, floatingMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, floatingMode)

                assertSoftly {
                    // no tolerance here, potential rounding errors must be mitigated by constraints itself
                    0.dp.shouldBeLessThanOrEqualTo(constraints.minKeyboardHeight)
                    constraints.minKeyboardHeight.shouldBeLessThanOrEqualTo(constraints.defKeyboardHeight)
                    constraints.defKeyboardHeight.shouldBeLessThanOrEqualTo(constraints.maxKeyboardHeight)
                }
            }
        }

        test("the default keyboard is never invisible") {
            checkAll(Arb.rootInsets(), Arb.enum<ImeWindowMode.Floating>()) { rootInsets, floatingMode ->
                val constraints = ImeWindowConstraints.of(rootInsets, floatingMode)
                // A window can be smaller than any sensible keyboard — then the window is the limit.
                val floor = min(visibleKeyboardHeight, rootInsets.boundsDp.height)

                constraints.defKeyboardHeight.shouldBeGreaterThanOrEqualTo(floor, tolerance)
            }
        }
    }
})
